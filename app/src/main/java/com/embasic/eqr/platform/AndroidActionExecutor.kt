package com.embasic.eqr.platform

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import com.embasic.eqr.core.action.ActionKind
import com.embasic.eqr.core.action.ResolvedAction
import com.embasic.eqr.core.payload.ParsedPayload
import com.embasic.eqr.core.payload.WifiSecurity
import com.embasic.eqr.core.risk.RiskReport
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

sealed interface ActionExecutionResult {
    data object Completed : ActionExecutionResult
    data object NoHandler : ActionExecutionResult
    data class Unsupported(val reason: String) : ActionExecutionResult
    data class InvalidPayload(val reason: String) : ActionExecutionResult
    data class Failed(val reason: String) : ActionExecutionResult
}

class AndroidActionExecutor {
    fun execute(
        context: Context,
        action: ResolvedAction,
        payload: ParsedPayload,
        riskReport: RiskReport,
    ): ActionExecutionResult = runCatching {
        when (action.kind) {
            ActionKind.COPY -> copy(context, payload.raw)
            ActionKind.SHARE -> launch(context, shareIntent(payload.raw))
            ActionKind.OPEN_URL -> {
                val url = (payload as? ParsedPayload.Url)?.raw
                    ?: return ActionExecutionResult.InvalidPayload("url_required")
                launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            ActionKind.OPEN_CLEAN_URL -> {
                val url = riskReport.cleanedUrl
                    ?: return ActionExecutionResult.InvalidPayload("clean_url_missing")
                launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            ActionKind.OPEN_DEEP_LINK -> {
                val deepLink = payload as? ParsedPayload.DeepLink
                    ?: return ActionExecutionResult.InvalidPayload("deep_link_required")
                launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(deepLink.raw)))
            }
            ActionKind.CONNECT_WIFI -> openWifi(context, payload)
            ActionKind.ADD_CONTACT -> addContact(context, payload)
            ActionKind.DIAL -> dial(context, payload)
            ActionKind.COMPOSE_SMS -> composeSms(context, payload)
            ActionKind.COMPOSE_EMAIL -> composeEmail(context, payload)
            ActionKind.ADD_CALENDAR -> addCalendar(context, payload)
            ActionKind.OPEN_MAP -> openMap(context, payload)
            ActionKind.WEB_SEARCH -> webSearch(context, payload)
            ActionKind.REGENERATE_QR ->
                ActionExecutionResult.Unsupported("generator_not_implemented")
        }
    }.getOrElse {
        ActionExecutionResult.Failed(it::class.simpleName ?: "action_failed")
    }

    private fun copy(context: Context, text: String): ActionExecutionResult {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return ActionExecutionResult.Unsupported("clipboard_unavailable")
        clipboard.setPrimaryClip(ClipData.newPlainText("E-QR", text))
        return ActionExecutionResult.Completed
    }

    private fun shareIntent(text: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }.let { Intent.createChooser(it, "E-QR ile paylaş") }

    private fun openWifi(
        context: Context,
        payload: ParsedPayload,
    ): ActionExecutionResult {
        val wifi = payload as? ParsedPayload.Wifi
            ?: return ActionExecutionResult.InvalidPayload("wifi_required")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            wifi.security != WifiSecurity.WEP
        ) {
            val builder = WifiNetworkSuggestion.Builder().setSsid(wifi.ssid)
            when (wifi.security) {
                WifiSecurity.OPEN -> Unit
                WifiSecurity.WPA,
                WifiSecurity.WPA2 -> {
                    val password = wifi.password
                        ?: return ActionExecutionResult.InvalidPayload("wifi_password_missing")
                    builder.setWpa2Passphrase(password)
                }
                WifiSecurity.WPA3 -> {
                    val password = wifi.password
                        ?: return ActionExecutionResult.InvalidPayload("wifi_password_missing")
                    builder.setWpa3Passphrase(password)
                }
                WifiSecurity.WEP,
                WifiSecurity.UNKNOWN ->
                    return launch(context, Intent(Settings.ACTION_WIFI_SETTINGS))
            }

            if (wifi.hidden) builder.setIsHiddenSsid(true)

            val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
                putParcelableArrayListExtra(
                    Settings.EXTRA_WIFI_NETWORK_LIST,
                    arrayListOf(builder.build()),
                )
            }
            return launch(context, intent)
        }

        return launch(context, Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    private fun addContact(
        context: Context,
        payload: ParsedPayload,
    ): ActionExecutionResult {
        val contact = payload as? ParsedPayload.Contact
            ?: return ActionExecutionResult.InvalidPayload("contact_required")
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            contact.name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            contact.phones.firstOrNull()?.let {
                putExtra(ContactsContract.Intents.Insert.PHONE, it)
            }
            contact.emails.firstOrNull()?.let {
                putExtra(ContactsContract.Intents.Insert.EMAIL, it)
            }
            contact.organization?.let {
                putExtra(ContactsContract.Intents.Insert.COMPANY, it)
            }
        }
        return launch(context, intent)
    }

    private fun dial(
        context: Context,
        payload: ParsedPayload,
    ): ActionExecutionResult {
        val phone = payload as? ParsedPayload.Phone
            ?: return ActionExecutionResult.InvalidPayload("phone_required")
        return launch(
            context,
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone.number, null)),
        )
    }

    private fun composeSms(
        context: Context,
        payload: ParsedPayload,
    ): ActionExecutionResult {
        val sms = payload as? ParsedPayload.Sms
            ?: return ActionExecutionResult.InvalidPayload("sms_required")
        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("smsto:${sms.recipients.joinToString(",")}"),
        ).apply {
            sms.body?.let { putExtra("sms_body", it) }
        }
        return launch(context, intent)
    }

    private fun composeEmail(
        context: Context,
        payload: ParsedPayload,
    ): ActionExecutionResult {
        val email = payload as? ParsedPayload.Email
            ?: return ActionExecutionResult.InvalidPayload("email_required")
        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("mailto:${email.recipients.joinToString(",")}"),
        ).apply {
            putExtra(Intent.EXTRA_EMAIL, email.recipients.toTypedArray())
            email.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            email.body?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }
        return launch(context, intent)
    }

    private fun addCalendar(
        context: Context,
        payload: ParsedPayload,
    ): ActionExecutionResult {
        val event = payload as? ParsedPayload.Calendar
            ?: return ActionExecutionResult.InvalidPayload("calendar_required")
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            event.title?.let { putExtra(CalendarContract.Events.TITLE, it) }
            event.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            parseCalendarMillis(event.start)?.let {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it)
            }
            parseCalendarMillis(event.end)?.let {
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it)
            }
        }
        return launch(context, intent)
    }

    private fun openMap(
        context: Context,
        payload: ParsedPayload,
    ): ActionExecutionResult {
        val geo = payload as? ParsedPayload.Geo
            ?: return ActionExecutionResult.InvalidPayload("geo_required")
        val label = geo.label?.let {
            "?q=${geo.latitude},${geo.longitude}(${Uri.encode(it)})"
        }.orEmpty()
        return launch(
            context,
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("geo:${geo.latitude},${geo.longitude}$label"),
            ),
        )
    }

    private fun webSearch(
        context: Context,
        payload: ParsedPayload,
    ): ActionExecutionResult {
        val product = payload as? ParsedPayload.ProductCode
            ?: return ActionExecutionResult.InvalidPayload("product_code_required")
        return launch(
            context,
            Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, product.value)
            },
        )
    }

    private fun launch(
        context: Context,
        intent: Intent,
    ): ActionExecutionResult = try {
        context.startActivity(intent)
        ActionExecutionResult.Completed
    } catch (_: ActivityNotFoundException) {
        ActionExecutionResult.NoHandler
    }
}

private fun parseCalendarMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null

    val candidates = listOf(
        "yyyyMMdd'T'HHmmss'Z'" to TimeZone.getTimeZone("UTC"),
        "yyyyMMdd'T'HHmmss" to TimeZone.getDefault(),
        "yyyyMMdd" to TimeZone.getDefault(),
    )

    return candidates.firstNotNullOfOrNull { (pattern, zone) ->
        runCatching {
            SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = zone
            }.parse(value)?.time
        }.getOrNull()
    }
}
