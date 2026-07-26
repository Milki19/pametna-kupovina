package rs.pametnakupovina.backend.retailer;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class RetailerRepository {

    private static final RowMapper<Retailer> RETAILER_ROW_MAPPER =
            (resultSet, rowNumber) -> new Retailer(
                    resultSet.getLong("id"),
                    resultSet.getString("code"),
                    resultSet.getString("name"),
                    resultSet.getString("dataset_url"),
                    resultSet.getObject("created_at", OffsetDateTime.class)
            );

    private final JdbcClient jdbcClient;

    public RetailerRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Retailer> findAll() {
        return jdbcClient.sql("""
                        SELECT id, code, name, dataset_url, created_at
                        FROM app.retailer
                        ORDER BY name
                        """)
                .query(RETAILER_ROW_MAPPER)
                .list();
    }

    public Optional<Retailer> findByCode(String retailerCode) {
        return jdbcClient.sql("""
                    SELECT id,
                           code,
                           name,
                           dataset_url,
                           created_at
                    FROM app.retailer
                    WHERE code = ?
                    """)
                .param(retailerCode)
                .query(RETAILER_ROW_MAPPER)
                .optional();
    }
}