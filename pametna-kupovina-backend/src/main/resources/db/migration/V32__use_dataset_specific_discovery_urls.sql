UPDATE app.retailer_data_source
SET discovery_url = REGEXP_REPLACE(
        source_url,
        '^https://data[.]gov[.]rs/s/resources/([^/]+)/.*$',
        'https://data.gov.rs/sr/datasets/\1/'
    ),
    updated_at = NOW()
WHERE parser_profile = 'GOV_RS_SEMICOLON_CSV'
  AND source_url ~ '^https://data[.]gov[.]rs/s/resources/[^/]+/';
