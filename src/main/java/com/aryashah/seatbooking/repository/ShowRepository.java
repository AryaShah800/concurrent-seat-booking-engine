package com.aryashah.seatbooking.repository;

import com.aryashah.seatbooking.domain.Show;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowRepository extends JpaRepository<Show, Long> {
}
