package rs.pametnakupovina.app.data.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripCostContractTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `dekodira cenu vremena i stajanja kako je server šalje`() {
        val costs = json.decodeFromString<TripCostsDto>(
            """
            {"valuePerHour": 1200.00, "costPerStop": 0.00,
             "defaultValuePerHour": 400.00, "defaultCostPerStop": 80.00,
             "maxValuePerHour": 10000.00, "maxCostPerStop": 2000.00,
             "customized": true, "currency": "RSD"}
            """.trimIndent()
        )

        assertEquals(1200.0, costs.valuePerHour, 0.0)
        assertEquals(10000.0, costs.maxValuePerHour, 0.0)
        assertTrue(costs.customized)
    }

    /** Bez iznosa server vraća cenu tržišta. */
    @Test
    fun `povratak na podrazumevano šalje prazan zahtev`() {
        assertEquals("{}", json.encodeToString(TripCostRequestDto(null, null)))
        assertEquals(
            """{"valuePerHour":650.5,"costPerStop":0.0}""",
            json.encodeToString(TripCostRequestDto(650.5, 0.0))
        )
    }
}
