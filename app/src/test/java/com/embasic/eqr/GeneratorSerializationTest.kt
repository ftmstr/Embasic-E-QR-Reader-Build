package com.embasic.eqr

import com.embasic.eqr.core.generator.GeneratorDraft
import com.embasic.eqr.core.generator.GeneratorType
import com.embasic.eqr.core.generator.serializeGeneratorDraft
import com.embasic.eqr.core.payload.DefaultPayloadInterpreter
import com.embasic.eqr.core.payload.ParsedPayload
import com.embasic.eqr.core.scan.DecodedCode
import com.embasic.eqr.core.scan.DecoderSource
import com.embasic.eqr.core.scan.Symbology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratorSerializationTest {
    private val parser = DefaultPayloadInterpreter()

    @Test
    fun wifiRoundTripsEscapedFields() {
        val raw = serializeGeneratorDraft(
            GeneratorDraft(
                type = GeneratorType.WIFI,
                primary = "Office;5G",
                secondary = "WPA2",
                tertiary = "p:ass",
                flag = true,
            )
        )
        val payload = parser.interpret(code(raw))
        assertTrue(payload is ParsedPayload.Wifi)
        payload as ParsedPayload.Wifi
        assertEquals("Office;5G", payload.ssid)
        assertEquals("p:ass", payload.password)
        assertTrue(payload.hidden)
    }

    @Test
    fun contactRoundTripsCoreFields() {
        val raw = serializeGeneratorDraft(
            GeneratorDraft(
                type = GeneratorType.CONTACT,
                primary = "Ada Lovelace",
                secondary = "+44123",
                tertiary = "ada@example.com",
                quaternary = "Analytical Engine",
            )
        )
        val payload = parser.interpret(code(raw))
        assertTrue(payload is ParsedPayload.Contact)
        payload as ParsedPayload.Contact
        assertEquals("Ada Lovelace", payload.name)
        assertEquals("+44123", payload.phones.first())
        assertEquals("ada@example.com", payload.emails.first())
    }

    @Test
    fun contactSpecialCharactersRoundTrip() {
        val raw = serializeGeneratorDraft(
            GeneratorDraft(
                type = GeneratorType.CONTACT,
                primary = "Ada; Lovelace",
                quaternary = "Engine, Lab",
            )
        )
        val payload = parser.interpret(code(raw)) as ParsedPayload.Contact
        assertEquals("Ada; Lovelace", payload.name)
        assertEquals("Engine, Lab", payload.organization)
    }

    @Test
    fun calendarUsesVcalendarEnvelopeAndRoundTrips() {
        val raw = serializeGeneratorDraft(
            GeneratorDraft(
                type = GeneratorType.CALENDAR,
                primary = "Toplantı",
                secondary = "20261006T120000Z",
                tertiary = "20261006T130000Z",
                quaternary = "Salon; 1",
            )
        )
        assertTrue(raw.startsWith("BEGIN:VCALENDAR"))
        val payload = parser.interpret(code(raw)) as ParsedPayload.Calendar
        assertEquals("Toplantı", payload.title)
        assertEquals("Salon; 1", payload.location)
    }

    @Test
    fun emailAndGeoRoundTrip() {
        val emailRaw = serializeGeneratorDraft(
            GeneratorDraft(
                type = GeneratorType.EMAIL,
                primary = "a@example.com",
                secondary = "Merhaba Dünya",
                tertiary = "İçerik",
            )
        )
        val email = parser.interpret(code(emailRaw)) as ParsedPayload.Email
        assertEquals("Merhaba Dünya", email.subject)
        assertEquals("İçerik", email.body)

        val geoRaw = serializeGeneratorDraft(
            GeneratorDraft(
                type = GeneratorType.GEO,
                primary = "37.123",
                secondary = "38.456",
                tertiary = "Viranşehir",
            )
        )
        val geo = parser.interpret(code(geoRaw)) as ParsedPayload.Geo
        assertEquals(37.123, geo.latitude, 0.0)
        assertEquals(38.456, geo.longitude, 0.0)
    }

    @Test
    fun urlAndSmsUseCanonicalSchemes() {
        assertEquals(
            "https://example.com/a",
            serializeGeneratorDraft(
                GeneratorDraft(type = GeneratorType.URL, primary = "https://example.com/a")
            )
        )
        assertEquals(
            "SMSTO:+905551112233:Merhaba",
            serializeGeneratorDraft(
                GeneratorDraft(
                    type = GeneratorType.SMS,
                    primary = "+905551112233",
                    secondary = "Merhaba",
                )
            )
        )
    }

    private fun code(raw: String) = DecodedCode(
        rawText = raw,
        symbology = Symbology.QR_CODE,
        decoderSource = DecoderSource.DIRECT_INPUT,
    )
}
