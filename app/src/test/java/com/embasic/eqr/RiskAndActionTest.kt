package com.embasic.eqr

import com.embasic.eqr.core.action.ActionKind
import com.embasic.eqr.core.action.DefaultActionResolver
import com.embasic.eqr.core.payload.DefaultPayloadInterpreter
import com.embasic.eqr.core.payload.ParsedPayload
import com.embasic.eqr.core.risk.LocalRiskEngine
import com.embasic.eqr.core.risk.RiskSeverity
import com.embasic.eqr.core.scan.DecodedCode
import com.embasic.eqr.core.scan.DecoderSource
import com.embasic.eqr.core.scan.Symbology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskAndActionTest {
    private val parser = DefaultPayloadInterpreter()
    private val risk = LocalRiskEngine()
    private val actions = DefaultActionResolver()

    @Test
    fun urlUserInfoIsSuspicious() {
        val payload = parse("https://trusted.example@attacker.example/login") as ParsedPayload.Url
        val report = risk.assess(payload)
        assertEquals("attacker.example", payload.host)
        assertTrue(report.signals.any { it.code == "URL_USERINFO" })
        assertEquals(RiskSeverity.SUSPICIOUS, report.severity)
    }
    @Test
    fun directIpAndUnusualPortAreSuspicious() {
        val payload = parse("http://192.168.1.10:8080/login") as ParsedPayload.Url
        val report = risk.assess(payload)
        assertEquals(RiskSeverity.SUSPICIOUS, report.severity)
        assertTrue(report.signals.any { it.code == "URL_HTTP" })
        assertTrue(report.signals.any { it.code == "URL_DIRECT_IP" })
        assertTrue(report.signals.any { it.code == "URL_UNUSUAL_PORT" })
    }

    @Test
    fun abbreviatedDecimalIpv4HostsAreFlaggedWithoutDnsLookup() {
        listOf(
            "https://127.1/path",
            "https://127.0.1/path",
            "https://2130706433/path",
            "https://192.168.65535/path",
        ).forEach { raw ->
            val payload = parse(raw) as ParsedPayload.Url
            val report = risk.assess(payload)
            assertTrue("$raw should report a direct-IP destination", report.signals.any {
                it.code == "URL_DIRECT_IP"
            })
            assertEquals(RiskSeverity.SUSPICIOUS, report.severity)
        }
    }

    @Test
    fun hexOctalAndTrailingDotIpv4HostsAreFlagged() {
        listOf(
            "https://0x7f000001/",
            "https://0X7F000001/",
            "https://0x7f.1/",
            "https://0x/",
            "https://017700000001/",
            "https://0xC0.0xA8.01.01/",
            "https://127.0.0.1./",
        ).forEach { raw ->
            val payload = parse(raw) as ParsedPayload.Url
            assertTrue("$raw must produce a direct-IP warning", risk.assess(payload).signals.any {
                it.code == "URL_DIRECT_IP"
            })
        }
    }

    @Test
    fun malformedOrOutOfRangeHexOctalHostsAreNotMarkedDirectIp() {
        listOf(
            "https://08/",
            "https://0x100000000/",
            "https://0xGG.0.0.1/",
            "https://example.0x7f/",
        ).forEach { raw ->
            val payload = parse(raw) as ParsedPayload.Url
            assertFalse("$raw must not be classified as a valid IPv4 number", risk.assess(payload).signals.any {
                it.code == "URL_DIRECT_IP"
            })
        }
    }

    @Test
    fun outOfRangeDecimalNumericHostIsNotTreatedAsIpv4() {
        listOf(
            "https://4294967296/path",
            "https://256.1.1.1/path",
            "https://127.0.0.256/path",
            "https://example.com/path",
        ).forEach { raw ->
            val payload = parse(raw) as ParsedPayload.Url
            assertFalse("$raw is not a valid decimal IPv4 form", risk.assess(payload).signals.any {
                it.code == "URL_DIRECT_IP"
            })
        }
    }

    @Test
    fun unicodeIdnAndMixedScriptAreVisible() {
        val payload = parse("https://раypal.com/login") as ParsedPayload.Url
        val report = risk.assess(payload)
        assertTrue(report.signals.any { it.code == "URL_IDN_PUNYCODE" })
        assertTrue(report.signals.any { it.code == "URL_MIXED_SCRIPT_HOST" })
        assertEquals(RiskSeverity.STRONG, report.severity)
    }

    @Test
    fun trackingParametersProduceCleanUrlWithoutLosingFunctionalQuery() {
        val payload = parse("https://example.com/a?utm_source=x&id=42&fbclid=y#part") as ParsedPayload.Url
        val report = risk.assess(payload)
        assertEquals("https://example.com/a?id=42#part", report.cleanedUrl)
        assertTrue(report.signals.any { it.code == "URL_TRACKING_PARAMETERS" })
    }

    @Test
    fun cleanedUrlPreservesEmptyQueryValuesAndBareKeys() {
        val payload = parse(
            "https://example.com/?flag&empty=&utm_source=qr&token=a%2Bb&token=x#frag"
        ) as ParsedPayload.Url
        assertEquals(
            "https://example.com/?flag&empty=&token=a%2Bb&token=x#frag",
            risk.assess(payload).cleanedUrl,
        )
    }

    @Test
    fun cleanedUrlPreservesTrailingFragmentSeparator() {
        val payload = parse(
            "https://example.com/path?id=42&utm_campaign=qr#"
        ) as ParsedPayload.Url
        assertEquals(
            "https://example.com/path?id=42#",
            risk.assess(payload).cleanedUrl,
        )
    }

    @Test
    fun onlyTrackingQueryStillRemovesTrackingButKeepsFragment() {
        val payload = parse(
            "https://example.com/path?utm_source=qr&fbclid=abc#section"
        ) as ParsedPayload.Url
        assertEquals(
            "https://example.com/path#section",
            risk.assess(payload).cleanedUrl,
        )
    }

    @Test
    fun questionMarkInsideFragmentIsNotMistakenForTrackingQuery() {
        val payload = parse(
            "https://example.com/page#help?utm_source=fragment&id=42"
        ) as ParsedPayload.Url
        val report = risk.assess(payload)
        assertFalse(report.signals.any { it.code == "URL_TRACKING_PARAMETERS" })
        assertEquals(null, report.cleanedUrl)
    }

    @Test
    fun questionMarkInsideFragmentDoesNotCreatePhantomRedirectSignal() {
        val payload = parse(
            "https://example.com/page#section?redirect=https%3A%2F%2Fexample.net"
        ) as ParsedPayload.Url
        val report = risk.assess(payload)
        assertFalse(report.signals.any { it.code == "URL_REDIRECT_PARAMETER" })
    }

    @Test
    fun genuineQueryBeforeFragmentIsStillParsed() {
        val payload = parse(
            "https://example.com/page?utm_source=real&id=42#section?redirect=ignored"
        ) as ParsedPayload.Url
        val report = risk.assess(payload)
        assertTrue(report.signals.any { it.code == "URL_TRACKING_PARAMETERS" })
        assertFalse(report.signals.any { it.code == "URL_REDIRECT_PARAMETER" })
        assertEquals(
            "https://example.com/page?id=42#section?redirect=ignored",
            report.cleanedUrl,
        )
    }

    @Test
    fun redirectAndExecutableDownloadAreFlagged() {
        val payload = parse(
            "https://example.com/file.apk?redirect=https%3A%2F%2Fother.example"
        ) as ParsedPayload.Url
        val report = risk.assess(payload)
        assertTrue(report.signals.any { it.code == "URL_REDIRECT_PARAMETER" })
        assertTrue(report.signals.any { it.code == "URL_EXECUTABLE_DOWNLOAD" })
        assertEquals(RiskSeverity.STRONG, report.severity)
    }

    @Test
    fun openWifiAndPrefilledSmsProduceSignals() {
        val wifi = parse("WIFI:T:nopass;S:Cafe;;")
        assertTrue(risk.assess(wifi).signals.any { it.code == "WIFI_OPEN_NETWORK" })

        val sms = parse("sms:+905551112233?body=PAY")
        assertTrue(risk.assess(sms).signals.any { it.code == "SMS_PREFILLED_BODY" })
    }

    @Test
    fun strongUrlActionsRequireConfirmationAndNeverExecuteThemselves() {
        val payload = parse("https://example.com/file.apk?utm_source=x") as ParsedPayload.Url
        val report = risk.assess(payload)
        val resolved = actions.resolve(payload, report)

        val open = resolved.first { it.kind == ActionKind.OPEN_URL }
        assertTrue(open.requiresConfirmation)
        assertNotNull(resolved.firstOrNull { it.kind == ActionKind.OPEN_CLEAN_URL })
        assertFalse(resolved.isEmpty())
    }

    @Test
    fun deepLinkRequiresExplicitConfirmation() {
        val payload = parse("myapp://profile/42")
        val resolved = actions.resolve(payload, risk.assess(payload))
        assertTrue(resolved.any { it.kind == ActionKind.COPY })
        assertTrue(resolved.any { it.kind == ActionKind.SHARE })
        val open = resolved.first { it.kind == ActionKind.OPEN_DEEP_LINK }
        assertTrue(open.requiresConfirmation)
        assertFalse(resolved.any { it.kind == ActionKind.OPEN_URL })
    }

    @Test
    fun unsafeSchemesNeverBecomeOpenableDeepLinks() {
        val unsafe = listOf(
            "javascript:alert(1)",
            "vbscript:msgbox(1)",
            "data:text/html,test",
            "file:///sdcard/secret.txt",
            "content://com.example/private",
            "intent://scan/#Intent;scheme=zxing;end",
        )

        unsafe.forEach { raw ->
            val payload = parse(raw)
            assertTrue(raw, payload is ParsedPayload.Text)
            val resolved = actions.resolve(payload, risk.assess(payload))
            assertFalse(raw, resolved.any { it.kind == ActionKind.OPEN_DEEP_LINK })
            assertFalse(raw, resolved.any { it.kind == ActionKind.OPEN_URL })
        }
    }

    @Test
    fun encodedRedirectKeyAndIpv6DownloadRemainVisible() {
        val redirect = parse(
            "https://example.com/?redirect%5Furl=https%3A%2F%2Fevil.example"
        ) as ParsedPayload.Url
        assertTrue(risk.assess(redirect).signals.any { it.code == "URL_REDIRECT_PARAMETER" })

        val ipv6 = parse("https://[2001:db8::1]:8443/file.apk") as ParsedPayload.Url
        val report = risk.assess(ipv6)
        assertTrue(report.signals.any { it.code == "URL_DIRECT_IP" })
        assertTrue(report.signals.any { it.code == "URL_UNUSUAL_PORT" })
        assertTrue(report.signals.any { it.code == "URL_EXECUTABLE_DOWNLOAD" })
        assertEquals(RiskSeverity.STRONG, report.severity)
    }
    private fun parse(raw: String): ParsedPayload = parser.interpret(
        DecodedCode(
            rawText = raw,
            symbology = Symbology.QR_CODE,
            decoderSource = DecoderSource.ML_KIT,
        )
    )
}
