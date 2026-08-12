package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Ereignisart;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fachprotokoll — nur anfügbar, filterbar, mit Speicherbudget (ADR-0005).
 *
 * <p>Liegt in der Datenbank, nicht im Arbeitsspeicher: Es ist der Nachweis über
 * das selbsttätige Handeln des Roboters und muss Neustarts überstehen.
 *
 * <p>Die Grundregel gilt oberhalb dieser Klasse: <b>protokolliert werden
 * Zustandsänderungen und Handlungen, keine Abfragen</b>. Bei 5.000 Terminals im
 * Minutentakt entstünden sonst 7,2 Millionen Einträge am Tag.
 */
public class Fachprotokoll {

    private final Datenbank db;
    private final Speicherwaechter waechter;

    public Fachprotokoll(Datenbank db, Speicherwaechter waechter) {
        this.db = db;
        this.waechter = waechter;
    }

    /**
     * Schreibt einen Eintrag.
     *
     * <p>Die Einrichtung wird als Text UND als Verweis abgelegt: Der Text ist
     * der unveränderliche Inhalt des Protokolls, der Verweis trägt die
     * Mandantengrenze. Ein Verweis allein genügt nicht — würde die Organisation
     * umbenannt oder gelöscht, wäre der Eintrag nicht mehr lesbar, und das
     * widerspräche der Unveränderlichkeit (FA-PRO-010).
     */
    /**
     * Die Einrichtung für Vorgänge, die zu <b>keiner</b> Einrichtung gehören.
     *
     * <h2>Warum nicht mehr „Praxis"</h2>
     *
     * Bis zum 12.08.2026 trugen instanzweite Vorgänge — Anmeldungen,
     * Benutzerverwaltung, Sicherungen, Protokollrotation — die Einrichtung
     * {@code "Praxis"}. Das funktionierte, solange es keine Organisationseinheit
     * dieses Namens gab: Ein Eintrag, dessen Einrichtungsname zu keiner
     * Organisation passt, bekommt keine {@code organisation_id} und bleibt für
     * eingeschränkte Benutzer unsichtbar.
     *
     * <p><b>Der Platzhalter konnte aber jederzeit zu einer echten Einrichtung
     * werden.</b> Legt jemand eine Praxis mit diesem Namen an, kippen
     * schlagartig alle Instanzereignisse in ihren Geltungsbereich — und ein
     * Praxisadministrator sieht Anmeldungen, Sicherungen und
     * Benutzeränderungen der gesamten Instanz. Eine Mandantengrenze, die von
     * einem frei wählbaren Namen abhängt, ist keine.
     *
     * <p>Die Klammern sind Absicht: Der Wert soll als Kennzeichnung lesbar sein
     * und nicht wie ein Praxisname aussehen. Zusätzlich weist die
     * Organisationsverwaltung diesen Namen ab — die Kollision ist damit nicht
     * nur unwahrscheinlich, sondern ausgeschlossen.
     */
    public static final String INSTANZ = "(instanzweit)";

    /**
     * Die Einrichtung, unter der ein Ereignis zu stehen hat.
     *
     * <p>Bei einem gerätebezogenen Ereignis die Einrichtung des Geräts, sonst
     * die vom Aufrufer genannte. Ist das Gerät unbekannt — etwa weil es gerade
     * erst erfasst wird —, bleibt die genannte stehen: Sie ist dann das Beste,
     * was es gibt.
     */
    private String einrichtungDesGeraets(String geraet, String vorgabe) {
        if (geraet == null || geraet.isBlank()) return vorgabe;
        // COALESCE mit zwei Unterabfragen statt UNION ALL + LIMIT: Ohne
        // ORDER BY ist die Reihenfolge einer Union nicht festgelegt, und die
        // Abfrage lieferte mal den Bestand, mal die Meldung des Agenten. Ein
        // Ergebnis, das von der Laune des Planers abhängt, ist keins.
        //
        // Der BESTAND hat Vorrang: Er ist die gepflegte Zuordnung, die Meldung
        // des Agenten nur ihr Abbild.
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT coalesce(
                    (SELECT o.bezeichnung FROM kartenterminal t
                       JOIN organisation o ON o.id = t.organisation_id
                      WHERE t.terminal_id = ? LIMIT 1),
                    (SELECT g.einrichtung FROM geraet g WHERE g.terminal_id = ? LIMIT 1))""")) {
            p.setString(1, geraet);
            p.setString(2, geraet);
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) {
                    String e = rs.getString(1);
                    if (e != null && !e.isBlank()) return e;
                }
            }
        } catch (SQLException e) {
            // Kein Abbruch: Ein Ereignis gehoert protokolliert, notfalls mit
            // der genannten Einrichtung.
            System.err.println("Einrichtung des Geräts nicht ermittelbar: " + e.getMessage());
        }
        return vorgabe;
    }

    public Ereignis anfuegen(String einrichtung, String geraet, Ereignisart art,
                             boolean erfolg, String ausloeser, String vorgang,
                             String text, String handlung) {
        String sql = """
                INSERT INTO fachprotokoll
                  (einrichtung, geraet, art, stufe, erfolg, ausloeser, vorgang, text, handlung,
                   organisation_id)
                VALUES (?,?,?,?,?,?,?,?,?,
                        -- Verweis aus dem Namen, sofern eindeutig. Mehrdeutige
                        -- Namen bleiben ohne Zuordnung: lieber eine Luecke, die
                        -- auffaellt, als eine falsche Zuordnung.
                        (SELECT o.id FROM organisation o WHERE o.bezeichnung = ?
                         AND (SELECT count(*) FROM organisation x WHERE x.bezeichnung = ?) = 1))
                RETURNING id, zeitpunkt""";
        // Trägt der Eintrag ein GERÄT, gilt dessen Einrichtung — nicht die des
        // Aufrufers.
        //
        // Zentral erzwungen statt an jeder Aufrufstelle gepflegt, und zwar aus
        // Erfahrung: Ein Agent ist für EINE Einrichtung registriert, betreut in
        // einem MVZ aber Geräte mehrerer Betriebsstätten. Am 12.08.2026 standen
        // deshalb Erreichbarkeit und Erkennung eines Geräts der Orthopädie
        // unter „Kardiologie Nord" — dem Namen aus der Agentregistrierung. Für
        // den Zuständigen der Orthopädie war das Ereignis unsichtbar.
        //
        // Dieselbe Überlegung wie beim Geltungsbereich: Was an jeder Stelle von
        // Hand richtig gemacht werden muss, wird irgendwo vergessen.
        String tatsaechlich = einrichtungDesGeraets(geraet, einrichtung);

        try (PreparedStatement p = db.verbindung().prepareStatement(sql)) {
            p.setString(10, tatsaechlich);
            p.setString(11, tatsaechlich);
            p.setString(1, tatsaechlich);
            p.setString(2, geraet);
            p.setString(3, art.name());
            p.setString(4, art.stufe().name());
            p.setBoolean(5, erfolg);
            p.setString(6, ausloeser);
            p.setString(7, vorgang);
            p.setString(8, text);
            p.setString(9, handlung);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return new Ereignis(rs.getLong("id"),
                        rs.getTimestamp("zeitpunkt").toInstant(),
                        einrichtung, geraet, art, erfolg, ausloeser, vorgang, text, handlung);
            }
        } catch (SQLException e) {
            // Ein Fehler beim Protokollieren darf die Kernfunktion nicht anhalten
            // (ADR-0005). Er wird technisch gemeldet, nicht fachlich weitergereicht.
            System.err.println("Fachprotokoll nicht schreibbar: " + e.getMessage());
            return new Ereignis(-1, Instant.now(), einrichtung, geraet, art,
                    erfolg, ausloeser, vorgang, text, handlung);
        }
    }

    /**
     * Gefilterte Liste — speist den Excel-Export.
     *
     * <p>Auch hier gilt die Mandantengrenze. Ein Export, der mehr enthält als
     * die Ansicht, wäre der bequemste Weg an fremde Daten.
     */
    public List<Ereignis> filtern(String geraet, String art, String ergebnis, int grenze,
                                  Geltungsbereich bereich) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, zeitpunkt, einrichtung, geraet, art, erfolg, ausloeser, vorgang, text, handlung
                FROM fachprotokoll WHERE 1=1""");
        List<Object> werte = new ArrayList<>();
        sql.append(bereich.bedingung("organisation_id"));
        if (!bereich.unbeschraenkt() && !bereich.leer()) werte.add(bereich);
        if (geraet != null && !geraet.isBlank())   { sql.append(" AND geraet = ?");  werte.add(geraet); }
        if (art != null && !art.isBlank())         { sql.append(" AND art = ?");     werte.add(art); }
        if (ergebnis != null && !ergebnis.isBlank()) {
            sql.append(" AND erfolg = ?"); werte.add("erfolg".equals(ergebnis));
        }
        sql.append(" ORDER BY zeitpunkt DESC, id DESC LIMIT ?");
        werte.add(grenze);

        List<Ereignis> ergebnisse = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement(sql.toString())) {
            fuellen(p, werte);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    Ereignisart a;
                    try { a = Ereignisart.valueOf(rs.getString("art")); }
                    catch (Exception e) { continue; }
                    ergebnisse.add(new Ereignis(rs.getLong("id"),
                            rs.getTimestamp("zeitpunkt").toInstant(),
                            rs.getString("einrichtung"), rs.getString("geraet"), a,
                            rs.getBoolean("erfolg"), rs.getString("ausloeser"),
                            rs.getString("vorgang"), rs.getString("text"),
                            rs.getString("handlung")));
                }
            }
        } catch (SQLException e) {
            System.err.println("Fachprotokoll nicht lesbar: " + e.getMessage());
        }
        return ergebnisse;
    }

    /**
     * Eine Seite der gefilterten Protokollansicht (ADR-0005).
     *
     * <p><b>Warum das nötig wurde:</b> Bis zum 07.08.2026 zeigte die Ansicht die
     * 200 jüngsten Einträge und konnte nicht zurückblättern. Wer einen Vorgang
     * von gestern nachvollziehen wollte, musste exportieren. Für eine
     * Nachvollziehbarkeit, die der Roboter seinen Betreibern schuldet, ist das
     * zu wenig — er handelt selbsttätig, und <i>jede</i> Handlung muss im
     * Nachhinein auffindbar sein, nicht nur die der letzten Stunde.
     *
     * @param seitenNummer 1-basiert
     */
    public Seite seite(String geraet, String art, String ergebnis,
                       int seitenNummer, int seitengroesse, Geltungsbereich bereich) {
        return seite(geraet, art, ergebnis, seitenNummer, seitengroesse, bereich, false);
    }

    /**
     * @param aelteste zuerst die ältesten Einträge statt der neuesten
     *
     * <p>Beide Richtungen laufen über {@code fachprotokoll_zeit_idx}: Ein
     * Btree-Index ist in beide Richtungen lesbar, ein zweiter Index wäre
     * verschwendeter Platz.
     */
    public Seite seite(String geraet, String art, String ergebnis,
                       int seitenNummer, int seitengroesse, Geltungsbereich bereich,
                       boolean aelteste) {
        return seite(geraet, art == null || art.isBlank() ? List.of() : List.of(art),
                ergebnis, null, null, seitenNummer, seitengroesse, bereich, aelteste);
    }

    /**
     * Wie oben, aber mit Zeitraum und mehreren Ereignisarten (FA-PRO-050).
     *
     * <p>Der Zeitstrahl eines Geräts braucht beides: „die letzten 7 Tage" und
     * „ohne Statuswechsel". Ein Filter auf genau eine Art genügt dafür nicht —
     * wer Verifikationen und Firmware-Wechsel sehen will, aber nicht die
     * Statuswechsel, kann das sonst nicht ausdrücken.
     *
     * @param arten leer heißt: alle
     * @param seit  {@code null} heißt: ohne Untergrenze
     * @param vorgang genau ein Vorgang (FA-PRO-060), oder {@code null}
     */
    public Seite seite(String geraet, List<String> arten, String ergebnis,
                       java.time.Instant seit, String vorgang,
                       int seitenNummer, int seitengroesse, Geltungsbereich bereich) {
        return seite(geraet, arten, ergebnis, seit, vorgang, seitenNummer, seitengroesse,
                bereich, false);
    }

    public Seite seite(String geraet, List<String> arten, String ergebnis,
                       java.time.Instant seit, String vorgang,
                       int seitenNummer, int seitengroesse, Geltungsbereich bereich,
                       boolean aelteste) {
        int nr = Math.max(1, seitenNummer);
        // Nach oben begrenzt, damit eine manipulierte Anfrage nicht das
        // gesamte Protokoll zieht - bei 200.000 Eintraegen waere das ein
        // Ausfall der Oberflaeche.
        int gr = Math.min(500, Math.max(1, seitengroesse));

        StringBuilder wo = new StringBuilder(" WHERE 1=1");
        List<Object> werte = new ArrayList<>();
        // Mandantengrenze zuerst - sie steht in jeder Abfrage an derselben
        // Stelle und faellt bei einer Durchsicht sofort auf.
        //
        // Eintraege OHNE Zuordnung (organisation_id IS NULL) bleiben fuer
        // eingeschraenkte Benutzer unsichtbar. Das betrifft Vorgaenge ohne
        // Einrichtungsbezug - Anmeldungen, Rotation, Instanzverwaltung. Sie
        // gehen den Instanzbetreiber an, nicht eine einzelne Praxis.
        wo.append(bereich.bedingung("organisation_id"));
        if (!bereich.unbeschraenkt() && !bereich.leer()) werte.add(bereich);

        if (geraet != null && !geraet.isBlank())   { wo.append(" AND geraet = ?");  werte.add(geraet); }
        if (vorgang != null && !vorgang.isBlank()) { wo.append(" AND vorgang = ?"); werte.add(vorgang); }
        if (arten != null && !arten.isEmpty()) {
            wo.append(" AND art = ANY (?)");
            werte.add(arten.toArray(new String[0]));
        }
        if (seit != null) { wo.append(" AND zeitpunkt >= ?"); werte.add(java.sql.Timestamp.from(seit)); }
        if (ergebnis != null && !ergebnis.isBlank()) {
            wo.append(" AND erfolg = ?"); werte.add("erfolg".equals(ergebnis));
        }

        long gesamt = 0;
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT count(*) FROM fachprotokoll" + wo)) {
            fuellen(p, werte);
            try (ResultSet rs = p.executeQuery()) { if (rs.next()) gesamt = rs.getLong(1); }
        } catch (SQLException e) {
            System.err.println("Fachprotokoll nicht zählbar: " + e.getMessage());
        }

        List<Ereignis> eintraege = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT id, zeitpunkt, einrichtung, geraet, art, erfolg, ausloeser, vorgang, text, handlung
                FROM fachprotokoll""" + wo
                + (aelteste ? " ORDER BY zeitpunkt ASC, id ASC"
                            : " ORDER BY zeitpunkt DESC, id DESC")
                + " LIMIT ? OFFSET ?")) {
            int i = fuellen(p, werte);
            p.setInt(i++, gr);
            p.setLong(i, (long) (nr - 1) * gr);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    Ereignisart a;
                    try { a = Ereignisart.valueOf(rs.getString("art")); }
                    catch (Exception e) { continue; }
                    eintraege.add(new Ereignis(rs.getLong("id"),
                            rs.getTimestamp("zeitpunkt").toInstant(),
                            rs.getString("einrichtung"), rs.getString("geraet"), a,
                            rs.getBoolean("erfolg"), rs.getString("ausloeser"),
                            rs.getString("vorgang"), rs.getString("text"),
                            rs.getString("handlung")));
                }
            }
        } catch (SQLException e) {
            System.err.println("Fachprotokoll nicht lesbar: " + e.getMessage());
        }
        return new Seite(eintraege, nr, gr, gesamt);
    }

    /** Setzt Filterwerte typrichtig; ein {@link Geltungsbereich} bringt sein Array selbst mit. */
    private static int fuellen(PreparedStatement p, List<Object> werte) throws SQLException {
        int i = 1;
        for (Object w : werte) {
            if (w instanceof Geltungsbereich g) i = g.wertSetzen(p.getConnection(), p, i);
            // Ein String[] muss als Datenbank-Array uebergeben werden;
            // setObject reicht es sonst als unbekannten Typ durch und die
            // Abfrage scheitert zur Laufzeit.
            else if (w instanceof String[] a) p.setArray(i++, p.getConnection().createArrayOf("text", a));
            else p.setObject(i++, w);
        }
        return i;
    }

    /** Eine Seite der Protokollansicht mit den Angaben für die Blätterleiste. */
    public record Seite(List<Ereignis> eintraege, int seitenNummer, int seitengroesse, long gesamt) {
        public int seitenZahl()      { return (int) Math.max(1, (gesamt + seitengroesse - 1) / seitengroesse); }
        public boolean hatVorherige() { return seitenNummer > 1; }
        public boolean hatNaechste()  { return seitenNummer < seitenZahl(); }
        /** Nummer des ersten Eintrags dieser Seite, 1-basiert — für „21 bis 40 von 137“. */
        public long von() { return gesamt == 0 ? 0 : (long) (seitenNummer - 1) * seitengroesse + 1; }
        public long bis() { return Math.min(gesamt, (long) seitenNummer * seitengroesse); }
    }

    public long anzahl() {
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM fachprotokoll")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) { return 0; }
    }
}
