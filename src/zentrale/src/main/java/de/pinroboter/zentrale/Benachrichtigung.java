package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Geheimnisspeicher;

import java.sql.*;
import java.util.*;

/**
 * Benachrichtigung per E-Mail (FA-ALA-020).
 *
 * <h2>Das Sammelfenster ist der Kern, nicht der Versand</h2>
 *
 * Bei einem ausgefallenen Konnektor mit 5.000 angeschlossenen Terminals
 * entstehen binnen Sekunden tausende Alarme. Eine Mail je Alarm hätte drei
 * Folgen, und alle drei sind schlecht:
 *
 * <ol>
 *   <li>Der Mailserver der Praxis stuft den Absender als Spam ein.</li>
 *   <li>Der Empfänger richtet einen Filter ein — und sieht danach auch die
 *       <b>eine</b> wichtige Mail nicht mehr.</li>
 *   <li>Die eigentliche Information — „ein Konnektor ist ausgefallen" — geht in
 *       tausend Einzelmeldungen unter.</li>
 * </ol>
 *
 * Deshalb wird gesammelt: Neue Alarme kommen in eine Warteschlange, und nach
 * Ablauf des Fensters geht <b>eine</b> Mail mit einer Zusammenfassung hinaus.
 * Die Vorgabe sind fünf Minuten.
 *
 * <p>Ein Fenster von {@code 0} bedeutet „sofort" und ist ausdrücklich möglich —
 * für Einrichtungen mit wenigen Geräten, wo jede Meldung einzeln ankommen soll.
 * Bei 5.000 Terminals ist das die falsche Wahl, aber das ist die Entscheidung
 * des Betreibers.
 *
 * <h2>Was nie in einer Mail steht</h2>
 *
 * <b>Keine PIN, kein Chiffrat, keine personenbezogenen Daten.</b> Eine Mail
 * verlässt das Haus, wird über fremde Server geleitet und liegt danach in einem
 * Postfach, dessen Schutz wir nicht kennen. Die Nachricht nennt deshalb nur:
 * was passiert ist, an welchem Gerät, und was zu tun ist.
 *
 * <p>Der Alarmtext selbst wird von {@link Alarmverwaltung} erzeugt und enthält
 * bereits keine Geheimnisse — der Nachweis dafür läuft in den Systemnachweisen.
 * Diese Klasse fügt nichts hinzu, was dort nicht schon steht.
 */
public class Benachrichtigung {

    /** Schweregrade in aufsteigender Dringlichkeit — für den Vergleich mit der Schwelle. */
    private static final List<String> STUFEN = List.of("HINWEIS", "WARNUNG", "KRITISCH");

    public record Servereinstellung(String host, int port, boolean tlsVerlangt,
                                    String benutzer, boolean kennwortHinterlegt,
                                    String absender) {}

    public record Regel(long organisationId, String organisation, String empfaenger,
                        String schwelle, int sammelfensterSekunden, boolean aktiv) {}

    private final Datenbank db;
    private final Geheimnisspeicher tresor;

    public Benachrichtigung(Datenbank db, Geheimnisspeicher tresor) {
        this.db = db;
        this.tresor = tresor;
    }

    // ------------------------------------------------------------- Einstellung

    public Optional<Servereinstellung> server() {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT host, port, tls_verlangt, benutzer, kennwort_chiffrat, absender "
                + "FROM mail_server WHERE nur_eine")) {
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Servereinstellung(rs.getString("host"), rs.getInt("port"),
                        rs.getBoolean("tls_verlangt"), rs.getString("benutzer"),
                        rs.getString("kennwort_chiffrat") != null, rs.getString("absender")));
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    /**
     * Speichert die Servereinstellung.
     *
     * @param kennwort leer lässt ein hinterlegtes Kennwort unverändert — sonst
     *        müsste es bei jeder anderen Änderung neu eingegeben werden, und
     *        genau daran scheitert Bedienung in der Praxis.
     * @return Fehlermeldung im Klartext, oder {@code null}
     */
    public String serverSpeichern(String host, String port, boolean tlsVerlangt,
                                  String benutzer, String kennwort, String absender) {
        if (host == null || host.isBlank()) return "Bitte den Namen des Mailservers eingeben.";
        if (absender == null || !Mailversand.gueltigeAdresse(absender)) {
            return "Bitte eine gültige Absenderadresse eingeben, z. B. pin-roboter@praxis.de";
        }
        int p;
        try { p = Integer.parseInt(port.trim()); }
        catch (Exception e) { return "Der Port muss eine Zahl sein (üblich: 587, 465 oder 25)."; }
        if (p < 1 || p > 65535) return "Der Port muss zwischen 1 und 65535 liegen.";

        String chiffrat = null;
        boolean kennwortSetzen = kennwort != null && !kennwort.isBlank();
        if (kennwortSetzen) {
            if (tresor == null) {
                return "Ohne GEHEIMNIS_SCHLUESSEL lässt sich kein Kennwort speichern. "
                     + "Der Mailserver kann dann nur ohne Anmeldung genutzt werden.";
            }
            chiffrat = tresor.verschluesseln(kennwort);
        }

        try (PreparedStatement s = db.verbindung().prepareStatement("""
                INSERT INTO mail_server (nur_eine, host, port, tls_verlangt, benutzer,
                                         kennwort_chiffrat, absender, geaendert)
                VALUES (TRUE,?,?,?,?,?,?,now())
                ON CONFLICT (nur_eine) DO UPDATE SET
                  host = EXCLUDED.host, port = EXCLUDED.port,
                  tls_verlangt = EXCLUDED.tls_verlangt, benutzer = EXCLUDED.benutzer,
                  kennwort_chiffrat = COALESCE(EXCLUDED.kennwort_chiffrat,
                                               mail_server.kennwort_chiffrat),
                  absender = EXCLUDED.absender, geaendert = now()""")) {
            s.setString(1, host.trim());
            s.setInt(2, p);
            s.setBoolean(3, tlsVerlangt);
            s.setString(4, benutzer == null || benutzer.isBlank() ? null : benutzer.trim());
            s.setString(5, chiffrat);
            s.setString(6, absender.trim());
            s.executeUpdate();
            return null;
        } catch (SQLException e) {
            return "Die Einstellung konnte nicht gespeichert werden.";
        }
    }

    /** Der Zugang zum Versand — mit entschlüsseltem Kennwort. Nie protokollieren. */
    Optional<Mailversand.Zugang> zugang() {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT host, port, tls_verlangt, benutzer, kennwort_chiffrat, absender "
                + "FROM mail_server WHERE nur_eine")) {
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String chiffrat = rs.getString("kennwort_chiffrat");
                char[] kennwort = new char[0];
                if (chiffrat != null && tresor != null) {
                    String klar = tresor.entschluesseln(chiffrat);
                    if (klar != null) kennwort = klar.toCharArray();
                }
                return Optional.of(new Mailversand.Zugang(rs.getString("host"), rs.getInt("port"),
                        rs.getBoolean("tls_verlangt"), rs.getString("benutzer"),
                        kennwort, rs.getString("absender")));
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    // ------------------------------------------------------------------ Regeln

    public List<Regel> regeln(Geltungsbereich bereich) {
        List<Regel> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT r.organisation_id, o.bezeichnung, r.empfaenger, r.schwelle, "
                + "r.sammelfenster_sekunden, r.aktiv FROM mail_regel r "
                + "JOIN organisation o ON o.id = r.organisation_id WHERE 1=1"
                + bereich.bedingung("r.organisation_id") + " ORDER BY o.bezeichnung")) {
            bereich.wertSetzen(db.verbindung(), p, 1);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    liste.add(new Regel(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getInt(5), rs.getBoolean(6)));
                }
            }
        } catch (SQLException e) {
            System.err.println("Benachrichtigungsregeln nicht lesbar: " + e.getMessage());
        }
        return liste;
    }

    /** Legt eine Regel an oder ändert sie. Mandantengebunden. */
    public String regelSpeichern(long organisationId, String empfaenger, String schwelle,
                                 String fenster, boolean aktiv, Geltungsbereich bereich) {
        if (!bereich.umfasst(organisationId)) {
            return "Diese Organisationseinheit steht Ihnen nicht zur Verfügung.";
        }
        List<String> adressen = adressenZerlegen(empfaenger);
        if (adressen.isEmpty()) return "Bitte mindestens eine Empfängeradresse eingeben.";
        for (String a : adressen) {
            if (!Mailversand.gueltigeAdresse(a)) {
                return "„" + a + "“ ist keine gültige E-Mail-Adresse.";
            }
        }
        if (!STUFEN.contains(schwelle)) return "Bitte einen gültigen Schweregrad wählen.";
        int sek;
        try { sek = Integer.parseInt(fenster.trim()); }
        catch (Exception e) { return "Das Sammelfenster muss eine Zahl in Sekunden sein."; }
        if (sek < 0 || sek > 86400) {
            return "Das Sammelfenster muss zwischen 0 Sekunden und 24 Stunden liegen.";
        }

        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO mail_regel (organisation_id, empfaenger, schwelle,
                                        sammelfenster_sekunden, aktiv, geaendert)
                VALUES (?,?,?,?,?,now())
                ON CONFLICT (organisation_id) DO UPDATE SET
                  empfaenger = EXCLUDED.empfaenger, schwelle = EXCLUDED.schwelle,
                  sammelfenster_sekunden = EXCLUDED.sammelfenster_sekunden,
                  aktiv = EXCLUDED.aktiv, geaendert = now()""")) {
            p.setLong(1, organisationId);
            p.setString(2, String.join(", ", adressen));
            p.setString(3, schwelle);
            p.setInt(4, sek);
            p.setBoolean(5, aktiv);
            p.executeUpdate();
            return null;
        } catch (SQLException e) { return "Die Regel konnte nicht gespeichert werden."; }
    }

    public String regelLoeschen(long organisationId, Geltungsbereich bereich) {
        if (!bereich.umfasst(organisationId)) {
            return "Diese Organisationseinheit steht Ihnen nicht zur Verfügung.";
        }
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "DELETE FROM mail_regel WHERE organisation_id = ?")) {
            p.setLong(1, organisationId);
            p.executeUpdate();
            return null;
        } catch (SQLException e) { return "Die Regel konnte nicht entfernt werden."; }
    }

    static List<String> adressenZerlegen(String eingabe) {
        if (eingabe == null) return List.of();
        return Arrays.stream(eingabe.split("[,;\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    // ------------------------------------------------------------ Einreihen

    /**
     * Reiht einen neuen Alarm ein, wenn eine passende Regel greift.
     *
     * <p>Wird nur für <b>neue</b> Alarme aufgerufen — {@code Alarmverwaltung.melden}
     * sagt, ob einer neu war. Ein wiederkehrender Alarm desselben Zustands darf
     * nicht erneut melden, sonst entsteht die Flut auf anderem Weg.
     *
     * @return true, wenn tatsächlich eingereiht wurde
     */
    public boolean einreihen(Long organisationId, String schweregrad, String objekt,
                             String meldung, String handlung) {
        if (organisationId == null) return false;
        var regel = regelFuer(organisationId);
        if (regel.isEmpty() || !regel.get().aktiv()) return false;
        if (STUFEN.indexOf(schweregrad) < STUFEN.indexOf(regel.get().schwelle())) return false;

        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO mail_warteschlange (organisation_id, schweregrad, objekt,
                                                meldung, handlung)
                VALUES (?,?,?,?,?)""")) {
            p.setLong(1, regel.get().organisationId());
            p.setString(2, schweregrad);
            p.setString(3, objekt);
            p.setString(4, meldung);
            p.setString(5, handlung);
            p.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("Alarm nicht einreihbar: " + e.getMessage());
            return false;
        }
    }

    /**
     * Die zuständige Regel: die der Einheit selbst, sonst die der nächsten
     * darüber.
     *
     * <p>Ein Dienstleister stellt seine Adresse einmal am Verbund ein und
     * bekommt alles; eine einzelne Praxis kann davon abweichen. Ohne diese
     * Vererbung müsste jede der 1.000 Einrichtungen einzeln gepflegt werden.
     */
    Optional<Regel> regelFuer(long organisationId) {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                WITH RECURSIVE aufwaerts AS (
                    SELECT id, eltern_id, 0 AS abstand FROM organisation WHERE id = ?
                    UNION ALL
                    SELECT o.id, o.eltern_id, a.abstand + 1
                    FROM organisation o JOIN aufwaerts a ON o.id = a.eltern_id
                )
                SELECT r.organisation_id, o.bezeichnung, r.empfaenger, r.schwelle,
                       r.sammelfenster_sekunden, r.aktiv
                FROM aufwaerts a
                JOIN mail_regel r    ON r.organisation_id = a.id
                JOIN organisation o  ON o.id = a.id
                ORDER BY a.abstand LIMIT 1""")) {
            p.setLong(1, organisationId);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Regel(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getBoolean(6)));
            }
        } catch (SQLException e) { return Optional.empty(); }
    }

    // -------------------------------------------------------------- Versenden

    /** Was ein Durchlauf getan hat — für Protokoll und Nachweis. */
    public record Durchlauf(int mails, int alarme, List<String> fehler) {
        public boolean etwasGetan() { return mails > 0 || !fehler.isEmpty(); }
    }

    /**
     * Verschickt alles, dessen Sammelfenster abgelaufen ist.
     *
     * <p>Läuft im Hintergrund im Takt von {@link #TAKT}. Ein Fenster gilt als
     * abgelaufen, wenn der <b>älteste</b> wartende Alarm der Einrichtung länger
     * wartet als das Fenster — nicht der jüngste. Sonst schöbe jeder neu
     * hinzukommende Alarm den Versand weiter hinaus, und bei einem anhaltenden
     * Ausfall ginge nie eine Mail hinaus.
     */
    public Durchlauf versendeFaellige() {
        List<String> fehler = new ArrayList<>();
        int mails = 0, alarme = 0;

        var zugang = zugang();
        List<Long> faellige = faelligeOrganisationen();
        if (faellige.isEmpty()) return new Durchlauf(0, 0, fehler);
        if (zugang.isEmpty()) {
            return new Durchlauf(0, 0, List.of(
                    "Es warten Alarme auf Benachrichtigung, aber es ist kein Mailserver "
                    + "eingerichtet."));
        }

        for (long orgId : faellige) {
            var regel = regelFuer(orgId);
            if (regel.isEmpty()) continue;
            var wartend = wartende(orgId);
            if (wartend.isEmpty()) continue;

            var e = Mailversand.senden(zugang.get(),
                    adressenZerlegen(regel.get().empfaenger()),
                    betreff(wartend, regel.get().organisation()),
                    text(wartend, regel.get()));

            if (e.geklappt()) {
                markiereVersendet(orgId);
                mails++;
                alarme += wartend.size();
            } else {
                // Die Alarme bleiben in der Warteschlange und werden beim
                // naechsten Durchlauf erneut versucht. Sie zu verwerfen waere
                // die schlechtere Wahl: Eine nicht zugestellte Meldung ueber
                // eine gesperrte Karte ist genau die, die man braucht.
                fehler.add(regel.get().organisation() + ": " + e.fehler());
            }
        }
        return new Durchlauf(mails, alarme, fehler);
    }

    private List<Long> faelligeOrganisationen() {
        List<Long> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT w.organisation_id
                FROM mail_warteschlange w
                JOIN mail_regel r ON r.organisation_id = w.organisation_id
                WHERE w.versendet IS NULL AND r.aktiv
                GROUP BY w.organisation_id, r.sammelfenster_sekunden
                HAVING min(w.angelegt) <= now() - make_interval(secs => r.sammelfenster_sekunden)""")) {
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) liste.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            System.err.println("Fällige Benachrichtigungen nicht ermittelbar: " + e.getMessage());
        }
        return liste;
    }

    record Wartend(String schweregrad, String objekt, String meldung, String handlung) {}

    private List<Wartend> wartende(long orgId) {
        List<Wartend> liste = new ArrayList<>();
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT schweregrad, objekt, meldung, handlung FROM mail_warteschlange
                WHERE organisation_id = ? AND versendet IS NULL
                ORDER BY CASE schweregrad WHEN 'KRITISCH' THEN 0 WHEN 'WARNUNG' THEN 1
                         ELSE 2 END, angelegt""")) {
            p.setLong(1, orgId);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    liste.add(new Wartend(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4)));
                }
            }
        }
        catch (SQLException e) {
            // Nicht verschlucken: Ein stiller SQL-Fehler ist praktisch
            // unauffindbar - so blieb ein fehlerhaftes WHERE monatelang
            // unentdeckt. Der Aufruf schlaegt trotzdem nicht fehl.
            System.err.println("Benachrichtigung.wartende: " + e.getMessage());
        }
        return liste;
    }

    private void markiereVersendet(long orgId) {
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "UPDATE mail_warteschlange SET versendet = now() "
                + "WHERE organisation_id = ? AND versendet IS NULL")) {
            p.setLong(1, orgId);
            p.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Warteschlange nicht aktualisierbar: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Inhalt

    /**
     * Der Betreff nennt Zahl und höchsten Schweregrad.
     *
     * <p>Wer zwanzig Mails im Postfach hat, entscheidet nach dem Betreff, welche
     * er zuerst öffnet. „PIN-Roboter: 1 Meldung" und „PIN-Roboter: 412 Meldungen
     * (kritisch)" verlangen unterschiedlich schnelle Reaktion.
     */
    static String betreff(List<Wartend> w, String organisation) {
        String hoechster = w.stream().map(Wartend::schweregrad)
                .max(Comparator.comparingInt(STUFEN::indexOf)).orElse("HINWEIS");
        String stufe = switch (hoechster) {
            case "KRITISCH" -> " (kritisch)";
            case "WARNUNG"  -> " (Warnung)";
            default         -> "";
        };
        return "PIN-Roboter " + organisation + ": "
                + (w.size() == 1 ? "1 Meldung" : w.size() + " Meldungen") + stufe;
    }

    /**
     * Der Mailtext.
     *
     * <p>Gleichartige Meldungen werden zusammengefasst: Bei einem
     * Konnektorausfall stehen sonst 400-mal dieselbe Zeile untereinander, und
     * niemand liest bis zum Ende.
     */
    static String text(List<Wartend> w, Regel regel) {
        StringBuilder s = new StringBuilder();
        s.append("Der PIN-Roboter meldet ")
         .append(w.size() == 1 ? "einen Vorgang" : w.size() + " Vorgänge")
         .append(" für ").append(regel.organisation()).append(".\n\n");

        // Nach Meldungstext gruppieren und die betroffenen Geraete zaehlen.
        Map<String, List<Wartend>> gruppen = new LinkedHashMap<>();
        for (Wartend x : w) gruppen.computeIfAbsent(x.meldung(), k -> new ArrayList<>()).add(x);

        for (var g : gruppen.entrySet()) {
            var erste = g.getValue().get(0);
            s.append("— ").append(bezeichnung(erste.schweregrad())).append(": ")
             .append(g.getKey()).append("\n");
            if (g.getValue().size() == 1) {
                if (erste.objekt() != null) s.append("  Gerät: ").append(erste.objekt()).append("\n");
            } else {
                s.append("  Betroffen: ").append(g.getValue().size()).append(" Geräte");
                String beispiele = g.getValue().stream().limit(5)
                        .map(Wartend::objekt).filter(Objects::nonNull)
                        .reduce((a, b) -> a + ", " + b).orElse("");
                if (!beispiele.isEmpty()) {
                    s.append(" (").append(beispiele);
                    if (g.getValue().size() > 5) s.append(", …");
                    s.append(")");
                }
                s.append("\n");
            }
            if (erste.handlung() != null && !erste.handlung().isBlank()) {
                s.append("  Zu tun: ").append(erste.handlung()).append("\n");
            }
            s.append("\n");
        }

        s.append("Einzelheiten in der Oberfläche des PIN-Roboters.\n\n");
        s.append("Diese Nachricht wurde automatisch erzeugt und fasst ")
         .append(regel.sammelfensterSekunden() == 0
                 ? "eine einzelne Meldung"
                 : "die Meldungen aus " + fensterText(regel.sammelfensterSekunden()))
         .append(" zusammen. Sie enthält bewusst keine Zugangsdaten und keine "
                 + "personenbezogenen Daten.\n");
        return s.toString();
    }

    static String fensterText(int sekunden) {
        if (sekunden < 60) return sekunden + " Sekunden";
        if (sekunden % 3600 == 0) return (sekunden / 3600) + (sekunden == 3600 ? " Stunde" : " Stunden");
        return (sekunden / 60) + " Minuten";
    }

    private static String bezeichnung(String schweregrad) {
        return switch (schweregrad) {
            case "KRITISCH" -> "Kritisch";
            case "WARNUNG"  -> "Warnung";
            default         -> "Hinweis";
        };
    }
}
