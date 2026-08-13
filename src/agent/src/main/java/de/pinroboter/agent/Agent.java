package de.pinroboter.agent;

import de.pinroboter.gemeinsam.Ereignisart;
import de.pinroboter.gemeinsam.PinStatus;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;

/**
 * Standort-Agent: überwacht die Kartenterminals und stellt den verifizierten
 * Zustand der SMC-B wieder her.
 *
 * <p>Steht immer im Netz der Einrichtung und ist der einzige Baustein, der
 * Geheimnisse kennt (ADR-0002). Arbeitet weiter, wenn die Zentrale ausfällt —
 * der TI-Zugang der Praxis darf nicht an unserer Verwaltungsschicht hängen.
 */
public class Agent {

    private final KonnektorClient konnektor;
    private final HttpClient http;
    /**
     * Eigener Client für die Zentrale (13.08.2026). Der TI-Client vertraut nur
     * der CA aus {@code CA_URL} und käme an einem öffentlichen Zertifikat nicht
     * vorbei — siehe {@link Zentraleverbindung}.
     */
    private final HttpClient zentraleHttp;
    private final URI terminalBasis;
    /**
     * Adresse der Zentrale. <b>Änderbar zur Laufzeit</b> (PO, 13.08.2026): Bei
     * einem Serverumzug wären die ausgelieferten Agenten sonst verloren —
     * niemand kommt an 150 Praxisrechner. Siehe {@link Zentralenadresse}.
     */
    private volatile URI zentrale;
    private final Verifikationsablauf ablauf;

    /** PIN je Kartenhandle. Im Produkt verschlüsselt abgelegt (NFA-SIC-010). */
    private final Map<String, char[]> pins = new ConcurrentHashMap<>();
    /** Zuletzt gesehener Fehlversuchszähler — Grundlage der Sicherungsprüfung. */
    private final Map<String, Integer> letzteVersuche = new ConcurrentHashMap<>();
    private final Set<String> angehalten = ConcurrentHashMap.newKeySet();
    private final List<Verifikationsablauf.Meldung> gepuffert = new CopyOnWriteArrayList<>();

    private volatile boolean notAus = false;
    private volatile String agentToken;

    /**
     * Was der Agent über sich weiß — für die Oberfläche zur Inbetriebnahme und
     * Fehlersuche (PO, 13.08.2026). Enthält keine Geheimnisse.
     */
    final Betriebszustand zustand = new Betriebszustand();

    /**
     * Adresse der Webadmin-Schnittstelle des Konnektors, oder {@code null}.
     * Herstellerspezifisch und immer optional (NFA-QS-035).
     */
    private volatile URI webadmin;

    /**
     * Kennzeichen des zuletzt gemeldeten Bestands. Verhindert, dass sich ein
     * unveraenderter Bestand in jedem Pruefzyklus wiederholt - dieselbe
     * Ueberlegung wie bei der Protokoll-Grundregel: Zustandsaenderungen
     * melden, nicht Abfragen.
     */
    private volatile String letzterBestandsabdruck = "";

    /** Schlüsselpaar für die Ende-zu-Ende-Übergabe der PIN (ADR-0009). */
    private volatile Schluesselpaar schluessel;

    /** Ablage der verschlüsselten PINs. Der Schlüssel dafür kommt aus der Umgebung. */
    private volatile Pinablage ablage;

    /**
     * Die stehenden Verbindungen zu den Kartenterminals (ADR-0011).
     *
     * <p>Seit dem Umbau der wichtigste Zustand des Agenten: Ohne angemeldete
     * Verbindung kann keine PIN übergeben werden, egal wie oft der Konnektor
     * gefragt wird.
     */
    private final SmcbVerbindungen smcb;

    /**
     * Slot → Kartenhandle, je Terminal.
     *
     * <p>Die Übersetzung zwischen zwei Welten: Der Konnektor spricht von
     * Kartenhandles, das Terminal von Steckplätzen. Gefüllt aus {@code GetCards}
     * in jedem Durchgang.
     */
    private final Map<String, String> slotZuKarte = new ConcurrentHashMap<>();

    /** Letzter bekannter PIN-Status je Kartenhandle, mit Zeitstempel. */
    private record Statusnotiz(PinStatus status, int verbleibendeVersuche,
                               boolean aktualisierend, long zeitpunkt) {}
    private final Map<String, Statusnotiz> letzterStatus = new ConcurrentHashMap<>();

    /**
     * Wie alt eine Statusnotiz höchstens sein darf, um eine PIN-Anfrage zu
     * beantworten — mindestens das, sonst das Anderthalbfache des Prüfintervalls.
     *
     * <p>Hintergrund: Solange die Verbindung steht, kann <b>jede</b> Stelle am
     * Konnektor eine PIN-Anfrage auslösen. Der Fehlversuchszähler aus dem
     * letzten Durchgang ist dann das Beste, was wir haben — ihn im Antwortpfad
     * frisch zu holen scheidet aus, weil dieser Aufruf dieselbe Karte betrifft,
     * die der Konnektor gerade bearbeitet.
     *
     * <p>Der Schutz bleibt trotzdem wirksam: Ein unbemerkter Fremdversuch senkt
     * den Zähler, und schon der erste erkannte Rückgang hält die Automatik an
     * (Sicherungspruefung). Zusammen mit der Untergrenze „mehr als ein Versuch
     * übrig" braucht es also mehrere unbemerkte Fremdversuche <i>und</i> unseren,
     * bevor eine Sperrung überhaupt in Reichweite käme.
     */
    private static final long STATUS_HOECHSTALTER_MS = 90_000;

    public static void main(String[] args) throws Exception {
        BrainpoolFreischaltung.zustandAusgeben("vor anwenden");
        BrainpoolFreischaltung.anwenden();   // muss als Erstes laufen
        BrainpoolFreischaltung.zustandAusgeben("nach anwenden");
        String konnektorUrl = env("KONNEKTOR_URL", "https://simulator:8443/");
        String terminalUrl  = env("TERMINAL_URL",  "https://simulator:8444/");
        // Die Remote SMCB API liegt am echten Gerät auf demselben Port wie die
        // übrige Verwaltungsschnittstelle — deshalb ist das die Vorgabe. Der
        // Simulator braucht einen eigenen Port, weil der eingebaute HTTP-Server
        // des JDK keine Protokollumschaltung auf WebSocket beherrscht.
        String smcbUrl      = env("TERMINAL_SMCB_URL", terminalUrl);
        String zentraleUrl  = env("ZENTRALE_URL",  "http://zentrale:8090/");
        String caUrl        = env("CA_URL",        "http://simulator:8480/ca.pem");
        int intervall       = Integer.parseInt(env("PRUEFINTERVALL_SEKUNDEN", "10"));

        System.out.println("""
                ────────────────────────────────────────────────────────────
                  PIN-Roboter · Standort-Agent
                ────────────────────────────────────────────────────────────""");

        String caPem = vertrauensankerHolen(caUrl);
        HttpClient http = TlsClient.mitVertrauensanker(caPem);
        System.out.println("  TLS bereit (TLS 1.2, Brainpool über BouncyCastle)");

        var kontext = new KonnektorClient.Aufrufkontext(
                env("MANDANT_ID", "Mandant1"),
                env("CLIENTSYSTEM_ID", "PIN-Roboter"),
                env("ARBEITSPLATZ_ID", "Arbeitsplatz1"));

        // Die gespeicherte Adresse sticht die Umgebungsvariable: Sie ist die
        // jüngere, bewusste Entscheidung an diesem Standort (Serverumzug).
        var zentralenadresse = new Zentralenadresse(
                java.nio.file.Path.of(env("ZENTRALE_URL_DATEI", "/daten/zentrale-url")),
                zentraleUrl);
        if (zentralenadresse.abweichend()) {
            System.out.printf("  Zentrale aus dem Datenverzeichnis: %s%n"
                            + "  (die Umgebungsvariable nennt %s)%n",
                    zentralenadresse.aktuell(), zentralenadresse.ausUmgebung());
        }

        Agent agent = new Agent(new KonnektorClient(http, URI.create(konnektorUrl), kontext),
                http, URI.create(terminalUrl), zentralenadresse.aktuell(),
                URI.create(smcbUrl));

        // Ohne diese Angabe laeuft alles weiter, nur ohne Unterstuetzung beim
        // Einrichten (FA-GER-035, NFA-QS-035). Vorbelegt auf den Konnektor
        // selbst, weil die Webadmin-Oberflaeche dort ueblicherweise mitlaeuft.
        agent.webadmin = URI.create(env("WEBADMIN_URL", konnektorUrl));

        // FA-AGT-010: Beim ersten Start loest der Agent sein einmalig gueltiges
        // Registrierungsgeheimnis gegen ein dauerhaftes Token ein. Das Token
        // liegt danach im Datenverzeichnis und wird nie wieder ausgegeben.
        agent.agentToken = agent.tokenBesorgen(env("TOKEN_DATEI", "/daten/agent-token"),
                                               System.getenv("REGISTRIERUNG"));
        agent.zustand.registrierung(agent.agentToken != null && !agent.agentToken.isBlank(),
                null);

        // ADR-0009: Der Browser verschluesselt die PIN mit dem oeffentlichen
        // Schluessel DIESES Agenten. Der private Teil verlaesst sein Geraet nie.
        agent.schluessel = Schluesselpaar.ladenOderErzeugen(
                java.nio.file.Path.of(env("SCHLUESSEL_DATEI", "/daten/agent-schluessel")));
        agent.ablage = Pinablage.ausUmgebung(
                java.nio.file.Path.of(env("PIN_ABLAGE", "/daten/pins")));
        System.out.println("  Schlüsselpaar bereit (PIN-Übergabe Ende-zu-Ende)");
        agent.pins.putAll(agent.ablage.alleLaden());
        System.out.println("  Aus der Ablage geladen: " + agent.pins.size()
                + " PIN(s)   (Werte werden nie ausgegeben)");

        // Nur fuer TESTAUFBAUTEN: PIN ueber eine Umgebungsvariable.
        // Format: PIN_<kartenhandle mit - als _>=<wert>, z. B. PIN_card_smcb_001=123456
        //
        // Fuer den Betrieb ist dieser Weg ausdruecklich UNGEEIGNET (ADR-0009):
        // Die PIN steht im Klartext auf dem Docker-Host, landet in jeder
        // Sicherung dieser Datei und ist ueber "docker inspect" einsehbar. Im
        // Betrieb hinterlegt ein Benutzer sie ueber die Oberflaeche.
        //
        // Eine ueber die Oberflaeche hinterlegte PIN hat VORRANG: Sonst wuerde
        // ein vergessener Testeintrag sie nach jedem Neustart wieder
        // verdraengen - lautlos, und mit einer PIN, die niemand mehr erwartet.
        int gefunden = 0;
        for (var e : System.getenv().entrySet()) {
            if (!e.getKey().startsWith("PIN_")) continue;
            // PIN_ABLAGE und PIN_ABLAGE_SCHLUESSEL sind Konfiguration, keine
            // PINs. Ohne diese Ausnahme landeten sie als vermeintliche
            // Karten-PIN im Speicher.
            if (e.getKey().equals("PIN_ABLAGE") || e.getKey().equals("PIN_ABLAGE_SCHLUESSEL")) continue;
            String handle = e.getKey().substring(4).replace('_', '-');
            if (agent.pins.containsKey(handle)) {
                System.out.println("  Hinweis: PIN_" + e.getKey().substring(4) + " wird ignoriert — "
                        + "für diese Karte ist bereits eine PIN über die Oberfläche hinterlegt.");
                continue;
            }
            agent.pinHinterlegen(handle, e.getValue().toCharArray());
            gefunden++;
        }
        if (gefunden > 0) {
            System.out.println("  WARNUNG: " + gefunden + " PIN(s) stammen aus Umgebungsvariablen. "
                    + "Das ist nur für Testaufbauten vorgesehen — im Betrieb steht die PIN damit "
                    + "im Klartext auf dem Host. Bitte über die Oberfläche hinterlegen (ADR-0009).");
        }
        System.out.println("  Hinterlegte PINs gesamt: " + agent.pins.size()
                + "   (Werte werden nie ausgegeben)");

        // Die Oberfläche zeigt, was oben eingerichtet wurde. Sie startet ZULETZT:
        // Wäre sie vorher da, zeigte sie einen Zustand, den es noch nicht gibt.
        agent.zustand.grunddaten(bausteinversion(), zentralenadresse.aktuell().toString(),
                konnektorUrl, intervall);
        agent.zustand.schluessel(agent.schluessel != null, agent.ablage.aktiv());
        new Agentenoberflaeche(agent.zustand, zentralenadresse, agent::zentraleSetzen).starten(
                Integer.parseInt(env("OBERFLAECHE_PORT",
                        String.valueOf(Agentenoberflaeche.VORGABEPORT))),
                env("OBERFLAECHE_ADRESSE", Agentenoberflaeche.VORGABEADRESSE));

        agent.ueberwachen(intervall);
    }

    Agent(KonnektorClient konnektor, HttpClient http, URI terminalBasis, URI zentrale) {
        this(konnektor, http, terminalBasis, zentrale, terminalBasis);
    }

    Agent(KonnektorClient konnektor, HttpClient http, URI terminalBasis, URI zentrale,
          URI smcbBasis) {
        this(konnektor, http, Zentraleverbindung.client(), terminalBasis, zentrale, smcbBasis);
    }

    /** Setzt die Zentrale um — nach geprüfter Erreichbarkeit (Zentralenadresse). */
    void zentraleSetzen(URI neu) { this.zentrale = neu; }

    Agent(KonnektorClient konnektor, HttpClient http, HttpClient zentraleHttp,
          URI terminalBasis, URI zentrale, URI smcbBasis) {
        this.zentraleHttp = zentraleHttp;
        this.konnektor = konnektor; this.http = http;
        this.terminalBasis = terminalBasis; this.zentrale = zentrale;
        this.smcb = new SmcbVerbindungen(http, terminalBasis, smcbBasis, new Kartenkenntnis(),
                new Verbindungsprotokoll());
        this.ablauf = new Verifikationsablauf(konnektor, http, terminalBasis, gepuffert::add,
                smcb::angemeldet);
    }

    /**
     * Was der Agent über eine Karte weiß, wenn das Terminal nach ihrem Slot
     * fragt (ADR-0011).
     *
     * <p>Diese Klasse ist die Stelle, an der eine <b>fremd ausgelöste</b>
     * PIN-Anfrage auf unsere Schutzlogik trifft. Sie ist deshalb bewusst
     * misstrauisch: Was sie nicht sicher weiß, lehnt sie ab.
     */
    private class Kartenkenntnis implements SmcbVerbindungen.Kartenkenntnis {

        @Override public String kartenhandle(String terminalId, String slot) {
            return slotZuKarte.get(terminalId + "/" + slot);
        }

        @Override public char[] pin(String kartenhandle) {
            char[] p = pins.get(kartenhandle);
            return p == null ? null : p.clone();   // die Verbindung überschreibt ihre Kopie
        }

        @Override public String hindernis(String terminalId, String kartenhandle) {
            Statusnotiz n = letzterStatus.get(kartenhandle);
            if (n == null) {
                return "Der Zustand dieser Karte ist noch nicht bekannt.";
            }
            if (System.currentTimeMillis() - n.zeitpunkt() > STATUS_HOECHSTALTER_MS) {
                return "Der bekannte Zustand dieser Karte ist zu alt, um eine PIN zu verantworten.";
            }
            var urteil = Sicherungspruefung.pruefe(
                    n.status(), n.verbleibendeVersuche(),
                    letzteVersuche.getOrDefault(kartenhandle, -1),
                    angehalten.contains(kartenhandle), notAus,
                    pins.containsKey(kartenhandle), n.aktualisierend());
            return urteil instanceof Sicherungspruefung.Urteil.Abgelehnt a ? a.grund() : null;
        }
    }

    /**
     * Verbindungsereignisse ins Fachprotokoll — ohne PIN, ohne Slotgeheimnisse.
     *
     * <p><b>Jede Meldung bekommt eine eigene Vorgangsnummer.</b> Sie muss
     * eindeutig sein: Die Protokollansicht blättert danach, und eine Nummer, die
     * mehrfach vorkommt, steht auf zwei Seiten gleichzeitig. Die Terminalkennung
     * gehört in den Text, nicht in dieses Feld — ein Fehler, den ein
     * Systemnachweis am 10.08.2026 aufgedeckt hat.
     */
    private class Verbindungsprotokoll implements SmcbVerbindung.Beobachter {

        private String neuerVorgang() {
            return UUID.randomUUID().toString().substring(0, 8);
        }

        @Override public void angemeldet(String terminalId) {
            // Bewusst nur technisch: Eine stehende Verbindung ist ein Zustand,
            // keine Handlung. Bei 5.000 Geräten und gelegentlichen Neuaufbauten
            // wäre das Fachprotokoll sonst voll davon (Protokoll-Grundregel).
            System.out.printf("  [%s] Verbindung zur Remote SMCB API steht%n", terminalId);
        }

        @Override public void pinGeliefert(String terminalId, String slot) {
            // Eigener Eintrag, obwohl der Verifikationsablauf den Erfolg
            // ebenfalls meldet: Das sind zwei verschiedene Dinge. Hier hat ein
            // Geheimnis das Haus verlassen; dort ist ein Zustand bestätigt. Die
            // Übergabe kann gelingen, während die Karte die PIN ablehnt — und
            // sie kann von jemand anderem ausgelöst worden sein.
            gepuffert.add(new Verifikationsablauf.Meldung(neuerVorgang(),
                    Ereignisart.PIN_UEBERGEBEN,
                    "PIN auf Anfrage von " + terminalId + " übergeben (" + slot + ")", true));
        }

        @Override public void pinVerweigert(String terminalId, String slot, String grund) {
            // Wichtiger als der Erfolgsfall: Hier hat die Schutzlogik gegriffen.
            // Ohne diesen Eintrag wäre nicht nachvollziehbar, warum eine
            // Freischaltung ausblieb (NFA-USA-030).
            gepuffert.add(new Verifikationsablauf.Meldung(neuerVorgang(),
                    Ereignisart.PIN_UEBERGABE_VERWEIGERT,
                    "PIN-Anfrage von " + terminalId + " abgelehnt (" + slot + "): " + grund, false));
            System.out.printf("  [%s] PIN-Anfrage abgelehnt: %s%n", terminalId, grund);
        }

        @Override public void getrennt(String terminalId, String grund) {
            System.out.printf("  [%s] Verbindung beendet: %s%n", terminalId, grund);
        }
    }

    public void pinHinterlegen(String kartenhandle, char[] pin) { pins.put(kartenhandle, pin); }

    /** Überwachungsschleife: prüft zyklisch und handelt, wenn nötig. */
    /** Zuletzt geprüft, je Terminal — Grundlage des gruppenweisen Takts. */
    private final Map<String, Long> zuletztGeprueft = new ConcurrentHashMap<>();

    /** Prüfintervall je Terminal, von der Zentrale gemeldet (FA-UEB-010). */
    private volatile Map<String, Integer> intervalle = Map.of();

    /**
     * Der Überwachungslauf.
     *
     * <p><b>Jedes Terminal hat seinen eigenen Takt</b> (FA-UEB-010). Bis zum
     * 08.08.2026 galt ein globaler Wert aus der Umgebung; das Intervall der
     * Gerätegruppe war wirkungslos. Bei 5.000 Terminals ist es aber der
     * einzige Hebel, mit dem die Last steuerbar bleibt — die Notaufnahme
     * braucht 30 Sekunden, das Archiv kommt mit fünf Minuten aus.
     *
     * <p>Die Schleife wacht im <b>Grundtakt</b> auf und prüft dann nur die
     * Geräte, deren Zeit gekommen ist. Der Grundtakt ist der aus der Umgebung;
     * er ist zugleich die Untergrenze, denn feiner kann kein Gerät geprüft
     * werden.
     *
     * @param grundtaktSekunden Takt der Schleife und Vorgabe für Geräte, für
     *        die die Zentrale kein Intervall meldet
     */
    void ueberwachen(int grundtaktSekunden) throws Exception {
        System.out.printf("  Grundtakt: %d s%n  Zentrale: %s%n%n", grundtaktSekunden, zentrale);

        while (true) {
            try {
                notAus = notAusAbfragen();
                zustand.notAus(notAus);
                zustand.zentraleErreicht();
                intervalleAbrufen();
                var terminals = konnektor.terminals();
                var karten = konnektor.karten();

                // Für die Oberfläche: Zahlen, keine Inhalte.
                long erreichbar = terminals.stream()
                        .filter(KonnektorClient.Terminal::erreichbar).count();
                // Nicht „smcb" nennen: So heißt das Feld mit den Verbindungen.
                long smcbKarten = karten.stream()
                        .filter(k -> "SMC-B".equals(k.kartentyp())).count();
                zustand.konnektorErreicht(terminals.size(), (int) erreichbar, (int) smcbKarten);
                zustand.pins(pins.size());
                for (var t : terminals) {
                    zustand.geraet(t.id(), t.erreichbar()
                            ? (t.pevimAktiv() ? "erreichbar, PEviM aktiv"
                                              : "erreichbar, PEviM nicht aktiv")
                            : "nicht erreichbar");
                }

                bestandMelden(terminals, karten);
                pinAuftraegeAbarbeiten();
                verbindungenPflegen(terminals, karten);
                zustand.verbindungen(smcb.anzahlAngemeldet());

                long jetzt = System.currentTimeMillis();
                for (var t : terminals) {
                    int fuerDieses = intervalle.getOrDefault(t.id(), grundtaktSekunden);
                    Long letzte = zuletztGeprueft.get(t.id());
                    // Beim ersten Durchgang ist "letzte" leer — dann wird
                    // sofort geprueft. Ein Geraet soll nicht bis zum Ablauf
                    // seines Intervalls unbekannt bleiben.
                    if (letzte != null && jetzt - letzte < fuerDieses * 1000L) continue;
                    zuletztGeprueft.put(t.id(), jetzt);

                    var karte = karten.stream()
                            .filter(k -> k.terminalId().equals(t.id()) && "SMC-B".equals(k.kartentyp()))
                            .findFirst().orElse(null);
                    durchgang(t, karte);
                }
            } catch (Exception e) {
                System.out.println("  Konnektor nicht erreichbar: " + e.getMessage());
                // Dieselbe Meldung in die Oberfläche — mit einem Hinweis, was
                // zu tun ist. Im Protokoll steht sie nur als Java-Text.
                zustand.konnektorFehler(String.valueOf(e.getMessage()));
            }
            Thread.sleep(grundtaktSekunden * 1000L);
        }
    }

    /**
     * Hält die Verbindungen zu den Kartenterminals im Sollzustand (ADR-0011).
     *
     * <p>Eine Verbindung soll stehen, wenn <b>alle vier</b> Bedingungen gelten:
     * Das Gerät ist erreichbar, PEviM ist aktiv, es steckt eine SMC-B darin, und
     * für diese Karte ist eine PIN hinterlegt. Fehlt eine davon, wird getrennt.
     *
     * <p>Das Trennen ist kein Aufräumen, sondern Pflicht: Die Spezifikation
     * lässt <b>genau eine</b> Verbindung je Gerät zu. Eine, die wir nutzlos
     * offen halten, sperrt den Platz für jeden anderen — etwa für einen zweiten
     * Agenten in einem Ausfallszenario.
     */
    private void verbindungenPflegen(List<KonnektorClient.Terminal> terminals,
                                     List<KonnektorClient.Karte> karten) {
        // Zuordnung Slot → Kartenhandle neu aufbauen. Vollständig ersetzen statt
        // ergänzen: Eine gezogene Karte muss aus der Zuordnung verschwinden,
        // sonst antwortete der Agent später mit der PIN einer Karte, die gar
        // nicht mehr im Gerät steckt.
        Map<String, String> neu = new LinkedHashMap<>();
        for (var k : karten) {
            if (k.verwaltet()) neu.put(k.terminalId() + "/" + k.slotname(), k.kartenhandle());
        }
        slotZuKarte.keySet().retainAll(neu.keySet());
        slotZuKarte.putAll(neu);

        Set<String> gewuenscht = new LinkedHashSet<>();
        for (var t : terminals) {
            if (!t.erreichbar() || !t.pevimAktiv()) continue;
            boolean pinDa = karten.stream().anyMatch(k -> k.verwaltet()
                    && k.terminalId().equals(t.id()) && pins.containsKey(k.kartenhandle()));
            if (pinDa) gewuenscht.add(t.id());
        }
        smcb.abgleichen(gewuenscht);
    }

    /**
     * Holt die Prüfintervalle von der Zentrale.
     *
     * <p>Bleibt die Zentrale stumm, gilt weiter, was zuletzt bekannt war —
     * ein Ausfall der Zentrale hält die Überwachung nicht an (FA-AGT-030).
     */
    private void intervalleAbrufen() {
        if (agentToken == null) return;
        try {
            var a = zentraleHttp.send(HttpRequest.newBuilder(zentrale.resolve("/api/agent/intervalle"))
                            .header("X-Agent-Token", agentToken)
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (a.statusCode() != 200) return;
            var neu = new LinkedHashMap<String, Integer>();
            var m = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\\d+)")
                    .matcher(a.body().replaceFirst("^.*?\"intervalle\"\\s*:\\s*\\{", ""));
            while (m.find()) neu.put(m.group(1), Integer.parseInt(m.group(2)));
            if (!neu.isEmpty() && !neu.equals(intervalle)) {
                System.out.println("  Prüfintervalle aktualisiert: " + neu.size() + " Gerät(e)");
                intervalle = neu;
            }
        } catch (Exception ignored) {
            // Stumme Zentrale: der zuletzt bekannte Stand gilt weiter.
        }
    }

    /**
     * Meldet den beim Konnektor vorgefundenen Bestand an die Zentrale
     * (FA-GER-030, FA-GER-035).
     *
     * <p><b>Warum das der Agent tut und nicht die Zentrale:</b> Der Konnektor
     * steht im Praxisnetz und ist von außen nicht erreichbar — der Agent baut
     * ausgehend auf (ADR-0002). Die Zentrale könnte die Abfrage also gar nicht
     * selbst durchführen.
     *
     * <p><b>Gemeldet wird nur, was sich geändert hat.</b> Bei 5.000 Terminals
     * im Minutentakt wäre eine vollständige Meldung je Zyklus rund 7 Millionen
     * Datensätze am Tag — dieselbe Überlegung wie bei der Protokoll-Grundregel.
     *
     * <p>Die Zentrale <b>übernimmt nichts selbsttätig</b>. Sie legt den Fund ab
     * und bietet ihn zur Bestätigung an; das verlangt FA-GER-030 ausdrücklich.
     */
    private void bestandMelden(List<KonnektorClient.Terminal> terminals,
                               List<KonnektorClient.Karte> karten) {
        if (agentToken == null) return;

        var eintraege = new ArrayList<Map<String, Object>>();
        for (var t : terminals) {
            var smcb = karten.stream()
                    .filter(k -> k.terminalId().equals(t.id()) && "SMC-B".equals(k.kartentyp()))
                    .findFirst().orElse(null);
            var e = new LinkedHashMap<String, Object>();
            e.put("terminalId", t.id());
            e.put("name", t.name());
            e.put("modell", t.modell());
            e.put("firmware", t.firmware());
            e.put("erreichbar", t.erreichbar());
            e.put("pevimUnterstuetzt", t.pevimUnterstuetzt());
            e.put("pevimAktiv", t.pevimAktiv());
            e.put("hatSmcb", smcb != null);
            if (smcb != null) {
                e.put("kartenhandle", smcb.kartenhandle());
                e.put("iccsn", smcb.iccsn());
                e.put("zertifikatGueltigBis", smcb.zertifikatGueltigBis());
            }

            // FA-GER-040: ALLE Karten melden, nicht nur die SMC-B. eGK und HBA
            // stecken in der Praxis regelmaessig mit im Terminal; sie sind fuer
            // PEviM ausdruecklich unzulaessig (A_24080). Sie zu verschweigen
            // waere bequem, aber der Betreiber soll sehen, was in seinem Geraet
            // steckt - und dass der Roboter sie in Ruhe laesst.
            var alleKarten = new ArrayList<Map<String, Object>>();
            for (var k : karten) {
                if (!k.terminalId().equals(t.id())) continue;
                var km = new LinkedHashMap<String, Object>();
                km.put("kartenhandle", k.kartenhandle());
                km.put("kartentyp", k.kartentyp());
                km.put("iccsn", k.iccsn());
                km.put("verwaltet", k.verwaltet());
                km.put("zertifikatGueltigBis", k.zertifikatGueltigBis());
                alleKarten.add(km);
            }
            e.put("karten", alleKarten);
            eintraege.add(e);
        }

        String modell = konnektor.informationsmodell(webadmin);
        String abdruck = String.valueOf(eintraege.hashCode()) + ":"
                + (modell == null ? "-" : modell.hashCode());
        if (abdruck.equals(letzterBestandsabdruck)) return;   // nichts Neues

        var meldung = new LinkedHashMap<String, Object>();
        meldung.put("terminals", eintraege);
        // Ist die Webadmin-Schnittstelle nicht verfuegbar, wird das ausdruecklich
        // gemeldet - die Oberflaeche weist dann auf die entfallende
        // Unterstuetzung hin (NFA-QS-035), statt das Feld leer zu lassen.
        meldung.put("informationsmodellVerfuegbar", modell != null);
        // FA-AGT-040: Der Agent nennt die Protokollversion, die er spricht.
        meldung.put("protokollversion", de.pinroboter.gemeinsam.Protokollversion.AKTUELL);
        meldung.put("bausteinversion", bausteinversion());
        // Der oeffentliche Schluessel ist nicht geheim - er MUSS die Zentrale
        // erreichen, damit der Browser damit verschluesseln kann (ADR-0009).
        if (schluessel != null) meldung.put("oeffentlicherSchluessel", schluessel.oeffentlichBase64());
        if (modell != null) meldung.put("informationsmodell", modell);

        // Fuer welche Karten eine PIN vorliegt - NUR die Kartenhandles, nie ein
        // Wert und nie ein Chiffrat.
        //
        // Warum das noetig ist: Die Zentrale leitete "PIN hinterlegt" bisher
        // allein aus IHREN EIGENEN Auftraegen ab. Eine PIN, die auf einem
        // anderen Weg zum Agenten kam - aus der Ablage nach einem Neustart,
        // aus der Umgebung im Testaufbau -, war fuer sie unsichtbar. Die
        // Oberflaeche zeigte dann "keine PIN hinterlegt", waehrend der Agent
        // Karten freischaltete. Ein Zustand, den niemand zusammenbringt.
        //
        // Der Agent ist hier die Wahrheit: Er hat die PIN oder nicht.
        meldung.put("pinsVorhanden", new ArrayList<>(pins.keySet()));

        if (senden("/api/agent/bestand", alsJson(meldung))) {
            letzterBestandsabdruck = abdruck;
            System.out.printf("  Bestand gemeldet: %d Terminals, Informationsmodell %s%n",
                    eintraege.size(), modell != null ? "gelesen" : "nicht verfügbar");
        }
    }

    /**
     * Holt PIN-Aufträge von der Zentrale und arbeitet sie ab (FA-PIN-010, FA-PIN-060).
     *
     * <p>Die Zentrale liefert ein Chiffrat, das nur dieser Agent entschlüsseln
     * kann. Der Klartext existiert danach ausschließlich hier — im
     * Arbeitsspeicher und verschlüsselt in der Ablage.
     *
     * <p><b>Weder PIN noch Chiffrat werden protokolliert.</b> Gemeldet wird
     * ausschließlich, dass ein Auftrag abgearbeitet wurde.
     */
    private void pinAuftraegeAbarbeiten() {
        if (agentToken == null || schluessel == null) return;
        String antwort;
        try {
            var a = zentraleHttp.send(HttpRequest.newBuilder(zentrale.resolve("/api/agent/pin-auftraege"))
                            .header("X-Agent-Token", agentToken)
                            .timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (a.statusCode() != 200) return;
            antwort = a.body();
        } catch (Exception e) { return; }

        // Bewusst ein enger Zerleger statt einer Bibliothek: Die Struktur ist
        // klein und fest, und in einem Pfad, durch den eine PIN laeuft, ist
        // weniger fremder Code besser als mehr.
        var m = java.util.regex.Pattern.compile(
                "\\{\"id\":(\\d+),\"kartenhandle\":\"([^\"]*)\",\"art\":\"([^\"]*)\""
                + "(?:,\"chiffrat\":\"([^\"]*)\")?"
                + "(?:,\"chiffratNeu\":\"([^\"]*)\")?").matcher(antwort);
        while (m.find()) {
            long id = Long.parseLong(m.group(1));
            String handle = m.group(2), art = m.group(3), chiffrat = m.group(4);
            String chiffratNeu = m.group(5);
            boolean erfolg = false;
            String fehler = null;
            try {
                if ("LOESCHEN".equals(art)) {
                    char[] alt = pins.remove(handle);
                    if (alt != null) java.util.Arrays.fill(alt, '\0');
                    ablage.entfernen(handle);
                    erfolg = true;
                } else if ("AENDERN".equals(art)) {
                    fehler = pinAendern(handle, chiffrat, chiffratNeu);
                    erfolg = fehler == null;
                } else if (chiffrat != null && !chiffrat.isBlank()) {
                    char[] pin = schluessel.entschluesseln(chiffrat);
                    ablage.speichern(handle, pin);
                    char[] vorher = pins.put(handle, pin);
                    if (vorher != null) java.util.Arrays.fill(vorher, '\0');
                    erfolg = true;
                } else {
                    fehler = "Es wurde kein Chiffrat mitgeliefert.";
                }
            } catch (Exception e) {
                // Die Meldung darf keinen Rueckschluss auf den Inhalt zulassen.
                fehler = "Die übermittelte PIN konnte nicht entschlüsselt werden. "
                       + "Wurde der Agent neu eingerichtet? Dann bitte neu hinterlegen.";
            }
            System.out.printf("  PIN-Auftrag %d (%s) %s%n", id, art,
                    erfolg ? "ausgeführt" : "fehlgeschlagen");
            quittieren(id, handle, art, erfolg, fehler);
        }
    }

    /**
     * Ändert die PIN einer SMC-B (FA-PIN-060, {@code A_24082}).
     *
     * <p><b>Auch das Ändern kann die Karte sperren.</b> Stimmt die alte PIN
     * nicht, zählt der Fehlversuchszähler herunter. Deshalb dieselbe
     * Sicherungsprüfung wie vor jeder Verifikation: Bleibt nur noch ein
     * Versuch, wird abgebrochen statt es zu wagen (FA-PIN-030).
     *
     * <p><b>Bei Fehlschlag bleibt die hinterlegte PIN unverändert.</b> Die neue
     * wird erst übernommen, wenn die Karte die Änderung bestätigt hat — sonst
     * hätte der Agent eine PIN im Speicher, die auf der Karte nicht gilt, und
     * würde damit reihenweise Fehlversuche erzeugen.
     *
     * @return Fehlermeldung im Klartext, oder {@code null} bei Erfolg
     */
    private String pinAendern(String kartenhandle, String chiffratAlt, String chiffratNeu) {
        if (chiffratAlt == null || chiffratNeu == null) {
            return "Für die Änderung fehlt einer der beiden Werte.";
        }
        char[] alt = null, neu = null;
        try {
            alt = schluessel.entschluesseln(chiffratAlt);
            neu = schluessel.entschluesseln(chiffratNeu);

            String terminalId = terminalZuKarte(kartenhandle);
            if (terminalId == null) return "Die Karte ist keinem bekannten Terminal zugeordnet.";

            // FA-PIN-030: erst nachsehen, wie viele Versuche uebrig sind.
            var status = konnektor.pinStatus(kartenhandle);
            if (status.verbleibendeVersuche() >= 0 && status.verbleibendeVersuche() <= 1) {
                return "Die Änderung wurde abgebrochen: Es ist nur noch ein Versuch übrig. "
                     + "Ein falscher Wert würde die Karte sperren. Bitte die PIN am "
                     + "Kartenterminal selbst ändern.";
            }
            if (status.aktualisierend()) {
                return "Am Kartenterminal läuft gerade ein Update. Bitte später erneut versuchen.";
            }

            try (TerminalClient terminal = new TerminalClient(http, terminalBasis, terminalId)) {
                var anmeldung = terminal.anmelden();
                if (!anmeldung.erfolgreich()) {
                    return "Das Kartenterminal hat die Anmeldung abgelehnt: " + anmeldung.fehler();
                }
                var e = terminal.pinAendern(kartenhandle, alt, neu);
                if (!e.geklappt()) {
                    return e.fehler() + (e.verbleibendeVersuche() >= 0
                            ? " Verbleibende Versuche: " + e.verbleibendeVersuche() + "."
                            : "");
                }
                // Erst JETZT die hinterlegte PIN ersetzen.
                char[] vorher = pins.put(kartenhandle, neu.clone());
                if (vorher != null) java.util.Arrays.fill(vorher, '\0');
                ablage.speichern(kartenhandle, neu);
                return null;
            }
        } catch (Exception e) {
            return "Die Änderung konnte nicht ausgeführt werden.";
        } finally {
            if (alt != null) java.util.Arrays.fill(alt, '\0');
            if (neu != null) java.util.Arrays.fill(neu, '\0');
        }
    }

    /** Das Terminal, in dem eine Karte steckt — aus der letzten Konnektorabfrage. */
    private String terminalZuKarte(String kartenhandle) {
        try {
            return konnektor.karten().stream()
                    .filter(k -> k.kartenhandle().equals(kartenhandle))
                    .map(KonnektorClient.Karte::terminalId).findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

    private void quittieren(long id, String handle, String art, boolean erfolg, String fehler) {
        var m = new LinkedHashMap<String, Object>();
        m.put("auftragId", id);
        m.put("kartenhandle", handle);
        m.put("art", art);
        m.put("erfolg", erfolg);
        if (fehler != null) m.put("fehler", fehler);
        senden("/api/agent/pin-quittung", alsJson(m));
    }

    private void durchgang(KonnektorClient.Terminal t, KonnektorClient.Karte karte) {
        var meldung = new LinkedHashMap<String, Object>();
        meldung.put("terminalId", t.id());
        meldung.put("name", t.name());
        meldung.put("modell", t.modell());
        meldung.put("firmware", t.firmware());
        meldung.put("erreichbar", t.erreichbar());
        meldung.put("pevimUnterstuetzt", t.pevimUnterstuetzt());
        meldung.put("pevimAktiv", t.pevimAktiv());

        if (karte == null || !t.erreichbar()) {
            meldung.put("pinStatus", PinStatus.UNBEKANNT.name());
            meldung.put("verbleibendeVersuche", -1);
            // Ausdruecklich zuruecksetzen: Ohne diese Zeile bliebe ein zuvor
            // gemeldetes „aktualisierend" in der Zentrale stehen, sobald die
            // Karte verschwindet oder das Geraet ausfaellt — die Oberflaeche
            // zeigte dann dauerhaft ein Update an, das laengst vorbei ist.
            meldung.put("aktualisierend", false);
            if (!t.erreichbar()) meldung.put("hinweis", "Das Gerät antwortet nicht.");
            melden(meldung);
            return;
        }

        try {
            var status = konnektor.pinStatus(karte.kartenhandle());
            meldung.put("kartenhandle", karte.kartenhandle());
            meldung.put("pinStatus", status.status().name());
            meldung.put("verbleibendeVersuche", status.verbleibendeVersuche());
            meldung.put("aktualisierend", status.aktualisierend());
            meldung.put("automatikAngehalten", angehalten.contains(karte.kartenhandle()));
            meldung.put("smcbVerbindung", smcb.angemeldet(t.id()));

            // Für die Schutzlogik im Antwortpfad festhalten: Fragt das Terminal
            // gleich nach der PIN, ist das hier der jüngste Kenntnisstand.
            letzterStatus.put(karte.kartenhandle(), new Statusnotiz(
                    status.status(), status.verbleibendeVersuche(),
                    status.aktualisierend(), System.currentTimeMillis()));

            // Ein Gerät ohne aktives PEviM ist kein Fehler, aber es muss sichtbar sein:
            // ohne diese Funktion kann die Automatik dort nichts ausrichten (A_24074).
            if (!t.pevimAktiv()) {
                meldung.put("hinweis", t.pevimUnterstuetzt()
                        ? "Die Funktion „PIN-Eingabe via Managementschnittstelle“ ist am Gerät nicht aktiviert."
                        : "Dieses Modell unterstützt die automatische PIN-Eingabe nicht.");
                if (t.pevimUnterstuetzt())
                    meldung.put("handlung", "Ein Administrator muss die Funktion direkt am Kartenterminal aktivieren.");
            }

            var urteil = Sicherungspruefung.pruefe(
                    status.status(), status.verbleibendeVersuche(),
                    letzteVersuche.getOrDefault(karte.kartenhandle(), -1),
                    angehalten.contains(karte.kartenhandle()), notAus,
                    pins.containsKey(karte.kartenhandle()), status.aktualisierend());

            if (status.verbleibendeVersuche() >= 0)
                letzteVersuche.put(karte.kartenhandle(), status.verbleibendeVersuche());

            if (urteil instanceof Sicherungspruefung.Urteil.Abgelehnt a) {
                if (status.status().automatikMoeglich() && !notAus) {
                    meldung.putIfAbsent("hinweis", a.grund());
                    meldung.putIfAbsent("handlung", a.handlung());
                }
            } else if (t.pevimAktiv()) {
                System.out.printf("  [%s] Karte nicht freigeschaltet — starte Verifikation%n", t.id());
                // Die Verbindung muss stehen, bevor der Konnektor angestoßen
                // wird: Das Terminal fragt sofort danach, nicht später.
                smcb.sicherstellen(t.id());
                var erg = ablauf.ausfuehren(t.id(), karte.kartenhandle());
                System.out.printf("  [%s] %s%n", t.id(), erg.erfolgreich() ? "freigeschaltet" : erg.meldung());

                if (erg.automatikAnhalten()) angehalten.add(karte.kartenhandle());
                if (!erg.erfolgreich()) {
                    meldung.put("hinweis", erg.meldung());
                    meldung.put("handlung", erg.handlung());
                    meldung.put("automatikAngehalten", angehalten.contains(karte.kartenhandle()));
                }
            }
        } catch (Exception e) {
            meldung.put("pinStatus", PinStatus.UNBEKANNT.name());
            meldung.put("hinweis", "Der Zustand konnte nicht ermittelt werden.");
        }
        melden(meldung);
    }

    /**
     * Holt das Agent-Token: aus der Datei, sonst per Registrierung.
     *
     * <p>Ohne Token arbeitet der Agent weiter — er ueberwacht und verifiziert
     * eigenstaendig (FA-AGT-030). Nur die Meldungen an die Zentrale entfallen.
     * Der TI-Zugang einer Praxis darf nicht daran haengen, dass eine
     * Verwaltungsschicht erreichbar ist.
     */
    String tokenBesorgen(String dateipfad, String registrierungsgeheimnis) {
        java.nio.file.Path datei = java.nio.file.Path.of(dateipfad);
        try {
            if (java.nio.file.Files.exists(datei)) {
                String t = java.nio.file.Files.readString(datei).trim();
                if (!t.isBlank()) { System.out.println("  Agent-Token aus Datei geladen"); return t; }
            }
        } catch (Exception ignored) { }

        if (registrierungsgeheimnis == null || registrierungsgeheimnis.isBlank()) {
            System.out.println("""
                      KEIN AGENT-TOKEN VORHANDEN.
                      Die Ueberwachung laeuft weiter, aber es werden keine Zustaende an die
                      Zentrale gemeldet. Bitte in der Zentrale ein Registrierungsgeheimnis
                      erzeugen und als Umgebungsvariable REGISTRIERUNG eintragen.""");
            return null;
        }

        try {
            var a = zentraleHttp.send(HttpRequest.newBuilder(zentrale.resolve("/api/registrieren"))
                    .header("X-Registrierung", registrierungsgeheimnis)
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (a.statusCode() != 200) {
                System.out.println("  Registrierung abgelehnt: " + a.body());
                return null;
            }
            var m = java.util.regex.Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(a.body());
            if (!m.find()) return null;
            String token = m.group(1);
            try {
                java.nio.file.Files.createDirectories(datei.getParent());
                java.nio.file.Files.writeString(datei, token);
                java.nio.file.Files.setPosixFilePermissions(datei,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (Exception e) {
                System.out.println("  Hinweis: Token konnte nicht gespeichert werden — "
                        + "nach einem Neustart ist eine neue Registrierung noetig.");
            }
            System.out.println("  Registrierung erfolgreich, Token erhalten");
            return token;
        } catch (Exception e) {
            System.out.println("  Registrierung nicht moeglich: " + e.getMessage());
            return null;
        }
    }

    /** Sendet Zustand und gepufferte Ereignisse an die Zentrale. */
    private void melden(Map<String, Object> zustand) {
        List<Map<String, Object>> ereignisse = new ArrayList<>();
        for (var m : gepuffert) {
            ereignisse.add(Map.of("art", m.art().name(), "erfolg", m.erfolg(),
                    "text", m.text(), "vorgang", m.vorgang()));
        }
        gepuffert.clear();
        zustand.put("ereignisse", ereignisse);

        if (agentToken == null) return;   // ohne Token keine Meldung, aber Weiterbetrieb
        try {
            senden("/api/agent/melden", alsJson(zustand));
        } catch (Exception e) {
            // Ausfall der Zentrale hält den Agenten nicht auf (FA-AGT-030).
            // Die Ereignisse wären im Produkt zu puffern statt zu verwerfen.
        }
    }

    /**
     * Schickt eine Meldung an die Zentrale.
     *
     * @return ob die Zentrale sie angenommen hat. Ein Ausfall der Zentrale hält
     *         den Agenten nicht auf (FA-AGT-030) — der TI-Zugang der Praxis darf
     *         nicht an unserer Verwaltungsschicht hängen.
     */
    private boolean senden(String pfad, String json) {
        if (agentToken == null) return false;
        try {
            var a = zentraleHttp.send(HttpRequest.newBuilder(zentrale.resolve(pfad))
                            .header("Content-Type", "application/json")
                            .header("X-Agent-Token", agentToken)
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.discarding());
            return a.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean notAusAbfragen() {
        if (agentToken == null) return notAus;
        try {
            var a = zentraleHttp.send(HttpRequest.newBuilder(zentrale.resolve("/api/agent/notaus"))
                    .header("X-Agent-Token", agentToken)
                    .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return a.body().contains("\"notAus\":true");
        } catch (Exception e) { return notAus; }
    }

    @SuppressWarnings("unchecked")
    private static String alsJson(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
        if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean erst = true;
            for (var e : m.entrySet()) {
                if (!erst) sb.append(",");
                sb.append(alsJson(String.valueOf(e.getKey()))).append(":").append(alsJson(e.getValue()));
                erst = false;
            }
            return sb.append("}").toString();
        }
        if (o instanceof Collection<?> c) {
            StringBuilder sb = new StringBuilder("[");
            boolean erst = true;
            for (var e : c) { if (!erst) sb.append(","); sb.append(alsJson(e)); erst = false; }
            return sb.append("]").toString();
        }
        return alsJson(String.valueOf(o));
    }

    private static String vertrauensankerHolen(String url) throws Exception {
        HttpClient einfach = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        for (int versuch = 1; versuch <= 30; versuch++) {
            try {
                var a = einfach.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (a.statusCode() == 200) return a.body();
            } catch (Exception ignored) { }
            System.out.println("  Warte auf den Simulator … (" + versuch + ")");
            Thread.sleep(2000);
        }
        throw new IllegalStateException("Vertrauensanker konnte nicht geladen werden: " + url);
    }

    /**
     * Die Version dieses Bausteins, aus dem Manifest des Jars.
     *
     * <p>Zur Anzeige, nicht zur Entscheidung — dafür ist die Protokollversion
     * da. Im Entwicklungslauf ohne Jar steht „unbekannt"; das ist kein Fehler.
     */
    private static String bausteinversion() {
        String v = Agent.class.getPackage().getImplementationVersion();
        return v == null ? "unbekannt" : v;
    }

    private static String env(String name, String vorgabe) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? vorgabe : v;
    }
}
