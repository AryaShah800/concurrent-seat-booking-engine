package com.aryashah.seatbooking.web;

import com.aryashah.seatbooking.service.ShowService;
import com.aryashah.seatbooking.web.Dtos.CreateShowRequest;
import com.aryashah.seatbooking.web.Dtos.SeatResponse;
import com.aryashah.seatbooking.web.Dtos.ShowResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shows")
public class ShowController {

    private final ShowService showService;
    private final Clock clock;

    public ShowController(ShowService showService, Clock clock) {
        this.showService = showService;
        this.clock = clock;
    }

    @Operation(summary = "Create a show with a grid of seats")
    @PostMapping
    public ResponseEntity<ShowResponse> create(@Valid @RequestBody CreateShowRequest request) {
        var show = showService.createShow(request.name(), request.startsAt(), request.rows(), request.seatsPerRow());
        return ResponseEntity.created(URI.create("/api/shows/" + show.getId())).body(ShowResponse.of(show));
    }

    @Operation(summary = "List the seats of a show and their current status")
    @GetMapping("/{showId}/seats")
    public List<SeatResponse> seats(@PathVariable Long showId) {
        Instant now = clock.instant();
        return showService.seatsFor(showId).stream().map(seat -> SeatResponse.of(seat, now)).toList();
    }
}
