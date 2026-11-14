package com.aryashah.seatbooking.repository;

import com.aryashah.seatbooking.domain.Seat;
import com.aryashah.seatbooking.domain.SeatStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByShowIdOrderByIdAsc(Long showId);

    long countByStatus(SeatStatus status);

    /**
     * Frees every seat whose hold has expired. It bumps the version by hand, because a bulk update
     * skips Hibernate's versioning. That way a confirm racing with the sweeper still fails its
     * optimistic-lock check.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Seat s
               set s.status = :available, s.holdId = null, s.holdExpiresAt = null, s.version = s.version + 1
             where s.status = :held and s.holdExpiresAt <= :now
            """)
    int releaseExpiredHolds(@Param("now") Instant now,
                            @Param("held") SeatStatus held,
                            @Param("available") SeatStatus available);
}
