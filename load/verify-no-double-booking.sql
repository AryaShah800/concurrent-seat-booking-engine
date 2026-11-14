-- Run after a load test:
--   docker compose exec -T postgres psql -U seatbooking -d seatbooking -f - < load/verify-no-double-booking.sql
SELECT
    (SELECT count(*) FROM bookings)                                     AS bookings,
    (SELECT count(*) FROM seats WHERE status = 'BOOKED')                AS booked_seats,
    (SELECT count(*) FROM (SELECT seat_id FROM bookings
                           GROUP BY seat_id HAVING count(*) > 1) d)     AS seats_booked_twice,
    (SELECT count(*) FROM seats s
      WHERE s.status = 'BOOKED'
        AND NOT EXISTS (SELECT 1 FROM bookings b WHERE b.seat_id = s.id)) AS booked_without_booking,
    (SELECT count(*) FROM bookings b JOIN holds h ON h.id = b.hold_id
      WHERE h.customer_id <> b.customer_id)                             AS booking_customer_mismatch;
