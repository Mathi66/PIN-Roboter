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
        SPEICHER_KNAPP(Schweregrad.WARNUNG),
        // Eine ausgebliebene Sicherung merkt man erst, wenn man sie braucht —
        // deshalb ein Alarm und keine Logzeile (FA-BAK-010).
        SICHERUNG_FEHLGESCHLAGEN(Schweregrad.WARNUNG),
        // Die selbsttätige Aktualisierung ist eingeschaltet, aber der Agent
        // läuft seit Tagen auf einer älteren Version als die Zentrale. Warum,
        // weiß hier niemand — abgelaufener Token, angehaltener Watchtower,
        // nicht erreichbare Programmablage. Gemeldet wird deshalb die WIRKUNG:
        // Die Fehlerbehebungen, für die das Ganze gebaut wurde, kommen nicht an.
        AGENT_VERALTET(Schweregrad.WARNUNG);

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
    /**
     * Meldet einen Alarm. Die Einrichtung wird als Text und als Verweis
     * abgelegt — der Verweis trägt die Mandantengrenze.
     */
    public boolean melden(String einrichtung, String objekt, Art art,
                          String meldung, String handlung) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO alarm (einrichtung, objekt, art, schweregrad, meldung, handlung,
                                   organisation_id)
                VALUES (?,?,?,?,?,?,
                        (SELECT o.id FROM organisation o WHERE o.bezeichnung = ?
                         AND (SELECT count(*) FROM organisation x WHERE x.bezeichnung = ?) = 1))
                ON CONFLICT (einrichtung, coalesce(objekt, ''), art) WHERE behoben_am IS NULL
                DO UPDATE SET zuletzt = now(), meldung = EXCLUDED.meldung,
                              handlung = EXCLUDED.handlung
                RETURNING (xmax = 0) AS ist_neu""")) {
            p.setString(7, einrichtung);
            p.setString(8, einrichtung);
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

    /**
     * Quittiert einen Alarm: „zur Kenntnis genommen".
     *
     * <p><b>Quittieren behebt nichts.</b> {@code behoben_am} bleibt leer, der
     * Alarm besteht weiter, solange der Zustand besteht. Sonst ließe sich ein
     * echtes Problem per Mausklick verschwinden lassen — und genau das ist der
     * Fehler, den man in einer Praxis unter Zeitdruck macht.
     *
     * <p>Der Geltungsbereich ist <b>Pflichtparameter</b>. Ein Alarm nennt Gerät
     * und Betriebszustand einer Einrichtung; ihn quittieren zu können hieße,
     * fremde Vorgänge stillzustellen.
     *
     * @return ob quittiert wurde. {@code false} auch dann, wenn der Alarm
     *         außerhalb des Bereichs liegt — der Aufrufer erfährt nicht, ob es
     *         ihn gibt.
     */
    public boolean quittieren(long id, String benutzer, Geltungsbereich bereich) {
        if (bereich.leer()) return false;
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                UPDATE alarm SET quittiert_von = ?, quittiert_am = now()
                WHERE id = ? AND behoben_am IS NULL AND quittiert_von IS NULL"""
                + bereich.bedingung("organisation_id"))) {
            p.setString(1, benutzer);
            p.setLong(2, id);
            bereich.wertSetzen(db.verbindung(), p, 3);
            return p.executeUpdate() == 1;
        } catch (SQLException e) { return false; }
    }

    /**
     * Schließt einen Alarm von Hand (Vorgabe PO, 13.08.2026).
     *
     * <h2>Warum es das braucht — und warum es nicht „löschen" heißt</h2>
     *
     * {@link #beheben} wird ausgelöst, wenn ein Agent meldet, dass der Zustand
     * weg ist. Für ein Gerät, das es <b>nicht mehr gibt</b>, kommt diese
     * Meldung nie: Der Alarm bleibt ewig offen. Am 13.08.2026 standen so elf
     * Alarme in der Lage-Übersicht, jeder einzelne zu einem Kartenterminal aus
     * einem längst gelöschten Demo- oder Prüfbestand.
     *
     * <p><b>Gelöscht wird nichts.</b> Der Datensatz bleibt, {@code behoben_am}
     * wird gesetzt — der Alarm verschwindet damit aus der Liste der offenen,
     * bleibt aber nachvollziehbar (ADR-0005). Ein echtes {@code DELETE} würde
     * die Frage „warum war das Gerät drei Wochen lang nicht erreichbar?"
     * unbeantwortbar machen.
     *
     * <p><b>Es hebt das Problem nicht auf.</b> Besteht die Ursache weiter,
     * meldet der Agent den Alarm im nächsten Prüflauf erneut. Das ist gewollt:
     * Ein Wegklicken, das eine echte Störung dauerhaft verschwinden ließe, wäre
     * genau der Fehler, den man unter Zeitdruck macht.
     *
     * @return ob geschlossen wurde; {@code false} auch außerhalb des Bereichs
     */
    public boolean schliessen(long id, Geltungsbereich bereich) {
        if (bereich.leer()) return false;
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE alarm SET behoben_am = now() WHERE id = ? AND behoben_am IS NULL"
                + bereich.bedingung("organisation_id"))) {
            p.setLong(1, id);
            bereich.wertSetzen(db.verbindung(), p, 2);
            return p.executeUpdate() == 1;
        } catch (SQLException e) { return false; }
    }

    /**
     * Schließt alle offenen Alarme des Geltungsbereichs auf einmal.
     *
     * <p>Der Geltungsbereich ist auch hier die Grenze: Eine Sammelaktion, die
     * versehentlich fremde Mandanten trifft, ist schlimmer als gar keine. Wer
     * für eine Betriebsstätte zuständig ist, räumt nur deren Alarme weg.
     *
     * @return wie viele geschlossen wurden — die Zahl gehört in die Rückmeldung
     *         und ins Protokoll, sonst weiß hinterher niemand, was passiert ist
     */
    public int alleSchliessen(Geltungsbereich bereich) {
        if (bereich.leer()) return 0;
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE alarm SET behoben_am = now() WHERE behoben_am IS NULL"
                + bereich.bedingung("organisation_id"))) {
            bereich.wertSetzen(db.verbindung(), p, 1);
            return p.executeUpdate();
        } catch (SQLException e) { return 0; }
    }

    /**
     * Offene Alarme im Geltungsbereich.
     *
     * <p>Ein Alarm nennt Gerät und Handlungsempfehlung — er verrät damit
     * Bestand und Betriebszustand einer Einrichtung. Die Mandantengrenze gilt
     * hier genauso wie beim Bestand selbst.
     */
    /**
     * Alle offenen Alarme im Geltungsbereich — über <b>alle</b> Einrichtungen.
     *
     * <h2>Warum es diese Methode geben muss</h2>
     *
     * Die Lage-Übersicht rief bis zum 11.08.2026 {@link #offene(String,
     * Geltungsbereich)} mit fest {@code "Praxis"} auf. In einer Installation
     * mit mehreren Betriebsstätten zeigte die wichtigste Seite des Produkts
     * damit <b>keinen einzigen Alarm</b> — es sei denn, eine Einrichtung hieß
     * zufällig „Praxis".
     *
     * <p>Der Geltungsbereich ist die richtige und einzige Grenze: Er sagt
     * bereits, was jemand sehen darf. Eine zusätzliche Einschränkung auf eine
     * einzelne Einrichtung war nie eine Sicherheitsmaßnahme, sondern ein Rest
     * aus der Zeit, als es nur eine gab.
     */
    public List<Alarm> offene(Geltungsbereich bereich) {
        List<Alarm> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT id, objekt, art, schweregrad, meldung, handlung, seit,
                       quittiert_von, quittiert_am, einrichtung
                FROM alarm WHERE behoben_am IS NULL"""
                + bereich.bedingung("organisation_id")
                + " ORDER BY CASE schweregrad WHEN 'KRITISCH' THEN 0 WHEN 'WARNUNG' THEN 1"
                + " ELSE 2 END, quittiert_von NULLS FIRST, seit")) {
            bereich.wertSetzen(db.verbindung(), p, 1);
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
        } catch (SQLException e) {
            System.err.println("Alarme nicht lesbar: " + e.getMessage());
        }
        return liste;
    }

    public List<Alarm> offene(String einrichtung, Geltungsbereich bereich) {
        List<Alarm> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT id, objekt, art, schweregrad, meldung, handlung, seit,
                       quittiert_von, quittiert_am
                FROM alarm WHERE einrichtung = ? AND behoben_am IS NULL"""
                + bereich.bedingung("organisation_id")
                + " ORDER BY CASE schweregrad WHEN 'KRITISCH' THEN 0 WHEN 'WARNUNG' THEN 1"
                + " ELSE 2 END, quittiert_von NULLS FIRST, seit")) {
            p.setString(1, einrichtung);
            bereich.wertSetzen(db.verbindung(), p, 2);
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
        }
        catch (SQLException e) {
            // Nicht verschlucken: Ein stiller SQL-Fehler ist praktisch
            // unauffindbar - so blieb ein fehlerhaftes WHERE monatelang
            // unentdeckt. Der Aufruf schlaegt trotzdem nicht fehl.
            System.err.println("Alarmverwaltung.offene: " + e.getMessage());
        }
        return liste;
    }

    /** Zahl der offenen, noch nicht quittierten Alarme je Schweregrad. */
    public Map<Schweregrad, Integer> zaehlen(String einrichtung) {
        Map<Schweregrad, Integer> m = new EnumMap<>(Schweregrad.class);
        for (Schweregrad s : Schweregrad.values()) m.put(s, 0);
        for (Alarm a : offene(einrichtung, Geltungsbereich.alles())) {
            if (!a.quittiert()) m.merge(a.schweregrad(), 1, Integer::sum);
        }
        return m;
    }
}
