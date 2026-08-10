package de.pinroboter.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hält die Verbindungen zu allen Kartenterminals mit Remote SMCB API
 * (ADR-0011).
 *
 * <h2>Warum es eine Verwaltung braucht</h2>
 *
 * Im alten Modell war eine PIN-Übergabe ein Vorgang mit Anfang und Ende: Sitzung
 * auf, PIN hin, Sitzung zu. Jetzt ist sie ein <b>Zustand</b>. Die Verbindung
 * steht dauerhaft, und das Terminal fragt darüber, wann immer der Konnektor eine
 * Verifikation auslöst. Damit verschiebt sich die Arbeit des Agenten von
 * „handeln" zu „erreichbar bleiben".
 *
 * <p>Daraus folgen drei Aufgaben, die vorher niemand hatte:
 * <ul>
 *   <li><b>Aufbauen</b>, sobald ein Terminal eine SMC-B mit hinterlegter PIN hat</li>
 *   <li><b>Abbauen</b>, sobald das nicht mehr gilt — eine offene Verbindung zu
 *       einem Gerät, für das wir keine PIN haben, nützt nichts und blockiert den
 *       einzigen Verbindungsplatz, den die Spezifikation zulässt</li>
 *   <li><b>Nachfassen</b> nach einem Abbruch, aber mit Abstand: Ein Gerät ohne
 *       Remote SMCB API würde sonst im Sekundentakt angefragt</li>
 * </ul>
 *
 * <h2>Die Verschiebung der Verantwortung</h2>
 *
 * <p><b>Wer die Verifikation auslöst, ist nicht mehr unsere Entscheidung.</b>
 * Solange die Verbindung steht, kann jede Stelle am Konnektor eine PIN-Anfrage
 * auslösen — ein Primärsystem, ein Administrator, ein Kartenwechsel. Die
 * {@link SmcbVerbindung.Freigabe} ist dann die <b>einzige</b> Bremse zwischen
 * einer fremden Anfrage und einem verbrauchten Fehlversuch.
 *
 * <p>Deshalb prüft sie hier vollständig — Not-Aus, angehaltene Automatik,
 * laufendes Update, Fehlversuchszähler — und nicht bloß, ob wir selbst gerade
 * etwas angestoßen haben.
 */
public class SmcbVerbindungen implements AutoCloseable {

    /** Was der Agent über eine Karte weiß, wenn das Terminal nach ihrem Slot fragt. */
    public interface Kartenkenntnis {
        /** @return das Kartenhandle im genannten Slot, oder {@code null} */
        String kartenhandle(String terminalId, String slot);

        /** @return die PIN zu einem Kartenhandle (Kopie), oder {@code null} */
        char[] pin(String kartenhandle);

        /** @return Ablehnungsgrund im Klartext, oder {@code null} für „darf" */
        String hindernis(String terminalId, String kartenhandle);
    }

    /**
     * Mindestabstand zwischen zwei Aufbauversuchen für dasselbe Gerät.
     *
     * <p>Bewusst großzügig: Der häufigste Grund für einen Fehlschlag ist ein
     * Terminal, das die Remote SMCB API gar nicht anbietet oder bei dem sie
     * nicht freigeschaltet ist. Das ändert sich nicht in Sekunden, sondern erst,
     * wenn ein Administrator ans Gerät geht.
     */
    static final Duration WARTEZEIT_NACH_FEHLSCHLAG = Duration.ofMinutes(2);

    private final HttpClient http;
    private final URI terminalBasis;

    /**
     * Wo die Remote SMCB API liegt.
     *
     * <p>Am echten Gerät derselbe Ursprung wie die übrige
     * Verwaltungsschnittstelle. Getrennt geführt, weil der Simulator einen
     * eigenen Port braucht — und weil ein Hersteller es anders lösen könnte.
     */
    private final URI smcbBasis;

    private final Kartenkenntnis kenntnis;
    private final SmcbVerbindung.Beobachter beobachter;

    private final Map<String, SmcbVerbindung> verbindungen = new ConcurrentHashMap<>();
    private final Map<String, Instant> naechsterVersuch = new ConcurrentHashMap<>();

    public SmcbVerbindungen(HttpClient http, URI terminalBasis, URI smcbBasis,
                            Kartenkenntnis kenntnis, SmcbVerbindung.Beobachter beobachter) {
        this.http = http;
        this.terminalBasis = terminalBasis;
        this.smcbBasis = smcbBasis;
        this.kenntnis = kenntnis;
        this.beobachter = beobachter;
    }

    /**
     * Gleicht die offenen Verbindungen an den Sollzustand an.
     *
     * <p>Wird in jedem Überwachungsdurchgang aufgerufen. Nicht öfter: Ein
     * Verbindungsaufbau kostet einen Schlüssel, und der lässt sich nur einmal
     * einlösen.
     *
     * @param gewuenscht Terminals, für die eine Verbindung stehen soll
     */
    public void abgleichen(Set<String> gewuenscht) {
        for (String id : Set.copyOf(verbindungen.keySet())) {
            if (!gewuenscht.contains(id)) trennen(id);
        }

        // Abgelaufene Sperrfristen entfernen. Ohne diese Zeile wüchse die Liste
        // mit jedem Gerät, das je einmal gefehlt hat — bei 5.000 Terminals über
        // Monate Laufzeit ein Speicherleck, das niemand bemerkt, bis es zu spät
        // ist. Nur abgelaufene: Eine noch laufende Sperrfrist bleibt auch dann
        // stehen, wenn das Gerät zwischenzeitlich aus dem Sollzustand fiel —
        // sonst umginge ein flatterndes Gerät den Schutz durch bloßes Flattern.
        Instant jetzt = Instant.now();
        naechsterVersuch.entrySet().removeIf(e -> jetzt.isAfter(e.getValue()));

        for (String id : gewuenscht) {
            sicherstellen(id);
        }
    }

    /** Baut die Verbindung auf, falls sie nicht schon steht. */
    public void sicherstellen(String terminalId) {
        SmcbVerbindung v = verbindungen.get(terminalId);
        if (v != null) {
            if (v.zustand() == SmcbVerbindung.Zustand.ANGEMELDET) return;
            // Gescheitert oder getrennt: aufräumen, bevor neu aufgebaut wird.
            // Sonst blieben tote Einträge liegen und der Zustandsbericht an die
            // Zentrale zeigte Verbindungen, die es nicht mehr gibt.
            v.close();
            verbindungen.remove(terminalId);
        }

        Instant frei = naechsterVersuch.get(terminalId);
        if (frei != null && Instant.now().isBefore(frei)) return;

        SmcbVerbindung neu = new SmcbVerbindung(
                terminalId,
                smcbBasis,
                http,
                () -> schluesselHolen(terminalId),
                slot -> pinFuer(terminalId, slot),
                slot -> hindernisFuer(terminalId, slot),
                beobachter);

        if (neu.verbinden()) {
            verbindungen.put(terminalId, neu);
            naechsterVersuch.remove(terminalId);
        } else {
            neu.close();
            naechsterVersuch.put(terminalId, Instant.now().plus(WARTEZEIT_NACH_FEHLSCHLAG));
        }
    }

    /**
     * Trennt die Verbindung zu einem Gerät.
     *
     * <p>Die Sperrfrist wird dabei <b>nur</b> gelöscht, wenn tatsächlich eine
     * Verbindung bestand. Ein Gerät, zu dem der Aufbau gerade gescheitert ist,
     * behält seine Frist — sonst ließe sie sich dadurch umgehen, dass das Gerät
     * kurz aus dem Sollzustand fällt und wiederkommt.
     */
    public void trennen(String terminalId) {
        SmcbVerbindung v = verbindungen.remove(terminalId);
        if (v != null) {
            v.close();
            naechsterVersuch.remove(terminalId);
        }
    }

    /** Ob für dieses Terminal eine angemeldete Verbindung besteht. */
    public boolean angemeldet(String terminalId) {
        SmcbVerbindung v = verbindungen.get(terminalId);
        return v != null && v.zustand() == SmcbVerbindung.Zustand.ANGEMELDET;
    }

    /** Der letzte Fehler eines Terminals — für die Anzeige in der Zentrale. */
    public String letzterFehler(String terminalId) {
        SmcbVerbindung v = verbindungen.get(terminalId);
        return v == null ? null : v.letzterFehler();
    }

    public int anzahlAngemeldet() {
        return (int) verbindungen.values().stream()
                .filter(v -> v.zustand() == SmcbVerbindung.Zustand.ANGEMELDET).count();
    }

    @Override
    public void close() {
        verbindungen.values().forEach(SmcbVerbindung::close);
        verbindungen.clear();
        naechsterVersuch.clear();
    }

    // ------------------------------------------------------------------ intern

    /**
     * Holt den Authentisierungsschlüssel vom Gerät.
     *
     * <p>Nicht privat, damit ein Test die Aufrufe zählen kann: Die Zusage
     * „nach einem Fehlschlag wird zwei Minuten nicht nachgefasst" ist sonst
     * nicht überprüfbar, und bei 5.000 Geräten ist sie kein Detail.
     */
    String schluesselHolen(String terminalId) {
        try (TerminalClient t = new TerminalClient(http, terminalBasis, terminalId)) {
            var anmeldung = t.anmelden();
            if (!anmeldung.erfolgreich()) return null;
            return t.smcbSchluessel();
        } catch (Exception e) {
            return null;
        }
    }

    private char[] pinFuer(String terminalId, String slot) {
        String handle = kenntnis.kartenhandle(terminalId, slot);
        return handle == null ? null : kenntnis.pin(handle);
    }

    /**
     * Die Sicherungsprüfung zum Zeitpunkt der Anfrage.
     *
     * <p>Ein unbekannter Slot ist hier <b>kein</b> stiller Fehler, sondern eine
     * Ablehnung mit Begründung: Fragt ein Terminal nach einem Slot, den der
     * Konnektor nicht kennt, stimmt etwas nicht — und in diesem Zustand irgendeine
     * PIN zu schicken wäre der teuerste denkbare Fehler.
     */
    private String hindernisFuer(String terminalId, String slot) {
        String handle = kenntnis.kartenhandle(terminalId, slot);
        if (handle == null) {
            return "Das Terminal fragt nach " + slot + ", der Konnektor kennt dort keine Karte.";
        }
        return kenntnis.hindernis(terminalId, handle);
    }
}
