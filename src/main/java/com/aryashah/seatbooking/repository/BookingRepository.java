package com.aryashah.seatbooking.repository;

import com.aryashah.seatbooking.domain.Booking;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    long countBySeatId(Long seatId);
}
