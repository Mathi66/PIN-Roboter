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
    private final URI terminalBasis;
    private final URI zentrale;
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

    public static void main(String[] args) throws Exception {
        BrainpoolFreischaltung.zustandAusgeben("vor anwenden");
        BrainpoolFreischaltung.anwenden();   // muss als Erstes laufen
        BrainpoolFreischaltung.zustandAusgeben("nach anwenden");
        String konnektorUrl = env("KONNEKTOR_URL", "https://simulator:8443/");
        String terminalUrl  = env("TERMINAL_URL",  "https://simulator:8444/");
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

        Agent agent = new Agent(new KonnektorClient(http, URI.create(konnektorUrl), kontext),
                http, URI.create(terminalUrl), URI.create(zentraleUrl));

        // Ohne diese Angabe laeuft alles weiter, nur ohne Unterstuetzung beim
        // Einrichten (FA-GER-035, NFA-QS-035). Vorbelegt auf den Konnektor
        // selbst, weil die Webadmin-Oberflaeche dort ueblicherweise mitlaeuft.
        agent.webadmin = URI.create(env("WEBADMIN_URL", konnektorUrl));

        // FA-AGT-010: Beim ersten Start loest der Agent sein einmalig gueltiges
        // Registrierungsgeheimnis gegen ein dauerhaftes Token ein. Das Token
        // liegt danach im Datenverzeichnis und wird nie wieder ausgegeben.
        agent.agentToken = agent.tokenBesorgen(env("TOKEN_DATEI", "/daten/agent-token"),
                                               System.getenv("REGISTRIERUNG"));

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

        agent.ueberwachen(intervall);
    }

    Agent(KonnektorClient konnektor, HttpClient http, URI terminalBasis, URI zentrale) {
        this.konnektor = konnektor; this.http = http;
        this.terminalBasis = terminalBasis; this.zentrale = zentrale;
        this.ablauf = new Verifikationsablauf(konnektor, http, terminalBasis, gepuffert::add);
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
                intervalleAbrufen();
                var terminals = konnektor.terminals();
                var karten = konnektor.karten();

                bestandMelden(terminals, karten);
                pinAuftraegeAbarbeiten();

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
            }
            Thread.sleep(grundtaktSekunden * 1000L);
        }
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
            var a = http.send(HttpRequest.newBuilder(zentrale.resolve("/api/agent/intervalle"))
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
            var a = http.send(HttpRequest.newBuilder(zentrale.resolve("/api/agent/pin-auftraege"))
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
                + "(?:,\"chiffrat\":\"([^\"]*)\")?").matcher(antwort);
        while (m.find()) {
            long id = Long.parseLong(m.group(1));
            String handle = m.group(2), art = m.group(3), chiffrat = m.group(4);
            boolean erfolg = false;
            String fehler = null;
            try {
                if ("LOESCHEN".equals(art)) {
                    char[] alt = pins.remove(handle);
                    if (alt != null) java.util.Arrays.fill(alt, '\0');
                    ablage.entfernen(handle);
                    erfolg = true;
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
                char[] pin = pins.get(karte.kartenhandle()).clone();
                var erg = ablauf.ausfuehren(t.id(), karte.kartenhandle(), pin);
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
            var a = http.send(HttpRequest.newBuilder(zentrale.resolve("/api/registrieren"))
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
            var a = http.send(HttpRequest.newBuilder(zentrale.resolve(pfad))
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
            var a = http.send(HttpRequest.newBuilder(zentrale.resolve("/api/agent/notaus"))
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
