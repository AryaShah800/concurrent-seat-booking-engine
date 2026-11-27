# Concurrent Seat Booking Engine

[![CI](https://github.com/AryaShah800/concurrent-seat-booking-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/AryaShah800/concurrent-seat-booking-engine/actions/workflows/ci.yml)

A Spring Boot 3 / PostgreSQL backend for ticket-style seat booking that guarantees **one seat is booked at most once**, even when many clients race for it.

**Live demo: https://seat-booking-demo.onrender.com** (seat map UI; API docs at `/swagger-ui.html`). Pick a free seat and press **Run race** to fire 20 concurrent hold requests for it: exactly one wins and 19 get `409`. It runs on Render's free tier, so the first visit after about 15 idle minutes takes up to a minute to wake up. The demo is one container with the API and its own PostgreSQL 16 (`deploy/demo/`): data resets on every restart, only the newest 50 shows are kept, and Kafka and the notification service are not included (`BOOKING_OUTBOX_ENABLED=false`). Checked locally under `docker run -m 512m`: about 340 MiB in use, 20 concurrent holds on one seat gave one `201` and nineteen `409`.

- **Seat holds with expiry.** A customer holds a seat for a configurable TTL (default 5 minutes) and then confirms it. Lapsed holds stop blocking the seat immediately, and a scheduled sweeper releases them in the database.
- **Optimistic locking.** `@Version` on the seat row means that when N requests race for one seat, exactly one `UPDATE ... WHERE version = ?` succeeds. The rest get `409 SEAT_UNAVAILABLE`.
- **Idempotent bookings.** `POST /api/bookings` requires an `Idempotency-Key` header.
  - Retrying with the same key and body returns the original booking (`200` plus `Idempotent-Replayed: true`).
  - Reusing the key with a different body returns `422`.
  - Concurrent duplicates collapse into one booking.
- **Database as the last line of defence.** UNIQUE constraints on `bookings.seat_id`, `bookings.hold_id` and `bookings.idempotency_key`.

- **Booking events over Kafka.** Every state change is written to a transactional outbox and relayed to Kafka (at-least-once). A second service, `notification-service`, consumes them idempotently, with a dead-letter topic.
- **Metrics, dashboard and UI.** Micrometer + Prometheus + Grafana, and a small seat map page.

**Not included:** Redis and authentication. Holds live in PostgreSQL (the source of truth for who may book what), which is a deliberate choice, see [Design decisions](#design-decisions-and-trade-offs).

## Architecture

```mermaid
flowchart LR
    client([Client / seat map UI]) -->|REST| api[Booking API<br/>Spring Boot]
    api -->|"hold / confirm<br/>(one transaction)"| pg[(PostgreSQL<br/>seats, holds, bookings,<br/>outbox_event)]
    pg -->|"poll, FOR UPDATE SKIP LOCKED"| relay[Outbox publisher]
    relay -->|"acks=all, key = seatId"| kafka{{Kafka KRaft<br/>booking.events}}
    kafka --> notif[notification-service<br/>idempotent consumer]
    notif --> ndb[(PostgreSQL<br/>notifications DB)]
    kafka -.->|"poison / retries exhausted"| dlt{{booking.events.DLT}}
    api -->|/actuator/prometheus| prom[Prometheus] --> graf[Grafana]
```

## Stack

Java 21 · Spring Boot 3.5 (Web, Data JPA, Validation, Actuator, Kafka) · PostgreSQL 16 · Apache Kafka (KRaft) · Flyway · Micrometer / Prometheus / Grafana · springdoc-openapi (Swagger UI) · JUnit 5 + Testcontainers · JaCoCo · k6 · Docker / Docker Compose · GitHub Actions

## How a booking works

```mermaid
sequenceDiagram
    participant C as Client
    participant API as Booking API
    participant DB as PostgreSQL
    C->>API: POST /api/holds {seatId, customerId}
    API->>DB: SELECT seat (version v)
    API->>DB: INSERT hold, UPDATE seat SET status=HELD, version=v+1 WHERE version=v
    alt another request updated the seat first
        DB-->>API: 0 rows updated (optimistic lock failure)
        API-->>C: 409 SEAT_UNAVAILABLE
    else won the race
        API-->>C: 201 {holdId, expiresAt}
    end
    C->>API: POST /api/bookings (Idempotency-Key: K) {holdId, customerId}
    API->>DB: booking with key K exists? -> replay it (200)
    API->>DB: INSERT booking, UPDATE hold, UPDATE seat SET status=BOOKED (version check)
    API-->>C: 201 {bookingId}
```

| Seat state | Meaning |
| --- | --- |
| `AVAILABLE` | Free to hold |
| `HELD` | Reserved until `hold_expires_at`; after that, anyone can hold it again |
| `BOOKED` | Final |

## API

Interactive docs: `http://localhost:8080/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`).

| Method | Path | Result |
| --- | --- | --- |
| `POST` | `/api/shows` `{name, startsAt, rows, seatsPerRow}` | `201`, creates a show with seats `A1`, `A2`, ... |
| `GET` | `/api/shows/{id}/seats` | Seats with their current status |
| `POST` | `/api/holds` `{seatId, customerId}` | `201` hold, or `409 SEAT_UNAVAILABLE` |
| `DELETE` | `/api/holds/{holdId}?customerId=` | `204`, releases the hold early |
| `POST` | `/api/bookings` + `Idempotency-Key` `{holdId, customerId}` | `201` booking, `200` replay, `409 HOLD_NOT_ACTIVE`, `403 HOLD_NOT_OWNED`, `422 IDEMPOTENCY_KEY_REUSED` |
| `GET` | `/api/bookings/{id}` | The booking |

Errors use RFC 9457 problem details with a stable `code` field.

## Design decisions and trade-offs

- **Optimistic locking, not row locks or a distributed lock.** Contention on one seat is short and the loser should fail fast with a 409, not queue. A version check costs one failed UPDATE per loser and needs no lock manager. Under heavy contention on a single hot seat most requests are wasted work; that is acceptable for seat maps where demand spreads over many seats.
- **Holds live in PostgreSQL, not Redis.** A hold and the seat it blocks change in one transaction, so they cannot disagree. A Redis hold store would need its own expiry and a reconciliation story for crashes between the two systems. The cost is more write load on PostgreSQL; the load test below shows what that costs.
- **Transactional outbox, not "write DB then publish".** Publishing from the request thread can lose an event (crash after commit) or announce a change that rolled back. The outbox row commits or rolls back with the seat change. The relay is at-least-once, so events carry an `eventId` and consumers de-duplicate on it. Ordering is per seat (Kafka key = seat id), not global.
- **Broker outage does not break bookings.** Events queue in `outbox_event` and drain when Kafka returns (tested by pausing a real broker). `seatbooking_outbox_unpublished` shows the backlog. Published rows are purged after 7 days by default.
- **Separate database per service.** `notification-service` never reads the booking tables; the event JSON (`contracts/booking-event.schema.json`) is the only coupling, checked by a test in both services.
- **Idempotency by key plus request fingerprint.** The same key with a different body is a client bug and gets 422, not a silent replay.

## Run it

```bash
docker compose up -d --build          # PostgreSQL 16, Kafka, API on http://localhost:8080, notification-service on :8081
# or, with a local PostgreSQL: mvn spring-boot:run  (DB_URL / DB_USERNAME / DB_PASSWORD)
```

Configuration (environment variables): `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_SIZE` (default 20), `BOOKING_HOLD_TTL` (ISO-8601, default `PT5M`), `BOOKING_SWEEP_INTERVAL` (default `PT5S`), `APP_PORT` (compose host port, default 8080).

The compose file's database password is a local-development default. Set `DB_PASSWORD` for anything else.

## Tests

```bash
mvn verify                          # engine; needs Docker: Testcontainers starts postgres:16-alpine and apache/kafka:3.9.0
(cd notification-service && mvn verify)   # consumer service, same containers
```

All integration tests run against a **real PostgreSQL 16 in Testcontainers**, not H2, so the locking and constraint behaviour is PostgreSQL's own. A mutable test clock makes hold expiry deterministic, with no sleeps.

| Test class | What it proves |
| --- | --- |
| `ConcurrentBookingTest` | **50 threads race for one seat behind a start gate: exactly 1 hold wins, 49 get `SEAT_UNAVAILABLE`** (repeated 5 times). 20 concurrent confirms with the same key give 1 booking and 19 replays of it. 20 confirms with different keys give 1 booking. 50 HTTP clients doing hold and confirm on one seat give exactly one `201`, one booking and 49 `409`s. |
| `BookingLifecycleTest` | Hold then confirm; booked and held seats block others; expired holds free the seat and can't be confirmed; sweeper; release; ownership checks; idempotent retry; key reuse rejected; 404s; a new show's seat grid is inserted in row order |
| `BookingApiTest` | REST contract end to end: status codes, `Idempotent-Replayed` header, validation errors, problem details, OpenAPI and health endpoints |
| `OutboxKafkaTest` | Against a real Kafka broker: a confirmed booking is published exactly once with an event id; events written while the broker is paused arrive after it recovers; a failed confirm leaves no event; an expired hold produces one `HoldExpired`, **even with four sweepers running at once**; only old published rows are purged |
| `OutboxPublisherBatchTest` | A partly failed send marks nothing published; a fully acknowledged batch is marked in one UPDATE |
| `DemoPrunerTest` | The public demo keeps only the newest shows (with their seats, holds and bookings) and trims old outbox rows |
| `MetricsEndpointTest`, `SeatMapPageTest` | Prometheus exposes the booking metrics; the seat map page is served at `/` |
| `SeatTest`, `BookingPropertiesTest` | Plain unit tests of the state rules and config |
| `BookingServiceTest` | Mockito unit tests that force rare race interleavings deterministically. Example: a duplicate same-key confirm that reads the hold just after the winner committed must replay the booking, not return 409. CI's concurrency test caught this case once before it was fixed. |

CI runs `mvn verify` on JDK 21 and prints the JaCoCo coverage in the job summary. The CI run for commit `c4f711c` had **55 test executions, 0 failures, 91.7% line and 76.2% branch coverage**. `notification-service` has 5 more tests (exactly-once handling of redelivered events, malformed and badly typed events going to the dead-letter topic, and the shared event contract).

## Load test (k6)

`load/booking-race.js` is a flash-sale scenario. Every virtual user loops, with no think time:

1. Hold a random seat.
2. On a win, confirm it with a fresh `Idempotency-Key`.
3. Replay that confirm with the same key, expecting the same `bookingId`.

The run fails if any response has an unexpected status or a replay returns a different booking. Afterwards, `load/verify-no-double-booking.sql` checks the database directly.

```bash
docker compose up -d --build
docker compose --profile load run --rm k6                      # 200 VUs, 60 s, 4 shows x 2,600 seats
VUS=100 DURATION=60s SHOWS=8 docker compose --profile load run --rm k6
docker compose exec -T postgres psql -U seatbooking -d seatbooking < load/verify-no-double-booking.sql
```

### Measured results (2026-11-14)

How these were measured:
- Each run started from a fresh database (`docker compose down -v` then `up`).
- A 20 s warm-up at 50 VUs ran first so the JVM was warm.
- The measured run had 8 shows × 2,600 = 20,800 seats, so the venue did not sell out.
- The code measured was commit `bd57d6b`. The only later change to `src/main` (the late-duplicate replay fix) affects a path that returns 409. That path never fired in these runs (0 unexpected statuses), so the numbers stand.
- k6 0.54.0 ran in a container on the compose network. Raw k6 summaries are in [`load/measured/`](load/measured/).

| | 100 VUs, 60 s | 200 VUs, 60 s |
| --- | --- | --- |
| HTTP requests (throughput) | 69,521 (1,009 req/s) | 42,161 (see note below) |
| Holds won / lost to a race (409) | 17,018 / 18,451 | 12,017 / 6,094 |
| Bookings created | 17,018 | 12,017 |
| Idempotent replays returning the same booking | 17,018 / 17,018 | 12,017 / 12,017 |
| Unexpected status codes | 0 | 0 |
| **Seats booked twice (SQL check)** | **0** | **0** |
| Hold latency p50 / p95 / p99 | 47.6 / 118.4 / 171.8 ms | 64.2 / 273.4 / 487.7 ms |
| Confirm latency p50 / p95 / p99 | 92.7 / 381.0 / 474.3 ms | 223.3 / 1,226.9 / 1,450.9 ms |
| All requests p95 | 303.0 ms | 1,074.0 ms |

**Read these numbers with care.**
- They come from **one Windows 11 laptop** (12 logical CPUs; Docker Desktop VM with 7.6 GB RAM). The app, PostgreSQL and k6 all ran on it, while other unrelated builds were running too.
- They show the locking holds under contention. They are not a capacity figure for real hardware.
- At 200 VUs, throughput drops and confirm latency rises because the 30-connection pool and the shared CPU saturate. Every confirm does an idempotency lookup plus a 3-row write transaction.
- The 561 req/s figure that used to be quoted for the 200 VU column was **not reproduced** when the same commit was re-run in the A/B session below (699 and 837 req/s), so it is no longer claimed. Treat throughput as machine-dependent; the latency and correctness rows are the stable part.
- An earlier run at 200 VUs on a cold JVM, without warm-up, measured confirm p95 at 2.3 s. That also had 0 double bookings.

## Project layout

```
src/main/java/com/aryashah/seatbooking
  domain/       Seat (@Version), Hold, Booking, Show
  repository/   Spring Data JPA repositories (+ bulk expiry updates)
  service/      BookingService (holds, confirms, idempotency), HoldExpiryJob, ShowService
  web/          REST controllers, DTOs, problem-detail error handler
src/main/resources/db/migration   Flyway schema (UNIQUE constraints, partial indexes)
load/           k6 script, SQL double-booking check, measured k6 summaries
```

## License

MIT

## Booking events (transactional outbox to Kafka)

Every hold, release, expiry and confirmation writes a row to `outbox_event` in the same database
transaction as the seat change. `OutboxPublisher` relays unpublished rows to the Kafka topic
`booking.events` (key = seat id) using `FOR UPDATE SKIP LOCKED`, waits for the broker ack, then
marks the row published. Delivery is **at-least-once**: each payload carries a unique `eventId`
that consumers use to de-duplicate. If Kafka is unreachable the booking API keeps working and the
events wait in the table (covered by `OutboxKafkaTest`, which pauses a real broker container).
`docker compose up` starts Kafka in KRaft mode (no Zookeeper).

### Does the outbox cost throughput? Controlled runs (2026-11-21)

An earlier single re-run on the full stack showed 240 req/s and confirm p95 2,091 ms, which looked like a large outbox penalty. A single run on a shared laptop cannot support that claim, so I ran back-to-back pairs with one script, [`load/ab.sh`](load/ab.sh):

- Core services only (PostgreSQL, Kafka, API; no Grafana/Prometheus), `docker compose down -v` before every run, one stack at a time.
- Warm-up: 20 s at 50 VUs on 1 show, then the tables are truncated. Measured run: 200 VUs, 60 s, 8 shows (20,800 seats).
- A = outbox relay off (`BOOKING_OUTBOX_ENABLED=false`), B = relay on. Baseline (BL) = commit `bd57d6b` (before outbox/Kafka existed), same script and k6 scenario.
- Hold p95 and confirm p95 are in ms. Raw k6 output, pool samples and SQL checks for every run are in [`load/measured/ab/`](load/measured/ab/).

| Run | Code | req/s | Bookings | Hold p95 | Confirm p95 | Double bookings |
| --- | --- | --- | --- | --- | --- | --- |
| A1 | relay off | 304 | 8,657 | 174 | 1,866 | 0 |
| B1 | relay on | 148 | 5,017 | 311 | 3,201 | 0 |
| A2 | relay off | 679 | 13,770 | 313 | 1,217 | 0 |
| B2 | relay on | 752 | 15,010 | 184 | 1,158 | 0 |
| BL1 | `bd57d6b` | 837 | 17,345 | 176 | 1,069 | 0 |
| BL2 | `bd57d6b` | 699 | 14,611 | 162 | 1,104 | 0 |
| B3 | relay on | 334 | 9,048 | 210 | 1,817 | 0 |

Every run also had 0 replay mismatches and 0 unexpected statuses.

What this does and does not show:
- **Correctness is stable.** 0 seats booked twice in all seven runs (SQL check).
- **Run-to-run noise is larger than any effect I can measure.** The same configuration ranged from 148 to 752 req/s (relay on) and 304 to 679 req/s (relay off). The first run of each pair (A1, B1) was slow, which I attribute to a cold JVM and a busy machine (other jobs were running), but B3 was also slow with no obvious change, so noise is not limited to run 1. I did not control the machine's load.
- **In the best runs the relay is not visible.** B2 (752 req/s, confirm p95 1,158 ms) matched or beat A2 (679, 1,217), and the pre-Kafka baseline's warm confirm p95 (1,069 and 1,104 ms) is in the same range as B2. So the earlier 561 to 240 req/s drop is not attributable to the outbox relay; it is mostly measurement noise.
- **The baseline is somewhat faster on average** (699 to 837 req/s versus 334 to 752 for relay-on runs), which is what the extra outbox insert per transition would predict, but three relay-on runs against two baseline runs on a shared laptop are not enough to put a number on it.
- **Pool saturation shows up as bursts.** HikariCP pending-connection samples (every 5 s, in `*.pool.txt`) were 0 in A1 and B1 but spiked in the warm runs (up to 161 in A2, 85 in B2, 47 in B3), i.e. the 30-connection pool is the bottleneck at 200 VUs whether or not the relay runs. The baseline build exposes no metrics, so it has no pool samples.
- BL1/BL2: the baseline schema has no `outbox_event` table, so the post-warm-up truncate in `ab.sh` failed and the warm-up bookings stayed in the database. The k6 counts are for the measured phase only; the SQL double-booking check covered warm-up plus measured bookings (18,571 and 17,057 rows).

## notification-service (second service)

`notification-service/` is a separate Spring Boot app with its own database (`notifications`). It
consumes `booking.events`, records each `eventId` in `processed_event` in the same transaction as
its side effect (a `notification` row for `BookingConfirmed`), so redelivered events are ignored.
Unreadable records go straight to `booking.events.DLT`; transient failures are retried 3 times
first. The event shape is defined in `contracts/booking-event.schema.json`, and both services have
a test that validates against it. Run it with `docker compose up -d --build`.

## Observability

`/actuator/prometheus` exposes `seatbooking_operation_seconds{operation,outcome}` (hold, release,
confirm; success / rejected / error, with histogram buckets), `seatbooking_confirm_replays_total`,
`seatbooking_holds_expired_total` and `seatbooking_outbox_unpublished` (events waiting for Kafka),
plus the standard JVM and HikariCP metrics.

```
docker compose --profile obs up -d --build      # Prometheus :9090, Grafana :3000 (anonymous viewer)
docker compose --profile load run --rm k6       # watch the dashboard fill
```

![Grafana dashboard during a 50-VU k6 run](docs/grafana-dashboard.png)

The screenshot is from a 50 VU / 60 s run on a shared laptop (raw k6 summary in
`load/measured/2026-11-21_50vu_60s_with_observability.txt`; not comparable to the 200 VU table above).
During that run the outbox backlog peaked at a few hundred events and drained to zero within seconds
of the load ending, which is the at-least-once relay keeping up.

## Seat map UI

Open `http://localhost:8080/` after `docker compose up`. A static page (no build step) served by the
engine: pick a free seat to hold it, watch the hold countdown, confirm or release. "Run race" fires 20
simultaneous hold requests at the selected seat and shows that exactly one wins and the rest get 409.
`?show=2` picks another show, `?as=name` sets the customer id.

![Seat map with a hold in progress](docs/seat-map.png)
