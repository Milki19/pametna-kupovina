package rs.pametnakupovina.app.data.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

class ProductDetailsContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `dekodira aktuelnu format cenu i istoriju`() {
        val payload = """
            {
              "canonicalProductId": 1,
              "name": "VODA DONAT 1/1-PALANACKI-300",
              "brand": "DONAT",
              "barcode": "3838600041300",
              "quantityValue": 1000.0,
              "baseUnit": "ml",
              "requestedDate": "2026-03-02",
              "latestPriceDate": "2026-03-02",
              "offers": [{
                "retailerProductId": 10,
                "retailerCode": "EUROPROM",
                "retailerName": "Europrom",
                "storeFormatCode": "EUROPROM",
                "storeFormatName": "Europrom",
                "priceDate": "2026-03-02",
                "regularPrice": 239.0,
                "effectivePrice": 239.0,
                "priceScope": "STORE_FORMAT"
              }],
              "priceHistory": []
            }
        """.trimIndent()

        val result = json.decodeFromString<CanonicalProductDetailsDto>(payload)

        assertEquals(1L, result.canonicalProductId)
        assertEquals(239.0, result.offers.single().effectivePrice, 0.0)
        assertEquals("STORE_FORMAT", result.offers.single().priceScope)
        assertNull(result.offers.single().storeId)
    }

    @Test
    fun `retrofit koristi product details putanju i parametre`() {
        val method = ShoppingApiService::class.java.declaredMethods.single {
            it.name == "getProductDetails"
        }
        assertEquals(
            "api/v1/products/{canonicalProductId}",
            requireNotNull(method.getAnnotation(GET::class.java)).value
        )

        val firstAnnotations = method.parameterAnnotations.take(3)
        assertEquals(
            "canonicalProductId",
            firstAnnotations[0].filterIsInstance<Path>().single().value
        )
        assertEquals(
            listOf("date", "historyLimit"),
            firstAnnotations.drop(1).map {
                it.filterIsInstance<Query>().single().value
            }
        )
    }
}
