package ma.mobility.booking.api;

import ma.mobility.booking.service.BookingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/bookings")
public class BookingController {
    private final BookingService bookings;
    public BookingController(BookingService bookings) { this.bookings = bookings; }

    @PostMapping
    public ResponseEntity<BookingView> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @RequestBody BookingRequest request) {
        return ResponseEntity.ok(bookings.create(key, request));
    }

    @GetMapping("/{id}")
    public BookingView get(@PathVariable String id) { return bookings.get(id); }
}
