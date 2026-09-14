package rs.pametnakupovina.app.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/**
 * The reason the server gave for refusing a request, written for the reader.
 * The body can be read once, so ask for it once per error.
 */
fun HttpException.serverMessage(): String? = runCatching {
    response()?.errorBody()?.string()?.let { body ->
        Json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
    }
}.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
