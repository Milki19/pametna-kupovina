package rs.pametnakupovina.app.data.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

private const val AUTHORIZATION = "Authorization"
private const val BEARER = "Bearer "

/** Svaki zahtev nosi pristupni token sesije. */
class SessionInterceptor(
    private val sessionManager: SessionManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = sessionCall { sessionManager.accessToken() }

        return chain.proceed(
            chain.request().newBuilder()
                .header(AUTHORIZATION, BEARER + token)
                .build()
        )
    }
}

/**
 * Server je odbio token (istekao je usput ili je sesija opozvana): jednom se
 * obnavlja i zahtev ponavlja. Drugo odbijanje ide pozivaocu.
 */
class SessionAuthenticator(
    private val sessionManager: SessionManager
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null
        // 401 bez ovog zaglavlja nije o sesiji (npr. Google nije prihvatio
        // prijavu), pa obnova ne bi ništa promenila.
        if (response.header("WWW-Authenticate")?.startsWith("Bearer") != true) return null

        val rejected = response.request.header(AUTHORIZATION)
            ?.removePrefix(BEARER)
            ?: return null
        val renewed = sessionCall { sessionManager.renewAfterRejection(rejected) }

        return response.request.newBuilder()
            .header(AUTHORIZATION, BEARER + renewed)
            .build()
    }
}

/**
 * OkHttp sme da primi samo IOException iz presretača; sve drugo bi srušilo
 * njegovu nit umesto da stigne do ekrana kao „nema veze sa serverom“.
 */
private fun sessionCall(block: suspend () -> String): String = try {
    runBlocking(Dispatchers.IO) { block() }
} catch (error: IOException) {
    throw error
} catch (error: CancellationException) {
    throw IOException("Zahtev je otkazan.", error)
} catch (error: Exception) {
    throw SessionUnavailable(error)
}
