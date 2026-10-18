package com.embasic.eqr.core.payload

import com.embasic.eqr.core.scan.DecodedCode
import com.embasic.eqr.core.scan.Symbology
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

sealed interface ParsedPayload {
    val raw: String

    data class Url(
        override val raw: String,
        val scheme: String,
        val host: String,
        val path: String,
        val port: Int?,
    ) : ParsedPayload

    data class Text(override val raw: String) : ParsedPayload

    data class Wifi(
        override val raw: String,
        val ssid: String,
        val password: String?,
        val security: WifiSecurity,
        val hidden: Boolean,
    ) : ParsedPayload

    data class Contact(
        override val raw: String,
        val name: String?,
        val phones: List<String>,
        val emails: List<String>,
        val organization: String?,
    ) : ParsedPayload

    data class Phone(
        override val raw: String,
        val number: String,
    ) : ParsedPayload

    data class Sms(
        override val raw: String,
        val recipients: List<String>,
        val body: String?,
    ) : ParsedPayload

    data class Email(
        override val raw: String,
        val recipients: List<String>,
        val subject: String?,
        val body: String?,
    ) : ParsedPayload

    data class Calendar(
        override val raw: String,
        val title: String?,
        val start: String?,
        val end: String?,
        val location: String?,
    ) : ParsedPayload

    data class Geo(
        override val raw: String,
        val latitude: Double,
        val longitude: Double,
        val label: String?,
    ) : ParsedPayload

    data class ProductCode(
        override val raw: String,
        val value: String,
        val symbology: Symbology,
    ) : ParsedPayload

    data class DeepLink(
        override val raw: String,
        val scheme: String,
        val target: String,
    ) : ParsedPayload

    data class Unknown(override val raw: String) : ParsedPayload
}

enum class WifiSecurity {
    OPEN,
    WEP,
    WPA,
    WPA2,
    WPA3,
    UNKNOWN,
}

interface PayloadInterpreter {
    fun interpret(code: DecodedCode): ParsedPayload
}

class DefaultPayloadInterpreter : PayloadInterpreter {
    override fun interpret(code: DecodedCode): ParsedPayload {
        val raw = code.rawText.trim()
        if (raw.isBlank()) return ParsedPayload.Unknown(code.rawText)

        parseHttpUrl(raw)?.let { return it }
        parseWifi(raw)?.let { return it }
        parseContact(raw)?.let { return it }
        parsePhone(raw)?.let { return it }
        parseSms(raw)?.let { return it }
        parseEmail(raw)?.let { return it }
        parseCalendar(raw)?.let { return it }
        parseGeo(raw)?.let { return it }
        parseProductCode(raw, code.symbology)?.let { return it }
        parseDeepLink(raw)?.let { return it }

        return ParsedPayload.Text(raw)
    }

    private fun parseHttpUrl(raw: String): ParsedPayload.Url? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("http", "https")) return null

        val authority = uri.rawAuthority ?: return null
        val hostAndPort = authority.substringAfterLast('@')
        val displayHost = when {
            hostAndPort.startsWith("[") -> hostAndPort
                .substringAfter('[')
                .substringBefore(']')
            hostAndPort.count { it == ':' } == 1 -> hostAndPort.substringBeforeLast(':')
            else -> hostAndPort
        }.trim()

        if (displayHost.isBlank()) return null

        val parsedPort = uri.port.takeIf { it >= 0 } ?: when {
            hostAndPort.startsWith("[") && "]:" in hostAndPort ->
                hostAndPort.substringAfter("]:").toIntOrNull()
            hostAndPort.count { it == ':' } == 1 ->
                hostAndPort.substringAfterLast(':').toIntOrNull()
            else -> null
        }

        return ParsedPayload.Url(
            raw = raw,
            scheme = scheme,
            host = displayHost,
            path = uri.rawPath.orEmpty(),
            port = parsedPort,
        )
    }

    private fun parseWifi(raw: String): ParsedPayload.Wifi? {
        if (!raw.startsWith("WIFI:", ignoreCase = true)) return null
        val fields = parseEscapedPairs(raw.substringAfter(':'))
            .associate { it.first to it.second }
        val ssid = fields["S"]?.takeIf { it.isNotBlank() } ?: return null
        val securityRaw = fields["T"].orEmpty().uppercase()
        val security = when {
            securityRaw.isBlank() || securityRaw == "NOPASS" -> WifiSecurity.OPEN
            "WPA3" in securityRaw || "SAE" in securityRaw -> WifiSecurity.WPA3
            "WPA2" in securityRaw -> WifiSecurity.WPA2
            "WPA" in securityRaw -> WifiSecurity.WPA
            "WEP" in securityRaw -> WifiSecurity.WEP
            else -> WifiSecurity.UNKNOWN
        }

        return ParsedPayload.Wifi(
            raw = raw,
            ssid = ssid,
            password = fields["P"]?.takeIf { it.isNotEmpty() },
            security = security,
            hidden = fields["H"]?.equals("true", ignoreCase = true) == true,
        )
    }

    private fun parseContact(raw: String): ParsedPayload.Contact? {
        return when {
            raw.startsWith("BEGIN:VCARD", ignoreCase = true) &&
                raw.contains("END:VCARD", ignoreCase = true) -> parseVCard(raw)
            raw.startsWith("MECARD:", ignoreCase = true) -> parseMeCard(raw)
            else -> null
        }
    }

    private fun parseVCard(raw: String): ParsedPayload.Contact {
        val fields = parseStructuredLines(raw)
        return ParsedPayload.Contact(
            raw = raw,
            name = fields.firstValue("FN") ?: fields.firstValue("N"),
            phones = fields.valuesFor("TEL"),
            emails = fields.valuesFor("EMAIL"),
            organization = fields.firstValue("ORG"),
        )
    }

    private fun parseMeCard(raw: String): ParsedPayload.Contact {
        val fields = parseEscapedPairs(raw.substringAfter(':'))
        fun first(key: String) = fields.firstOrNull { it.first == key }?.second
        return ParsedPayload.Contact(
            raw = raw,
            name = first("N"),
            phones = fields.filter { it.first.startsWith("TEL") }
                .map { it.second }
                .filter { it.isNotBlank() },
            emails = fields.filter { it.first.startsWith("EMAIL") }
                .map { it.second }
                .filter { it.isNotBlank() },
            organization = first("ORG"),
        )
    }

    private fun parsePhone(raw: String): ParsedPayload.Phone? {
        if (!raw.startsWith("tel:", ignoreCase = true)) return null
        val number = decode(raw.substringAfter(':')).trim()
        return number.takeIf { it.isNotBlank() }?.let { ParsedPayload.Phone(raw, it) }
    }

    private fun parseSms(raw: String): ParsedPayload.Sms? {
        if (raw.startsWith("SMSTO:", ignoreCase = true)) {
            val rest = raw.substringAfter(':')
            val number = rest.substringBefore(':').trim()
            val body = rest.substringAfter(':', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
            if (number.isBlank()) return null
            return ParsedPayload.Sms(raw, listOf(number), body)
        }

        val scheme = raw.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme !in setOf("sms", "smsto", "mms", "mmsto")) return null

        val schemeSpecific = raw.substringAfter(':')
        val recipients = schemeSpecific
            .substringBefore('?')
            .split(',', ';')
            .map { decode(it).trim() }
            .filter { it.isNotBlank() }

        if (recipients.isEmpty()) return null
        val query = parseQuery(schemeSpecific.substringAfter('?', missingDelimiterValue = ""))
        return ParsedPayload.Sms(
            raw = raw,
            recipients = recipients,
            body = query["body"]?.firstOrNull(),
        )
    }

    private fun parseEmail(raw: String): ParsedPayload.Email? {
        if (!raw.startsWith("mailto:", ignoreCase = true)) return null

        val schemeSpecific = raw.substringAfter(':')
        val recipients = schemeSpecific
            .substringBefore('?')
            .split(',', ';')
            .map { decode(it).trim() }
            .filter { it.isNotBlank() }

        if (recipients.isEmpty()) return null
        val query = parseQuery(schemeSpecific.substringAfter('?', missingDelimiterValue = ""))
        return ParsedPayload.Email(
            raw = raw,
            recipients = recipients,
            subject = query["subject"]?.firstOrNull(),
            body = query["body"]?.firstOrNull(),
        )
    }

    private fun parseCalendar(raw: String): ParsedPayload.Calendar? {
        if (!raw.contains("BEGIN:VEVENT", ignoreCase = true) ||
            !raw.contains("END:VEVENT", ignoreCase = true)
        ) return null
        val fields = parseStructuredLines(raw)
        return ParsedPayload.Calendar(
            raw = raw,
            title = fields.firstValue("SUMMARY"),
            start = fields.firstValue("DTSTART"),
            end = fields.firstValue("DTEND"),
            location = fields.firstValue("LOCATION"),
        )
    }

    private fun parseGeo(raw: String): ParsedPayload.Geo? {
        if (!raw.startsWith("geo:", ignoreCase = true)) return null
        val schemeSpecific = raw.substringAfter(':')
        val coordinatePart = schemeSpecific.substringBefore('?')
        val pieces = coordinatePart.split(',')
        if (pieces.size < 2) return null

        val latitude = pieces[0].toDoubleOrNull() ?: return null
        val longitude = pieces[1].toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null

        val query = parseQuery(schemeSpecific.substringAfter('?', missingDelimiterValue = ""))
        val q = query["q"]?.firstOrNull()
        val label = q?.let {
            if ('(' in it && it.endsWith(')')) {
                it.substringAfter('(').dropLast(1).takeIf(String::isNotBlank)
            } else {
                it.takeIf(String::isNotBlank)
            }
        }

        return ParsedPayload.Geo(
            raw = raw,
            latitude = latitude,
            longitude = longitude,
            label = label,
        )
    }

    private fun parseProductCode(raw: String, symbology: Symbology): ParsedPayload.ProductCode? {
        val productFormats = setOf(
            Symbology.EAN_13,
            Symbology.EAN_8,
            Symbology.UPC_A,
            Symbology.UPC_E,
            Symbology.CODE_128,
            Symbology.CODE_39,
            Symbology.ITF,
            Symbology.CODABAR,
        )
        if (symbology !in productFormats || raw.any { !it.isDigit() }) return null
        return ParsedPayload.ProductCode(raw, raw, symbology)
    }

    private fun parseDeepLink(raw: String): ParsedPayload.DeepLink? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (scheme in setOf(
                "http", "https", "wifi", "tel", "sms", "smsto",
                "mms", "mmsto", "mailto", "geo",
                "javascript", "vbscript", "data", "file", "content", "intent"
            )
        ) return null
        val target = raw.substringAfter(':').takeIf { it.isNotBlank() } ?: return null
        return ParsedPayload.DeepLink(
            raw = raw,
            scheme = scheme,
            target = target,
        )
    }
}

private fun parseEscapedPairs(body: String): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    val token = StringBuilder()
    var escaped = false

    fun flush() {
        val field = token.toString()
        token.setLength(0)
        if (field.isBlank()) return
        val separator = field.indexOf(':')
        if (separator <= 0) return
        val key = field.substring(0, separator).uppercase()
        result += key to field.substring(separator + 1)
    }

    body.forEach { ch ->
        when {
            escaped -> {
                token.append(ch)
                escaped = false
            }
            ch == '\\' -> escaped = true
            ch == ';' -> flush()
            else -> token.append(ch)
        }
    }
    if (escaped) token.append('\\')
    flush()
    return result
}

private data class StructuredFields(
    val values: Map<String, List<String>>,
) {
    fun firstValue(key: String): String? = values[key]?.firstOrNull()
    fun valuesFor(key: String): List<String> = values[key].orEmpty()
}

private fun parseStructuredLines(raw: String): StructuredFields {
    val unfolded = raw
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .lines()
        .fold(mutableListOf<String>()) { acc, line ->
            if ((line.startsWith(" ") || line.startsWith("\t")) && acc.isNotEmpty()) {
                acc[acc.lastIndex] = acc.last() + line.drop(1)
            } else {
                acc += line
            }
            acc
        }

    val values = linkedMapOf<String, MutableList<String>>()
    unfolded.forEach { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@forEach
        val rawKey = line.substring(0, separator)
        val key = rawKey.substringBefore(';').uppercase()
        val value = unescapeStructuredValue(line.substring(separator + 1).trim())
        if (value.isNotEmpty()) values.getOrPut(key) { mutableListOf() }.add(value)
    }
    return StructuredFields(values)
}

private fun parseQuery(rawQuery: String?): Map<String, List<String>> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    val result = linkedMapOf<String, MutableList<String>>()
    rawQuery.split('&').forEach { pair ->
        val key = decode(pair.substringBefore('=')).lowercase()
        val value = decode(pair.substringAfter('=', missingDelimiterValue = ""))
        result.getOrPut(key) { mutableListOf() }.add(value)
    }
    return result
}

private fun decode(value: String): String = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrDefault(value)


private fun unescapeStructuredValue(value: String): String = buildString {
    var escaped = false
    value.forEach { ch ->
        if (escaped) {
            append(
                when (ch) {
                    'n', 'N' -> '\n'
                    else -> ch
                }
            )
            escaped = false
        } else if (ch == '\\') {
            escaped = true
        } else {
            append(ch)
        }
    }
    if (escaped) append('\\')
}
