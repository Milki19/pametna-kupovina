package rs.pametnakupovina.backend.priceimport;

import org.springframework.web.bind.annotation.GetMapping;
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
}
