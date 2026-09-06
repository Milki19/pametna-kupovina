package rs.pametnakupovina.backend.storepricing;

import org.springframework.stereotype.Service;

@Service
public class IdeaRodaPriceFormatMappingService {

    private final IdeaRodaPriceFormatMappingClient client;
    private final StorePriceFormatMappingRepository repository;

    public IdeaRodaPriceFormatMappingService(
            IdeaRodaPriceFormatMappingClient client,
            StorePriceFormatMappingRepository repository
    ) {
        this.client = client;
        this.repository = repository;
    }

    public StorePriceFormatImportResult importLatest() {
        StorePriceFormatSnapshot snapshot = client.fetchLatest();
        return repository.replaceIdeaRodaMappings(snapshot);
    }
}
