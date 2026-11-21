#!/usr/bin/env bash
# Controlled A/B: outbox relay off (A) vs on (B), back to back, same stack, same warm-up.
# Usage: load/ab.sh A|B <run-number>   (core compose services only, no Grafana/Prometheus)
set -u
cd "$(dirname "$0")/.."
MODE=$1; RUN=$2
OUT=load/measured/ab; mkdir -p $OUT
TAG=$(date +%Y-%m-%d)_${MODE}${RUN}
[ "$MODE" = A ] && export BOOKING_OUTBOX_ENABLED=false || export BOOKING_OUTBOX_ENABLED=true
docker compose down -v >/dev/null 2>&1
docker compose up -d --build >/dev/null 2>&1
for i in $(seq 1 60); do curl -sf localhost:${APP_PORT:-8080}/actuator/health >/dev/null && break; sleep 3; done
VUS=50 DURATION=20s SHOWS=1 docker compose --profile load run --rm k6 >/dev/null 2>&1
docker compose exec -T postgres psql -U seatbooking -d seatbooking -c "truncate outbox_event, bookings, holds, seats, shows restart identity cascade" >/dev/null
( while true; do curl -s localhost:${APP_PORT:-8080}/actuator/prometheus | grep -E '^(hikaricp_connections_(active|pending|max)|seatbooking_outbox_unpublished)' | tr '\n' ' ' | sed "s/^/$(date +%T) /"; echo; sleep 5; done > $OUT/$TAG.pool.txt ) &
SAMPLER=$!
VUS=200 DURATION=60s SHOWS=8 docker compose --profile load run --rm k6 2>&1 | sed 's/\x1b\[[0-9;]*[a-zA-Z]//g' > $OUT/$TAG.k6.txt
kill $SAMPLER 2>/dev/null
docker compose exec -T postgres psql -U seatbooking -d seatbooking < load/verify-no-double-booking.sql > $OUT/$TAG.verify.txt 2>&1
docker compose down -v >/dev/null 2>&1
grep -a -A9 '==== booking-race' $OUT/$TAG.k6.txt | head -11
