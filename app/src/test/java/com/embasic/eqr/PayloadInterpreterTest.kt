package com.embasic.eqr

import com.embasic.eqr.core.payload.DefaultPayloadInterpreter
import com.embasic.eqr.core.payload.ParsedPayload
import com.embasic.eqr.core.payload.WifiSecurity
import com.embasic.eqr.core.scan.DecodedCode
import com.embasic.eqr.core.scan.DecoderSource
import com.embasic.eqr.core.scan.Symbology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadInterpreterTest {
    private val interpreter = DefaultPayloadInterpreter()

    @Test
    fun wifiParsesEscapedFields() {
        val payload = interpreter.interpret(code("WIFI:T:WPA2;S:Office\\;5G;P:p\\:ass;H:true;;"))
        assertTrue(payload is ParsedPayload.Wifi)
        payload as ParsedPayload.Wifi
        assertEquals("Office;5G", payload.ssid)
        assertEquals("p:ass", payload.password)
        assertEquals(WifiSecurity.WPA2, payload.security)
        assertTrue(payload.hidden)
    }

    @Test
    fun vcardPreservesStructuredContactFields() {
        val raw = "BEGIN:VCARD\nVERSION:3.0\nFN:Ada Lovelace\nTEL:+44123\nEMAIL:ada@example.com\nEND:VCARD"
        val payload = interpreter.interpret(code(raw))
        assertTrue(payload is ParsedPayload.Contact)
        payload as ParsedPayload.Contact
        assertEquals("Ada Lovelace", payload.name)
        assertEquals(listOf("+44123"), payload.phones)
        assertEquals(listOf("ada@example.com"), payload.emails)
    }

    @Test
    fun mecardPreservesRepeatedPhoneFields() {
        val payload = interpreter.interpret(
            code("MECARD:N:Ada;TEL:+44123;TEL:+44999;EMAIL:a@example.com;;")
        )
        assertTrue(payload is ParsedPayload.Contact)
        assertEquals(listOf("+44123", "+44999"), (payload as ParsedPayload.Contact).phones)
    }

    @Test
    fun phoneSmsAndEmailAreSeparatedWithOpaqueUriQueries() {
        assertTrue(interpreter.interpret(code("tel:+905551112233")) is ParsedPayload.Phone)

        val sms = interpreter.interpret(code("sms:+905551112233?body=Merhaba%20Dunya"))
        assertTrue(sms is ParsedPayload.Sms)
        assertEquals("Merhaba Dunya", (sms as ParsedPayload.Sms).body)

        val email = interpreter.interpret(
            code("mailto:a@example.com?subject=Hi&body=Hello%20World")
        )
        assertTrue(email is ParsedPayload.Email)
        email as ParsedPayload.Email
        assertEquals("Hi", email.subject)
        assertEquals("Hello World", email.body)
    }

    @Test
    fun calendarAndGeoParseWithoutDateAssumptions() {
        val calendar = interpreter.interpret(
            code(
                "BEGIN:VEVENT\nSUMMARY:Toplantı\nDTSTART:20261006T120000Z\n" +
                    "DTEND:20261006T130000Z\nLOCATION:Ofis\nEND:VEVENT"
            )
        )
        assertTrue(calendar is ParsedPayload.Calendar)
        assertEquals("Toplantı", (calendar as ParsedPayload.Calendar).title)

        val geo = interpreter.interpret(code("geo:37.123,38.456?q=37.123,38.456(Viransehir)"))
        assertTrue(geo is ParsedPayload.Geo)
        geo as ParsedPayload.Geo
        assertEquals(37.123, geo.latitude, 0.0)
        assertEquals(38.456, geo.longitude, 0.0)
        assertEquals("Viransehir", geo.label)
    }

    @Test
    fun invalidGeoFallsBackToTextWithoutBecomingDeepLink() {
        assertTrue(interpreter.interpret(code("geo:999,999")) is ParsedPayload.Text)
    }

    @Test
    fun numericEanMapsToProductWithoutInventingProductName() {
        val payload = interpreter.interpret(code("8691234567890", Symbology.EAN_13))
        assertTrue(payload is ParsedPayload.ProductCode)
        assertEquals("8691234567890", (payload as ParsedPayload.ProductCode).value)
    }

    @Test
    fun customSchemeIsDeepLinkAndPlainTextStaysText() {
        val deep = interpreter.interpret(code("myapp://profile/42"))
        assertTrue(deep is ParsedPayload.DeepLink)
        assertEquals("myapp", (deep as ParsedPayload.DeepLink).scheme)

        assertTrue(interpreter.interpret(code("hello world")) is ParsedPayload.Text)
    }

    @Test
    fun malformedSupportedPayloadsFallBackSafely() {
        val textFallbacks = listOf(
            "https:///missing-host",
            "WIFI:T:WPA2;P:secret;;",
            "BEGIN:VCARD\nFN:Ada",
            "tel:",
            "sms:?body=missing-recipient",
            "mailto:?subject=missing-recipient",
            "BEGIN:VEVENT\nSUMMARY:Missing end",
            "geo:91,181",
            "myapp:",
            "javascript:alert(1)",
            "data:text/html,test",
            "file:///sdcard/secret.txt",
            "content://com.example/private",
            "intent://scan/#Intent;scheme=zxing;end",
        )

        textFallbacks.forEach { raw ->
            assertTrue(raw, interpreter.interpret(code(raw)) is ParsedPayload.Text)
        }
        assertTrue(
            interpreter.interpret(code("86912A4567890", Symbology.EAN_13)) is ParsedPayload.Text
        )
        assertTrue(interpreter.interpret(code("")) is ParsedPayload.Unknown)
    }
    @Test
    fun adversarialStructuredValuesStayDataOnly() {
        val contact = interpreter.interpret(
            code("MECARD:N:Eve\\;Admin;TEL:+90555;EMAIL:e@example.com;;")
        ) as ParsedPayload.Contact
        assertEquals("Eve;Admin", contact.name)

        val phone = interpreter.interpret(code("tel:javascript:alert(1)"))
        assertTrue(phone is ParsedPayload.Phone)
        assertEquals("javascript:alert(1)", (phone as ParsedPayload.Phone).number)

        val sms = interpreter.interpret(
            code("sms:+905551112233?body=PAY%26redirect%3Dhttps%3A%2F%2Fevil.example")
        ) as ParsedPayload.Sms
        assertEquals("PAY&redirect=https://evil.example", sms.body)

        val email = interpreter.interpret(
            code("mailto:a@example.com?subject=%3Cscript%3E&body=%0D%0ATest")
        ) as ParsedPayload.Email
        assertEquals("<script>", email.subject)
        assertEquals("\r\nTest", email.body)

        val calendar = interpreter.interpret(
            code("BEGIN:VEVENT\nSUMMARY:Line\\nBreak\nLOCATION:A\\,B\nEND:VEVENT")
        ) as ParsedPayload.Calendar
        assertEquals("Line\nBreak", calendar.title)
        assertEquals("A,B", calendar.location)

        assertTrue(
            interpreter.interpret(code("8691234567890", Symbology.QR_CODE)) is ParsedPayload.Text
        )
    }
    private fun code(
        value: String,
        symbology: Symbology = Symbology.QR_CODE,
    ) = DecodedCode(
        rawText = value,
        symbology = symbology,
        decoderSource = DecoderSource.ML_KIT,
    )
}
