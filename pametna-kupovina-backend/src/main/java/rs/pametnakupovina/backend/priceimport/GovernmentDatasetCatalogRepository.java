package rs.pametnakupovina.backend.priceimport;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class GovernmentDatasetCatalogRepository {

    private final JdbcClient jdbcClient;

    public GovernmentDatasetCatalogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void upsertAll(List<GovernmentPriceDataset> datasets) {
        for (GovernmentPriceDataset dataset : datasets) {
            jdbcClient.sql("""
                        INSERT INTO app.government_dataset_candidate (
                            portal_dataset_id,
                            slug,
                            title,
                            organization_name,
                            dataset_page_url,
                            resource_id,
                            resource_title,
                            resource_url,
                            resource_format,
                            resource_last_modified
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (portal_dataset_id) DO UPDATE SET
                            slug = EXCLUDED.slug,
                            title = EXCLUDED.title,
                            organization_name = EXCLUDED.organization_name,
                            dataset_page_url = EXCLUDED.dataset_page_url,
                            resource_id = EXCLUDED.resource_id,
                            resource_title = EXCLUDED.resource_title,
                            resource_url = EXCLUDED.resource_url,
                            resource_format = EXCLUDED.resource_format,
                            resource_last_modified =
                                EXCLUDED.resource_last_modified,
                            last_discovered_at = NOW(),
                            updated_at = NOW()
                        """)
                    .param(1, dataset.portalDatasetId())
                    .param(2, dataset.slug())
                    .param(3, dataset.title())
                    .param(4, nullable(dataset.organizationName()), Types.VARCHAR)
                    .param(5, dataset.datasetPageUrl())
                    .param(6, nullable(dataset.resourceId()), Types.VARCHAR)
                    .param(7, nullable(dataset.resourceTitle()), Types.VARCHAR)
                    .param(8, dataset.resourceUrl())
                    .param(9, dataset.resourceFormat())
                    .param(
                            10,
                            timestampWithTimeZone(
                                    dataset.resourceLastModified()
                            ),
                            Types.TIMESTAMP_WITH_TIMEZONE
                    )
                    .update();
        }
    }

    public int count() {
        return jdbcClient.sql("""
                        SELECT COUNT(*)::INTEGER
                        FROM app.government_dataset_candidate
                        """)
                .query(Integer.class)
                .single();
    }

    public List<GovernmentDatasetCandidate> findAll() {
        return jdbcClient.sql("""
                        SELECT id,
                               portal_dataset_id,
                               slug,
                               title,
                               organization_name,
                               dataset_page_url,
                               resource_id,
                               resource_title,
                               resource_url,
                               resource_format,
                               resource_last_modified,
                               review_status,
                               first_discovered_at,
                               last_discovered_at,
                               probed_at,
                               probe_verdict,
                               probe_summary
                        FROM app.government_dataset_candidate
                        ORDER BY organization_name NULLS LAST,
                                 title,
                                 id
                        """)
                .query((resultSet, rowNumber) ->
                        new GovernmentDatasetCandidate(
                                resultSet.getLong("id"),
                                resultSet.getString("portal_dataset_id"),
                                resultSet.getString("slug"),
                                resultSet.getString("title"),
                                resultSet.getString("organization_name"),
                                resultSet.getString("dataset_page_url"),
                                resultSet.getString("resource_id"),
                                resultSet.getString("resource_title"),
                                resultSet.getString("resource_url"),
                                resultSet.getString("resource_format"),
                                instant(resultSet.getTimestamp(
                                        "resource_last_modified"
                                )),
                                resultSet.getString("review_status"),
                                instant(resultSet.getTimestamp(
                                        "first_discovered_at"
                                )),
                                instant(resultSet.getTimestamp(
                                        "last_discovered_at"
                                )),
                                instant(resultSet.getTimestamp("probed_at")),
                                resultSet.getString("probe_verdict"),
                                resultSet.getString("probe_summary")
                        ))
                .list();
    }

    public GovernmentDatasetCandidate findById(long id) {
        return findAll().stream()
                .filter(candidate -> candidate.id() == id)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Nema kandidata sa id " + id
                ));
    }

    /** What the probe found, kept next to the chain that is waiting. */
    public void saveProbe(long id, String verdict, String summary) {
        jdbcClient.sql("""
                        UPDATE app.government_dataset_candidate
                           SET probed_at = NOW(),
                               probe_verdict = :verdict,
                               probe_summary = :summary,
                               updated_at = NOW()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("verdict", verdict)
                .param("summary", summary)
                .update();
    }

    /** The portal replaced the file, so the address we hold is gone. */
    public void updateResource(long id, String url, Instant lastModified) {
        if (url == null || url.isBlank()) {
            return;
        }

        jdbcClient.sql("""
                        UPDATE app.government_dataset_candidate
                           SET resource_url = :url,
                               resource_last_modified = COALESCE(
                                   :lastModified, resource_last_modified
                               ),
                               updated_at = NOW()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("url", url)
                .param("lastModified", lastModified == null
                        ? null
                        : java.time.OffsetDateTime.ofInstant(
                                lastModified, java.time.ZoneOffset.UTC))
                .update();
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static java.time.OffsetDateTime timestampWithTimeZone(
            Instant instant
    ) {
        return instant == null
                ? null
                : instant.atOffset(ZoneOffset.UTC);
    }
}
