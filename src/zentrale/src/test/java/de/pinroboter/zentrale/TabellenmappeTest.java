package de.pinroboter.zentrale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueft die selbst erzeugte Excel-Mappe.
 *
 * <p>Die Struktur wird hier geprueft; dass eine <em>unabhaengige</em> Bibliothek
 * die Datei tatsaechlich oeffnen kann, prueft zusaetzlich
 * {@code pruefungen/systemnachweise.sh} mit openpyxl. Ein selbst geschriebenes
 * Dateiformat nur gegen die eigene Umsetzung zu pruefen waere zirkulaer.
 */
class TabellenmappeTest {

    private Map<String, String> entpacken(byte[] daten) throws Exception {
        Map<String, String> teile = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(daten))) {
            java.util.zip.ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                teile.put(e.getName(), new String(z.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return teile;
    }

    @Test
    @DisplayName("Die Mappe enthaelt alle nach ECMA-376 noetigen Teile")
    void aufbauVollstaendig() throws Exception {
        byte[] d = Tabellenmappe.imSpeicher(m -> {
            m.blattBeginnen("Deckblatt");
            m.zeile("Titel");
            m.blattBeginnen("Protokoll");
            m.kopfzeile("A", "B");
            m.zeile("x", 1);
        });
        var teile = entpacken(d);
        for (String pflicht : new String[]{"[Content_Types].xml", "_rels/.rels",
                "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
                "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"}) {
            assertTrue(teile.containsKey(pflicht), "Es fehlt: " + pflicht);
        }
    }

    @Test
    @DisplayName("FA-PRO-040: Kopfzeile ist fixiert und hat einen Autofilter")
    void kopfzeileFixiertUndFilterbar() throws Exception {
        byte[] d = Tabellenmappe.imSpeicher(m -> {
            m.blattBeginnen("Protokoll");
            m.kopfzeile("Zeitpunkt", "Geraet", "Ereignis");
            m.zeile(Instant.now(), "KT-1", "Test");
        });
        String blatt = entpacken(d).get("xl/worksheets/sheet1.xml");
        assertTrue(blatt.contains("state=\"frozen\""), "Die Kopfzeile muss fixiert sein");
        assertTrue(blatt.contains("<autoFilter ref=\"A1:C2\""),
                "Der Autofilter muss die Kopfzeile umfassen");
    }

    @Test
    @DisplayName("Zahlen und Datumswerte sind echte Werte, kein Text")
    void echteDatentypen() throws Exception {
        Instant t = Instant.now();
        byte[] d = Tabellenmappe.imSpeicher(m -> {
            m.blattBeginnen("B");
            m.zeile(42, t, "Text");
        });
        String blatt = entpacken(d).get("xl/worksheets/sheet1.xml");
        assertTrue(blatt.contains("<v>42</v>"),
                "Zahlen ohne inlineStr, sonst kann Excel nicht damit rechnen");
        assertTrue(blatt.contains("s=\"2\""), "Datumswerte brauchen die Datumsvorlage");
        assertTrue(blatt.contains("inlineStr"), "Text bleibt Text");
    }

    @Test
    @DisplayName("Umlaute und Sonderzeichen ueberstehen den Export")
    void umlauteUndSonderzeichen() throws Exception {
        byte[] d = Tabellenmappe.imSpeicher(m -> {
            m.blattBeginnen("U");
            m.zeile("Behandlungsraum S\u00fcd", "Ger\u00e4t <weg> & abgemeldet", "Gr\u00f6\u00dfe: 5 \u00b5m");
        });
        String blatt = entpacken(d).get("xl/worksheets/sheet1.xml");
        assertTrue(blatt.contains("Behandlungsraum S\u00fcd"));
        assertTrue(blatt.contains("&lt;weg&gt;"), "XML-Sonderzeichen muessen maskiert sein");
        assertTrue(blatt.contains("&amp;"));
        assertTrue(blatt.contains("\u00b5m"));
    }

    @Test
    @DisplayName("Steuerzeichen zerstoeren die Datei nicht")
    void steuerzeichenWerdenEntfernt() throws Exception {
        // Steuerzeichen sind in XML unzulaessig. Kaemen sie ungefiltert in die
        // Datei, liesse Excel sie gar nicht erst oeffnen - aus einem einzigen
        // Byte in einem Geraetenamen wuerde ein unbrauchbarer Export.
        String heikel = "Text mit \u0000 und \u0007 Steuerzeichen";
        byte[] d = Tabellenmappe.imSpeicher(m -> {
            m.blattBeginnen("S");
            m.zeile(heikel);
        });
        String blatt = entpacken(d).get("xl/worksheets/sheet1.xml");
        assertFalse(blatt.contains("\u0000"));
        assertFalse(blatt.contains("\u0007"));
        assertTrue(blatt.contains("Steuerzeichen"));
    }

    @Test
    @DisplayName("Das serielle Datumsformat entspricht Excels Zaehlweise")
    void excelDatum() {
        // Bekannter Pruefwert: 1.1.2000 00:00 entspricht der Seriennummer 36526.
        Instant t = LocalDateTime.of(2000, 1, 1, 0, 0)
                .atZone(ZoneId.systemDefault()).toInstant();
        assertEquals(36526.0, Tabellenmappe.alsExcelDatum(t), 0.0001);
    }

    @Test
    @DisplayName("Spaltenbuchstaben stimmen auch jenseits von Z")
    void spaltenbuchstaben() {
        assertEquals("A", Tabellenmappe.spaltenbuchstabe(0));
        assertEquals("Z", Tabellenmappe.spaltenbuchstabe(25));
        assertEquals("AA", Tabellenmappe.spaltenbuchstabe(26));
        assertEquals("AZ", Tabellenmappe.spaltenbuchstabe(51));
        assertEquals("BA", Tabellenmappe.spaltenbuchstabe(52));
    }

    @Test
    @DisplayName("Blattnamen werden auf Excels Grenzen gebracht")
    void blattnamen() {
        assertEquals("Protokoll", Tabellenmappe.kuerzen("Protokoll"));
        assertEquals(31, Tabellenmappe.kuerzen("x".repeat(50)).length());
        assertFalse(Tabellenmappe.kuerzen("A/B*C[D]").matches(".*[/*\\[\\]].*"),
                "Excel verbietet diese Zeichen in Blattnamen");
    }
}
