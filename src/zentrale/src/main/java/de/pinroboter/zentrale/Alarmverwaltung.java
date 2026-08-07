package de.pinroboter.zentrale;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Alarmzustände (FA-ALA-010).
 *
 * <p><b>Ein Alarm ist kein Protokolleintrag.</b> Das Protokoll sammelt
 * Ereignisse — was geschehen ist. Ein Alarm beschreibt einen <em>andauernden
 * Zustand</em> — was gerade nicht in Ordnung ist. Deshalb gibt es je Objekt und
 * Art höchstens einen offenen Alarm, per Datenbank-Index erzwungen. Ohne diese
 * Regel erzeugte ein alle vier Sekunden prüfender Agent 900 Alarme pro Stunde
 * für dasselbe Problem, und niemand würde mehr hinsehen.
 *
 * <p>Jeder Alarm trägt eine <b>Handlungsempfehlung im Klartext</b>. Eine Meldung,
 * die nur sagt was kaputt ist, hilft einer Praxismitarbeiterin nicht weiter
 * (NFA-USA-020).
 */
public class Alarmverwaltung {

    public enum Schweregrad {
        HINWEIS("Hinweis"), WARNUNG("Warnung"), KRITISCH("Kritisch");
        private final String anzeige;
        Schweregrad(String a) { anzeige = a; }
        public String anzeige() { return anzeige; }
        public String css() { return this == KRITISCH ? "fehler"
                : this == WARNUNG ? "warnung" : "unbekannt"; }
    }

    /**
     * Alarmarten. Die Liste ist fest — nur so lässt sich ein Alarm eindeutig
     * einem Zustand zuordnen und beim Verschwinden wieder schließen.
     */
    public enum Art {
        AUTOMATIK_ANGEHALTEN(Schweregrad.KRITISCH),
        KARTE_GESPERRT(Schweregrad.KRITISCH),
        KARTE_UNVERIFIZIERT(Schweregrad.WARNUNG),
        HANDLUNGSBEDARF_KARTE(Schweregrad.WARNUNG),
        GERAET_NICHT_ERREICHBAR(Schweregrad.WARNUNG),
        KONNEKTOR_NICHT_ERREICHBAR(Schweregrad.KRITISCH),
        AGENT_STUMM(Schweregrad.KRITISCH),
        PEVIM_NICHT_AKTIV(Schweregrad.HINWEIS),
        SPEICHER_KNAPP(Schweregrad.WARNUNG);

        private final Schweregrad vorgabe;
        Art(Schweregrad s) { vorgabe = s; }
        public Schweregrad schweregrad() { return vorgabe; }
    }

    public record Alarm(long id, String objekt, Art art, Schweregrad schweregrad,
                        String meldung, String handlung, Instant seit,
                        String quittiertVon, Instant quittiertAm) {
        public boolean quittiert() { return quittiertVon != null; }

        public String dauer() {
            long m = Duration.between(seit, Instant.now()).toMinutes();
            if (m < 1) return "gerade eben";
            if (m < 60) return "seit " + m + " min";
            long h = m / 60;
            return h < 24 ? "seit " + h + " h" : "seit " + (h / 24) + " Tagen";
        }
    }

    private final Datenbank db;

    public Alarmverwaltung(Datenbank db) { this.db = db; }

    /**
     * Meldet einen Zustand. Besteht der Alarm schon, wird nur der Zeitstempel
     * aufgefrischt — kein zweiter Eintrag, keine erneute Benachrichtigung.
     *
     * @return true, wenn der Alarm NEU ist (nur dann ist eine Benachrichtigung fällig)
     */
    public boolean melden(String einrichtung, String objekt, Art art,
                          String meldung, String handlung) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO alarm (einrichtung, objekt, art, schweregrad, meldung, handlung)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT (einrichtung, coalesce(objekt, ''), art) WHERE behoben_am IS NULL
                DO UPDATE SET zuletzt = now(), meldung = EXCLUDED.meldung,
                              handlung = EXCLUDED.handlung
                RETURNING (xmax = 0) AS ist_neu""")) {
            p.setString(1, einrichtung);
            p.setString(2, objekt);
            p.setString(3, art.name());
            p.setString(4, art.schweregrad().name());
            p.setString(5, meldung);
            p.setString(6, handlung);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() && rs.getBoolean("ist_neu");
            }
        } catch (SQLException e) { return false; }
    }

    /**
     * Schließt einen Alarm, weil der Zustand behoben ist (FA-ALA-010).
     *
     * <p>Automatisch, nicht von Hand: Ein Alarm, den jemand wegklicken muss,
     * obwohl das Problem längst weg ist, erzieht dazu, Alarme wegzuklicken.
     *
     * @return true, wenn tatsächlich einer geschlossen wurde
     */
    public boolean beheben(String einrichtung, String objekt, Art art) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                UPDATE alarm SET behoben_am = now()
                WHERE einrichtung = ? AND coalesce(objekt,'') = coalesce(?,'')
                  AND art = ? AND behoben_am IS NULL""")) {
            p.setString(1, einrichtung);
            p.setString(2, objekt);
            p.setString(3, art.name());
            return p.executeUpdate() > 0;
        } catch (SQLException e) { return false; }
    }

    public boolean quittieren(long id, String benutzer) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                UPDATE alarm SET quittiert_von = ?, quittiert_am = now()
                WHERE id = ? AND behoben_am IS NULL AND quittiert_von IS NULL""")) {
            p.setString(1, benutzer);
            p.setLong(2, id);
            return p.executeUpdate() == 1;
        } catch (SQLException e) { return false; }
    }

    public List<Alarm> offene(String einrichtung) {
        List<Alarm> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT id, objekt, art, schweregrad, meldung, handlung, seit,
                       quittiert_von, quittiert_am
                FROM alarm WHERE einrichtung = ? AND behoben_am IS NULL
                ORDER BY CASE schweregrad WHEN 'KRITISCH' THEN 0 WHEN 'WARNUNG' THEN 1
                                          ELSE 2 END,
                         quittiert_von NULLS FIRST, seit""")) {
            p.setString(1, einrichtung);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    Timestamp q = rs.getTimestamp("quittiert_am");
                    liste.add(new Alarm(rs.getLong("id"), rs.getString("objekt"),
                            Art.valueOf(rs.getString("art")),
                            Schweregrad.valueOf(rs.getString("schweregrad")),
                            rs.getString("meldung"), rs.getString("handlung"),
                            rs.getTimestamp("seit").toInstant(),
                            rs.getString("quittiert_von"), q == null ? null : q.toInstant()));
                }
            }
        } catch (SQLException ignored) { }
        return liste;
    }

    /** Zahl der offenen, noch nicht quittierten Alarme je Schweregrad. */
    public Map<Schweregrad, Integer> zaehlen(String einrichtung) {
        Map<Schweregrad, Integer> m = new EnumMap<>(Schweregrad.class);
        for (Schweregrad s : Schweregrad.values()) m.put(s, 0);
        for (Alarm a : offene(einrichtung)) {
            if (!a.quittiert()) m.merge(a.schweregrad(), 1, Integer::sum);
        }
        return m;
    }
}
