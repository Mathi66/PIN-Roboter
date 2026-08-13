package de.pinroboter.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Was der Agent über sich selbst weiß — für die Oberfläche zur Inbetriebnahme
 * und Fehlersuche (PO, 13.08.2026).
 *
 * <h2>Warum es diese Klasse gibt</h2>
 *
 * Bis hierher war der einzige Einblick in den Agenten
 * {@code docker compose logs}: Java-Meldungen, Zeitstempel in UTC,
 * Ausnahmestapel. In einer Praxis kann damit niemand etwas anfangen, und bei
 * der Inbetriebnahme scheitert man an Kleinigkeiten — falsche Konnektor-Adresse,
 * fehlender Vertrauensanker, ein Aufrufkontext, den der Konnektor ablehnt.
 *
 * <h2>Was hier NICHT hineingehört</h2>
 *
 * Keine PIN, kein Token, kein privater Schlüssel, kein Kartenkennwort. Auch
 * nicht gekürzt oder verfremdet: Ein Zustand, den man ansehen kann, wird
 * irgendwann abfotografiert und weitergeschickt. Zahlen und Zustände genügen.
 *
 * <p>Alle Felder sind nebenläufig beschreibbar — die Überwachungsschleife
 * schreibt, der HTTP-Faden liest.
 */
final class Betriebszustand {

    /** So viele Meldungen bleiben für die Fehlersuche erhalten. */
    private static final int MELDUNGEN_HOECHSTENS = 60;

    /** Eine Prüfung mit Ergebnis in Klartext. */
    record Schritt(String was, boolean erfuellt, String klartext, String handlung) {}

    /** Eine Meldung mit Zeitpunkt — für die Fehlersuche, nicht als Fachprotokoll. */
    record Meldung(Instant zeit, boolean fehler, String text) {}

    private final Instant start = Instant.now();
    private final Deque<Meldung> meldungen = new ArrayDeque<>();

    private volatile String version = "unbekannt";
    private volatile String zentrale = "";
    private volatile String konnektorAdresse = "";
    private volatile String einrichtung = "";

    private volatile boolean registriert;
    private volatile boolean schluesselBereit;
    private volatile boolean pinAblageVerschluesselt;
    private volatile boolean notAus;

    private volatile Instant zentraleGesehen;
    private volatile Instant konnektorGesehen;
    private volatile String konnektorFehler;

    private volatile int terminals;
    private volatile int terminalsErreichbar;
    private volatile int smcbKarten;
    private volatile int pinsHinterlegt;
    private volatile int verbindungenOffen;
    private volatile int grundtakt;

    /** Kartenterminals mit ihrem zuletzt bekannten Zustand. */
    private final Map<String, String> geraete = new ConcurrentHashMap<>();

    // ------------------------------------------------------------- Schreiben

    void grunddaten(String version, String zentrale, String konnektor, int grundtakt) {
        this.version = version;
        this.zentrale = zentrale;
        this.konnektorAdresse = konnektor;
        this.grundtakt = grundtakt;
    }

    /** Nach einem Serverumzug: Die Anzeige muss die neue Adresse nennen. */
    void grunddatenZentrale(String zentrale) { this.zentrale = zentrale; }

    void registrierung(boolean ok, String einrichtung) {
        this.registriert = ok;
        if (einrichtung != null) this.einrichtung = einrichtung;
    }

    void schluessel(boolean bereit, boolean ablageVerschluesselt) {
        this.schluesselBereit = bereit;
        this.pinAblageVerschluesselt = ablageVerschluesselt;
    }

    void zentraleErreicht()   { this.zentraleGesehen = Instant.now(); }

    void konnektorErreicht(int terminals, int erreichbar, int smcb) {
        this.konnektorGesehen = Instant.now();
        this.konnektorFehler = null;
        this.terminals = terminals;
        this.terminalsErreichbar = erreichbar;
        this.smcbKarten = smcb;
    }

    void konnektorFehler(String meldung) {
        this.konnektorFehler = meldung;
        melden(true, "Konnektor nicht erreichbar: " + meldung);
    }

    void pins(int anzahl)          { this.pinsHinterlegt = anzahl; }
    void verbindungen(int offen)   { this.verbindungenOffen = offen; }
    void notAus(boolean an)        { this.notAus = an; }

    void geraet(String terminalId, String zustand) { geraete.put(terminalId, zustand); }

    /**
     * Eine Meldung für die Fehlersuche.
     *
     * <p>Ein Ringpuffer, keine Datei: Das Fachprotokoll führt die Zentrale, und
     * ein zweites dauerhaftes Protokoll im Agenten wäre eine zweite Wahrheit —
     * mit eigenem Speicherbedarf und eigener Rotation.
     */
    void melden(boolean fehler, String text) {
        synchronized (meldungen) {
            meldungen.addFirst(new Meldung(Instant.now(), fehler, text));
            while (meldungen.size() > MELDUNGEN_HOECHSTENS) meldungen.removeLast();
        }
    }

    // --------------------------------------------------------------- Lesen

    String version()      { return version; }
    String zentrale()     { return zentrale; }
    String konnektorAdresse() { return konnektorAdresse; }
    String einrichtung()  { return einrichtung; }
    boolean notAus()      { return notAus; }
    int terminals()       { return terminals; }
    int terminalsErreichbar() { return terminalsErreichbar; }
    int smcbKarten()      { return smcbKarten; }
    int pinsHinterlegt()  { return pinsHinterlegt; }
    int verbindungenOffen() { return verbindungenOffen; }
    int grundtakt()       { return grundtakt; }
    Instant start()       { return start; }
    Instant zentraleGesehen()  { return zentraleGesehen; }
    Instant konnektorGesehen() { return konnektorGesehen; }
    Map<String, String> geraete() { return Map.copyOf(geraete); }

    List<Meldung> letzteMeldungen() {
        synchronized (meldungen) { return List.copyOf(meldungen); }
    }

    /**
     * Ist der Agent arbeitsfähig?
     *
     * <p>Absichtlich streng: Ohne Registrierung oder ohne Konnektor kann er
     * seine Aufgabe nicht erfüllen. Eine Anzeige, die dann „in Ordnung" sagt,
     * wäre schlimmer als keine.
     */
    boolean arbeitsfaehig() {
        return registriert && schluesselBereit && konnektorFehler == null
                && konnektorGesehen != null;
    }

    /**
     * Die Inbetriebnahme als Prüfliste.
     *
     * <p>Jeder Schritt sagt, <b>was zu tun ist</b>, nicht nur was fehlt
     * (UX-Vorgabe 2). Reihenfolge ist die der Abhängigkeit: Ohne
     * Vertrauensanker keine TLS-Verbindung, ohne Konnektor keine Geräte.
     */
    List<Schritt> pruefliste() {
        var l = new ArrayList<Schritt>();

        l.add(new Schritt("Bei der Zentrale angemeldet", registriert,
                registriert ? "Der Agent hat ein dauerhaftes Token."
                            : "Der Agent konnte sich nicht anmelden.",
                registriert ? null
                        : "Prüfen Sie ZENTRALE_URL und REGISTRIERUNG in der .env. "
                          + "Der Zugangscode gilt einmalig und 24 Stunden — "
                          + "laden Sie andernfalls ein neues Einrichtungspaket herunter."));

        l.add(new Schritt("Schlüsselpaar für die PIN-Übergabe", schluesselBereit,
                schluesselBereit ? "Der private Schlüssel liegt im Datenverzeichnis."
                                 : "Kein Schlüsselpaar vorhanden.",
                schluesselBereit ? null
                        : "Das Datenverzeichnis /daten muss beschreibbar sein. "
                          + "Fehlt das Volume, geht der Schlüssel bei jedem Neustart verloren."));

        boolean konnektorOk = konnektorGesehen != null && konnektorFehler == null;
        l.add(new Schritt("Konnektor erreichbar", konnektorOk,
                konnektorOk ? terminals + " Kartenterminal(s) gemeldet"
                            : (konnektorFehler == null ? "Noch keine Antwort."
                                                       : konnektorFehler),
                konnektorOk ? null : hinweisZumKonnektor()));

        boolean pinsDa = pinsHinterlegt > 0;
        l.add(new Schritt("PINs hinterlegt", pinsDa,
                pinsHinterlegt + " von " + smcbKarten + " SMC-B",
                pinsDa ? null
                        : "PINs werden in der Zentrale hinterlegt, nicht hier — "
                          + "unter „Kartenterminals" + "“, beim jeweiligen Gerät. "
                          + "Sie werden verschlüsselt an genau diesen Agenten übertragen."));

        l.add(new Schritt("PIN-Ablage verschlüsselt", pinAblageVerschluesselt,
                pinAblageVerschluesselt
                        ? "Hinterlegte PINs überstehen einen Neustart."
                        : "PINs bleiben nur im Arbeitsspeicher.",
                pinAblageVerschluesselt ? null
                        : "PIN_ABLAGE_SCHLUESSEL in der .env setzen "
                          + "(openssl rand -base64 32), dann neu starten. "
                          + "Ohne ihn müssen PINs nach jedem Neustart erneut hinterlegt werden."));

        return l;
    }

    /**
     * Was bei einem nicht erreichbaren Konnektor zu tun ist.
     *
     * <p>Die Ursache lässt sich an der Fehlermeldung ablesen — und genau das
     * nimmt der Oberfläche die Arbeit ab, die sonst jemand mit einer
     * Suchmaschine erledigt.
     */
    private String hinweisZumKonnektor() {
        String f = konnektorFehler == null ? "" : konnektorFehler.toLowerCase();
        if (f.contains("malformed pem") || f.contains("certificate")) {
            return "Der Vertrauensanker passt nicht. CA_URL muss auf das Zertifikat "
                 + "der ausstellenden Stelle zeigen — Ihr Konnektor bietet es zum "
                 + "Herunterladen an.";
        }
        if (f.contains("no selectable") || f.contains("handshake")) {
            return "Die TLS-Verbindung kam nicht zustande. Meist passt der "
                 + "Vertrauensanker (CA_URL) nicht zu den Geräten.";
        }
        if (f.contains("connection refused") || f.contains("timed out")
                || f.contains("unreachable") || f.contains("timeout")) {
            return "Unter KONNEKTOR_URL antwortet niemand. Stimmen Adresse und Port? "
                 + "Ist dieser Rechner im selben Netz wie der Konnektor?";
        }
        if (f.contains("401") || f.contains("403") || f.contains("mandant")
                || f.contains("kontext")) {
            return "Der Konnektor lehnt den Aufrufkontext ab. MANDANT_ID, "
                 + "CLIENTSYSTEM_ID und ARBEITSPLATZ_ID müssen zu seiner "
                 + "Konfiguration passen.";
        }
        return "Prüfen Sie KONNEKTOR_URL und CA_URL in der .env.";
    }

    /** „vor 3 Minuten" statt eines Zeitstempels in UTC. */
    static String vorWieLange(Instant wann) {
        if (wann == null) return "noch nie";
        long s = Duration.between(wann, Instant.now()).getSeconds();
        if (s < 10)   return "gerade eben";
        if (s < 60)   return "vor " + s + " Sekunden";
        if (s < 3600) return "vor " + (s / 60) + " Minuten";
        if (s < 86400) return "vor " + (s / 3600) + " Stunden";
        return "vor " + (s / 86400) + " Tagen";
    }
}
