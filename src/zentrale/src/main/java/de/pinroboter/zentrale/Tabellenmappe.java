package de.pinroboter.zentrale;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Erzeugt eine Excel-Arbeitsmappe im Format {@code .xlsx} (ECMA-376).
 *
 * <p><b>Warum selbst geschrieben:</b> Apache POI wiegt rund 10 MB und brächte
 * ein Vielfaches dessen mit, was hier gebraucht wird — zwei Blätter, Text,
 * Zahlen, Datumswerte. Gegen die Speichergrenzen aus NFA-LEI-030 wäre das
 * schwer zu rechtfertigen. Ein {@code .xlsx} ist ein ZIP-Archiv mit
 * dokumentierten XML-Dateien; der hier benötigte Ausschnitt ist überschaubar.
 *
 * <p><b>Warum nicht CSV:</b> CSV zerlegt bei deutschen Excel-Einstellungen
 * regelmäßig die Spalten (Semikolon gegen Komma) und verstümmelt Umlaute. Ein
 * Export, den der Empfänger erst reparieren muss, ist kein Export.
 *
 * <p>Die Daten werden <b>zeilenweise geschrieben</b>, nicht vorher im Speicher
 * gesammelt. Bei hunderttausenden Protokollzeilen ist das der Unterschied
 * zwischen „läuft" und „Speicherüberlauf".
 */
public class Tabellenmappe implements AutoCloseable {

    /** Excel verträgt je Blatt nicht mehr Zeilen (ECMA-376). */
    public static final int MAX_ZEILEN = 1_048_576;

    private final ZipOutputStream zip;
    private final StringBuilder blatt = new StringBuilder();
    private final List<String> blattnamen = new ArrayList<>();
    private final List<byte[]> fertigeBlaetter = new ArrayList<>();
    private String aktuellerName;
    private int zeilennummer;
    private int spaltenzahl;
    private boolean kopfzeileGeschrieben;

    public Tabellenmappe(OutputStream ziel) {
        this.zip = new ZipOutputStream(ziel);
    }

    // ---------------------------------------------------------------- Blätter

    public void blattBeginnen(String name) {
        if (aktuellerName != null) blattAbschliessen();
        aktuellerName = kuerzen(name);
        blatt.setLength(0);
        zeilennummer = 0;
        spaltenzahl = 0;
        kopfzeileGeschrieben = false;
        blatt.append("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>""");
    }

    /**
     * Schreibt die Kopfzeile. Sie wird <b>fixiert</b> und bekommt einen
     * Autofilter (FA-PRO-040) — beides erwartet jeder, der eine Tabelle mit
     * mehr als zwei Bildschirmseiten öffnet.
     */
    public void kopfzeile(String... spalten) {
        spaltenzahl = spalten.length;
        zeile((Object[]) spalten);
        kopfzeileGeschrieben = true;
    }

    public void zeile(Object... werte) {
        zeilennummer++;
        blatt.append("<row r=\"").append(zeilennummer).append("\">");
        for (int i = 0; i < werte.length; i++) {
            String bezug = spaltenbuchstabe(i) + zeilennummer;
            Object w = werte[i];
            boolean kopf = kopfzeileGeschrieben && zeilennummer == 1;

            if (w == null) continue;
            if (w instanceof Number n) {
                // Echte Zahl, kein Text: Sonst kann in Excel niemand damit rechnen.
                blatt.append("<c r=\"").append(bezug).append("\" s=\"").append(kopf ? 1 : 0)
                     .append("\"><v>").append(n).append("</v></c>");
            } else if (w instanceof Instant t) {
                // Echter Datumswert im seriellen Excel-Format, kein Text.
                blatt.append("<c r=\"").append(bezug).append("\" s=\"2\"><v>")
                     .append(alsExcelDatum(t)).append("</v></c>");
            } else {
                blatt.append("<c r=\"").append(bezug).append("\" t=\"inlineStr\" s=\"")
                     .append(kopf ? 1 : 0).append("\"><is><t xml:space=\"preserve\">")
                     .append(xml(String.valueOf(w))).append("</t></is></c>");
            }
        }
        blatt.append("</row>");
    }

    private void blattAbschliessen() {
        blatt.append("</sheetData>");
        if (kopfzeileGeschrieben && spaltenzahl > 0) {
            String bis = spaltenbuchstabe(spaltenzahl - 1) + Math.max(zeilennummer, 1);
            blatt.append("<autoFilter ref=\"A1:").append(bis).append("\"/>");
        }
        blatt.append("</worksheet>");

        // Fixierung muss VOR sheetData stehen (Reihenfolge nach ECMA-376).
        String inhalt = blatt.toString();
        if (kopfzeileGeschrieben) {
            inhalt = inhalt.replace("<sheetData>", """
                    <sheetViews><sheetView workbookViewId="0" tabSelected="0">
                    <pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>
                    </sheetView></sheetViews><sheetData>""");
        }
        blattnamen.add(aktuellerName);
        fertigeBlaetter.add(inhalt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        aktuellerName = null;
    }

    @Override
    public void close() throws Exception {
        if (aktuellerName != null) blattAbschliessen();

        eintrag("[Content_Types].xml", inhaltstypen());
        eintrag("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Target="xl/workbook.xml"
                 Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"/>
                </Relationships>""");
        eintrag("xl/workbook.xml", arbeitsmappe());
        eintrag("xl/_rels/workbook.xml.rels", beziehungen());
        eintrag("xl/styles.xml", formatvorlagen());
        for (int i = 0; i < fertigeBlaetter.size(); i++) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + (i + 1) + ".xml"));
            zip.write(fertigeBlaetter.get(i));
            zip.closeEntry();
        }
        zip.finish();
    }

    // ---------------------------------------------------------------- Gerüst

    private void eintrag(String pfad, String inhalt) throws Exception {
        zip.putNextEntry(new ZipEntry(pfad));
        zip.write(inhalt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String inhaltstypen() {
        StringBuilder s = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""");
        for (int i = 1; i <= fertigeBlaetter.size(); i++) {
            s.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
             .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return s.append("</Types>").toString();
    }

    private String arbeitsmappe() {
        StringBuilder s = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets>""");
        for (int i = 0; i < blattnamen.size(); i++) {
            s.append("<sheet name=\"").append(xml(blattnamen.get(i)))
             .append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        return s.append("</sheets></workbook>").toString();
    }

    private String beziehungen() {
        StringBuilder s = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""");
        for (int i = 1; i <= fertigeBlaetter.size(); i++) {
            s.append("<Relationship Id=\"rId").append(i)
             .append("\" Target=\"worksheets/sheet").append(i)
             .append(".xml\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\"/>");
        }
        s.append("<Relationship Id=\"rId").append(fertigeBlaetter.size() + 1)
         .append("\" Target=\"styles.xml\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\"/>");
        return s.append("</Relationships>").toString();
    }

    /** Drei Vorlagen: 0 normal, 1 fett (Kopfzeile), 2 Datum mit Uhrzeit. */
    private String formatvorlagen() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <numFmts count="1">
                  <numFmt numFmtId="164" formatCode="DD.MM.YYYY\\ HH:MM:SS"/>
                </numFmts>
                <fonts count="2">
                  <font><sz val="11"/><name val="Calibri"/></font>
                  <font><b/><sz val="11"/><name val="Calibri"/></font>
                </fonts>
                <fills count="2"><fill><patternFill patternType="none"/></fill>
                  <fill><patternFill patternType="gray125"/></fill></fills>
                <borders count="1"><border/></borders>
                <cellStyleXfs count="1"><xf/></cellStyleXfs>
                <cellXfs count="3">
                  <xf xfId="0"/>
                  <xf xfId="0" fontId="1" applyFont="1"/>
                  <xf xfId="0" numFmtId="164" applyNumberFormat="1"/>
                </cellXfs>
                <!-- Ohne benannten Standardstil warnen Lesewerkzeuge (u. a. openpyxl),
                     die Mappe habe keinen. Die Datei ist dann zwar lesbar, aber eine
                     Warnung beim Oeffnen verunsichert Anwender ohne Not. -->
                <cellStyles count="1">
                  <cellStyle name="Standard" xfId="0" builtinId="0"/>
                </cellStyles>
                </styleSheet>""";
    }

    // ---------------------------------------------------------------- Hilfen

    /** Excel zählt Tage ab dem 30.12.1899 (einschließlich des Schaltjahrfehlers von 1900). */
    static double alsExcelDatum(Instant t) {
        LocalDateTime l = LocalDateTime.ofInstant(t, ZoneId.systemDefault());
        long tage = java.time.temporal.ChronoUnit.DAYS.between(
                java.time.LocalDate.of(1899, 12, 30), l.toLocalDate());
        double anteil = l.toLocalTime().toSecondOfDay() / 86400.0;
        return tage + anteil;
    }

    static String spaltenbuchstabe(int index) {
        StringBuilder s = new StringBuilder();
        int i = index;
        do { s.insert(0, (char) ('A' + i % 26)); i = i / 26 - 1; } while (i >= 0);
        return s.toString();
    }

    /** Excel-Blattnamen: höchstens 31 Zeichen, keine Sonderzeichen. */
    static String kuerzen(String name) {
        String bereinigt = name.replaceAll("[\\\\/?*\\[\\]:]", "-");
        return bereinigt.length() <= 31 ? bereinigt : bereinigt.substring(0, 31);
    }

    static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                default -> {
                    // Steuerzeichen sind in XML unzulässig und zerstören die Datei.
                    if (c >= 0x20 || c == '\t' || c == '\n') b.append(c);
                }
            }
        }
        return b.toString();
    }

    /** Für Tests: erzeugt die Mappe im Speicher. */
    public static byte[] imSpeicher(java.util.function.Consumer<Tabellenmappe> aufbau) {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        try (Tabellenmappe m = new Tabellenmappe(aus)) {
            aufbau.accept(m);
        } catch (Exception e) {
            throw new IllegalStateException("Arbeitsmappe nicht erzeugbar", e);
        }
        return aus.toByteArray();
    }
}
