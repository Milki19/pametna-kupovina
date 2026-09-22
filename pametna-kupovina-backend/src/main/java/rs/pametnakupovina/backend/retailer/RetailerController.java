package rs.pametnakupovina.backend.retailer;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/retailers")
public class RetailerController {

    private final RetailerRepository retailerRepository;

    public RetailerController(RetailerRepository retailerRepository) {
        this.retailerRepository = retailerRepository;
    }

    @GetMapping
    public List<Retailer> findAll() {
        return retailerRepository.findAll();
    }
}