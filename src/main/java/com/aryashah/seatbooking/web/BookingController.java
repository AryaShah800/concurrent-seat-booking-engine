package com.aryashah.seatbooking.web;

import com.aryashah.seatbooking.service.BookingService;
import com.aryashah.seatbooking.web.Dtos.BookingResponse;
import com.aryashah.seatbooking.web.Dtos.ConfirmRequest;
import com.aryashah.seatbooking.web.Dtos.HoldRequest;
import com.aryashah.seatbooking.web.Dtos.HoldResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Validated
public class BookingController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Operation(summary = "Hold a seat for a limited time (409 if it is already held or booked)")
    @PostMapping("/holds")
    public ResponseEntity<HoldResponse> hold(@Valid @RequestBody HoldRequest request) {
        var hold = bookingService.placeHold(request.seatId(), request.customerId());
        return ResponseEntity.created(URI.create("/api/holds/" + hold.getId())).body(HoldResponse.of(hold));
    }

    @Operation(summary = "Release a hold early")
    @DeleteMapping("/holds/{holdId}")
    public ResponseEntity<Void> release(@PathVariable UUID holdId,
                                        @RequestParam @NotBlank @Size(max = 100) String customerId) {
        bookingService.releaseHold(holdId, customerId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Confirm a hold as a booking. Retrying with the same Idempotency-Key is safe.")
    @PostMapping("/bookings")
    public ResponseEntity<BookingResponse> confirm(
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(min = 8, max = 100) String idempotencyKey,
            @Valid @RequestBody ConfirmRequest request) {
        var result = bookingService.confirm(idempotencyKey, request.holdId(), request.customerId());
        var body = BookingResponse.of(result.booking());
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/bookings/" + body.bookingId()))
                .body(body);
    }

    @Operation(summary = "Fetch a booking")
    @GetMapping("/bookings/{bookingId}")
    public BookingResponse get(@PathVariable UUID bookingId) {
        return BookingResponse.of(bookingService.getBooking(bookingId));
    }
}
