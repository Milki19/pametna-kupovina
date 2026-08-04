package rs.pametnakupovina.backend.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class StoreRepository {

    private static final RowMapper<StoreFormat> STORE_FORMAT_ROW_MAPPER =
            (resultSet, rowNumber) -> new StoreFormat(
                    resultSet.getLong("id"),
                    resultSet.getLong("retailer_id"),
                    resultSet.getString("retailer_code"),
                    resultSet.getString("code"),
                    resultSet.getString("name"),
                    resultSet.getBoolean("active"),
                    resultSet.getObject(
                            "created_at",
                            OffsetDateTime.class
                    ),
                    resultSet.getObject(
                            "updated_at",
                            OffsetDateTime.class
                    )
            );

    private static final RowMapper<Store> STORE_ROW_MAPPER =
            (resultSet, rowNumber) -> new Store(
                    resultSet.getLong("id"),
                    resultSet.getLong("retailer_id"),
                    resultSet.getString("retailer_code"),
                    resultSet.getString("retailer_name"),
                    resultSet.getLong("store_format_id"),
                    resultSet.getString("store_format_code"),
                    resultSet.getString("store_format_name"),
                    resultSet.getString("external_code"),
                    resultSet.getString("name"),
                    resultSet.getString("address"),
                    resultSet.getString("city"),
                    resultSet.getObject("latitude", Double.class),
                    resultSet.getObject("longitude", Double.class),
                    resultSet.getBoolean("active"),
                    resultSet.getObject(
                            "created_at",
                            OffsetDateTime.class
                    ),
                    resultSet.getObject(
                            "updated_at",
                            OffsetDateTime.class
                    )
            );

    private final JdbcClient jdbcClient;

    public StoreRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<StoreFormat> findFormatsByRetailerCode(
            String retailerCode
    ) {
        return jdbcClient.sql("""
                        SELECT format.id,
                               format.retailer_id,
                               retailer.code AS retailer_code,
                               format.code,
                               format.name,
                               format.active,
                               format.created_at,
                               format.updated_at
                        FROM app.store_format AS format
                        JOIN app.retailer AS retailer
                          ON retailer.id = format.retailer_id
                        WHERE retailer.code = ?
                        ORDER BY format.code
                        """)
                .param(retailerCode)
                .query(STORE_FORMAT_ROW_MAPPER)
                .list();
    }

    public List<Store> findStoresByRetailerCode(String retailerCode) {
        return jdbcClient.sql("""
                        SELECT store.id,
                               store.retailer_id,
                               retailer.code AS retailer_code,
                               retailer.name AS retailer_name,
                               store.store_format_id,
                               format.code AS store_format_code,
                               format.name AS store_format_name,
                               store.external_code,
                               store.name,
                               store.address,
                               store.city,
                               ST_Y(store.location::geometry) AS latitude,
                               ST_X(store.location::geometry) AS longitude,
                               store.active,
                               store.created_at,
                               store.updated_at
                        FROM app.store AS store
                        JOIN app.retailer AS retailer
                          ON retailer.id = store.retailer_id
                        JOIN app.store_format AS format
                          ON format.id = store.store_format_id
                         AND format.retailer_id = store.retailer_id
                        WHERE retailer.code = ?
                        ORDER BY store.external_code
                        """)
                .param(retailerCode)
                .query(STORE_ROW_MAPPER)
                .list();
    }
}

