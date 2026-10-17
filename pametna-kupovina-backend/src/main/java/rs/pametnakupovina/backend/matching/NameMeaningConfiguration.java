package rs.pametnakupovina.backend.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The model lives outside the jar (infra/ops/name-model.sh puts it on the
 * server). Without it the app works as before: look-alike pairs are scored
 * by shared letters only.
 */
@Configuration
public class NameMeaningConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NameMeaningConfiguration.class);

    @Bean
    NameMeaning nameMeaning(
            @Value("${matching.meaning.model-dir:}") String modelDirectory,
            @Value("${matching.meaning.model-code:multilingual-e5-small-q8}") String modelCode
    ) {
        if (modelDirectory.isBlank()) {
            return NameMeaning.unavailable();
        }
        Path directory = Path.of(modelDirectory);
        Path model = directory.resolve("model_quantized.onnx");
        Path tokenizer = directory.resolve("tokenizer.json");
        if (!Files.isReadable(model) || !Files.isReadable(tokenizer)) {
            log.warn("Model za značenje naziva nije pronađen u {}; predlozi se boduju samo po slovima", directory);
            return NameMeaning.unavailable();
        }
        try {
            NameMeaning meaning = new OnnxNameMeaning(model, tokenizer, modelCode);
            log.info("Model za značenje naziva učitan iz {}", directory);
            return meaning;
        } catch (Exception | LinkageError exception) {
            // A native library that does not load on this machine must not
            // stop the app.
            log.warn("Model za značenje naziva nije učitan; predlozi se boduju samo po slovima", exception);
            return NameMeaning.unavailable();
        }
    }
}
