package rs.pametnakupovina.app.text

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

/**
 * Text a ViewModel or repository hands to the screen without knowing the
 * language. It is resolved from string resources only when it is shown, so
 * the same state reads in Serbian or English depending on the phone.
 *
 * Arguments may themselves be [UiText]; they are resolved first, so
 * "Dodato: 3 stavke." can be a string around a plural.
 */
sealed interface UiText {
    /** Text that is already written for the reader, such as a server message. */
    data class Plain(val value: String) : UiText

    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Plural(
        @param:PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = listOf(count)
    ) : UiText

    fun resolve(resources: Resources): String = when (this) {
        is Plain -> value
        is Res -> resources.getString(id, *resolved(args, resources))
        is Plural -> resources.getQuantityString(id, count, *resolved(args, resources))
    }
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

fun uiPlural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
    UiText.Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())

fun String.asUiText(): UiText = UiText.Plain(this)

private fun resolved(args: List<Any>, resources: Resources): Array<Any> =
    args.map { if (it is UiText) it.resolve(resources) else it }.toTypedArray()

@Composable
fun UiText.asString(): String = resolve(LocalResources.current)

fun UiText.asString(context: Context): String = resolve(context.resources)

/**
 * A refusal the shopper can act on, raised below the UI. The screen shows
 * [text]. It is an [IllegalArgumentException] because it replaces `require`
 * checks on what the shopper entered.
 */
open class UserFacingException(
    val text: UiText,
    cause: Throwable? = null
) : IllegalArgumentException(text.toString(), cause)

/** `require`, with a message the shopper reads in their language. */
inline fun requireUser(condition: Boolean, text: () -> UiText) {
    if (!condition) throw UserFacingException(text())
}

inline fun <T : Any> requireUserNotNull(value: T?, text: () -> UiText): T =
    value ?: throw UserFacingException(text())
