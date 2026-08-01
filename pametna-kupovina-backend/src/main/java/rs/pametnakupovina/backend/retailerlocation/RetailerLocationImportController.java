package rs.pametnakupovina.backend.retailerlocation;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@RestController
@RequestMapping("/api/v1/imports/retailers")
public class RetailerLocationImportController {

    private final RetailerLocationImportService importService;

    public RetailerLocationImportController(
            RetailerLocationImportService importService
    ) {
        this.importService = importService;
    }

    @PostMapping(
            value = "/{retailerCode}/locations",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public RetailerLocationImportResult importLocations(
            @PathVariable("retailerCode")
            String retailerCode,

            @RequestPart("file")
            MultipartFile file,

            @RequestParam(
                    name = "maxRows",
                    defaultValue = "1000"
            )
            int maxRows
    ) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException(
                    "CSV fajl je prazan"
            );
        }

        if (maxRows < 1 || maxRows > 10_000) {
            throw new IllegalArgumentException(
                    "maxRows mora biti između 1 i 10000"
            );
        }

        try (InputStream inputStream = file.getInputStream()) {
            return importService.importLocations(
                    retailerCode,
                    inputStream,
                    maxRows
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Otvaranje CSV fajla nije uspelo.",
                    exception
            );
        }
    }
}