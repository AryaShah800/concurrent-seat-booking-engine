package com.aryashah.seatbooking.repository;

import com.aryashah.seatbooking.domain.Hold;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    /**
     * Marks lapsed holds EXPIRED and returns exactly the rows this statement changed.
     *
     * <p>One statement instead of SELECT then UPDATE: if two sweepers run at once, the second one
     * waits on the row locks, re-checks {@code status = 'ACTIVE'} after the first commits and skips
     * those rows. So each hold is reported, and gets a HoldExpired event, only once.
     */
    @Query(value = """
            update holds set status = 'EXPIRED'
             where status = 'ACTIVE' and expires_at <= :now
            returning id, seat_id as seatId, customer_id as customerId
            """, nativeQuery = true)
    List<ExpiredHold> expireActiveHoldsReturning(@Param("now") Instant now);

    interface ExpiredHold {
        UUID getId();

        Long getSeatId();

        String getCustomerId();
    }
}
