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

    public Ereignis anfuegen(String einrichtung, String geraet, Ereignisart art,
                             boolean erfolg, String ausloeser, String vorgang,
                             String text, String handlung) {
        String sql = """
                INSERT INTO fachprotokoll
                  (einrichtung, geraet, art, stufe, erfolg, ausloeser, vorgang, text, handlung)
                VALUES (?,?,?,?,?,?,?,?,?)
                RETURNING id, zeitpunkt""";
        try (PreparedStatement p = db.verbindung().prepareStatement(sql)) {
            p.setString(1, einrichtung);
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

    public List<Ereignis> filtern(String geraet, String art, String ergebnis, int grenze) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, zeitpunkt, einrichtung, geraet, art, erfolg, ausloeser, vorgang, text, handlung
                FROM fachprotokoll WHERE 1=1""");
        List<Object> werte = new ArrayList<>();
        if (geraet != null && !geraet.isBlank())   { sql.append(" AND geraet = ?");  werte.add(geraet); }
        if (art != null && !art.isBlank())         { sql.append(" AND art = ?");     werte.add(art); }
        if (ergebnis != null && !ergebnis.isBlank()) {
            sql.append(" AND erfolg = ?"); werte.add("erfolg".equals(ergebnis));
        }
        sql.append(" ORDER BY zeitpunkt DESC, id DESC LIMIT ?");
        werte.add(grenze);

        List<Ereignis> ergebnisse = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement(sql.toString())) {
            for (int i = 0; i < werte.size(); i++) p.setObject(i + 1, werte.get(i));
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
                       int seitenNummer, int seitengroesse) {
        int nr = Math.max(1, seitenNummer);
        // Nach oben begrenzt, damit eine manipulierte Anfrage nicht das
        // gesamte Protokoll zieht - bei 200.000 Eintraegen waere das ein
        // Ausfall der Oberflaeche.
        int gr = Math.min(500, Math.max(1, seitengroesse));

        StringBuilder wo = new StringBuilder(" WHERE 1=1");
        List<Object> werte = new ArrayList<>();
        if (geraet != null && !geraet.isBlank())   { wo.append(" AND geraet = ?");  werte.add(geraet); }
        if (art != null && !art.isBlank())         { wo.append(" AND art = ?");     werte.add(art); }
        if (ergebnis != null && !ergebnis.isBlank()) {
            wo.append(" AND erfolg = ?"); werte.add("erfolg".equals(ergebnis));
        }

        long gesamt = 0;
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT count(*) FROM fachprotokoll" + wo)) {
            for (int i = 0; i < werte.size(); i++) p.setObject(i + 1, werte.get(i));
            try (ResultSet rs = p.executeQuery()) { if (rs.next()) gesamt = rs.getLong(1); }
        } catch (SQLException e) {
            System.err.println("Fachprotokoll nicht zählbar: " + e.getMessage());
        }

        List<Ereignis> eintraege = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT id, zeitpunkt, einrichtung, geraet, art, erfolg, ausloeser, vorgang, text, handlung
                FROM fachprotokoll""" + wo
                + " ORDER BY zeitpunkt DESC, id DESC LIMIT ? OFFSET ?")) {
            int i = 1;
            for (Object w : werte) p.setObject(i++, w);
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
