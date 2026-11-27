package com.aryashah.seatbooking.service;

import com.aryashah.seatbooking.domain.Seat;
import com.aryashah.seatbooking.domain.SeatStatus;
import com.aryashah.seatbooking.domain.Show;
import com.aryashah.seatbooking.repository.SeatRepository;
import com.aryashah.seatbooking.repository.ShowRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final JdbcTemplate jdbc;

    public ShowService(ShowRepository shows, SeatRepository seats, JdbcTemplate jdbc) {
        this.shows = shows;
        this.seats = seats;
        this.jdbc = jdbc;
    }

    /** Creates a show with a rows x seatsPerRow grid labelled A1, A2, ... B1, ... */
    @Transactional
    public Show createShow(String name, Instant startsAt, int rows, int seatsPerRow) {
        if (rows < 1 || rows > 26 || seatsPerRow < 1 || seatsPerRow > 100) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_LAYOUT",
                    "rows must be 1-26 and seatsPerRow 1-100");
        }
        Show show = shows.saveAndFlush(new Show(name, startsAt));
        List<Object[]> grid = new ArrayList<>(rows * seatsPerRow);
        for (int r = 0; r < rows; r++) {
            char row = (char) ('A' + r);
            for (int n = 1; n <= seatsPerRow; n++) {
                grid.add(new Object[] {show.getId(), row + String.valueOf(n), SeatStatus.AVAILABLE.name()});
            }
        }
        // One JDBC batch instead of one INSERT per seat: seat ids come from BIGSERIAL (IDENTITY),
        // which stops Hibernate from batching saveAll, so a 26 x 100 show was 2,600 round trips.
        jdbc.batchUpdate("INSERT INTO seats (show_id, label, status) VALUES (?, ?, ?)", grid);
        return show;
    }

    @Transactional(readOnly = true)
    /** The most recently created show, if any (the demo page opens it by default). */
    public java.util.Optional<Show> latestShow() {
        return shows.findTopByOrderByIdDesc();
    }

    public List<Seat> seatsFor(Long showId) {
        if (!shows.existsById(showId)) {
            throw ApiException.notFound("Show " + showId);
        }
        return seats.findByShowIdOrderByIdAsc(showId);
    }
}
