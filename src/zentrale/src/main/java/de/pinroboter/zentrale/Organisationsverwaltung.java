package de.pinroboter.zentrale;

import java.sql.*;
import java.util.*;

/**
 * Organisationseinheiten anlegen, umbenennen, umhängen und löschen (FA-ORG-020).
 *
 * <h2>Warum das die heikelste Verwaltungsfunktion des Produkts ist</h2>
 *
 * Der Geltungsbereich eines Benutzers ergibt sich <b>aus dem Baum</b>: seine
 * Einheit und alles darunter ({@link Geltungsbereich}). Wer den Baum verändern
 * darf, verändert damit Geltungsbereiche — auch seinen eigenen.
 *
 * <p>Ein Standortadministrator, der eine fremde Praxis unter seinen Standort
 * hängen könnte, hätte damit Zugriff auf deren Geräte, Protokolle und Konten.
 * Ohne einen einzigen Fehler in der Mandantentrennung selbst — er hätte sie
 * schlicht <i>von innen</i> erweitert.
 *
 * <p>Deshalb gelten hier vier Regeln, jede einzeln geprüft und nachgewiesen:
 *
 * <ol>
 *   <li><b>Erst ab Standortebene</b> ({@code Rechte.darfOrganisationVerwalten}).
 *       Wer eine Praxis betreut, legt keine weiteren Praxen an.</li>
 *   <li><b>Das Elternteil muss im eigenen Bereich liegen.</b> Sonst ließen sich
 *       Geschwister oder Einheiten oberhalb anlegen.</li>
 *   <li><b>Beim Umhängen müssen Einheit UND Ziel im eigenen Bereich liegen.</b>
 *       Nur das Ziel zu prüfen erlaubt die Übernahme fremder Einheiten; nur die
 *       Einheit zu prüfen erlaubt das Wegschieben aus dem Bereich.</li>
 *   <li><b>Kein Zyklus.</b> Eine Einheit unter einen eigenen Nachfahren zu
 *       hängen trennt den Teilbaum vom Baum ab — er wäre für niemanden mehr
 *       sichtbar, auch nicht für den Instanzbetreiber.</li>
 * </ol>
 *
 * <h2>Löschen</h2>
 *
 * Der Fremdschlüssel steht auf {@code ON DELETE CASCADE}. Ein Löschen risse
 * damit den ganzen Teilbaum mit — samt Konnektoren, Terminals und Konten. Das
 * wäre in einer Praxis unter Zeitdruck ein Klick zu viel. Gelöscht wird deshalb
 * nur, was <b>leer</b> ist, und die Meldung sagt, was im Weg steht.
 */
public class Organisationsverwaltung {

    /** Eine Einheit mit ihrer Tiefe im Baum — für die eingerückte Darstellung. */
    public record Einheit(long id, String bezeichnung, String art, Long elternId, int tiefe,
                          boolean istDemo, int kinder, int benutzer, int konnektoren,
                          int terminals) {
        public String artAnzeige() {
            return switch (art) {
                case "VERBUND"     -> "Verbund";
                case "STANDORT"    -> "Standort";
                case "EINRICHTUNG" -> "Einrichtung";
                default            -> art;
            };
        }

        /** Ob sie sich löschen lässt — leer heißt: nichts hängt daran. */
        public boolean leer() {
            return kinder == 0 && benutzer == 0 && konnektoren == 0 && terminals == 0;
        }

        /** Was im Weg steht, in Klartext. Leer, wenn nichts im Weg steht. */
        public String hindernis() {
            List<String> teile = new ArrayList<>();
            if (kinder > 0)      teile.add(kinder + " untergeordnete Einheit(en)");
            if (benutzer > 0)    teile.add(benutzer + " Konto/Konten");
            if (konnektoren > 0) teile.add(konnektoren + " Konnektor(en)");
            if (terminals > 0)   teile.add(terminals + " Kartenterminal(s)");
            return String.join(", ", teile);
        }
    }

    /** Ergebnis einer Änderung. {@code fehler == null} heißt: geklappt. */
    public record Ergebnis(String fehler, String meldung) {
        public boolean geklappt() { return fehler == null; }
        static Ergebnis gut(String m) { return new Ergebnis(null, m); }
        static Ergebnis schlecht(String f) { return new Ergebnis(f, null); }
    }

    /**
     * Erlaubte Arten unterhalb einer gegebenen Art.
     *
     * <p>Ein Standort unter einer Einrichtung wäre nicht falsch im Sinne der
     * Datenbank, aber unsinnig im Sinne der Sache — und er würde die Anzeige
     * unverständlich machen.
     */
    private static List<String> erlaubtUnter(String elternArt) {
        return switch (elternArt) {
            case "VERBUND"  -> List.of("STANDORT", "EINRICHTUNG");
            case "STANDORT" -> List.of("EINRICHTUNG");
            default         -> List.of();          // unter einer Einrichtung nichts
        };
    }

    private final Datenbank db;

    public Organisationsverwaltung(Datenbank db) { this.db = db; }

    // ------------------------------------------------------------------ Lesen

    /**
     * Der Baum im Geltungsbereich, von oben nach unten sortiert.
     *
     * <p>Der Instanzbetreiber sieht alle Wurzeln; alle anderen sehen ihre eigene
     * Einheit als Wurzel und alles darunter. Nach oben gibt es keine Sicht.
     */
    public List<Einheit> baum(Geltungsbereich bereich) {
        if (bereich.leer()) return List.of();
        List<Einheit> ergebnis = new ArrayList<>();
        // Die Zaehlungen kommen als Unterabfragen mit: Bei hoechstens einigen
        // hundert Einheiten ist das guenstiger als vier Extraabfragen, und die
        // Anzeige braucht sie fuer die Loeschsperre ohnehin.
        String sql = """
                WITH RECURSIVE wurzeln AS (
                    SELECT o.* FROM organisation o
                    WHERE (o.eltern_id IS NULL OR NOT EXISTS (
                              SELECT 1 FROM organisation e WHERE e.id = o.eltern_id%s))
                          %s
                ), baum AS (
                    SELECT w.*, 0 AS tiefe, w.bezeichnung::text AS pfad FROM wurzeln w
                    UNION ALL
                    SELECT o.*, b.tiefe + 1, b.pfad || '/' || o.bezeichnung
                    FROM organisation o JOIN baum b ON o.eltern_id = b.id
                )
                SELECT b.id, b.bezeichnung, b.art, b.eltern_id, b.tiefe, b.ist_demo,
                       (SELECT count(*) FROM organisation k WHERE k.eltern_id = b.id)    AS kinder,
                       (SELECT count(*) FROM benutzer u WHERE u.organisation_id = b.id)  AS benutzer,
                       (SELECT count(*) FROM konnektor k WHERE k.organisation_id = b.id) AS konnektoren,
                       (SELECT count(*) FROM kartenterminal t WHERE t.organisation_id = b.id) AS terminals
                FROM baum b ORDER BY b.pfad"""
                .formatted(bereich.unbeschraenkt() ? "" : " AND e.id = ANY (?)",
                           bereich.unbeschraenkt() ? "" : "AND o.id = ANY (?)");

        try (PreparedStatement p = db.verbindung().prepareStatement(sql)) {
            if (!bereich.unbeschraenkt()) {
                Array a = idsAlsFeld(bereich);
                p.setArray(1, a);
                p.setArray(2, a);
            }
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    long eltern = rs.getLong("eltern_id");
                    Long elternId = rs.wasNull() ? null : eltern;   // sofort auswerten
                    ergebnis.add(new Einheit(rs.getLong("id"), rs.getString("bezeichnung"),
                            rs.getString("art"), elternId, rs.getInt("tiefe"),
                            rs.getBoolean("ist_demo"), rs.getInt("kinder"),
                            rs.getInt("benutzer"), rs.getInt("konnektoren"),
                            rs.getInt("terminals")));
                }
            }
        } catch (SQLException e) {
            System.err.println("Organisationsbaum nicht lesbar: " + e.getMessage());
        }
        return ergebnis;
    }

    /** Eine Einheit — nur, wenn sie im Geltungsbereich liegt. */
    public Optional<Einheit> einheit(long id, Geltungsbereich bereich) {
        return baum(bereich).stream().filter(e -> e.id() == id).findFirst();
    }

    // ---------------------------------------------------------------- Ändern

    /**
     * Legt eine Einheit unterhalb einer bestehenden an.
     *
     * <p>Das Elternteil ist <b>Pflicht</b>. Eine neue Wurzel anzulegen wäre das
     * Anlegen eines neuen Mandanten — das gehört zur Ersteinrichtung der
     * Instanz, nicht in die laufende Verwaltung.
     */
    public Ergebnis anlegen(String bezeichnung, String art, Long elternId, Geltungsbereich bereich) {
        if (bezeichnung == null || bezeichnung.isBlank()) {
            return Ergebnis.schlecht("Bitte eine Bezeichnung eingeben.");
        }
        if (elternId == null) {
            return Ergebnis.schlecht("Bitte auswählen, unterhalb welcher Einheit die neue "
                    + "Einheit liegen soll.");
        }
        var eltern = einheit(elternId, bereich);
        if (eltern.isEmpty()) {
            // Absichtlich unspezifisch: Ob es die Einheit gibt, geht einen
            // Aufrufer ausserhalb seines Bereichs nichts an.
            return Ergebnis.schlecht("Die gewählte übergeordnete Einheit steht Ihnen nicht "
                    + "zur Verfügung.");
        }
        var erlaubt = erlaubtUnter(eltern.get().art());
        if (!erlaubt.contains(art)) {
            return Ergebnis.schlecht(erlaubt.isEmpty()
                    ? "Unterhalb einer Einrichtung lassen sich keine weiteren Einheiten anlegen. "
                      + "Die Einrichtung ist die unterste Ebene."
                    : "Unterhalb eines " + eltern.get().artAnzeige() + "s ist nur zulässig: "
                      + erlaubt.stream().map(Organisationsverwaltung::artAnzeige)
                               .reduce((a, b) -> a + " oder " + b).orElse(""));
        }
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "INSERT INTO organisation (bezeichnung, art, eltern_id) VALUES (?,?,?)")) {
            p.setString(1, bezeichnung.strip());
            p.setString(2, art);
            p.setLong(3, elternId);
            p.executeUpdate();
            return Ergebnis.gut("„" + bezeichnung.strip() + "“ wurde unterhalb von „"
                    + eltern.get().bezeichnung() + "“ angelegt.");
        } catch (SQLException e) {
            return Ergebnis.schlecht("Die Einheit konnte nicht angelegt werden.");
        }
    }

    /** Benennt eine Einheit um. Die Zuordnung bleibt unverändert. */
    public Ergebnis umbenennen(long id, String bezeichnung, Geltungsbereich bereich) {
        if (bezeichnung == null || bezeichnung.isBlank()) {
            return Ergebnis.schlecht("Bitte eine Bezeichnung eingeben.");
        }
        if (einheit(id, bereich).isEmpty()) {
            return Ergebnis.schlecht("Diese Einheit steht Ihnen nicht zur Verfügung.");
        }
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE organisation SET bezeichnung = ? WHERE id = ?")) {
            p.setString(1, bezeichnung.strip());
            p.setLong(2, id);
            p.executeUpdate();
            return Ergebnis.gut("Die Einheit heißt jetzt „" + bezeichnung.strip() + "“.");
        } catch (SQLException e) {
            return Ergebnis.schlecht("Die Einheit konnte nicht umbenannt werden.");
        }
    }

    /**
     * Hängt eine Einheit unter ein anderes Elternteil.
     *
     * <p>Hier liegen die beiden Angriffe, gegen die diese Klasse gebaut ist:
     * eine fremde Einheit unter sich zu ziehen, und die eigene aus dem Bereich
     * zu schieben. Beides scheitert daran, dass <b>beide Seiten</b> im
     * Geltungsbereich liegen müssen.
     */
    public Ergebnis umhaengen(long id, Long neuesElternteil, Geltungsbereich bereich) {
        var einheit = einheit(id, bereich);
        if (einheit.isEmpty()) {
            return Ergebnis.schlecht("Diese Einheit steht Ihnen nicht zur Verfügung.");
        }
        if (neuesElternteil == null) {
            return Ergebnis.schlecht("Eine Einheit ohne übergeordnete Einheit wäre ein eigener "
                    + "Mandant. Das gehört zur Ersteinrichtung der Instanz, nicht in die "
                    + "laufende Verwaltung.");
        }
        if (neuesElternteil == id) {
            return Ergebnis.schlecht("Eine Einheit kann sich nicht selbst übergeordnet sein.");
        }
        var eltern = einheit(neuesElternteil, bereich);
        if (eltern.isEmpty()) {
            return Ergebnis.schlecht("Die gewählte übergeordnete Einheit steht Ihnen nicht "
                    + "zur Verfügung.");
        }
        if (!erlaubtUnter(eltern.get().art()).contains(einheit.get().art())) {
            return Ergebnis.schlecht("Ein " + einheit.get().artAnzeige() + " kann nicht unterhalb "
                    + "eines " + eltern.get().artAnzeige() + "s liegen.");
        }
        if (istNachfahre(neuesElternteil, id)) {
            return Ergebnis.schlecht("„" + eltern.get().bezeichnung() + "“ liegt unterhalb von „"
                    + einheit.get().bezeichnung() + "“. Diese Verschiebung würde den Zweig vom "
                    + "Baum abtrennen — er wäre danach für niemanden mehr sichtbar.");
        }
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE organisation SET eltern_id = ? WHERE id = ?")) {
            p.setLong(1, neuesElternteil);
            p.setLong(2, id);
            p.executeUpdate();
            return Ergebnis.gut("„" + einheit.get().bezeichnung() + "“ liegt jetzt unterhalb von „"
                    + eltern.get().bezeichnung() + "“.");
        } catch (SQLException e) {
            return Ergebnis.schlecht("Die Einheit konnte nicht umgehängt werden.");
        }
    }

    /**
     * Löscht eine Einheit — nur, wenn nichts mehr daran hängt.
     *
     * <p>Der Fremdschlüssel steht auf {@code ON DELETE CASCADE}: Ein Löschen
     * risse Teilbaum, Konten, Konnektoren und Terminals mit. Statt einer
     * Rückfrage, die man wegklickt, wird hier abgelehnt und gesagt, was im Weg
     * steht.
     */
    public Ergebnis loeschen(long id, Geltungsbereich bereich) {
        var einheit = einheit(id, bereich);
        if (einheit.isEmpty()) {
            return Ergebnis.schlecht("Diese Einheit steht Ihnen nicht zur Verfügung.");
        }
        if (einheit.get().elternId() == null) {
            return Ergebnis.schlecht("Die oberste Einheit lässt sich nicht löschen. Sie ist der "
                    + "Mandant selbst.");
        }
        if (!einheit.get().leer()) {
            return Ergebnis.schlecht("„" + einheit.get().bezeichnung() + "“ lässt sich nicht "
                    + "löschen: Daran hängen noch " + einheit.get().hindernis() + ". "
                    + "Bitte diese zuerst verschieben oder entfernen.");
        }
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "DELETE FROM organisation WHERE id = ?")) {
            p.setLong(1, id);
            p.executeUpdate();
            return Ergebnis.gut("„" + einheit.get().bezeichnung() + "“ wurde gelöscht.");
        } catch (SQLException e) {
            return Ergebnis.schlecht("Die Einheit konnte nicht gelöscht werden.");
        }
    }

    // ------------------------------------------------------------ Innenleben

    /** Ob {@code kandidat} unterhalb von {@code wurzel} liegt (Zyklusschutz). */
    private boolean istNachfahre(long kandidat, long wurzel) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                WITH RECURSIVE unterbau AS (
                    SELECT id FROM organisation WHERE id = ?
                    UNION ALL
                    SELECT o.id FROM organisation o JOIN unterbau u ON o.eltern_id = u.id
                )
                SELECT 1 FROM unterbau WHERE id = ?""")) {
            p.setLong(1, wurzel);
            p.setLong(2, kandidat);
            try (ResultSet rs = p.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            // Im Zweifel ablehnen: Ein nicht pruefbarer Zyklus ist schlimmer
            // als eine abgelehnte Verschiebung.
            return true;
        }
    }

    private Array idsAlsFeld(Geltungsbereich bereich) throws SQLException {
        return db.verbindung().createArrayOf("bigint", bereich.ids().toArray(new Long[0]));
    }

    static String artAnzeige(String art) {
        return switch (art) {
            case "VERBUND"     -> "Verbund";
            case "STANDORT"    -> "Standort";
            case "EINRICHTUNG" -> "Einrichtung";
            default            -> art;
        };
    }
}
