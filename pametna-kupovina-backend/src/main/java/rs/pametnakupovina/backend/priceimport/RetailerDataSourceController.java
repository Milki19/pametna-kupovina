package rs.pametnakupovina.backend.priceimport;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/imports/sources")
public class RetailerDataSourceController {

    private final RetailerDataSourceRepository repository;
    private final ImportWorkerStatusRepository workerStatusRepository;

    public RetailerDataSourceController(
            RetailerDataSourceRepository repository,
            ImportWorkerStatusRepository workerStatusRepository
    ) {
        this.repository = repository;
        this.workerStatusRepository = workerStatusRepository;
    }

    @GetMapping
    public List<RetailerDataSourceStatus> listSources() {
        return repository.findAll();
    }

    @GetMapping("/worker")
    public ImportWorkerStatus workerStatus() {
        return workerStatusRepository.latestStatus(Duration.ofMinutes(2));
    }

    /**
     * Confirms a pending catalog-format change (e.g. IDEA/Roda moving from
     * eight to three price formats) as legitimate, so future imports with
     * that same distinct-format count stop being flagged for review.
     */
    @PostMapping("/{id}/format-count/acknowledge")
    public ResponseEntity<Void> acknowledgeFormatCount(@PathVariable Long id) {
        return repository.acknowledgeFormatCount(id)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }
}
