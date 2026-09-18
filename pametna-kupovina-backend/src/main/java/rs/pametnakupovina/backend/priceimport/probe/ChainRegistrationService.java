package rs.pametnakupovina.backend.priceimport.probe;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.pametnakupovina.backend.priceimport.GovernmentDatasetCandidate;
import rs.pametnakupovina.backend.priceimport.GovernmentDatasetCatalogRepository;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Turns a chain that passed the probe into a source the daily cycle imports.
 * Nothing here guesses: the file was read first, and the chain is registered
 * with the same settings as the chains already running.
 */
@Service
public class ChainRegistrationService {

    private static final String PRICE_SOURCE_CODE = "PRIMARY_PRICE_CATALOG";
    private static final String PARSER_PROFILE = "PRAVILNIK_76_2026_CSV";

    private static final String CYRILLIC =
            "абвгдђежзијклљмнњопрстћуфхцчџшАБВГДЂЕЖЗИЈКЛЉМНЊОПРСТЋУФХЦЧЏШ";
    private static final String[] LATIN = {
            "a", "b", "v", "g", "d", "dj", "e", "z", "z", "i", "j", "k", "l", "lj",
            "m", "n", "nj", "o", "p", "r", "s", "t", "c", "u", "f", "h", "c", "c",
            "dz", "s"
    };

    private final JdbcClient jdbcClient;
    private final GovernmentDatasetCatalogRepository repository;

    public ChainRegistrationService(
            JdbcClient jdbcClient,
            GovernmentDatasetCatalogRepository repository
    ) {
        this.jdbcClient = jdbcClient;
        this.repository = repository;
    }

    @Transactional
    public RegisteredChain register(long candidateId, boolean allowReview) {
        GovernmentDatasetCandidate candidate = repository.findById(candidateId);
        String verdict = candidate.probeVerdict();

        if (verdict == null) {
            throw new IllegalStateException(
                    "Cenovnik još nije probno pročitan; prvo pokreni probu."
            );
        }

        if (PriceListProbeVerdict.REJECTED.name().equals(verdict)) {
            throw new IllegalStateException(
                    "Cenovnik nije prošao probu: " + candidate.probeSummary()
            );
        }

        if (PriceListProbeVerdict.NEEDS_REVIEW.name().equals(verdict) && !allowReview) {
            throw new IllegalStateException(
                    "Cenovnik traži pregled pre uključenja: " + candidate.probeSummary()
            );
        }

        String name = candidate.organizationName() == null
                ? candidate.title()
                : candidate.organizationName().strip();
        String code = retailerCode(name);

        Long existing = jdbcClient
                .sql("SELECT id FROM app.retailer WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .optional()
                .orElse(null);

        long retailerId = existing != null ? existing : jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES (:code, :name, :datasetUrl)
                        RETURNING id
                        """)
                .param("code", code)
                .param("name", name)
                .param("datasetUrl", candidate.datasetPageUrl())
                .query(Long.class)
                .single();

        int sources = jdbcClient.sql("""
                        INSERT INTO app.retailer_data_source (
                            retailer_id, code, source_type, parser_profile,
                            source_url, discovery_url, encoding, delimiter,
                            price_scope, schedule_cron, active
                        )
                        SELECT :retailerId, :sourceCode, 'PRICE_CATALOG', :parser,
                               :sourceUrl, :discoveryUrl, 'AUTO', ';',
                               'RETAILER_OR_FORMAT', '0 0 3 * * *', TRUE
                        WHERE NOT EXISTS (
                            SELECT 1 FROM app.retailer_data_source
                             WHERE retailer_id = :retailerId
                               AND code = :sourceCode
                        )
                        """)
                .param("retailerId", retailerId)
                .param("sourceCode", PRICE_SOURCE_CODE)
                .param("parser", PARSER_PROFILE)
                .param("sourceUrl", candidate.resourceUrl())
                .param("discoveryUrl", candidate.datasetPageUrl())
                .update();

        // Until someone finds the chain's shops, its prices belong to a price
        // list rather than an address: that is what the app shows under
        // "Lanci bez poznate adrese".
        jdbcClient.sql("""
                        INSERT INTO app.store_format (retailer_id, code, name, active)
                        SELECT :retailerId, :formatCode, :formatName, TRUE
                        WHERE NOT EXISTS (
                            SELECT 1 FROM app.store_format
                             WHERE retailer_id = :retailerId
                               AND code = :formatCode
                        )
                        """)
                .param("retailerId", retailerId)
                .param("formatCode", code + "_PRICE_LIST")
                .param("formatName", name + " (bez potvrđene lokacije)")
                .update();

        jdbcClient.sql("""
                        UPDATE app.government_dataset_candidate
                           SET review_status = 'REGISTERED', updated_at = NOW()
                         WHERE id = :id
                        """)
                .param("id", candidateId)
                .update();

        return new RegisteredChain(code, name, retailerId, sources > 0);
    }

    /** "Луки комерц Д.О.О." becomes LUKI_KOMERC, the shape every chain code has. */
    static String retailerCode(String name) {
        StringBuilder latin = new StringBuilder();

        for (char character : name.toCharArray()) {
            int index = CYRILLIC.indexOf(character);
            latin.append(index < 0 ? character : LATIN[index % LATIN.length]);
        }

        // "d.o.o." and "PR" say nothing about the chain, and every code in the
        // catalogue is bare: MAXI, IDEA_RODA, UNIVEREXPORT.
        String code = Normalizer.normalize(latin.toString(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", " ")
                .replaceAll("\\bD O O\\b", " ")
                .replaceAll("\\b(DOO|OD|PR|AD|SZR|STR)\\b", " ")
                .strip()
                .replaceAll("\\s+", "_");

        return code.length() <= 30 ? code : code.substring(0, 30).replaceAll("_+$", "");
    }
}
