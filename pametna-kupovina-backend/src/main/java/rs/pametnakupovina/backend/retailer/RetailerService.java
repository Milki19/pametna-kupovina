package rs.pametnakupovina.backend.retailer;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RetailerService {

    private final RetailerRepository retailerRepository;

    public RetailerService(RetailerRepository retailerRepository) {
        this.retailerRepository = retailerRepository;
    }

    public List<Retailer> findAll() {
        return retailerRepository.findAll();
    }
}