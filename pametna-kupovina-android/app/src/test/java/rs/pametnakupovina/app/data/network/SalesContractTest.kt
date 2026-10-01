package rs.pametnakupovina.app.data.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.http.GET

class SalesContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `dekodira stranu akcija sa kategorijama`() {
        val payload = """
            {
              "page": 0,
              "limit": 20,
              "totalElements": 1,
              "totalPages": 1,
              "hasNext": false,
              "items": [
                {
                  "productFamilyId": 71,
                  "canonicalProductId": 5,
                  "name": "Keks Plazma 300 g",
                  "brand": "Bambi",
                  "quantityValue": 300.0,
                  "baseUnit": "g",
                  "packageCount": 1,
                  "categoryCode": "SWEETS",
                  "categoryName": "Slatkiši",
                  "retailerCode": "MAXI",
                  "retailerName": "Maxi",
                  "salePrice": 199.99,
                  "regularPrice": 266.99,
                  "discountPercent": 25,
                  "saleEndDate": "2026-10-05",
                  "otherChainCount": 2,
                  "nearestStoreMeters": 850.0
                }
              ],
              "categories": [
                {"code": "SWEETS", "name": "Slatkiši", "productCount": 12}
              ],
              "nearbyChecked": true
            }
        """.trimIndent()

        val page = json.decodeFromString<SalePageDto>(payload)

        assertEquals(25, page.items.single().discountPercent)
        assertEquals(266.99, page.items.single().regularPrice, 0.0)
        assertEquals("2026-10-05", page.items.single().saleEndDate)
        assertEquals("SWEETS", page.categories.single().code)
        assertEquals(true, page.nearbyChecked)
    }

    @Test
    fun `stariji server bez akcije u pretrazi i dalje radi`() {
        val availability = json.decodeFromString<ProductRetailerAvailabilityDto>(
            """{"retailerCode":"LIDL","retailerName":"Lidl","latestPriceDate":"2026-10-01","minimumEffectivePrice":239.0}"""
        )
        assertNull(availability.discountPercent)
        assertNull(availability.saleRegularPrice)
    }

    @Test
    fun `akcije imaju svoj endpoint`() {
        val method = ShoppingApiService::class.java.declaredMethods.single { it.name == "getSales" }
        assertEquals("api/v1/products/on-sale", method.getAnnotation(GET::class.java)!!.value)
    }
}
