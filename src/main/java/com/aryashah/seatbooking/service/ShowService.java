package com.aryashah.seatbooking.service;

import com.aryashah.seatbooking.domain.Seat;
import com.aryashah.seatbooking.domain.Show;
import com.aryashah.seatbooking.repository.SeatRepository;
import com.aryashah.seatbooking.repository.ShowRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

    private final ShowRepository shows;
    private final SeatRepository seats;

    public ShowService(ShowRepository shows, SeatRepository seats) {
        this.shows = shows;
        this.seats = seats;
    }

    /** Creates a show with a rows x seatsPerRow grid labelled A1, A2, ... B1, ... */
    @Transactional
    public Show createShow(String name, Instant startsAt, int rows, int seatsPerRow) {
        if (rows < 1 || rows > 26 || seatsPerRow < 1 || seatsPerRow > 100) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_LAYOUT",
                    "rows must be 1-26 and seatsPerRow 1-100");
        }
        Show show = shows.save(new Show(name, startsAt));
        List<Seat> grid = new ArrayList<>(rows * seatsPerRow);
        for (int r = 0; r < rows; r++) {
            char row = (char) ('A' + r);
            for (int n = 1; n <= seatsPerRow; n++) {
                grid.add(new Seat(show.getId(), row + String.valueOf(n)));
            }
        }
        seats.saveAll(grid);
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
