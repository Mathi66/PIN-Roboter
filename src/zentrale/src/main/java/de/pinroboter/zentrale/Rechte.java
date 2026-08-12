package de.pinroboter.zentrale;

import java.sql.*;

/**
 * Wer darf was — abhängig von Rolle <b>und</b> Ebene.
 *
 * <p>Bis zum 07.08.2026 entschied allein die Rolle: Administrator oder Benutzer.
 * Der {@link Geltungsbereich} regelt seither, <i>welche Daten</i> jemand sieht.
 * Was fehlte, ist die zweite Frage: <b>welche Art von Handlung</b> ihm zusteht.
 *
 * <p>Beides zusammen ergibt erst die Berechtigung. Ein Praxisadministrator darf
 * die Geräte seiner Praxis verwalten — aber keine Einrichtung anlegen und keinen
 * Demodatensatz über die ganze Instanz ausrollen.
 *
 * <h2>Die Ebenen</h2>
 *
 * <pre>
 * Instanzbetreiber   Administrator ohne Organisationszuordnung
 *  └── Verbund       Administrator/Benutzer eines VERBUND
 *       └── Standort Administrator/Benutzer eines STANDORT
 *            └── Praxis  Administrator/Benutzer einer EINRICHTUNG
 * </pre>
 *
 * <h2>Warum eine eigene Klasse</h2>
 *
 * Damit die Regeln an <b>einer</b> Stelle stehen und sich vorlesen lassen. Über
 * mehrere Routen verstreute {@code if}-Abfragen sind das, was
 * Berechtigungsmodelle unprüfbar macht — man findet sie beim Nachlesen nicht
 * mehr alle.
 */
public final class Rechte {

    /** Ebene eines Kontos, abgeleitet aus der Art seiner Organisationseinheit. */
    public enum Ebene {
        /** Kein Bezug zu einer Organisation: verwaltet die Instanz als Ganzes. */
        INSTANZ("Instanz", 4),
        VERBUND("Verbund", 3),
        STANDORT("Standort", 2),
        EINRICHTUNG("Einrichtung", 1),
        /** Zugeordnet, aber die Einheit gibt es nicht mehr — sieht und darf nichts. */
        UNBEKANNT("ohne Bereich", 0);

        public final String anzeige;
        /** Höher heißt weiter reichend. Für Vergleiche, nicht für Anzeige. */
        public final int reichweite;
        Ebene(String a, int r) { anzeige = a; reichweite = r; }
    }

    private final Benutzerverwaltung.Benutzer benutzer;
    private final Ebene ebene;

    private Rechte(Benutzerverwaltung.Benutzer benutzer, Ebene ebene) {
        this.benutzer = benutzer;
        this.ebene = ebene;
    }

    /** Rechte ohne Anmeldung: nichts. */
    public static Rechte keine() { return new Rechte(null, Ebene.UNBEKANNT); }

    public static Rechte fuer(Datenbank db, Benutzerverwaltung.Benutzer b) {
        if (b == null) return keine();
        if (b.istInstanzbetreiber()) return new Rechte(b, Ebene.INSTANZ);
        if (b.organisationId() == null) {
            // Zugeordnet? Nein. Administrator? Nein. Also ein Benutzer ohne
            // Bereich - er darf nichts. Nicht alles: Diese Richtung ist die
            // ganze Sicherheit (siehe Geltungsbereich).
            return new Rechte(b, Ebene.UNBEKANNT);
        }
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT art FROM organisation WHERE id = ?")) {
            p.setLong(1, b.organisationId());
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return new Rechte(b, Ebene.UNBEKANNT);
                return new Rechte(b, switch (rs.getString("art")) {
                    case "VERBUND" -> Ebene.VERBUND;
                    // Ein Leistungserbringer steht ueber den Standorten und
                    // damit auf derselben Rechteebene wie ein Verbund: Er
                    // umfasst mehrere Betriebsstaetten (ADR-0012).
                    case "LEISTUNGSERBRINGER" -> Ebene.VERBUND;
                    case "STANDORT"    -> Ebene.STANDORT;
                    case "EINRICHTUNG" -> Ebene.EINRICHTUNG;
                    // Ein Fachbereich liegt INNERHALB einer Betriebsstaette —
                    // engster Bereich, nicht weiter.
                    case "FACHBEREICH" -> Ebene.EINRICHTUNG;
                    default            -> Ebene.UNBEKANNT;
                });
            }
        } catch (SQLException e) {
            return new Rechte(b, Ebene.UNBEKANNT);   // im Zweifel nichts
        }
    }

    public Ebene ebene() { return ebene; }

    public boolean istAdministrator() {
        return benutzer != null && benutzer.rolle() == Benutzerverwaltung.Rolle.ADMINISTRATOR;
    }

    // ------------------------------------------------------------ Die Regeln

    /**
     * Bestand pflegen: Konnektoren und Kartenterminals anlegen, ändern, löschen.
     *
     * <p>Jeder Administrator, auf jeder Ebene — begrenzt durch seinen
     * Geltungsbereich. Ein Praxisadministrator verwaltet seine Praxis, das ist
     * der Normalfall dieses Produkts.
     */
    public boolean darfBestandPflegen() {
        return istAdministrator() && ebene.reichweite > 0;
    }

    /**
     * Benutzer verwalten: anlegen, deaktivieren, zweiten Faktor zurücksetzen.
     *
     * <p>Ebenfalls jeder Administrator in seinem Bereich. Wer eine Praxis
     * betreut, muss dort auch Konten einrichten können — sonst hängt jede
     * Personalie an einer zentralen Stelle, und das trägt bei 1.000 Benutzern
     * nicht.
     */
    public boolean darfBenutzerVerwalten() {
        return istAdministrator() && ebene.reichweite > 0;
    }

    /**
     * Organisationseinheiten anlegen oder umhängen.
     *
     * <p><b>Erst ab Standortebene.</b> Wer eine Praxis betreut, legt keine
     * weiteren Praxen an — er könnte sonst seinen eigenen Geltungsbereich
     * erweitern, und damit wäre die Mandantengrenze von innen aufhebbar.
     */
    public boolean darfOrganisationVerwalten() {
        return istAdministrator() && ebene.reichweite >= Ebene.STANDORT.reichweite;
    }

    /**
     * Instanzweite Werkzeuge: Demodatensatz, Lasttestbestand.
     *
     * <p><b>Nur der Instanzbetreiber.</b> Beide legen Daten quer über alle
     * Mandanten an oder entfernen sie — ein Praxisadministrator, der den
     * Demodatensatz löscht, träfe damit fremde Einrichtungen.
     */
    public boolean darfInstanzVerwalten() {
        return istAdministrator() && ebene == Ebene.INSTANZ;
    }

    /**
     * Not-Aus auslösen und aufheben.
     *
     * <p>Jeder Administrator — der Not-Aus ist ein Sicherheitsmittel und darf
     * nicht an einer Genehmigung hängen. Wer den Verdacht hat, dass der Roboter
     * falsch handelt, muss ihn sofort anhalten können.
     *
     * <p><b>Bekannte Einschränkung:</b> Der Not-Aus wirkt derzeit
     * <b>instanzweit</b>, nicht je Einrichtung. Bei mehreren Mandanten an einer
     * Zentrale hält ein Praxisadministrator damit auch fremde Standorte an. Das
     * ist in {@code docs/05-sicherheit-datenschutz.md} als offener Punkt
     * vermerkt — die Alternative wäre gewesen, ihn für Praxisadministratoren zu
     * sperren, und das ist bei einem Sicherheitsmittel die schlechtere Wahl.
     */
    public boolean darfNotAus() {
        return istAdministrator() && ebene.reichweite > 0;
    }

    /** Kurzbeschreibung für Oberfläche und Protokoll. */
    public String beschreibung() {
        if (benutzer == null) return "nicht angemeldet";
        return (istAdministrator() ? "Administrator" : "Benutzer") + " · " + ebene.anzeige;
    }
}
