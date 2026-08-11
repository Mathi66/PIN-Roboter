package de.pinroboter.zentrale;

import java.sql.*;
import java.util.*;

/**
 * Gerätegruppen und Gerätezuordnung (FA-GER-050).
 *
 * <p>Gruppen sind mehr als Ordnung: Sie tragen das <b>Prüfintervall</b> und
 * später die <b>Rollout-Pläne</b> (ADR-0004). Bei 5.000 Terminals ist die Last
 * nur darüber steuerbar — die Notaufnahme braucht 30 Sekunden, das Archiv kommt
 * mit fünf Minuten aus.
 *
 * <p>Deshalb stehen sie schon in der ersten Version im Datenmodell, obwohl der
 * Firmware-Rollout eine spätere Stufe ist. Nachrüsten wäre ein Umbau des halben
 * Modells.
 */
public class Gruppenverwaltung {

    public record Gruppe(long id, String bezeichnung, String einrichtung,
                         int pruefintervallSekunden, boolean istVorgabe, int geraetezahl) {}

    public record Geraet(String terminalId, String einrichtung, long gruppeId,
                         String bezeichnung, String standort, boolean aktiv) {}

    private final Datenbank db;

    public Gruppenverwaltung(Datenbank db) { this.db = db; }

    // ---------------------------------------------------------------- Gruppen

    /** Gruppen im Geltungsbereich. Eine Gruppe trägt Prüfintervalle und später
     *  Rollout-Pläne — wer sie sieht, sieht die Betriebsplanung einer Praxis. */
    /**
     * Alle Gruppen im Geltungsbereich, unabhängig von der Einrichtung.
     *
     * <p>Bis zum 09.08.2026 gab es nur die Abfrage nach einer festen
     * Einrichtung — und die Oberfläche reichte dafür hart {@code "Praxis"}
     * durch. Damit war jede Gruppe an dieselbe Einrichtung gebunden, obwohl
     * FA-GER-050 verlangt: <i>„Eine Gruppe liegt innerhalb genau einer
     * Einrichtung."</i> Bei neun Praxen an einer Instanz war das nicht mehr
     * tragfähig.
     */
    public List<Gruppe> alle(Geltungsbereich bereich) {
        List<Gruppe> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT g.id, g.bezeichnung, g.einrichtung, g.pruefintervall_sekunden,
                       g.ist_vorgabe, count(t.terminal_id) AS anzahl
                FROM geraetegruppe g
                LEFT JOIN geraet t ON t.gruppe_id = g.id
                WHERE 1=1""" + bereich.bedingung("g.organisation_id")
                + " GROUP BY g.id ORDER BY g.ist_vorgabe DESC, lower(g.einrichtung),"
                + " lower(g.bezeichnung)")) {
            bereich.wertSetzen(db.verbindung(), p, 1);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) liste.add(lesen(rs));
            }
        } catch (SQLException e) {
            System.err.println("Gerätegruppen nicht lesbar: " + e.getMessage());
        }
        return liste;
    }

    public List<Gruppe> alle(String einrichtung, Geltungsbereich bereich) {
        List<Gruppe> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT g.id, g.bezeichnung, g.einrichtung, g.pruefintervall_sekunden,
                       g.ist_vorgabe, count(t.terminal_id) AS anzahl
                FROM geraetegruppe g
                LEFT JOIN geraet t ON t.gruppe_id = g.id
                WHERE g.einrichtung = ?""" + bereich.bedingung("g.organisation_id")
                + " GROUP BY g.id ORDER BY g.ist_vorgabe DESC, lower(g.bezeichnung)")) {
            p.setString(1, einrichtung);
            bereich.wertSetzen(db.verbindung(), p, 2);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) liste.add(lesen(rs));
            }
        }
        catch (SQLException e) {
            // Nicht verschlucken: Ein stiller SQL-Fehler ist praktisch
            // unauffindbar - so blieb ein fehlerhaftes WHERE monatelang
            // unentdeckt. Der Aufruf schlaegt trotzdem nicht fehl.
            System.err.println("Gruppenverwaltung.alle: " + e.getMessage());
        }
        return liste;
    }

    public Optional<Gruppe> vorgabegruppe(String einrichtung) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT g.id, g.bezeichnung, g.einrichtung, g.pruefintervall_sekunden,
                       g.ist_vorgabe, 0 AS anzahl
                FROM geraetegruppe g WHERE g.einrichtung = ? AND g.ist_vorgabe""")) {
            p.setString(1, einrichtung);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(lesen(rs)) : Optional.empty();
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    /** @return Fehlermeldung im Klartext, oder null bei Erfolg */
    public String anlegen(String bezeichnung, String einrichtung, int intervall) {
        if (bezeichnung == null || bezeichnung.isBlank()) {
            return "Bitte eine Bezeichnung für die Gruppe angeben.";
        }
        if (intervall < 10) {
            return "Das Prüfintervall muss mindestens 10 Sekunden betragen. "
                 + "Kürzere Abstände belasten Konnektor und Terminals unnötig.";
        }
        // INSERT ... SELECT statt VALUES: Ohne eindeutige Organisationseinheit
        // entsteht KEINE Zeile — eine Gruppe ohne Mandantenzuordnung waere fuer
        // jede eingeschraenkte Abfrage unsichtbar (Migration 23).
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO geraetegruppe (bezeichnung, einrichtung, pruefintervall_sekunden,
                                           organisation_id)
                SELECT ?, ?, ?, o.id
                  FROM organisation o
                 WHERE o.bezeichnung = ?
                   AND (SELECT count(*) FROM organisation x WHERE x.bezeichnung = ?) = 1""")) {
            p.setString(1, bezeichnung.trim());
            p.setString(2, einrichtung);
            p.setInt(3, intervall);
            p.setString(4, einrichtung);
            p.setString(5, einrichtung);
            if (p.executeUpdate() == 0) {
                // Sagt, WAS fehlt. „Die Gruppe konnte nicht angelegt werden"
                // liess jemanden ratlos zurueck, der nur die Einrichtung
                // vergessen hatte.
                return einrichtung == null || einrichtung.isBlank()
                        ? "Bitte die Einrichtung wählen, zu der die Gruppe gehört."
                        : "Zur Einrichtung „" + einrichtung + "“ gibt es keine eindeutige "
                          + "Organisationseinheit. Eine Gruppe ohne Einrichtung ließe sich "
                          + "niemandem zuordnen.";
            }
            return null;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                return "Eine Gruppe mit dieser Bezeichnung gibt es bereits.";
            }
            return "Die Gruppe konnte nicht angelegt werden.";
        }
    }

    /**
     * Eine Gruppe — aber nur, wenn sie im Geltungsbereich liegt.
     *
     * <p>Grundlage jeder ändernden Handlung. Bis zum 11.08.2026 gab es sie
     * nicht: {@code intervallSetzen} und {@code loeschen} nahmen eine
     * Kennung entgegen und arbeiteten damit, ohne zu prüfen, wem die Gruppe
     * gehört. Eine manipulierte Kennung genügte, um das Prüfintervall einer
     * fremden Einrichtung zu verstellen — und damit deren Überwachung
     * auszuhebeln.
     */
    public Optional<Gruppe> gruppe(long id, Geltungsbereich bereich) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT g.id, g.bezeichnung, g.einrichtung, g.pruefintervall_sekunden,
                       g.ist_vorgabe, count(t.terminal_id) AS anzahl
                FROM geraetegruppe g
                LEFT JOIN geraet t ON t.gruppe_id = g.id
                WHERE g.id = ?""" + bereich.bedingung("g.organisation_id")
                + " GROUP BY g.id")) {
            p.setLong(1, id);
            bereich.wertSetzen(db.verbindung(), p, 2);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(lesen(rs)) : Optional.empty();
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    public String intervallSetzen(long gruppeId, int sekunden, Geltungsbereich bereich) {
        if (sekunden < 10) return "Das Prüfintervall muss mindestens 10 Sekunden betragen. "
                + "Kürzere Abstände belasten Konnektor und Terminals unnötig.";
        if (gruppe(gruppeId, bereich).isEmpty()) {
            return "Diese Gruppe gibt es nicht — oder sie liegt außerhalb Ihres "
                 + "Zuständigkeitsbereichs.";
        }
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE geraetegruppe SET pruefintervall_sekunden = ? WHERE id = ?")) {
            p.setInt(1, sekunden);
            p.setLong(2, gruppeId);
            return p.executeUpdate() == 1 ? null : "Diese Gruppe gibt es nicht.";
        } catch (SQLException e) { return "Das Intervall konnte nicht geändert werden."; }
    }

    /**
     * Benennt eine Gruppe um (Befund PO, 11.08.2026).
     *
     * <p>Es gab diese Möglichkeit schlicht nicht. Wer sich vertippt hatte,
     * musste die Gruppe löschen und neu anlegen — und verlor dabei jede
     * Gerätezuordnung, weil die Geräte beim Löschen in die Vorgabegruppe
     * umziehen. Für einen Tippfehler ist das ein hoher Preis.
     */
    public String umbenennen(long gruppeId, String bezeichnung, Geltungsbereich bereich) {
        if (bezeichnung == null || bezeichnung.isBlank()) {
            return "Bitte eine Bezeichnung für die Gruppe angeben.";
        }
        if (gruppe(gruppeId, bereich).isEmpty()) {
            return "Diese Gruppe gibt es nicht — oder sie liegt außerhalb Ihres "
                 + "Zuständigkeitsbereichs.";
        }
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE geraetegruppe SET bezeichnung = ? WHERE id = ?")) {
            p.setString(1, bezeichnung.trim());
            p.setLong(2, gruppeId);
            return p.executeUpdate() == 1 ? null : "Diese Gruppe gibt es nicht.";
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                return "In dieser Einrichtung gibt es bereits eine Gruppe „"
                     + bezeichnung.trim() + "“.";
            }
            return "Die Gruppe konnte nicht umbenannt werden.";
        }
    }

    /**
     * Löscht eine Gruppe. Zugeordnete Geräte ziehen in die Vorgabegruppe
     * <b>ihrer eigenen Einrichtung</b> um — kein Gerät darf durch eine Löschung
     * aus der Überwachung fallen (FA-GER-050).
     *
     * <h2>Was hier falsch war (Befund PO, 11.08.2026)</h2>
     *
     * Die Einrichtung kam als Parameter von außen, und die Oberfläche übergab
     * fest {@code "Praxis"}. Zwei Folgen, beide schlecht:
     *
     * <ul>
     *   <li>Gibt es keine Gruppe in „Praxis", scheiterte jedes Löschen mit
     *       „Es gibt keine Vorgabegruppe" — unabhängig davon, welche Gruppe
     *       gemeint war.</li>
     *   <li>Gibt es eine, wanderten die Geräte einer fremden Praxis in deren
     *       Vorgabegruppe. Eine Löschung hätte Geräte <b>über die
     *       Mandantengrenze</b> verschoben.</li>
     * </ul>
     *
     * Die Einrichtung wird deshalb aus der Gruppe selbst gelesen. Sie ist kein
     * Parameter mehr — was man nicht übergeben kann, kann man auch nicht falsch
     * übergeben.
     */
    public String loeschen(long gruppeId, Geltungsbereich bereich) {
        var g = gruppe(gruppeId, bereich);
        if (g.isEmpty()) {
            return "Diese Gruppe gibt es nicht — oder sie liegt außerhalb Ihres "
                 + "Zuständigkeitsbereichs.";
        }
        if (g.get().istVorgabe()) {
            return "Die Vorgabegruppe lässt sich nicht löschen. Neu erkannte Geräte "
                 + "brauchen eine Gruppe, in der sie landen können.";
        }
        var vorgabe = vorgabegruppe(g.get().einrichtung());
        if (vorgabe.isEmpty()) vorgabe = vorgabegruppeAnlegen(g.get().einrichtung());
        if (vorgabe.isEmpty()) {
            return "Für die Einrichtung „" + g.get().einrichtung() + "“ gibt es keine "
                 + "Vorgabegruppe, in die die Geräte umziehen könnten. Bitte zuerst eine "
                 + "Gruppe in dieser Einrichtung anlegen.";
        }
        try {
            try (PreparedStatement p = db.verbindung().prepareStatement(
                    "UPDATE geraet SET gruppe_id = ? WHERE gruppe_id = ?")) {
                p.setLong(1, vorgabe.get().id());
                p.setLong(2, gruppeId);
                p.executeUpdate();
            }
            try (PreparedStatement p = db.verbindung().prepareStatement(
                    "DELETE FROM geraetegruppe WHERE id = ?")) {
                p.setLong(1, gruppeId);
                return p.executeUpdate() == 1 ? null : "Diese Gruppe gibt es nicht.";
            }
        } catch (SQLException e) { return "Die Gruppe konnte nicht gelöscht werden."; }
    }

    /** Ein Gerät in einer Gruppe — für die Detailseite. */
    public record Geraeteeintrag(String terminalId, String bezeichnung) {}

    /**
     * Die Geräte einer Gruppe.
     *
     * <p>Die Liste zeigte bisher nur ihre <b>Zahl</b>. „4 Geräte" beantwortet
     * aber nicht die Frage, die man an einer Gruppe hat: welche?
     */
    public List<Geraeteeintrag> geraeteDerGruppe(long gruppeId) {
        List<Geraeteeintrag> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT terminal_id, coalesce(bezeichnung, terminal_id) AS bez "
                + "FROM geraet WHERE gruppe_id = ? ORDER BY lower(coalesce(bezeichnung, terminal_id))")) {
            p.setLong(1, gruppeId);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) liste.add(new Geraeteeintrag(rs.getString(1), rs.getString(2)));
            }
        } catch (SQLException e) {
            System.err.println("Geräte der Gruppe nicht lesbar: " + e.getMessage());
        }
        return liste;
    }

    /**
     * Macht eine Gruppe zur Vorgabegruppe ihrer Einrichtung.
     *
     * <p>Beides in einer Transaktion: Erst die bisherige Vorgabe abwählen, dann
     * die neue setzen. Ein eindeutiger Index erlaubt nur eine je Einrichtung —
     * in der falschen Reihenfolge scheitert die zweite Anweisung, und die
     * Einrichtung stünde ohne Vorgabegruppe da.
     */
    public String vorgabeSetzen(long gruppeId, Geltungsbereich bereich) {
        var g = gruppe(gruppeId, bereich);
        if (g.isEmpty()) {
            return "Diese Gruppe gibt es nicht — oder sie liegt außerhalb Ihres "
                 + "Zuständigkeitsbereichs.";
        }
        if (g.get().istVorgabe()) return null;   // schon so, nichts zu tun
        boolean vorherAuto = true;
        try {
            vorherAuto = db.verbindung().getAutoCommit();
            db.verbindung().setAutoCommit(false);
            try (PreparedStatement p = db.verbindung().prepareStatement(
                    "UPDATE geraetegruppe SET ist_vorgabe = FALSE WHERE einrichtung = ? AND ist_vorgabe")) {
                p.setString(1, g.get().einrichtung());
                p.executeUpdate();
            }
            try (PreparedStatement p = db.verbindung().prepareStatement(
                    "UPDATE geraetegruppe SET ist_vorgabe = TRUE WHERE id = ?")) {
                p.setLong(1, gruppeId);
                p.executeUpdate();
            }
            db.verbindung().commit();
            return null;
        } catch (SQLException e) {
            try { db.verbindung().rollback(); } catch (SQLException ignored) { }
            return "Die Vorgabegruppe konnte nicht gewechselt werden.";
        } finally {
            try { db.verbindung().setAutoCommit(vorherAuto); } catch (SQLException ignored) { }
        }
    }

    // ---------------------------------------------------------------- Geräte

    /**
     * Welche Einrichtung zu welchem Kartenterminal gehört.
     *
     * <h2>Warum nicht aus der Meldung des Agenten</h2>
     *
     * Der Agent kennt <b>eine</b> Einrichtung — die aus seiner Registrierung.
     * In einem MVZ betreut er aber Geräte mehrerer Betriebsstätten, und dann
     * trügen in der Oberfläche alle denselben Namen. Die richtige Zuordnung
     * steht im Bestand, und nur dort.
     *
     * <p>Wird einmal je Seitenaufbau geholt, nicht je Zeile: Bei 5.000 Geräten
     * wären das sonst 5.000 Abfragen für eine Tabelle mit zwanzig Zeilen.
     */
    public java.util.Map<String, String> einrichtungJeTerminal() {
        var karte = new java.util.HashMap<String, String>();
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT t.terminal_id, o.bezeichnung
                     FROM kartenterminal t
                     JOIN organisation o ON o.id = t.organisation_id
                     UNION
                     SELECT g.terminal_id, g.einrichtung FROM geraet g""")) {
            while (rs.next()) karte.putIfAbsent(rs.getString(1), rs.getString(2));
        } catch (SQLException e) {
            // Kein Abbruch: Ohne die Karte zeigt die Oberflaeche die Einrichtung
            // aus der Agentenmeldung. Ungenau, aber besser als eine leere Seite.
            System.err.println("Gruppenverwaltung.einrichtungJeTerminal: " + e.getMessage());
        }
        return karte;
    }

    /**
     * Trägt ein gemeldetes Gerät ein, falls es noch unbekannt ist.
     * Neue Geräte landen in der Vorgabegruppe.
     *
     * @return true, wenn das Gerät neu war
     */
    public boolean geraetSicherstellen(String terminalId, String einrichtung, String bezeichnung) {
        var vorgabe = vorgabegruppe(einrichtung);
        if (vorgabe.isEmpty()) vorgabe = vorgabegruppeAnlegen(einrichtung);
        if (vorgabe.isEmpty()) return false;
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO geraet (terminal_id, einrichtung, gruppe_id, bezeichnung)
                VALUES (?,?,?,?) ON CONFLICT (terminal_id) DO NOTHING""")) {
            p.setString(1, terminalId);
            p.setString(2, einrichtung);
            p.setLong(3, vorgabe.get().id());
            p.setString(4, bezeichnung);
            return p.executeUpdate() == 1;
        } catch (SQLException e) { return false; }
    }

    /**
     * Legt die Vorgabegruppe einer Einrichtung an, falls sie noch fehlt.
     *
     * <p>Migration 6 erzeugt sie nur für die Einrichtung {@code "Praxis"}. Bis
     * zum 09.08.2026 fiel deshalb jedes Gerät eines Agenten an einem anderen
     * Standort <b>still durch</b>: {@code geraetSicherstellen} gab {@code false}
     * zurück, niemand sah hin, und das Terminal tauchte im Protokoll auf, aber
     * nirgends in der Geräteverwaltung — ohne Gruppe, also auch ohne
     * Prüfintervall.
     *
     * <p>Die Gruppe entsteht deshalb beim ersten Bedarf. Idempotent: Ein
     * Wettlauf zweier Agenten derselben Einrichtung endet an dem eindeutigen
     * Index {@code geraetegruppe_vorgabe_idx}, und der Verlierer liest die
     * inzwischen vorhandene Gruppe.
     */
    Optional<Gruppe> vorgabegruppeAnlegen(String einrichtung) {
        // INSERT ... SELECT statt VALUES mit Unterabfrage: Findet sich keine
        // eindeutige Organisation, entsteht KEINE Zeile.
        //
        // Vorher wurde sie mit organisation_id = NULL angelegt. Eine solche
        // Gruppe ist fuer jede mandantengebundene Abfrage unsichtbar
        // (NULL = ANY(...) ist niemals wahr) und erscheint nur dem
        // Instanzbetreiber — daher meldete die Uebersicht 9 Gruppen und die
        // Liste 19 (Befund PO, 11.08.2026, Migration 23).
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO geraetegruppe (bezeichnung, einrichtung, ist_vorgabe,
                                           pruefintervall_sekunden, organisation_id)
                SELECT 'Alle Geräte', ?, TRUE, 60, o.id
                  FROM organisation o
                 WHERE o.bezeichnung = ?
                   AND (SELECT count(*) FROM organisation x WHERE x.bezeichnung = ?) = 1
                ON CONFLICT DO NOTHING""")) {
            p.setString(1, einrichtung);
            p.setString(2, einrichtung);
            p.setString(3, einrichtung);
            p.executeUpdate();
        } catch (SQLException e) {
            // Nicht verschlucken: Ohne Vorgabegruppe wird das Gerät nicht erfasst.
            System.err.println("Vorgabegruppe für " + einrichtung
                    + " konnte nicht angelegt werden: " + e.getMessage());
        }

        var v = vorgabegruppe(einrichtung);
        if (v.isPresent()) return v;

        // Zweiter Anlauf für einen Fall, der am 10.08.2026 am laufenden Stand
        // auftrat: Es gibt bereits eine Gruppe „Alle Geräte" in dieser
        // Einrichtung — von Hand angelegt, ohne Vorgabe-Kennzeichen. Das
        // Einfügen oben scheitert dann am eindeutigen Namensindex, ON CONFLICT
        // schluckt es, und die Einrichtung bleibt ohne Vorgabegruppe. Jedes
        // gemeldete Gerät fiel durch.
        //
        // Statt einen Ausweichnamen zu erfinden („Alle Geräte (2)"), wird die
        // ÄLTESTE vorhandene Gruppe zur Vorgabe erklärt. Das ist deterministisch
        // und trifft die Absicht: Wer eine Gruppe angelegt hat, will Geräte
        // dort haben — nicht in einer zweiten mit fast gleichem Namen.
        //
        // Nur in DIESEM Fall. Hat eine Einrichtung fachlich benannte Gruppen
        // („Notaufnahme", „Archiv"), aber keine „Alle Geräte", so gelingt das
        // Einfügen oben, und es entsteht eine eigene Vorgabegruppe. Sonst
        // landete jedes neu erkannte Gerät in der Notaufnahme.
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                UPDATE geraetegruppe SET ist_vorgabe = TRUE
                WHERE id = (SELECT id FROM geraetegruppe WHERE einrichtung = ?
                            ORDER BY angelegt, id LIMIT 1)""")) {
            p.setString(1, einrichtung);
            if (p.executeUpdate() == 1) {
                System.err.println("Bestehende Gruppe in " + einrichtung
                        + " wurde zur Vorgabegruppe erklärt — neu erkannte Geräte "
                        + "landen dort.");
            }
        } catch (SQLException e) {
            System.err.println("Vorgabegruppe für " + einrichtung
                    + " konnte nicht bestimmt werden: " + e.getMessage());
        }

        var ergebnis = vorgabegruppe(einrichtung);
        if (ergebnis.isEmpty()) {
            // Der haeufigste Grund ist jetzt: Es gibt keine Organisationseinheit
            // mit diesem Namen. Frueher entstand hier stillschweigend eine
            // Gruppe ohne Organisation — sichtbar nur fuer den
            // Instanzbetreiber, unsichtbar fuer alle anderen.
            //
            // Still zu scheitern ist die schlechtere Wahl, aber falsche Daten
            // anzulegen ist die schlechteste. Deshalb: kein Datensatz, dafuer
            // eine Meldung, die den Grund nennt.
            System.err.println("Keine Vorgabegruppe für Einrichtung „" + einrichtung
                    + "“: Es gibt keine eindeutige Organisationseinheit dieses Namens. "
                    + "Gemeldete Geräte dieser Einrichtung können nicht zugeordnet werden. "
                    + "Bitte die Organisationseinheit anlegen oder den Agenten auf die "
                    + "richtige Einrichtung registrieren.");
        }
        return ergebnis;
    }

    public String zuordnen(String terminalId, long gruppeId) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE geraet SET gruppe_id = ? WHERE terminal_id = ?")) {
            p.setLong(1, gruppeId);
            p.setString(2, terminalId);
            return p.executeUpdate() == 1 ? null : "Dieses Gerät ist nicht erfasst.";
        } catch (SQLException e) {
            return "Die Zuordnung konnte nicht gespeichert werden.";
        }
    }

    /** Prüfintervalle je Gerät — der Agent holt sie sich hier ab. */
    public Map<String, Integer> intervalleJeGeraet(String einrichtung) {
        Map<String, Integer> m = new LinkedHashMap<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT t.terminal_id, g.pruefintervall_sekunden
                FROM geraet t JOIN geraetegruppe g ON g.id = t.gruppe_id
                WHERE t.einrichtung = ? AND t.aktiv""")) {
            p.setString(1, einrichtung);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) m.put(rs.getString(1), rs.getInt(2));
            }
        }
        catch (SQLException e) {
            // Nicht verschlucken: Ein stiller SQL-Fehler ist praktisch
            // unauffindbar - so blieb ein fehlerhaftes WHERE monatelang
            // unentdeckt. Der Aufruf schlaegt trotzdem nicht fehl.
            System.err.println("Gruppenverwaltung.intervalleJeGeraet: " + e.getMessage());
        }
        return m;
    }

    public Optional<Long> gruppeVon(String terminalId) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT gruppe_id FROM geraet WHERE terminal_id = ?")) {
            p.setString(1, terminalId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    private static Gruppe lesen(ResultSet rs) throws SQLException {
        return new Gruppe(rs.getLong("id"), rs.getString("bezeichnung"),
                rs.getString("einrichtung"), rs.getInt("pruefintervall_sekunden"),
                rs.getBoolean("ist_vorgabe"), rs.getInt("anzahl"));
    }
}
