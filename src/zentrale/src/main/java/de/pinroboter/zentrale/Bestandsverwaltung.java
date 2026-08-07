package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Geheimnisspeicher;

import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.sql.*;
import java.util.*;

/**
 * Bestandsverwaltung für Konnektoren und Kartenterminals
 * (FA-GER-010, FA-GER-020).
 *
 * <p>Zwei Regeln bestimmen den Aufbau dieser Klasse:
 *
 * <p><b>1. Zugangsdaten gehen nur hinein, nie heraus.</b> Kein Lesepfad gibt
 * ein Passwort zurück — die Records tragen stattdessen nur ein
 * {@code passwortHinterlegt}-Kennzeichen. Wer den Wert wirklich braucht (der
 * Agent, für die Anmeldung), holt ihn über {@link #konnektorPasswort(long)}
 * bzw. {@link #terminalAdminPasswort(long)}. Diese beiden Methoden sind die
 * einzigen Stellen, an denen entschlüsselt wird, und sie sind absichtlich
 * schwer zu übersehen.
 *
 * <p><b>2. Ein nicht erreichbares Gerät ist speicherbar.</b> Bei der
 * Ersteinrichtung steht die Gegenstelle oft noch nicht — eine Erfassung, die
 * das verbietet, zwingt zu Fantasiewerten. Die Erreichbarkeit wird geprüft und
 * <i>gemeldet</i>, nicht erzwungen (Akzeptanzkriterium FA-GER-010).
 */
public class Bestandsverwaltung {

    /** Zeitgrenze der Erreichbarkeitsprüfung. Die Anforderung nennt 10 Sekunden. */
    static final int PRUEF_ZEITGRENZE_MS = 3000;

    public record Konnektor(long id, long organisationId, String organisation,
                            String bezeichnung, String adresse, String modell,
                            String mandantId, String clientsystemId, String arbeitsplatzId,
                            String benutzerName, boolean passwortHinterlegt,
                            String webadminAdresse, boolean aktiv, boolean istDemo,
                            Boolean zuletztErreichbar, String zuletztGeprueft,
                            String pruefhinweis, int terminalzahl) {}

    public record Terminal(long id, String terminalId, long konnektorId, String konnektor,
                           long organisationId, String organisation, Long gruppeId, String gruppe,
                           String bezeichnung, String standortangabe, String adresse,
                           String modell, String seriennummer, String firmware,
                           String adminBenutzer, boolean adminPasswortHinterlegt,
                           boolean hatSmcb, boolean pevimAktiv, boolean aktiv, boolean istDemo,
                           Boolean zuletztErreichbar, String zuletztGeprueft, String pruefhinweis) {}

    /** Ergebnis einer Erreichbarkeitsprüfung — für die Anzeige gedacht, nicht für Logik. */
    public record Erreichbarkeit(boolean erreichbar, String hinweis, long dauerMs) {}

    /** Seite einer Trefferliste. Serverseitige Paginierung, FA-GER-060. */
    public record Seite<T>(List<T> eintraege, int seitenNummer, int seitengroesse, int gesamt) {
        public int seitenZahl() { return Math.max(1, (gesamt + seitengroesse - 1) / seitengroesse); }
        public boolean hatVorherige() { return seitenNummer > 1; }
        public boolean hatNaechste()  { return seitenNummer < seitenZahl(); }
    }

    private final Datenbank db;
    private final Geheimnisspeicher tresor;

    public Bestandsverwaltung(Datenbank db, Geheimnisspeicher tresor) {
        this.db = db;
        this.tresor = tresor;
    }

    /**
     * Ob Zugangsdaten überhaupt gespeichert werden können.
     * Ohne Schlüssel ist alles andere weiter benutzbar — nur eben ohne
     * hinterlegte Passwörter. Das ist besser als ein Startabbruch, weil die
     * Bestandserfassung damit trotzdem möglich bleibt.
     */
    public boolean zugangsdatenMoeglich() { return tresor != null; }

    // ------------------------------------------------------------- Konnektoren

    /**
     * Konnektoren im Geltungsbereich.
     *
     * <p>Der Geltungsbereich ist <b>Pflichtparameter</b>. Wer ihn weglässt,
     * bekommt keinen ungefilterten Zugriff, sondern einen Übersetzungsfehler —
     * so wird Mandantentrennung zentral erzwungen statt vergessen
     * ({@link Geltungsbereich}).
     */
    public List<Konnektor> konnektoren(Geltungsbereich bereich) {
        List<Konnektor> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT k.*, o.bezeichnung AS org_bez,
                       (SELECT count(*) FROM kartenterminal t WHERE t.konnektor_id = k.id) AS terminalzahl
                FROM konnektor k
                JOIN organisation o ON o.id = k.organisation_id
                WHERE 1=1""" + bereich.bedingung("k.organisation_id")
                + " ORDER BY lower(o.bezeichnung), lower(k.bezeichnung)")) {
            bereich.wertSetzen(db.verbindung(), p, 1);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) liste.add(konnektorLesen(rs));
            }
        } catch (SQLException ignored) { }
        return liste;
    }

    /**
     * Ein Konnektor — aber nur, wenn er im Geltungsbereich liegt.
     *
     * <p>Liegt er außerhalb, ist das Ergebnis <b>leer</b>, nicht etwa ein
     * Zugriffsfehler. Das ist Absicht: Ein „Zugriff verweigert" würde verraten,
     * dass es diesen Datensatz gibt. Für einen fremden Mandanten existiert er
     * schlicht nicht.
     */
    public Optional<Konnektor> konnektor(long id, Geltungsbereich bereich) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT k.*, o.bezeichnung AS org_bez,
                       (SELECT count(*) FROM kartenterminal t WHERE t.konnektor_id = k.id) AS terminalzahl
                FROM konnektor k
                JOIN organisation o ON o.id = k.organisation_id
                WHERE k.id = ?""" + bereich.bedingung("k.organisation_id"))) {
            p.setLong(1, id);
            bereich.wertSetzen(db.verbindung(), p, 2);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(konnektorLesen(rs)) : Optional.empty();
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    /**
     * Legt einen Konnektor an.
     *
     * @param passwort Klartext; wird verschlüsselt abgelegt und nie wieder
     *                 ausgeliefert. {@code null} oder leer heißt „keins hinterlegt".
     * @return die neue Kennung, oder eine Fehlermeldung im Klartext
     */
    public Ergebnis konnektorAnlegen(long organisationId, String bezeichnung, String adresse,
                                     String modell, String mandantId, String clientsystemId,
                                     String arbeitsplatzId, String benutzerName, String passwort,
                                     String webadminAdresse, Geltungsbereich bereich) {
        String fehler = konnektorPruefen(bezeichnung, adresse, mandantId, clientsystemId, arbeitsplatzId);
        if (fehler != null) return Ergebnis.fehler(fehler);
        // Die Einrichtung kommt aus einem Formularfeld und ist damit
        // manipulierbar. Ohne diese Pruefung koennte jemand einen Konnektor in
        // einer fremden Einrichtung anlegen - eine Mandantengrenze, die nur in
        // der Auswahlliste besteht, ist keine.
        if (!bereich.umfasst(organisationId)) {
            return Ergebnis.fehler("Die gewählte Einrichtung liegt außerhalb Ihres "
                    + "Zuständigkeitsbereichs.");
        }
        if (!organisationVorhanden(organisationId)) {
            return Ergebnis.fehler("Die gewählte Einrichtung gibt es nicht mehr. "
                    + "Bitte die Seite neu laden und erneut auswählen.");
        }
        String chiffrat;
        try { chiffrat = chiffrieren(passwort); }
        catch (IllegalStateException e) { return Ergebnis.fehler(e.getMessage()); }

        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO konnektor (organisation_id, bezeichnung, adresse, modell,
                    mandant_id, clientsystem_id, arbeitsplatz_id,
                    benutzer_name, passwort_chiffrat, webadmin_adresse)
                VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id""")) {
            p.setLong(1, organisationId);
            p.setString(2, bezeichnung.trim());
            p.setString(3, adresse.trim());
            p.setString(4, leerZuNull(modell));
            p.setString(5, mandantId.trim());
            p.setString(6, clientsystemId.trim());
            p.setString(7, arbeitsplatzId.trim());
            p.setString(8, leerZuNull(benutzerName));
            p.setString(9, chiffrat);
            p.setString(10, leerZuNull(webadminAdresse));
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return Ergebnis.erfolg(rs.getLong(1));
            }
        } catch (SQLException e) {
            return Ergebnis.fehler("Der Konnektor konnte nicht gespeichert werden: " + klartext(e));
        }
    }

    /**
     * Ändert einen Konnektor.
     *
     * <p>Ein leeres Passwortfeld lässt das gespeicherte Passwort unverändert —
     * sonst würde jede Änderung an der Bezeichnung die Zugangsdaten löschen.
     * Zum Entfernen dient {@link #konnektorPasswortEntfernen(long)}.
     */
    public Ergebnis konnektorAendern(long id, String bezeichnung, String adresse, String modell,
                                     String mandantId, String clientsystemId, String arbeitsplatzId,
                                     String benutzerName, String neuesPasswort,
                                     String webadminAdresse, boolean aktiv,
                                     Geltungsbereich bereich) {
        String fehler = konnektorPruefen(bezeichnung, adresse, mandantId, clientsystemId, arbeitsplatzId);
        if (fehler != null) return Ergebnis.fehler(fehler);
        // Fuer einen fremden Mandanten existiert der Datensatz nicht - dieselbe
        // Meldung wie bei einem geloeschten. Ein "Zugriff verweigert" wuerde
        // verraten, dass es ihn gibt.
        if (konnektor(id, bereich).isEmpty()) return Ergebnis.fehler("Diesen Konnektor gibt es nicht mehr.");

        String chiffrat;
        try { chiffrat = chiffrieren(neuesPasswort); }
        catch (IllegalStateException e) { return Ergebnis.fehler(e.getMessage()); }

        String sql = """
                UPDATE konnektor SET bezeichnung=?, adresse=?, modell=?, mandant_id=?,
                    clientsystem_id=?, arbeitsplatz_id=?, benutzer_name=?, webadmin_adresse=?, aktiv=?
                """ + (chiffrat == null ? "" : ", passwort_chiffrat=?") + " WHERE id=?";
        try (PreparedStatement p = db.verbindung().prepareStatement(sql)) {
            int i = 1;
            p.setString(i++, bezeichnung.trim());
            p.setString(i++, adresse.trim());
            p.setString(i++, leerZuNull(modell));
            p.setString(i++, mandantId.trim());
            p.setString(i++, clientsystemId.trim());
            p.setString(i++, arbeitsplatzId.trim());
            p.setString(i++, leerZuNull(benutzerName));
            p.setString(i++, leerZuNull(webadminAdresse));
            p.setBoolean(i++, aktiv);
            if (chiffrat != null) p.setString(i++, chiffrat);
            p.setLong(i, id);
            p.executeUpdate();
            return Ergebnis.erfolg(id);
        } catch (SQLException e) {
            return Ergebnis.fehler("Die Änderung konnte nicht gespeichert werden: " + klartext(e));
        }
    }

    public Ergebnis konnektorPasswortEntfernen(long id, Geltungsbereich bereich) {
        if (konnektor(id, bereich).isEmpty()) return Ergebnis.fehler("Diesen Konnektor gibt es nicht mehr.");
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE konnektor SET passwort_chiffrat=NULL WHERE id=?")) {
            p.setLong(1, id);
            return p.executeUpdate() > 0 ? Ergebnis.erfolg(id)
                    : Ergebnis.fehler("Diesen Konnektor gibt es nicht mehr.");
        } catch (SQLException e) { return Ergebnis.fehler(klartext(e)); }
    }

    /**
     * Löscht einen Konnektor.
     *
     * <p>Nur möglich, wenn keine Terminals zugeordnet sind. Das Schema würde
     * sie per {@code ON DELETE CASCADE} stillschweigend mitlöschen — genau das
     * verbietet das Akzeptanzkriterium, und aus gutem Grund: Ein Fehlgriff
     * würde einen halben Standort aus dem Bestand entfernen.
     */
    public Ergebnis konnektorLoeschen(long id, Geltungsbereich bereich) {
        var k = konnektor(id, bereich);
        if (k.isEmpty()) return Ergebnis.fehler("Diesen Konnektor gibt es nicht mehr.");
        if (k.get().terminalzahl() > 0) {
            List<String> namen = terminalnamen(id, 5);
            return Ergebnis.fehler(
                    "Dem Konnektor „" + k.get().bezeichnung() + "“ sind noch "
                    + k.get().terminalzahl() + " Kartenterminal(s) zugeordnet: "
                    + String.join(", ", namen) + (k.get().terminalzahl() > namen.size() ? " …" : "")
                    + ". Bitte diese Geräte zuerst einem anderen Konnektor zuordnen "
                    + "oder löschen.");
        }
        try (PreparedStatement p = db.verbindung().prepareStatement("DELETE FROM konnektor WHERE id=?")) {
            p.setLong(1, id);
            p.executeUpdate();
            return Ergebnis.erfolg(id);
        } catch (SQLException e) { return Ergebnis.fehler(klartext(e)); }
    }

    /** Einziger Weg zum Klartextpasswort eines Konnektors. */
    public String konnektorPasswort(long id) {
        return entschluesselnAus("SELECT passwort_chiffrat FROM konnektor WHERE id=?", id);
    }

    // -------------------------------------------------------------- Terminals

    /**
     * Terminals seitenweise, gefiltert und durchsuchbar (FA-GER-060).
     *
     * @param suche  Volltext über Bezeichnung, Kennung und Standort; {@code null} = alles
     * @param konnektorId  Einschränkung auf einen Konnektor; {@code null} = alle
     * @param nurAktive  nur aktive Geräte
     */
    /**
     * Wonach die Geräteliste sortiert werden darf (FA-GER-060).
     *
     * <p><b>Weißliste, kein Durchreichen.</b> Ein Sortierfeld aus der URL geht
     * unmittelbar in die Abfrage ein und lässt sich nicht als Parameter binden —
     * ohne feste Liste wäre das eine SQL-Einschleusung. Deshalb kommt hier
     * niemals ein Wert aus der Anfrage in die Abfrage, sondern immer nur ein
     * Ausdruck aus dieser Aufzählung.
     */
    public enum Sortierung {
        BEZEICHNUNG("Bezeichnung",   "lower(t.bezeichnung)"),
        KENNUNG("Kennung",           "lower(t.terminal_id)"),
        KONNEKTOR("Konnektor",       "lower(k.bezeichnung)"),
        STANDORT("Standort",         "lower(coalesce(t.standortangabe,''))"),
        MODELL("Modell",             "lower(coalesce(t.modell,''))"),
        FIRMWARE("Firmware",         "coalesce(t.firmware,'')"),
        ERREICHBARKEIT("Erreichbarkeit", "t.zuletzt_erreichbar NULLS LAST"),
        SMCB("SMC-B",                "t.hat_smcb DESC");

        public final String anzeige;
        private final String ausdruck;
        Sortierung(String a, String s) { anzeige = a; ausdruck = s; }

        static Sortierung aus(String name) {
            if (name == null) return BEZEICHNUNG;
            try { return valueOf(name.toUpperCase()); }
            catch (IllegalArgumentException e) { return BEZEICHNUNG; }
        }
    }

    /** Filter der Geräteliste. Alle Felder dürfen {@code null} sein — dann wird nicht gefiltert. */
    public record Filter(String suche, Long konnektorId, Boolean aktiv, Boolean erreichbar,
                         String modell, String firmware, Boolean hatSmcb) {
        public static Filter leer() { return new Filter(null, null, null, null, null, null, null); }
        public boolean istGesetzt() {
            return (suche != null && !suche.isBlank()) || konnektorId != null || aktiv != null
                    || erreichbar != null || (modell != null && !modell.isBlank())
                    || (firmware != null && !firmware.isBlank()) || hatSmcb != null;
        }
    }

    /**
     * Geräteliste mit Filter, Sortierung und Seitenblättern (FA-GER-060).
     *
     * <p>Alles serverseitig. Bei 5.000 Geräten ist jede Variante, die erst lädt
     * und dann im Browser sortiert, unbrauchbar.
     */
    public Seite<Terminal> terminals(Filter filter, Sortierung sortierung, boolean absteigend,
                                     int seitenNummer, int seitengroesse, Geltungsbereich bereich) {
        int nr = Math.max(1, seitenNummer);
        int gr = Math.min(200, Math.max(1, seitengroesse));
        StringBuilder wo = new StringBuilder(" WHERE 1=1");
        List<Object> werte = new ArrayList<>();

        // Mandantengrenze zuerst, damit sie in jeder Abfrage an derselben
        // Stelle steht und bei einer Durchsicht sofort auffaellt.
        wo.append(bereich.bedingung("t.organisation_id"));
        if (!bereich.unbeschraenkt() && !bereich.leer()) werte.add(bereich);

        if (filter.suche() != null && !filter.suche().isBlank()) {
            wo.append(" AND (lower(t.bezeichnung) LIKE ? OR lower(t.terminal_id) LIKE ?"
                    + " OR lower(coalesce(t.standortangabe,'')) LIKE ?)");
            String m = "%" + filter.suche().trim().toLowerCase() + "%";
            werte.add(m); werte.add(m); werte.add(m);
        }
        if (filter.konnektorId() != null) { wo.append(" AND t.konnektor_id = ?"); werte.add(filter.konnektorId()); }
        if (filter.aktiv() != null)       { wo.append(" AND t.aktiv = ?");        werte.add(filter.aktiv()); }
        if (filter.hatSmcb() != null)     { wo.append(" AND t.hat_smcb = ?");     werte.add(filter.hatSmcb()); }
        if (filter.erreichbar() != null) {
            // Ein nie geprueftes Geraet ist nicht "nicht erreichbar" - es ist
            // unbekannt. Der Filter "nicht erreichbar" darf es nicht einsammeln,
            // sonst sucht der Betreiber Fehler, wo keine sind.
            wo.append(" AND t.zuletzt_erreichbar = ?"); werte.add(filter.erreichbar());
        }
        if (filter.modell() != null && !filter.modell().isBlank()) {
            wo.append(" AND t.modell = ?"); werte.add(filter.modell().trim());
        }
        if (filter.firmware() != null && !filter.firmware().isBlank()) {
            wo.append(" AND t.firmware = ?"); werte.add(filter.firmware().trim());
        }

        int gesamt = 0;
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT count(*) FROM kartenterminal t JOIN konnektor k ON k.id = t.konnektor_id" + wo)) {
            fuellen(p, werte);
            try (ResultSet rs = p.executeQuery()) { if (rs.next()) gesamt = rs.getInt(1); }
        } catch (SQLException ignored) { }

        Sortierung s = sortierung == null ? Sortierung.BEZEICHNUNG : sortierung;
        // Zweites Sortierfeld ist Pflicht: Ohne eindeutige Reihenfolge wandern
        // Zeilen zwischen den Seiten, sobald zwei Geraete denselben Wert haben.
        String ordnung = s.ausdruck + (absteigend ? " DESC" : " ASC") + ", t.id ASC";

        List<Terminal> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT t.*, k.bezeichnung AS konn_bez, o.bezeichnung AS org_bez,
                       g.bezeichnung AS grp_bez
                FROM kartenterminal t
                JOIN konnektor k    ON k.id = t.konnektor_id
                JOIN organisation o ON o.id = t.organisation_id
                LEFT JOIN geraetegruppe g ON g.id = t.gruppe_id
                """ + wo + " ORDER BY " + ordnung + " LIMIT ? OFFSET ?")) {
            int i = fuellen(p, werte);
            p.setInt(i++, gr);
            p.setInt(i, (nr - 1) * gr);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) liste.add(terminalLesen(rs));
            }
        } catch (SQLException ignored) { }
        return new Seite<>(liste, nr, gr, gesamt);
    }

    /** Werte, die als Filterauswahl angeboten werden — nur was tatsächlich vorkommt. */
    public List<String> vorhandeneWerte(String spalte, Geltungsbereich bereich) {
        // Auch hier eine Weissliste: Der Spaltenname geht direkt in die Abfrage.
        String sicher = switch (spalte) {
            case "modell", "firmware" -> spalte;
            default -> null;
        };
        if (sicher == null) return List.of();
        List<String> werte = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT DISTINCT " + sicher + " FROM kartenterminal WHERE " + sicher
                + " IS NOT NULL AND " + sicher + " <> ''"
                + bereich.bedingung("organisation_id") + " ORDER BY 1")) {
            bereich.wertSetzen(db.verbindung(), p, 1);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) werte.add(rs.getString(1));
            }
        } catch (SQLException ignored) { }
        return werte;
    }

    public Optional<Terminal> terminal(long id, Geltungsbereich bereich) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT t.*, k.bezeichnung AS konn_bez, o.bezeichnung AS org_bez,
                       g.bezeichnung AS grp_bez
                FROM kartenterminal t
                JOIN konnektor k    ON k.id = t.konnektor_id
                JOIN organisation o ON o.id = t.organisation_id
                LEFT JOIN geraetegruppe g ON g.id = t.gruppe_id
                WHERE t.id = ?""" + bereich.bedingung("t.organisation_id"))) {
            p.setLong(1, id);
            bereich.wertSetzen(db.verbindung(), p, 2);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(terminalLesen(rs)) : Optional.empty();
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    public Ergebnis terminalAnlegen(String terminalId, long konnektorId, String bezeichnung,
                                    String standort, String adresse, String modell,
                                    String seriennummer, String adminBenutzer, String adminPasswort,
                                    boolean hatSmcb, boolean pevimAktiv, Geltungsbereich bereich) {
        if (leer(terminalId))   return Ergebnis.fehler("Bitte die Kennung des Kartenterminals angeben (z. B. KT-EMPFANG-01).");
        if (leer(bezeichnung))  return Ergebnis.fehler("Bitte eine Bezeichnung angeben, unter der das Gerät wiedererkannt wird.");
        if (leer(adresse))      return Ergebnis.fehler("Bitte die Adresse des Kartenterminals angeben (Name oder IP-Adresse, optional mit Port).");
        // Die Einrichtung des Terminals kommt vom Konnektor. Liegt der
        // ausserhalb des Bereichs, ist er fuer diesen Benutzer nicht vorhanden -
        // damit auch kein Terminal daran.
        var k = konnektor(konnektorId, bereich);
        if (k.isEmpty()) return Ergebnis.fehler("Bitte einen Konnektor auswählen, an dem das Terminal betrieben wird.");

        // A_24080: PEviM ist ausschliesslich fuer SMC-B zulaessig. Ein Terminal
        // ohne SMC-B mit aktiver PIN-Automatik waere ein Widerspruch - und im
        // schlimmsten Fall ein Versuch gegen die falsche Karte.
        if (pevimAktiv && !hatSmcb) {
            return Ergebnis.fehler("Die PIN-Automatik ist nur für Terminals mit SMC-B zulässig "
                    + "(gematik A_24080). Bitte entweder „SMC-B vorhanden“ setzen oder "
                    + "die PIN-Automatik abwählen.");
        }
        String chiffrat;
        try { chiffrat = chiffrieren(adminPasswort); }
        catch (IllegalStateException e) { return Ergebnis.fehler(e.getMessage()); }

        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO kartenterminal (terminal_id, konnektor_id, organisation_id, bezeichnung,
                    standortangabe, adresse, modell, seriennummer, admin_benutzer, admin_chiffrat,
                    hat_smcb, pevim_aktiv)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id""")) {
            p.setString(1, terminalId.trim());
            p.setLong(2, konnektorId);
            p.setLong(3, k.get().organisationId());
            p.setString(4, bezeichnung.trim());
            p.setString(5, leerZuNull(standort));
            p.setString(6, adresse.trim());
            p.setString(7, leerZuNull(modell));
            p.setString(8, leerZuNull(seriennummer));
            p.setString(9, leerZuNull(adminBenutzer));
            p.setString(10, chiffrat);
            p.setBoolean(11, hatSmcb);
            p.setBoolean(12, pevimAktiv);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return Ergebnis.erfolg(rs.getLong(1));
            }
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("kartenterminal_terminal_id_key")) {
                return Ergebnis.fehler("Die Kennung „" + terminalId.trim() + "“ ist bereits "
                        + "vergeben. Jede Terminal-Kennung darf nur einmal vorkommen.");
            }
            return Ergebnis.fehler("Das Kartenterminal konnte nicht gespeichert werden: " + klartext(e));
        }
    }

    public Ergebnis terminalAendern(long id, String bezeichnung, String standort, String adresse,
                                    String modell, String seriennummer, String adminBenutzer,
                                    String neuesAdminPasswort, boolean hatSmcb, boolean pevimAktiv,
                                    boolean aktiv, Geltungsbereich bereich) {
        if (leer(bezeichnung)) return Ergebnis.fehler("Bitte eine Bezeichnung angeben.");
        if (leer(adresse))     return Ergebnis.fehler("Bitte die Adresse des Kartenterminals angeben.");
        if (terminal(id, bereich).isEmpty()) return Ergebnis.fehler("Dieses Kartenterminal gibt es nicht mehr.");
        if (pevimAktiv && !hatSmcb) {
            return Ergebnis.fehler("Die PIN-Automatik ist nur für Terminals mit SMC-B zulässig "
                    + "(gematik A_24080).");
        }
        String chiffrat;
        try { chiffrat = chiffrieren(neuesAdminPasswort); }
        catch (IllegalStateException e) { return Ergebnis.fehler(e.getMessage()); }

        String sql = """
                UPDATE kartenterminal SET bezeichnung=?, standortangabe=?, adresse=?, modell=?,
                    seriennummer=?, admin_benutzer=?, hat_smcb=?, pevim_aktiv=?, aktiv=?
                """ + (chiffrat == null ? "" : ", admin_chiffrat=?") + " WHERE id=?";
        try (PreparedStatement p = db.verbindung().prepareStatement(sql)) {
            int i = 1;
            p.setString(i++, bezeichnung.trim());
            p.setString(i++, leerZuNull(standort));
            p.setString(i++, adresse.trim());
            p.setString(i++, leerZuNull(modell));
            p.setString(i++, leerZuNull(seriennummer));
            p.setString(i++, leerZuNull(adminBenutzer));
            p.setBoolean(i++, hatSmcb);
            p.setBoolean(i++, pevimAktiv);
            p.setBoolean(i++, aktiv);
            if (chiffrat != null) p.setString(i++, chiffrat);
            p.setLong(i, id);
            p.executeUpdate();
            return Ergebnis.erfolg(id);
        } catch (SQLException e) {
            return Ergebnis.fehler("Die Änderung konnte nicht gespeichert werden: " + klartext(e));
        }
    }

    public Ergebnis terminalLoeschen(long id, Geltungsbereich bereich) {
        var t = terminal(id, bereich);
        if (t.isEmpty()) return Ergebnis.fehler("Dieses Kartenterminal gibt es nicht mehr.");
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "DELETE FROM kartenterminal WHERE id=?")) {
            p.setLong(1, id);
            p.executeUpdate();
            return Ergebnis.erfolg(id);
        } catch (SQLException e) { return Ergebnis.fehler(klartext(e)); }
    }

    // ----------------------------------------------------------- Massenaktionen

    /**
     * Ergebnis einer Massenaktion.
     *
     * <p>Getrennt gezählt, weil ein Betreiber wissen muss, ob wirklich alles
     * durchgelaufen ist. „Aktion ausgeführt" bei 40 von 50 Geräten wäre eine
     * Falschmeldung — und bei 5.000 Geräten fällt so etwas niemandem auf,
     * wenn man es nicht ausdrücklich sagt.
     */
    public record Massenergebnis(int geaendert, int uebersprungen, List<String> hinweise) {
        public boolean vollstaendig() { return uebersprungen == 0; }
    }

    /** Aktiviert oder deaktiviert mehrere Geräte auf einmal (FA-GER-060). */
    public Massenergebnis massenAktivSetzen(List<Long> ids, boolean aktiv, Geltungsbereich bereich) {
        if (ids == null || ids.isEmpty()) {
            return new Massenergebnis(0, 0, List.of("Es war kein Gerät ausgewählt."));
        }
        // Auch hier: Die Kennungen kommen aus dem Formular. Ohne die
        // Bereichsgrenze koennte eine praeparierte Anfrage fremde Geraete
        // deaktivieren - lautlos, denn die Liste zeigt sie ja nicht.
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE kartenterminal SET aktiv = ? WHERE id = ANY (?)"
                + bereich.bedingung("organisation_id"))) {
            p.setBoolean(1, aktiv);
            p.setArray(2, kennungsArray(ids));
            bereich.wertSetzen(db.verbindung(), p, 3);
            int n = p.executeUpdate();
            return new Massenergebnis(n, ids.size() - n, n == ids.size() ? List.of()
                    : List.of("%d von %d Geräten gibt es nicht mehr. Bitte die Liste neu laden."
                            .formatted(ids.size() - n, ids.size())));
        } catch (SQLException e) {
            return new Massenergebnis(0, ids.size(), List.of("Die Änderung ist fehlgeschlagen: "
                    + klartext(e)));
        }
    }

    /**
     * Ordnet mehrere Geräte einer Gerätegruppe zu (FA-GER-060, FA-GER-050).
     *
     * <p>Eine Gruppe liegt innerhalb genau einer Einrichtung (FA-GER-050).
     * Geräte einer anderen Einrichtung werden deshalb <b>übersprungen und
     * benannt</b>, nicht stillschweigend verschoben — sonst wäre die
     * Mandantentrennung über eine Massenaktion aushebelbar.
     */
    public Massenergebnis massenGruppeZuweisen(List<Long> ids, long gruppeId, Geltungsbereich bereich) {
        if (ids == null || ids.isEmpty()) {
            return new Massenergebnis(0, 0, List.of("Es war kein Gerät ausgewählt."));
        }
        String einrichtung;
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT einrichtung FROM geraetegruppe WHERE id = ?")) {
            p.setLong(1, gruppeId);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) {
                    return new Massenergebnis(0, ids.size(),
                            List.of("Diese Gerätegruppe gibt es nicht mehr."));
                }
                einrichtung = rs.getString(1);
            }
        } catch (SQLException e) {
            return new Massenergebnis(0, ids.size(), List.of(klartext(e)));
        }

        List<String> fremde = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT t.bezeichnung FROM kartenterminal t
                JOIN organisation o ON o.id = t.organisation_id
                WHERE t.id = ANY (?) AND o.bezeichnung <> ?""")) {
            p.setArray(1, kennungsArray(ids));
            p.setString(2, einrichtung);
            try (ResultSet rs = p.executeQuery()) { while (rs.next()) fremde.add(rs.getString(1)); }
        } catch (SQLException ignored) { }

        try (PreparedStatement p = db.verbindung().prepareStatement("""
                UPDATE kartenterminal t SET gruppe_id = ?
                FROM organisation o
                WHERE o.id = t.organisation_id AND t.id = ANY (?) AND o.bezeichnung = ?"""
                + bereich.bedingung("t.organisation_id"))) {
            p.setLong(1, gruppeId);
            p.setArray(2, kennungsArray(ids));
            p.setString(3, einrichtung);
            bereich.wertSetzen(db.verbindung(), p, 4);
            int n = p.executeUpdate();

            List<String> hinweise = new ArrayList<>();
            if (!fremde.isEmpty()) {
                hinweise.add(("%d Gerät(e) gehören zu einer anderen Einrichtung als die Gruppe "
                        + "und wurden übersprungen: %s. Eine Gruppe liegt innerhalb genau einer "
                        + "Einrichtung.").formatted(fremde.size(),
                        String.join(", ", fremde.subList(0, Math.min(5, fremde.size()))))
                        + (fremde.size() > 5 ? " …" : ""));
            }
            return new Massenergebnis(n, ids.size() - n, hinweise);
        } catch (SQLException e) {
            return new Massenergebnis(0, ids.size(), List.of("Die Zuordnung ist fehlgeschlagen: "
                    + klartext(e)));
        }
    }

    /** Einziger Weg zum Klartextpasswort eines Terminal-Administrationszugangs. */
    public String terminalAdminPasswort(long id) {
        return entschluesselnAus("SELECT admin_chiffrat FROM kartenterminal WHERE id=?", id);
    }

    /**
     * Übernimmt einen ermittelten Fund in den Bestand (FA-GER-030).
     *
     * <p>Nur nach ausdrücklicher Bestätigung durch einen Administrator — diese
     * Methode wird nie selbsttätig aufgerufen.
     *
     * <p>Der Konnektor liefert keine Netzadresse des Terminals; die wird beim
     * Übernehmen vom Administrator ergänzt oder bleibt zunächst leer. Deshalb
     * ist {@code adresse} hier optional, anders als beim Anlegen von Hand.
     */
    public Ergebnis uebernehmen(Ermittlung.Fund fund, long konnektorId, String adresse,
                                Geltungsbereich bereich) {
        if (fund.bestandsId() != null) {
            return Ergebnis.fehler("Das Kartenterminal „" + fund.terminalId()
                    + "“ ist bereits erfasst. Doppelte Übernahme ist nicht möglich.");
        }
        var k = konnektor(konnektorId, bereich);
        if (k.isEmpty()) {
            return Ergebnis.fehler("Bitte einen Konnektor auswählen, dem das Gerät zugeordnet wird.");
        }
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO kartenterminal (terminal_id, konnektor_id, organisation_id, bezeichnung,
                    adresse, modell, firmware, hat_smcb, pevim_aktiv, kartenhandle, iccsn)
                VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id""")) {
            p.setString(1, fund.terminalId());
            p.setLong(2, konnektorId);
            p.setLong(3, k.get().organisationId());
            p.setString(4, leer(fund.name()) ? fund.terminalId() : fund.name());
            p.setString(5, leerZuNull(adresse));
            p.setString(6, leerZuNull(fund.modell()));
            p.setString(7, leerZuNull(fund.firmware()));
            p.setBoolean(8, fund.hatSmcb());
            // PEviM wird uebernommen, wie das Geraet es meldet - aber nur, wenn
            // eine SMC-B steckt (A_24080). Der Konnektor kann beides melden;
            // die Regel setzen wir durch, nicht er.
            p.setBoolean(9, fund.pevimAktiv() && fund.hatSmcb());
            p.setString(10, leerZuNull(fund.kartenhandle()));
            p.setString(11, leerZuNull(fund.iccsn()));
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return Ergebnis.erfolg(rs.getLong(1));
            }
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("kartenterminal_terminal_id_key")) {
                return Ergebnis.fehler("Die Kennung „" + fund.terminalId()
                        + "“ ist inzwischen von jemand anderem vergeben worden.");
            }
            return Ergebnis.fehler("Die Übernahme ist fehlgeschlagen: " + klartext(e));
        }
    }

    // --------------------------------------------------------- Organisationen

    /** Einrichtungen zur Auswahl, in hierarchischer Reihenfolge. */
    public List<Bestandsseiten.Organisationsauswahl> organisationen(Geltungsbereich bereich) {
        List<Bestandsseiten.Organisationsauswahl> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT id, bezeichnung, art FROM organisation WHERE 1=1"""
                + bereich.bedingung("id")
                + " ORDER BY CASE art WHEN 'VERBUND' THEN 1 WHEN 'STANDORT' THEN 2 ELSE 3 END,"
                + " lower(bezeichnung)")) {
            bereich.wertSetzen(db.verbindung(), p, 1);
            try (ResultSet rs = p.executeQuery()) {
            while (rs.next()) {
                liste.add(new Bestandsseiten.Organisationsauswahl(
                        rs.getLong("id"), rs.getString("bezeichnung"), rs.getString("art")));
            }
            }
        } catch (SQLException ignored) { }
        return liste;
    }

    // -------------------------------------------------- Erreichbarkeitsprüfung

    /**
     * Prüft, ob unter der Adresse eine TCP-Verbindung zustande kommt.
     *
     * <p>Bewusst nur die Transportebene: Ein vollständiger TLS-Aufbau mit
     * Brainpool-Zertifikaten und Aufrufkontext gehört zum Agenten, nicht in die
     * Bestandsmaske. Für die Frage „habe ich mich bei der Adresse vertippt?" —
     * und das ist die häufigste — genügt diese Prüfung und antwortet in
     * Sekundenbruchteilen statt in zehn Sekunden.
     */
    public Erreichbarkeit erreichbarkeitPruefen(String adresse) {
        long start = System.nanoTime();
        if (leer(adresse)) {
            return new Erreichbarkeit(false, "Es ist keine Adresse hinterlegt.", 0);
        }
        String host;
        int port;
        try {
            String a = adresse.trim();
            if (!a.contains("://")) a = "https://" + a;
            URI u = URI.create(a);
            host = u.getHost();
            port = u.getPort() > 0 ? u.getPort() : ("http".equals(u.getScheme()) ? 80 : 443);
            if (host == null) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            return new Erreichbarkeit(false,
                    "Die Adresse „" + adresse + "“ ist nicht lesbar. Erwartet wird ein "
                    + "Rechnername oder eine IP-Adresse, wahlweise mit Port, "
                    + "zum Beispiel 192.168.1.10:443.", ms(start));
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), PRUEF_ZEITGRENZE_MS);
            return new Erreichbarkeit(true,
                    "Verbindung zu " + host + ":" + port + " hergestellt.", ms(start));
        } catch (java.net.UnknownHostException e) {
            return new Erreichbarkeit(false,
                    "Der Name „" + host + "“ ist im Netz nicht auflösbar. Bitte die "
                    + "Schreibweise prüfen oder die IP-Adresse eintragen.", ms(start));
        } catch (java.net.SocketTimeoutException e) {
            return new Erreichbarkeit(false,
                    "Unter " + host + ":" + port + " antwortet innerhalb von "
                    + (PRUEF_ZEITGRENZE_MS / 1000) + " Sekunden nichts. Das Gerät ist "
                    + "ausgeschaltet, nicht im Netz, oder eine Firewall blockiert den Port.",
                    ms(start));
        } catch (Exception e) {
            return new Erreichbarkeit(false,
                    "Die Verbindung zu " + host + ":" + port + " wurde abgelehnt. Meist ist "
                    + "der Port falsch oder der Dienst läuft nicht.", ms(start));
        }
    }

    /** Prüft und schreibt das Ergebnis in den Bestand, damit die Liste es zeigen kann. */
    public Erreichbarkeit konnektorPruefen(long id, Geltungsbereich bereich) {
        var k = konnektor(id, bereich);
        if (k.isEmpty()) return new Erreichbarkeit(false, "Diesen Konnektor gibt es nicht mehr.", 0);
        var e = erreichbarkeitPruefen(k.get().adresse());
        ergebnisMerken("konnektor", id, e);
        return e;
    }

    public Erreichbarkeit terminalPruefen(long id, Geltungsbereich bereich) {
        var t = terminal(id, bereich);
        if (t.isEmpty()) return new Erreichbarkeit(false, "Dieses Kartenterminal gibt es nicht mehr.", 0);
        var e = erreichbarkeitPruefen(t.get().adresse());
        ergebnisMerken("kartenterminal", id, e);
        return e;
    }

    private void ergebnisMerken(String tabelle, long id, Erreichbarkeit e) {
        try (PreparedStatement p = db.verbindung().prepareStatement("UPDATE " + tabelle
                + " SET zuletzt_erreichbar=?, zuletzt_geprueft=now(), pruefhinweis=? WHERE id=?")) {
            p.setBoolean(1, e.erreichbar());
            p.setString(2, e.hinweis());
            p.setLong(3, id);
            p.executeUpdate();
        } catch (SQLException ignored) { }
    }

    // ----------------------------------------------------------------- Ergebnis

    /** Rückgabe einer schreibenden Operation: entweder eine Kennung oder ein Klartextfehler. */
    public record Ergebnis(Long id, String fehler) {
        public static Ergebnis erfolg(long id)      { return new Ergebnis(id, null); }
        public static Ergebnis fehler(String text)  { return new Ergebnis(null, text); }
        public boolean geklappt()                   { return fehler == null; }
    }

    // -------------------------------------------------------------- Innenleben

    private String konnektorPruefen(String bezeichnung, String adresse, String mandantId,
                                    String clientsystemId, String arbeitsplatzId) {
        if (leer(bezeichnung))    return "Bitte eine Bezeichnung angeben, unter der der Konnektor wiedererkannt wird.";
        if (leer(adresse))        return "Bitte die Adresse des Konnektors angeben (Name oder IP-Adresse, optional mit Port).";
        // Ohne vollstaendigen Aufrufkontext schlaegt spaeter JEDER Fachaufruf
        // fehl - mit einer Meldung, die den Zusammenhang nicht erkennen laesst
        // (TUC_KON_000). Deshalb hier hart pruefen statt spaeter raten.
        if (leer(mandantId))      return "Bitte die Mandanten-ID angeben. Sie steht in der Konnektor-Konfiguration und wird bei jedem Aufruf mitgeschickt.";
        if (leer(clientsystemId)) return "Bitte die Clientsystem-ID angeben. Sie steht in der Konnektor-Konfiguration.";
        if (leer(arbeitsplatzId)) return "Bitte die Arbeitsplatz-ID angeben. Sie bestimmt, welche Kartenterminals der Konnektor für uns anspricht.";
        return null;
    }

    private String chiffrieren(String klartext) {
        if (klartext == null || klartext.isEmpty()) return null;
        if (tresor == null) {
            throw new IllegalStateException(
                    "Zugangsdaten können nicht gespeichert werden, weil kein Schlüssel "
                    + "hinterlegt ist. Bitte in der Konfiguration die Variable "
                    + "GEHEIMNIS_SCHLUESSEL setzen und die Anwendung neu starten. "
                    + "Alle übrigen Angaben lassen sich auch ohne Schlüssel speichern.");
        }
        return tresor.verschluesseln(klartext);
    }

    private String entschluesselnAus(String sql, long id) {
        if (tresor == null) return null;
        try (PreparedStatement p = db.verbindung().prepareStatement(sql)) {
            p.setLong(1, id);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? tresor.entschluesseln(rs.getString(1)) : null;
            }
        } catch (SQLException e) { return null; }
    }

    private List<String> terminalnamen(long konnektorId, int hoechstens) {
        List<String> namen = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT bezeichnung FROM kartenterminal WHERE konnektor_id=? "
                + "ORDER BY lower(bezeichnung) LIMIT ?")) {
            p.setLong(1, konnektorId);
            p.setInt(2, hoechstens);
            try (ResultSet rs = p.executeQuery()) { while (rs.next()) namen.add(rs.getString(1)); }
        } catch (SQLException ignored) { }
        return namen;
    }

    private boolean organisationVorhanden(long id) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT 1 FROM organisation WHERE id=?")) {
            p.setLong(1, id);
            try (ResultSet rs = p.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { return false; }
    }

    private Konnektor konnektorLesen(ResultSet rs) throws SQLException {
        Timestamp gp = rs.getTimestamp("zuletzt_geprueft");
        boolean erreichbar = rs.getBoolean("zuletzt_erreichbar");
        return new Konnektor(rs.getLong("id"), rs.getLong("organisation_id"), rs.getString("org_bez"),
                rs.getString("bezeichnung"), rs.getString("adresse"), rs.getString("modell"),
                rs.getString("mandant_id"), rs.getString("clientsystem_id"), rs.getString("arbeitsplatz_id"),
                rs.getString("benutzer_name"), rs.getString("passwort_chiffrat") != null,
                rs.getString("webadmin_adresse"), rs.getBoolean("aktiv"), rs.getBoolean("ist_demo"),
                rs.wasNull() && gp == null ? null : (gp == null ? null : erreichbar),
                zeitpunkt(gp), rs.getString("pruefhinweis"), rs.getInt("terminalzahl"));
    }

    private Terminal terminalLesen(ResultSet rs) throws SQLException {
        Timestamp gp = rs.getTimestamp("zuletzt_geprueft");
        boolean erreichbar = rs.getBoolean("zuletzt_erreichbar");
        long gruppeId = rs.getLong("gruppe_id");
        return new Terminal(rs.getLong("id"), rs.getString("terminal_id"),
                rs.getLong("konnektor_id"), rs.getString("konn_bez"),
                rs.getLong("organisation_id"), rs.getString("org_bez"),
                rs.wasNull() ? null : gruppeId, rs.getString("grp_bez"),
                rs.getString("bezeichnung"), rs.getString("standortangabe"), rs.getString("adresse"),
                rs.getString("modell"), rs.getString("seriennummer"), rs.getString("firmware"),
                rs.getString("admin_benutzer"), rs.getString("admin_chiffrat") != null,
                rs.getBoolean("hat_smcb"), rs.getBoolean("pevim_aktiv"),
                rs.getBoolean("aktiv"), rs.getBoolean("ist_demo"),
                gp == null ? null : erreichbar, zeitpunkt(gp), rs.getString("pruefhinweis"));
    }

    private static String zeitpunkt(Timestamp t) {
        return t == null ? null : t.toLocalDateTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }

    /**
     * Setzt die Filterwerte typrichtig.
     *
     * <p>Die frühere Fassung machte aus allem außer {@code Long} eine
     * Zeichenkette. Ein {@code Boolean} wurde damit zu {@code "true"} und
     * verglich sich gegen eine {@code boolean}-Spalte ins Leere — der Filter
     * lieferte lautlos null Treffer statt eines Fehlers. Solche Fehler sind die
     * unangenehmsten: Die Oberfläche sagt „keine Geräte", und niemand merkt,
     * dass die Frage falsch gestellt war.
     */
    private static int fuellen(PreparedStatement p, List<Object> werte) throws SQLException {
        int i = 1;
        for (Object w : werte) {
            if (w instanceof Long l)          p.setLong(i++, l);
            else if (w instanceof Integer n)  p.setInt(i++, n);
            else if (w instanceof Boolean b)  p.setBoolean(i++, b);
            else if (w instanceof Geltungsbereich g) i = g.wertSetzen(p.getConnection(), p, i);
            else                              p.setString(i++, String.valueOf(w));
        }
        return i;
    }

    /**
     * Baut ein Datenbank-Array aus Kennungen.
     *
     * <p>{@code List.toArray()} liefert {@code Object[]}; der PostgreSQL-Treiber
     * braucht den passenden Typ, sonst scheitert die Umsetzung zur Laufzeit.
     */
    private java.sql.Array kennungsArray(List<Long> ids) throws SQLException {
        return db.verbindung().createArrayOf("bigint", ids.toArray(new Long[0]));
    }

    private static long ms(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000; }
    private static boolean leer(String s)   { return s == null || s.isBlank(); }
    private static String leerZuNull(String s) { return leer(s) ? null : s.trim(); }

    /** Datenbankmeldungen sind für Betreiber unlesbar — hier bleibt nur die erste Zeile. */
    private static String klartext(SQLException e) {
        String m = String.valueOf(e.getMessage());
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }
}
