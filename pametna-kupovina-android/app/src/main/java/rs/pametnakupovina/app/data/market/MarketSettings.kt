package rs.pametnakupovina.app.data.market

import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * The market the account shops in, as the server sends it with the account:
 * amounts are in its currency and written with its separators, and "this
 * month" follows its clock rather than the phone's.
 */
@Serializable
data class MarketSettings(
    val code: String,
    val currency: String,
    val currencyMinorUnits: Int = 2,
    val locale: String,
    val timeZone: String
) {
    val formatLocale: Locale get() = Locale.forLanguageTag(locale)

    val zone: ZoneId get() = ZoneId.of(timeZone)

    companion object {
        /**
         * Serbia, the first market. The app shows it until the server has
         * named the account's market, so a first start offline still reads
         * the way it always has.
         */
        val Default = MarketSettings(
            code = "RS",
            currency = "RSD",
            currencyMinorUnits = 2,
            locale = "sr-Latn-RS",
            timeZone = "Europe/Belgrade"
        )
    }
}

/**
 * The market every amount and month on screen follows. One per process: an
 * account shops in one market, and formatting runs far from any ViewModel.
 */
object CurrentMarket {
    @Volatile
    var settings: MarketSettings = MarketSettings.Default
}
