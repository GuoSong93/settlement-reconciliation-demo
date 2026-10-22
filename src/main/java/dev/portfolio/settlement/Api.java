package dev.portfolio.settlement;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class Api {
    private final SettlementService service;
    public Api(SettlementService service) { this.service=service; }
    @GetMapping("/state") Object state() { return service.overview(); }
    @PostMapping("/seed") Object seed() { return service.seed(); }
    record Bill(String billRef,String csv) {}
    @PostMapping("/imports") Object imports(@RequestBody Bill b) { return service.importBill(b.billRef(),b.csv()); }
    @GetMapping("/imports/{id}") Object imported(@PathVariable("id") String id) { return service.reconciliation(id); }
    @PostMapping("/imports/{id}/confirm") Object confirm(@PathVariable("id") String id) { return service.confirm(id); }
    @GetMapping("/batches/{id}") Object batch(@PathVariable("id") String id) { return service.batch(id); }
    record Callback(String eventId,List<SettlementService.PaymentResult> results) {}
    @PostMapping("/batches/{id}/results") Object results(@PathVariable("id") String id,@RequestBody Callback c) { return service.callback(id,c.eventId(),c.results()); }
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,NoSuchElementException.class})
    ResponseEntity<?> error(RuntimeException e) { return ResponseEntity.status(e instanceof NoSuchElementException?404:e instanceof IllegalStateException?409:400).body(Map.of("error",e.getMessage())); }
}
