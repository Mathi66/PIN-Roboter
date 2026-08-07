package de.pinroboter.agent;

import de.pinroboter.gemeinsam.Ereignisart;
import de.pinroboter.gemeinsam.PinStatus;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
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

        // NFA-BET-010: Konfiguration ausschliesslich ueber Umgebungsvariablen.
        // Format: PIN_<kartenhandle mit - als _>=<wert>, z. B.
        //   PIN_card_smcb_001=123456
        // Im Produkt hinterlegt ein Benutzer die PIN ueber die Oberflaeche; sie
        // wird verschluesselt beim Agenten abgelegt und ist nie wieder auslesbar.
        int gefunden = 0;
        for (var e : System.getenv().entrySet()) {
            if (!e.getKey().startsWith("PIN_")) continue;
            String handle = e.getKey().substring(4).replace('_', '-');
            agent.pinHinterlegen(handle, e.getValue().toCharArray());
            gefunden++;
        }
        System.out.println("  Hinterlegte PINs: " + gefunden + "   (Werte werden nie ausgegeben)");

        agent.ueberwachen(intervall);
    }

    Agent(KonnektorClient konnektor, HttpClient http, URI terminalBasis, URI zentrale) {
        this.konnektor = konnektor; this.http = http;
        this.terminalBasis = terminalBasis; this.zentrale = zentrale;
        this.ablauf = new Verifikationsablauf(konnektor, http, terminalBasis, gepuffert::add);
    }

    public void pinHinterlegen(String kartenhandle, char[] pin) { pins.put(kartenhandle, pin); }

    /** Überwachungsschleife: prüft zyklisch und handelt, wenn nötig. */
    void ueberwachen(int intervallSekunden) throws Exception {
        System.out.printf("  Prüfintervall: %d s%n  Zentrale: %s%n%n", intervallSekunden, zentrale);

        while (true) {
            try {
                notAus = notAusAbfragen();
                var terminals = konnektor.terminals();
                var karten = konnektor.karten();

                for (var t : terminals) {
                    var karte = karten.stream()
                            .filter(k -> k.terminalId().equals(t.id()) && "SMC-B".equals(k.kartentyp()))
                            .findFirst().orElse(null);
                    durchgang(t, karte);
                }
            } catch (Exception e) {
                System.out.println("  Konnektor nicht erreichbar: " + e.getMessage());
            }
            Thread.sleep(intervallSekunden * 1000L);
        }
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

    /** Sendet Zustand und gepufferte Ereignisse an die Zentrale. */
    private void melden(Map<String, Object> zustand) {
        List<Map<String, Object>> ereignisse = new ArrayList<>();
        for (var m : gepuffert) {
            ereignisse.add(Map.of("art", m.art().name(), "erfolg", m.erfolg(),
                    "text", m.text(), "vorgang", m.vorgang()));
        }
        gepuffert.clear();
        zustand.put("ereignisse", ereignisse);

        try {
            String json = alsJson(zustand);
            http.send(HttpRequest.newBuilder(zentrale.resolve("/api/melden"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // Ausfall der Zentrale hält den Agenten nicht auf (FA-AGT-030).
            // Die Ereignisse wären im Produkt zu puffern statt zu verwerfen.
        }
    }

    private boolean notAusAbfragen() {
        try {
            var a = http.send(HttpRequest.newBuilder(zentrale.resolve("/api/notaus"))
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

    private static String env(String name, String vorgabe) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? vorgabe : v;
    }
}
