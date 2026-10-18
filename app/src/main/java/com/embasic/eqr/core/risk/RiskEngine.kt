package com.embasic.eqr.core.risk

import com.embasic.eqr.core.payload.ParsedPayload
import com.embasic.eqr.core.payload.WifiSecurity
import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

enum class RiskSeverity {
    NONE,
    INFO,
    SUSPICIOUS,
    STRONG,
}

data class RiskSignal(
    val code: String,
    val severity: RiskSeverity,
    val evidence: String? = null,
)

data class RiskReport(
    val severity: RiskSeverity,
    val signals: List<RiskSignal>,
    val cleanedUrl: String? = null,
) {
    val hasWarnings: Boolean get() = signals.isNotEmpty()
}

interface RiskEngine {
    fun assess(payload: ParsedPayload): RiskReport
}

class LocalRiskEngine(
    private val shortenerHosts: Set<String> = DEFAULT_SHORTENERS,
) : RiskEngine {
    override fun assess(payload: ParsedPayload): RiskReport {
        val signals = mutableListOf<RiskSignal>()
        var cleanedUrl: String? = null

        when (payload) {
            is ParsedPayload.Url -> {
                if (payload.scheme == "http") {
                    signals += signal("URL_HTTP", RiskSeverity.INFO, "http")
                }

                val rawUserInfo = runCatching { URI(payload.raw).rawUserInfo }.getOrNull()
                if (!rawUserInfo.isNullOrBlank()) {
                    signals += signal("URL_USERINFO", RiskSeverity.SUSPICIOUS)
                }

                if (isDirectIp(payload.host)) {
                    signals += signal("URL_DIRECT_IP", RiskSeverity.SUSPICIOUS, payload.host)
                }

                payload.port?.let { port ->
                    val expected = when (payload.scheme) {
                        "http" -> 80
                        "https" -> 443
                        else -> -1
                    }
                    if (port != expected) {
                        signals += signal("URL_UNUSUAL_PORT", RiskSeverity.SUSPICIOUS, port.toString())
                    }
                }

                val hostLower = payload.host.lowercase()
                val asciiHost = runCatching { IDN.toASCII(payload.host) }.getOrDefault(payload.host)
                val hasUnicode = payload.host.any { it.code > 127 }
                val hasPunycode = hostLower.split('.').any { it.startsWith("xn--") } ||
                    asciiHost.lowercase().split('.').any { it.startsWith("xn--") }

                if (hasUnicode || hasPunycode) {
                    signals += signal(
                        "URL_IDN_PUNYCODE",
                        RiskSeverity.SUSPICIOUS,
                        "${payload.host} -> $asciiHost",
                    )
                }

                if (hasMixedScripts(payload.host)) {
                    signals += signal("URL_MIXED_SCRIPT_HOST", RiskSeverity.STRONG, payload.host)
                }

                val comparableHost = asciiHost.lowercase().removePrefix("www.")
                if (comparableHost in shortenerHosts) {
                    signals += signal("URL_SHORTENER", RiskSeverity.INFO, comparableHost)
                }

                if (payload.raw.length > 180) {
                    signals += signal("URL_LONG_COMPLEX", RiskSeverity.INFO, payload.raw.length.toString())
                }

                val queryPairs = rawQueryPairs(payload.raw)
                if (queryPairs.any { decode(it.first).lowercase() in REDIRECT_KEYS }) {
                    signals += signal("URL_REDIRECT_PARAMETER", RiskSeverity.SUSPICIOUS)
                }

                val trackingPairs = queryPairs.filter { isTrackingKey(decode(it.first)) }
                if (trackingPairs.isNotEmpty()) {
                    signals += signal(
                        "URL_TRACKING_PARAMETERS",
                        RiskSeverity.INFO,
                        trackingPairs.joinToString(",") { decode(it.first) },
                    )
                    cleanedUrl = removeTrackingParameters(payload.raw)
                }

                val lowerPath = payload.path.lowercase()
                DOWNLOAD_EXTENSIONS.firstOrNull { lowerPath.endsWith(it) }?.let { ext ->
                    signals += signal("URL_EXECUTABLE_DOWNLOAD", RiskSeverity.STRONG, ext)
                }
            }

            is ParsedPayload.Wifi -> when (payload.security) {
                WifiSecurity.OPEN ->
                    signals += signal("WIFI_OPEN_NETWORK", RiskSeverity.SUSPICIOUS, payload.ssid)
                WifiSecurity.WEP ->
                    signals += signal("WIFI_WEP", RiskSeverity.SUSPICIOUS, payload.ssid)
                else -> Unit
            }

            is ParsedPayload.DeepLink ->
                signals += signal("CUSTOM_URI_SCHEME", RiskSeverity.INFO, payload.scheme)

            is ParsedPayload.Sms -> if (!payload.body.isNullOrBlank()) {
                signals += signal("SMS_PREFILLED_BODY", RiskSeverity.INFO)
            }

            else -> Unit
        }

        return RiskReport(
            severity = signals.maxOfOrNull { it.severity } ?: RiskSeverity.NONE,
            signals = signals.distinctBy { it.code to it.evidence },
            cleanedUrl = cleanedUrl?.takeIf { it != payload.raw },
        )
    }

    private fun signal(
        code: String,
        severity: RiskSeverity,
        evidence: String? = null,
    ) = RiskSignal(code, severity, evidence)

    companion object {
        val DEFAULT_SHORTENERS = setOf(
            "bit.ly",
            "t.co",
            "tinyurl.com",
            "is.gd",
            "cutt.ly",
            "rebrand.ly",
            "ow.ly",
            "buff.ly",
        )

        private val REDIRECT_KEYS = setOf(
            "url",
            "redirect",
            "redirect_url",
            "redirect_uri",
            "target",
            "dest",
            "destination",
            "next",
            "continue",
            "return",
            "return_url",
            "returnurl",
        )

        private val DOWNLOAD_EXTENSIONS = setOf(
            ".apk",
            ".exe",
            ".msi",
            ".dmg",
            ".pkg",
            ".bat",
            ".cmd",
            ".ps1",
            ".jar",
        )
    }
}

private fun parseIpv4Number(part: String): Long? {
    if (part.isEmpty()) return null
    // The WHATWG URL host parser also accepts hexadecimal and leading-zero octal
    // IPv4 numbers. Never use network DNS lookups to classify a QR destination.
    val hexadecimal = part.startsWith("0x", ignoreCase = true)
    val octal = !hexadecimal && part.length > 1 && part.startsWith('0')
    val radix = if (hexadecimal) 16 else if (octal) 8 else 10
    val digits = if (hexadecimal) part.substring(2) else part
    if (digits.isEmpty()) return if (hexadecimal) 0L else null
    val valid = when (radix) {
        16 -> digits.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
        8 -> digits.all { it in '0'..'7' }
        else -> digits.all { it in '0'..'9' }
    }
    return if (valid) digits.toLongOrNull(radix) else null
}

private fun isDirectIp(host: String): Boolean {
    val clean = host.removePrefix("[").removeSuffix("]")
    // Browsers accept short, decimal, hexadecimal, octal and trailing-dot IPv4.
    val bareHost = clean.removeSuffix(".")
    val parts = bareHost.split('.')
    if (parts.size in 1..4) {
        val numbers = parts.map(::parseIpv4Number)
        if (numbers.all { it != null }) {
            val pieces = numbers.filterNotNull()
            val maxLast = (1L shl (8 * (5 - pieces.size))) - 1
            if (pieces.dropLast(1).all { it in 0L..255L } &&
                pieces.last() in 0L..maxLast
            ) return true
        }
    }

    return ':' in clean &&
        clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
}

private fun hasMixedScripts(host: String): Boolean {
    val scripts = host
        .filter(Char::isLetter)
        .map { Character.UnicodeScript.of(it.code) }
        .filter { it != Character.UnicodeScript.COMMON && it != Character.UnicodeScript.INHERITED }
        .toSet()

    val relevant = scripts.filter {
        it == Character.UnicodeScript.LATIN ||
            it == Character.UnicodeScript.CYRILLIC ||
            it == Character.UnicodeScript.GREEK
    }.toSet()

    return relevant.size > 1
}

private fun rawQueryPairs(raw: String): List<Pair<String, String>> {
    // The fragment can itself contain '?'; it must never be parsed as a URL query.
    val query = raw.substringBefore('#')
        .substringAfter('?', missingDelimiterValue = "")
    if (query.isBlank()) return emptyList()

    return query.split('&')
        .filter(String::isNotBlank)
        .map { pair ->
            pair.substringBefore('=') to pair.substringAfter('=', missingDelimiterValue = "")
        }
}

private fun isTrackingKey(key: String): Boolean {
    val lower = key.lowercase()
    return lower.startsWith("utm_") ||
        lower in setOf("gclid", "fbclid", "dclid", "msclkid", "mc_cid", "mc_eid")
}

private fun removeTrackingParameters(raw: String): String {
    // Preserve the original bytes of all non-tracking query segments. Rebuilding
    // key/value pairs would change distinctions such as "flag" vs "flag=",
    // potentially altering an endpoint's behavior.
    val beforeFragment = raw.substringBefore('#')
    val fragmentSuffix = raw.substring(beforeFragment.length)
    val queryStart = beforeFragment.indexOf('?')
    if (queryStart < 0) return raw

    val base = beforeFragment.substring(0, queryStart)
    val kept = beforeFragment.substring(queryStart + 1)
        .split('&')
        .filterNot { segment -> isTrackingKey(decode(segment.substringBefore('='))) }
    val query = kept.joinToString("&")

    return buildString {
        append(base)
        if (kept.any { it.isNotEmpty() }) append('?').append(query)
        append(fragmentSuffix)
    }
}

private fun decode(value: String): String = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrDefault(value)
