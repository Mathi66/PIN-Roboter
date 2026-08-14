package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Ereignisart;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Gezielte Aktualisierung eines Standort-Agenten (FA-AGT-100, PO 13.08.2026).
 *
 * <h2>Warum zusätzlich zum nächtlichen Lauf</h2>
 *
 * Der nächtliche Lauf zieht irgendwann alle Agenten nach. Das genügt nicht,
 * wenn eine Fehlerbehebung an <b>genau einem</b> Standort gebraucht wird — oder
 * wenn jemand vor dem Rollout an einem Standort sehen will, ob die neue Fassung
 * trägt. Beides ist am Anfang eines Produkts der Regelfall, nicht die Ausnahme.
 *
 * <h2>Wer was tut</h2>
 *
 * <pre>
 * Administrator  →  Auftrag ANGEFORDERT (Zielversion, wer, wann)
 * Agent (holt)   →  ruft die Aktualisierung an  →  ANGESTOSSEN
 *                   danach verschwindet sein Container
 * Agent (neu)    →  meldet seine Version  →  ERFOLGREICH oder FEHLGESCHLAGEN
 * </pre>
 *
 * <p><b>Der Agent quittiert, bevor er neu startet.</b> Danach kann er nichts
 * mehr sagen — sein Container wird ersetzt. Ob es geklappt hat, entscheidet
 * deshalb nicht seine Rückmeldung, sondern die <b>Version, mit der er
 * wiederkommt</b>. Das ist auch die ehrlichere Prüfung: Sie misst das Ergebnis
 * und nicht die Absicht.
 *
 * <h2>Was passiert, wenn er nicht wiederkommt</h2>
 *
 * Nach {@link #FRIST} gilt der Auftrag als gescheitert. Das ist wichtig: Ein
 * Auftrag, der ewig „läuft", sieht aus wie Fortschritt. Der Fehlschlag nennt
 * den wahrscheinlichsten Grund und was zu tun ist — und der Agent selbst läuft
 * in aller Regel weiter, denn ein fehlgeschlagener Abruf des Abbilds lässt den
 * alten Container unangetastet.
 */
public class Agentenaktualisierung {

    /**
     * Wie lange ein angestoßenes Update dauern darf.
     *
     * <p>Abbild holen, Container ersetzen, hochfahren, erste Meldung: Das sind
     * im LAN Sekunden, über eine schwache Praxisleitung ein paar Minuten. Zehn
     * Minuten sind großzügig und trotzdem kurz genug, dass niemand vor einer
     * Anzeige wartet, die nie fertig wird.
     */
    public static final Duration FRIST = Duration.ofMinutes(10);

    public enum Status {
        ANGEFORDERT("angefordert"),
        ANGESTOSSEN("läuft"),
        ERFOLGREICH("erfolgreich"),
        FEHLGESCHLAGEN("fehlgeschlagen"),
        ABGEBROCHEN("abgebrochen");

        private final String anzeige;
        Status(String a) { anzeige = a; }
        public String anzeige() { return anzeige; }
        public boolean offen() { return this == ANGEFORDERT || this == ANGESTOSSEN; }
    }

    /** Ein Auftrag, wie ihn die Oberfläche zeigt. */
    public record Auftrag(long id, long agentId, String zielversion, String vonVersion,
                          Status status, Instant angefordertAm, String angefordertVon,
                          Instant abgeschlossenAm, String meldung, boolean zurKenntnis) {

        /** Wie lange der Auftrag schon offen ist — für die Anzeige „seit 2 min“. */
        public String dauer() {
            Instant bis = abgeschlossenAm != null ? abgeschlossenAm : Instant.now();
            long s = Duration.between(angefordertAm, bis).toSeconds();
            if (s < 60) return s + " s";
            long m = s / 60;
            return m < 60 ? m + " min" : (m / 60) + " h " + (m % 60) + " min";
        }
    }

    private final Datenbank db;
    private final Fachprotokoll protokoll;

    public Agentenaktualisierung(Datenbank db, Fachprotokoll protokoll) {
        this.db = db;
        this.protokoll = protokoll;
    }

    /**
     * Fordert die Aktualisierung eines Agenten an.
     *
     * @return Fehlertext im Klartext, oder {@code null} bei Erfolg
     */
    public String anfordern(long agentId, String zielversion, String benutzer,
                            String einrichtung) {
        if (zielversion == null || zielversion.isBlank()) {
            return "Bitte die Version angeben, auf die aktualisiert werden soll.";
        }
        String ziel = zielversion.trim();

        String laufend = laufendeVersion(agentId);
        if (ziel.equals(laufend)) {
            return "Dieser Agent läuft bereits auf Version " + ziel + ".";
        }

        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO agent_aktualisierung
                       (agent_id, zielversion, von_version, status, angefordert_von)
                VALUES (?,?,?,'ANGEFORDERT',?)""")) {
            p.setLong(1, agentId);
            p.setString(2, ziel);
            p.setString(3, laufend);
            p.setString(4, benutzer == null ? "unbekannt" : benutzer);
            p.executeUpdate();
        } catch (SQLException e) {
            // Der Teilindex lässt nur einen offenen Auftrag je Agent zu. Zwei
            // gleichzeitige Anstöße hießen zwei Neustarts, und keiner wüsste,
            // welcher gewirkt hat.
            if (offener(agentId) != null) {
                return "Für diesen Agenten läuft bereits eine Aktualisierung. "
                     + "Bitte deren Ergebnis abwarten.";
            }
            System.err.println("Aktualisierung nicht anforderbar: " + e.getMessage());
            return "Die Anforderung ließ sich nicht speichern. Bitte erneut versuchen.";
        }

        protokoll.anfuegen(einrichtung, "Standort-Agent " + agentId,
                Ereignisart.AGENT_AKTUALISIERUNG, true, benutzer, null,
                "Aktualisierung des Standort-Agenten auf Version " + ziel + " angefordert"
                + (laufend == null ? "" : " (bisher " + laufend + ")"), null);
        return null;
    }

    /** Der offene Auftrag eines Agenten, oder {@code null}. */
    public Auftrag offener(long agentId) {
        return einer("agent_id = ? AND status IN ('ANGEFORDERT','ANGESTOSSEN')", agentId);
    }

    /** Der jüngste Auftrag eines Agenten, egal in welchem Zustand. */
    public Auftrag letzter(long agentId) {
        return einer("agent_id = ?", agentId);
    }

    /**
     * Der Agent hat den Auftrag angenommen und die Aktualisierung angestoßen.
     *
     * <p>Ab hier ist Schweigen zu erwarten: Sein Container wird ersetzt.
     *
     * @param meldung was der Agent berichtet, oder {@code null}
     * @param erfolg  ob der Anstoß überhaupt gelungen ist. Scheitert schon er —
     *                etwa weil die Aktualisierung gar nicht mitläuft —, ist der
     *                Auftrag sofort erledigt und muss nicht zehn Minuten lang
     *                so aussehen, als arbeite jemand daran.
     */
    public void angestossen(long agentId, boolean erfolg, String meldung) {
        Auftrag a = offener(agentId);
        if (a == null) return;
        if (erfolg) {
            aktualisieren(a.id(), Status.ANGESTOSSEN, null, meldung);
        } else {
            aktualisieren(a.id(), Status.FEHLGESCHLAGEN, Instant.now(),
                    meldung == null ? "Der Agent konnte die Aktualisierung nicht anstoßen."
                                    : meldung);
        }
    }

    /**
     * Wird bei jeder Meldung eines Agenten aufgerufen und entscheidet über
     * Erfolg, Fehlschlag und Zeitüberschreitung.
     *
     * <p><b>Das ist die eigentliche Verifikation.</b> Nicht die Quittung des
     * Agenten zählt, sondern die Version, mit der er wiederkommt.
     *
     * @param gemeldeteVersion die Bausteinversion aus der aktuellen Meldung
     * @return kurzer Text für das Protokoll, oder {@code null} wenn sich nichts
     *         geändert hat
     */
    public String abschlussPruefen(long agentId, String gemeldeteVersion) {
        Auftrag a = offener(agentId);
        if (a == null) return null;

        if (gemeldeteVersion != null && gemeldeteVersion.equals(a.zielversion())) {
            aktualisieren(a.id(), Status.ERFOLGREICH, Instant.now(),
                    "Der Agent meldet sich mit Version " + gemeldeteVersion + ".");
            return "Standort-Agent %d läuft jetzt auf Version %s (vorher %s)"
                    .formatted(agentId, gemeldeteVersion,
                               a.vonVersion() == null ? "unbekannt" : a.vonVersion());
        }

        if (a.angefordertAm().isBefore(Instant.now().minus(FRIST))) {
            aktualisieren(a.id(), Status.FEHLGESCHLAGEN, Instant.now(),
                    "Der Agent meldet sich weiterhin mit Version "
                    + (gemeldeteVersion == null ? "unbekannt" : gemeldeteVersion)
                    + ", nicht mit " + a.zielversion() + ". "
                    + "Häufigste Ursachen: Die Version gibt es in der Programmablage nicht, "
                    + "oder die Zugangsdaten wurden abgelehnt. Am Standort hilft "
                    + "„docker compose logs aktualisierung“. "
                    + "Der Agent läuft unverändert weiter.");
            return "Aktualisierung von Standort-Agent %d auf %s fehlgeschlagen"
                    .formatted(agentId, a.zielversion());
        }
        return null;
    }

    /**
     * Bricht einen offenen Auftrag ab.
     *
     * <p>Nötig, wenn ein Agent gar nicht mehr wiederkommt: Sonst stünde die
     * Anzeige bis zum Ablauf der Frist auf „läuft“ und danach auf einem
     * Fehlschlag, den niemand mehr einordnen kann.
     */
    public String abbrechen(long agentId, String benutzer, String einrichtung) {
        Auftrag a = offener(agentId);
        if (a == null) return "Für diesen Agenten läuft keine Aktualisierung.";
        aktualisieren(a.id(), Status.ABGEBROCHEN, Instant.now(),
                "Von " + (benutzer == null ? "unbekannt" : benutzer) + " abgebrochen.");
        protokoll.anfuegen(einrichtung, "Standort-Agent " + agentId,
                Ereignisart.AGENT_AKTUALISIERUNG, true, benutzer, null,
                "Aktualisierung des Standort-Agenten abgebrochen", null);
        return null;
    }

    /**
     * Läuft Aufträge ab, deren Frist verstrichen ist — auch ohne Meldung.
     *
     * <p>Ein Agent, der gar nicht wiederkommt, meldet definitionsgemäß nichts.
     * Ohne diesen Takt bliebe sein Auftrag für immer auf „läuft“ stehen.
     */
    public List<String> fristenPruefen() {
        var abgelaufen = new ArrayList<String>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT id, agent_id, zielversion FROM agent_aktualisierung
                 WHERE status IN ('ANGEFORDERT','ANGESTOSSEN')
                   AND angefordert_am < now() - (? || ' seconds')::interval""")) {
            p.setString(1, String.valueOf(FRIST.toSeconds()));
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1), agentId = rs.getLong(2);
                    String ziel = rs.getString(3);
                    aktualisieren(id, Status.FEHLGESCHLAGEN, Instant.now(),
                            "Der Agent hat sich seit der Anforderung nicht wieder gemeldet. "
                            + "Bitte am Standort prüfen, ob der Container läuft: "
                            + "„docker compose ps“ und „docker compose logs“.");
                    abgelaufen.add("Aktualisierung von Standort-Agent %d auf %s "
                            .formatted(agentId, ziel) + "ohne Rückmeldung abgelaufen");
                }
            }
        } catch (SQLException e) {
            System.err.println("Fristen nicht prüfbar: " + e.getMessage());
        }
        return abgelaufen;
    }

    /**
     * Nimmt den letzten Fehlschlag zur Kenntnis.
     *
     * <p>Er verschwindet damit aus der Agentenzeile, bleibt aber im Verlauf.
     * Ohne diesen Weg stünde eine behobene Störung dort für immer — und eine
     * Meldung, die man nicht loswird, wird ignoriert. Dann ignoriert man auch
     * die nächste.
     */
    public String zurKenntnis(long agentId) {
        var a = letzter(agentId);
        if (a == null || a.status().offen()) {
            return "Für diesen Agenten gibt es nichts zur Kenntnis zu nehmen.";
        }
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE agent_aktualisierung SET zur_kenntnis_am = now() WHERE id = ?")) {
            p.setLong(1, a.id());
            p.executeUpdate();
            return null;
        } catch (SQLException e) {
            return "Der Hinweis ließ sich nicht ausblenden.";
        }
    }

    /** Die letzten Aufträge eines Agenten — für die Detailansicht. */
    public List<Auftrag> verlauf(long agentId, int anzahl) {
        var liste = new ArrayList<Auftrag>();
        try (PreparedStatement p = db.verbindung().prepareStatement(
                grundabfrage() + " WHERE agent_id = ? ORDER BY id DESC LIMIT ?")) {
            p.setLong(1, agentId);
            p.setInt(2, anzahl);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) liste.add(lesen(rs));
            }
        } catch (SQLException e) {
            System.err.println("Verlauf nicht lesbar: " + e.getMessage());
        }
        return liste;
    }

    // ------------------------------------------------------------ Innenleben

    private static String grundabfrage() {
        return """
               SELECT id, agent_id, zielversion, von_version, status, angefordert_am,
                      angefordert_von, abgeschlossen_am, meldung, zur_kenntnis_am
                 FROM agent_aktualisierung""";
    }

    private Auftrag einer(String bedingung, long agentId) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                grundabfrage() + " WHERE " + bedingung + " ORDER BY id DESC LIMIT 1")) {
            p.setLong(1, agentId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? lesen(rs) : null;
            }
        } catch (SQLException e) {
            System.err.println("Auftrag nicht lesbar: " + e.getMessage());
            return null;
        }
    }

    private static Auftrag lesen(ResultSet rs) throws SQLException {
        Timestamp fertig = rs.getTimestamp("abgeschlossen_am");
        Status st;
        try { st = Status.valueOf(rs.getString("status")); }
        catch (IllegalArgumentException e) { st = Status.FEHLGESCHLAGEN; }
        return new Auftrag(rs.getLong("id"), rs.getLong("agent_id"),
                rs.getString("zielversion"), rs.getString("von_version"), st,
                rs.getTimestamp("angefordert_am").toInstant(),
                rs.getString("angefordert_von"),
                fertig == null ? null : fertig.toInstant(),
                rs.getString("meldung"),
                rs.getTimestamp("zur_kenntnis_am") != null);
    }

    private void aktualisieren(long id, Status status, Instant fertig, String meldung) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                UPDATE agent_aktualisierung
                   SET status = ?,
                       angestossen_am = CASE WHEN ? THEN now() ELSE angestossen_am END,
                       abgeschlossen_am = ?,
                       meldung = coalesce(?, meldung)
                 WHERE id = ?""")) {
            p.setString(1, status.name());
            p.setBoolean(2, status == Status.ANGESTOSSEN);
            if (fertig == null) p.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE);
            else p.setTimestamp(3, Timestamp.from(fertig));
            p.setString(4, meldung);
            p.setLong(5, id);
            p.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Auftrag nicht änderbar: " + e.getMessage());
        }
    }

    private String laufendeVersion(long agentId) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT bausteinversion FROM agent WHERE id = ?")) {
            p.setLong(1, agentId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }
}
