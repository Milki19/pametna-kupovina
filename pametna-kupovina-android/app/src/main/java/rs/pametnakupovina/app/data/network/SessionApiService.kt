package rs.pametnakupovina.app.data.network

import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Otvaranje i obnova sesije. Ide kroz poseban klijent bez pristupnog tokena,
 * jer se baš ovim pozivima token i dobija.
 */
interface SessionApiService {

    @POST("api/v1/sessions")
    suspend fun createSession(@Body request: CreateSessionRequestDto): SessionDto

    @POST("api/v1/sessions/refresh")
    suspend fun refreshSession(@Body request: RefreshSessionRequestDto): SessionDto
}
