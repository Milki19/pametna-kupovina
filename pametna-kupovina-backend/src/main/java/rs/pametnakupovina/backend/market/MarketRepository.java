package rs.pametnakupovina.backend.market;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.ZoneId;

/**
 * Markets are a handful of rows that change with a migration, not with a
 * request, so every lookup is one indexed read and nothing is cached.
 */
@Repository
public class MarketRepository {

    private static final String COLUMNS = """
            market.id, market.code, market.name, market.currency_code,
            market.currency_minor_units, market.locale,
            market.default_language, market.time_zone,
            market.travel_cost_per_km, market.value_per_hour,
            market.cost_per_stop
            """;

    private static final RowMapper<Market> ROW_MAPPER =
            (resultSet, rowNumber) -> new Market(
                    resultSet.getInt("id"),
                    resultSet.getString("code"),
                    resultSet.getString("name"),
                    resultSet.getString("currency_code"),
                    resultSet.getInt("currency_minor_units"),
                    resultSet.getString("locale"),
                    resultSet.getString("default_language"),
                    ZoneId.of(resultSet.getString("time_zone")),
                    resultSet.getBigDecimal("travel_cost_per_km"),
                    resultSet.getBigDecimal("value_per_hour"),
                    resultSet.getBigDecimal("cost_per_stop")
            );

    private final JdbcClient jdbcClient;

    public MarketRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** The market everything belongs to unless it says otherwise. */
    public Market defaultMarket() {
        return jdbcClient.sql("SELECT " + COLUMNS
                        + " FROM app.market AS market WHERE market.is_default")
                .query(ROW_MAPPER)
                .single();
    }

    public Market forAccount(long accountId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                         FROM app.account AS account
                         JOIN app.market AS market ON market.id = account.market_id
                        WHERE account.id = :accountId
                        """)
                .param("accountId", accountId)
                .query(ROW_MAPPER)
                .optional()
                .orElseGet(this::defaultMarket);
    }

    /** The market of the account the list belongs to. */
    public Market forShoppingList(long listId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                         FROM app.shopping_list AS list
                         JOIN app.account AS account ON account.id = list.account_id
                         JOIN app.market AS market ON market.id = account.market_id
                        WHERE list.id = :listId
                        """)
                .param("listId", listId)
                .query(ROW_MAPPER)
                .optional()
                .orElseGet(this::defaultMarket);
    }

    public Market forRetailer(long retailerId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                         FROM app.retailer AS retailer
                         JOIN app.market AS market ON market.id = retailer.market_id
                        WHERE retailer.id = :retailerId
                        """)
                .param("retailerId", retailerId)
                .query(ROW_MAPPER)
                .optional()
                .orElseGet(this::defaultMarket);
    }

    public Market forRetailerCode(String retailerCode) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                         FROM app.retailer AS retailer
                         JOIN app.market AS market ON market.id = retailer.market_id
                        WHERE retailer.code = :code
                        """)
                .param("code", retailerCode)
                .query(ROW_MAPPER)
                .optional()
                .orElseGet(this::defaultMarket);
    }

    public Market forGovernmentDataset(long candidateId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                         FROM app.government_dataset_candidate AS candidate
                         JOIN app.market AS market ON market.id = candidate.market_id
                        WHERE candidate.id = :candidateId
                        """)
                .param("candidateId", candidateId)
                .query(ROW_MAPPER)
                .optional()
                .orElseGet(this::defaultMarket);
    }
}
