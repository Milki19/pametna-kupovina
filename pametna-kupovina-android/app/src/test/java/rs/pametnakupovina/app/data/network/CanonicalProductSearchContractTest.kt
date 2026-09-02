package rs.pametnakupovina.app.data.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.Query

class CanonicalProductSearchContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `dekodira stranicu sa vise canonical rezultata`() {
        val payload = """
            {
              "query": "DONAT",
              "page": 0,
              "limit": 10,
              "totalElements": 2,
              "totalPages": 1,
              "hasNext": false,
              "items": [
                {
                  "productFamilyId": 71,
                  "canonicalProductId": 1,
                  "name": "VODA DONAT 1/1-PALANACKI-300",
                  "brand": "DONAT",
                  "barcode": "3838600041300",
                  "quantityValue": 1000.0,
                  "baseUnit": "ml",
                  "categoryCode": "WATER",
                  "categoryName": "Voda",
                  "variantCount": 2,
                  "availability": [
                    {
                      "retailerCode": "LIDL",
                      "retailerName": "Lidl",
                      "latestPriceDate": "2026-03-02",
                      "storeCount": 0,
                      "formatCount": 1,
                      "minimumEffectivePrice": 239.0
                    }
                  ],
                  "score": 1.0
                },
                {
                  "canonicalProductId": 2,
                  "name": "DONAT MG mineralna voda",
                  "score": 0.82
                }
              ]
            }
        """.trimIndent()

        val result = json.decodeFromString<CanonicalProductSearchPageDto>(
            payload
        )

        assertEquals("DONAT", result.query)
        assertEquals(2L, result.totalElements)
        assertFalse(result.hasNext)
        assertEquals(2, result.items.size)
        assertEquals("3838600041300", result.items.first().barcode)
        assertEquals(71L, result.items.first().productFamilyId)
        assertEquals(2, result.items.first().variantCount)
        assertEquals("LIDL", result.items.first().availability.first().retailerCode)
        assertEquals(1000.0, result.items.first().quantityValue ?: 0.0, 0.0)
        assertNull(result.items.last().barcode)
    }

    @Test
    fun `retrofit koristi canonical search endpoint i query parametre`() {
        val method = ShoppingApiService::class.java.declaredMethods.single {
            it.name == "searchProducts"
        }
        val get = requireNotNull(method.getAnnotation(GET::class.java))

        assertEquals(
            "api/v1/products/search",
            get.value
        )

        val queryNames = method.parameterAnnotations
            .take(3)
            .map { annotations ->
                annotations.filterIsInstance<Query>().single().value
            }
        assertEquals(listOf("query", "page", "limit"), queryNames)
    }

    @Test
    fun `izabrani rezultat salje canonical id i barkod za potvrdu`() {
        val request = AddShoppingListItemRequestDto(
            name = "VODA DONAT 1/1-PALANACKI-300",
            rawInput = "DONAT",
            barcode = "3838600041300",
            canonicalProductId = 1,
            quantity = 1.0,
            matchingRule = ShoppingItemRuleDto.EXACT_PRODUCT
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"barcode\":\"3838600041300\""))
        assertTrue(encoded.contains("\"canonicalProductId\":1"))
        assertTrue(encoded.contains("\"matchingRule\":\"EXACT_PRODUCT\""))
    }

    @Test
    fun `izabrana porodica salje family id bez tacnog barkoda`() {
        val request = AddShoppingListItemRequestDto(
            name = "Grčki jogurt Pilos 400 g",
            rawInput = "grcki jogurt",
            productFamilyId = 77,
            quantity = 1.0,
            matchingRule = ShoppingItemRuleDto.PRODUCT_FAMILY
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"productFamilyId\":77"))
        assertTrue(encoded.contains("\"matchingRule\":\"PRODUCT_FAMILY\""))
        assertFalse(encoded.contains("canonicalProductId"))
        assertFalse(encoded.contains("barcode"))
    }
}
