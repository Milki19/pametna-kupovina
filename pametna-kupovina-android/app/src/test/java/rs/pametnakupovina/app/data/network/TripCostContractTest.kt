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
        // Server pre kilometra ga ne šalje, pa ekran to polje ne prikazuje.
        assertEquals(null, costs.costPerKm)
    }

    @Test
    fun `dekodira i cenu kilometra kad je server šalje`() {
        val costs = json.decodeFromString<TripCostsDto>(
            """
            {"valuePerHour": 400.00, "costPerStop": 80.00, "costPerKm": 32.50,
             "defaultValuePerHour": 400.00, "defaultCostPerStop": 80.00, "defaultCostPerKm": 20.00,
             "maxValuePerHour": 10000.00, "maxCostPerStop": 2000.00, "maxCostPerKm": 500.00,
             "customized": true, "currency": "RSD"}
            """.trimIndent()
        )

        assertEquals(32.5, costs.costPerKm!!, 0.0)
        assertEquals(20.0, costs.defaultCostPerKm!!, 0.0)
        assertEquals(500.0, costs.maxCostPerKm!!, 0.0)
        assertEquals(
            """{"valuePerHour":400.0,"costPerStop":80.0,"costPerKm":32.5}""",
            json.encodeToString(TripCostRequestDto(400.0, 80.0, 32.5))
        )
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
