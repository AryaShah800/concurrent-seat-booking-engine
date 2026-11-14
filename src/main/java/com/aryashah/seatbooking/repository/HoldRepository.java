package com.aryashah.seatbooking.repository;

import com.aryashah.seatbooking.domain.Hold;
import com.aryashah.seatbooking.domain.HoldStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Hold h set h.status = :expired where h.status = :active and h.expiresAt <= :now")
    int expireActiveHolds(@Param("now") Instant now,
                          @Param("active") HoldStatus active,
                          @Param("expired") HoldStatus expired);
}
