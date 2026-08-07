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

    public long anzahl() {
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM fachprotokoll")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) { return 0; }
    }
}
