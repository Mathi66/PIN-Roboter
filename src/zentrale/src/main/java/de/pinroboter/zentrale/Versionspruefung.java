package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Geheimnisspeicher;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Erkennt neue Versionen in der Container-Registry (FA-UPD-010, ADR-0001).
 *
 * <h2>Warum das ohne Docker-Rechte geht</h2>
 *
 * ADR-0001 trennt zwei Dinge, die alle fertigen Werkzeuge vermischen:
 * <b>Erkennen</b> ist eine reine HTTPS-Abfrage an die Registry;
 * <b>Ausführen</b> braucht Zugriff auf den Docker-Socket, und der entspricht
 * Root-Rechten auf dem Host. Diese Klasse macht nur das Erste. Damit ist der
 * überwiegende Teil des Nutzens ohne jedes Sicherheitsrisiko zu haben.
 *
 * <h2>Der Fehlerzustand ist die eigentliche Anforderung</h2>
 *
 * Die Akzeptanz sagt es ausdrücklich:
 *
 * <blockquote>Ungültige oder abgelaufene Zugangsdaten erscheinen als
 * <b>sichtbarer Fehlerzustand</b>, nicht als ausbleibende Meldung.</blockquote>
 *
 * Das ist der schwierige Teil, nicht die Abfrage. Ein Token läuft nach Monaten
 * ab; wenn die Prüfung dann still versagt, sieht der Betreiber nur, dass keine
 * Updates mehr gemeldet werden — und hält das für „es gibt keine". Genau so
 * bleibt eine Sicherheitslücke ein Jahr lang ungepatcht.
 *
 * <p>Deshalb wird der letzte Fehler <b>gespeichert</b> und angezeigt, bis eine
 * Abfrage wieder gelingt.
 */
public class Versionspruefung {

    /** Wie oft gefragt wird. Häufiger bringt nichts — Images erscheinen nicht im Minutentakt. */
    static final Duration TAKT = Duration.ofHours(6);

    private static final Duration GEDULD = Duration.ofSeconds(20);

    public record Version(String nummer, boolean sicherheitsrelevant, String hinweis,
                          boolean neuerAlsLaufende) {}

    /**
     * Der Zustand der Prüfung.
     *
     * @param fehler {@code null}, wenn die letzte Abfrage gelang. Sonst der
     *        Klartext für die Oberfläche — er sagt, was zu tun ist.
     */
    public record Zustand(String laufendeVersion, List<Version> verfuegbare,
                          Instant zuletztGeprueft, String fehler, boolean zugangHinterlegt) {

        public boolean neueVerfuegbar() {
            return verfuegbare.stream().anyMatch(Version::neuerAlsLaufende);
        }

        public boolean sicherheitsupdateOffen() {
            return verfuegbare.stream()
                    .anyMatch(v -> v.neuerAlsLaufende() && v.sicherheitsrelevant());
        }
    }

    private final Datenbank db;
    private final Geheimnisspeicher tresor;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(GEDULD).followRedirects(HttpClient.Redirect.NORMAL).build();

    public Versionspruefung(Datenbank db, Geheimnisspeicher tresor) {
        this.db = db;
        this.tresor = tresor;
    }

    // ------------------------------------------------------------ Einstellung

    public String zugangSpeichern(String registry, String abbild, String benutzer,
                                  String token) {
        if (registry == null || registry.isBlank()) {
            return "Bitte die Adresse der Registry angeben, z. B. ghcr.io";
        }
        if (abbild == null || abbild.isBlank()) {
            return "Bitte den Namen des Abbilds angeben, z. B. nieting/pin-roboter";
        }
        String chiffrat = null;
        if (token != null && !token.isBlank()) {
            if (tresor == null) {
                return "Ohne GEHEIMNIS_SCHLUESSEL lässt sich kein Token speichern.";
            }
            chiffrat = tresor.verschluesseln(token);
        }
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                INSERT INTO registry_zugang (nur_eine, registry, abbild, benutzer,
                                             token_chiffrat, geaendert)
                VALUES (TRUE,?,?,?,?,now())
                ON CONFLICT (nur_eine) DO UPDATE SET
                  registry = EXCLUDED.registry, abbild = EXCLUDED.abbild,
                  benutzer = EXCLUDED.benutzer,
                  token_chiffrat = COALESCE(EXCLUDED.token_chiffrat,
                                            registry_zugang.token_chiffrat),
                  geaendert = now()""")) {
            p.setString(1, registry.trim());
            p.setString(2, abbild.trim());
            p.setString(3, benutzer == null || benutzer.isBlank() ? null : benutzer.trim());
            p.setString(4, chiffrat);
            p.executeUpdate();
            return null;
        } catch (SQLException e) {
            return "Der Zugang konnte nicht gespeichert werden.";
        }
    }

    // -------------------------------------------------------------- Abfragen

    /** Der zuletzt ermittelte Zustand — ohne neue Abfrage. */
    public Zustand zustand() {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT registry, abbild, token_chiffrat, letzter_fehler,
                       zuletzt_geprueft, gefundene_versionen
                FROM registry_zugang WHERE nur_eine""")) {
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) {
                    return new Zustand(laufendeVersion(), List.of(), null, null, false);
                }
                Timestamp z = rs.getTimestamp("zuletzt_geprueft");
                return new Zustand(laufendeVersion(),
                        versionenLesen(rs.getString("gefundene_versionen")),
                        z == null ? null : z.toInstant(),
                        rs.getString("letzter_fehler"),
                        rs.getString("token_chiffrat") != null);
            }
        } catch (SQLException e) {
            return new Zustand(laufendeVersion(), List.of(), null,
                    "Der Zustand der Versionsprüfung ist nicht lesbar.", false);
        }
    }

    /**
     * Fragt die Registry ab und merkt sich das Ergebnis.
     *
     * @return derselbe Zustand wie {@link #zustand()}, nach der Abfrage
     */
    public Zustand pruefen() {
        String registry = null, abbild = null, benutzer = null, token = null;
        try (PreparedStatement p = db.verbindung().prepareStatement(
                "SELECT registry, abbild, benutzer, token_chiffrat FROM registry_zugang "
                + "WHERE nur_eine")) {
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) {
                    registry = rs.getString("registry");
                    abbild = rs.getString("abbild");
                    benutzer = rs.getString("benutzer");
                    String ch = rs.getString("token_chiffrat");
                    if (ch != null && tresor != null) token = tresor.entschluesseln(ch);
                }
            }
        }
        catch (SQLException e) {
            // Nicht verschlucken: Ein stiller SQL-Fehler ist praktisch
            // unauffindbar - so blieb ein fehlerhaftes WHERE monatelang
            // unentdeckt. Der Aufruf schlaegt trotzdem nicht fehl.
            System.err.println("Versionspruefung.pruefen: " + e.getMessage());
        }

        if (registry == null) {
            return zustand();   // nichts eingerichtet — kein Fehler, nur nichts zu tun
        }

        try {
            var bauer = HttpRequest.newBuilder(
                            URI.create(registry + "/v2/" + abbild + "/tags/list"))
                    .timeout(GEDULD).GET();
            if (token != null && !token.isBlank()) {
                String basic = Base64.getEncoder().encodeToString(
                        ((benutzer == null ? "x" : benutzer) + ":" + token)
                                .getBytes(StandardCharsets.UTF_8));
                bauer.header("Authorization", "Basic " + basic);
            }
            var a = http.send(bauer.build(), HttpResponse.BodyHandlers.ofString());

            if (a.statusCode() == 401 || a.statusCode() == 403) {
                // Der Fall, um den es in der Anforderung geht.
                merken(null, "Die Registry hat die Zugangsdaten abgelehnt (HTTP "
                        + a.statusCode() + "). Das Token ist vermutlich abgelaufen oder "
                        + "zurückgezogen. Bitte ein neues erzeugen und hier hinterlegen — "
                        + "bis dahin werden keine neuen Versionen erkannt.");
                return zustand();
            }
            if (a.statusCode() != 200) {
                merken(null, "Die Registry antwortete mit HTTP " + a.statusCode()
                        + ". Bitte Adresse und Name des Abbilds prüfen.");
                return zustand();
            }

            var tags = tagsLesen(a.body());
            if (tags.isEmpty()) {
                merken(null, "Die Registry kennt keine Versionen für „" + abbild + "“. "
                        + "Bitte den Namen des Abbilds prüfen.");
                return zustand();
            }
            merken(anreichern(tags, registry), null);
            return zustand();

        } catch (java.net.UnknownHostException e) {
            merken(null, "Die Registry „" + registry + "“ ist nicht erreichbar. "
                    + "Besteht eine Verbindung ins Internet?");
        } catch (java.net.http.HttpTimeoutException e) {
            merken(null, "Die Registry antwortet nicht innerhalb von "
                    + GEDULD.toSeconds() + " Sekunden.");
        } catch (Exception e) {
            merken(null, "Die Registry konnte nicht abgefragt werden: " + e.getMessage());
        }
        return zustand();
    }

    // ------------------------------------------------------------ Innenleben

    /**
     * Die laufende Version aus dem Manifest des Jars.
     *
     * <p>Im Entwicklungslauf ohne Jar steht dort nichts; dann gilt alles als
     * neuer. Das ist die harmlose Richtung — im Betrieb liegt immer ein Jar vor.
     */
    static String laufendeVersion() {
        String v = Versionspruefung.class.getPackage().getImplementationVersion();
        return v == null ? "unbekannt" : v;
    }

    static List<String> tagsLesen(String json) {
        List<String> tags = new ArrayList<>();
        var m = Pattern.compile("\"tags\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (!m.find()) return tags;
        for (String t : m.group(1).split(",")) {
            String s = t.trim().replaceAll("^\"|\"$", "");
            // "latest" ist ein Zeiger, keine Version. Ihn anzuzeigen hieße,
            // dem Betreiber ein Update auf "dasselbe nochmal" vorzuschlagen.
            if (!s.isEmpty() && !s.equals("latest")) tags.add(s);
        }
        return tags;
    }

    /** Änderungshinweise dazuholen und mit der laufenden Version vergleichen. */
    private List<Version> anreichern(List<String> tags, String registry) {
        Map<String, String[]> hinweise = hinweiseHolen(registry);
        String laufend = laufendeVersion();
        List<Version> liste = new ArrayList<>();
        for (String t : tags) {
            String[] h = hinweise.get(t);
            liste.add(new Version(t,
                    h != null && "true".equals(h[0]),
                    h == null ? null : h[1],
                    neuer(t, laufend)));
        }
        // Neueste zuerst — der Betreiber will wissen, was oben liegt.
        liste.sort((a, b) -> vergleiche(b.nummer(), a.nummer()));
        return liste;
    }

    private Map<String, String[]> hinweiseHolen(String registry) {
        Map<String, String[]> m = new LinkedHashMap<>();
        try {
            var a = http.send(HttpRequest.newBuilder(URI.create(registry + "/hinweise"))
                    .timeout(GEDULD).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (a.statusCode() != 200) return m;
            var e = Pattern.compile(
                    "\\{\\s*\"version\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"sicherheit\"\\s*:\\s*(true|false)"
                    + "\\s*,\\s*\"text\"\\s*:\\s*\"([^\"]*)\"").matcher(a.body());
            while (e.find()) m.put(e.group(1), new String[]{e.group(2), e.group(3)});
        } catch (Exception ignored) {
            // Ohne Hinweise bleibt die Liste brauchbar — nur ohne Text.
        }
        return m;
    }

    /** Vergleicht Versionen nach Zahlenfolge, nicht als Zeichenkette. */
    static int vergleiche(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? zahl(x[i]) : 0;
            int yi = i < y.length ? zahl(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    static boolean neuer(String kandidat, String laufend) {
        // "unbekannt" heißt Entwicklungslauf — dann ist alles neuer.
        if ("unbekannt".equals(laufend)) return true;
        return vergleiche(kandidat, laufend) > 0;
    }

    private static int zahl(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D", "")); }
        catch (Exception e) { return 0; }
    }

    private void merken(List<Version> versionen, String fehler) {
        String json = versionen == null ? null : versionenSchreiben(versionen);
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                UPDATE registry_zugang
                SET zuletzt_geprueft = now(), letzter_fehler = ?,
                    gefundene_versionen = COALESCE(?, gefundene_versionen)
                WHERE nur_eine""")) {
            p.setString(1, fehler);
            p.setString(2, json);
            p.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Versionsstand nicht speicherbar: " + e.getMessage());
        }
    }

    // Bewusst von Hand: Es sind drei Felder, und eine Bibliothek dafür
    // aufzunehmen wäre unverhältnismäßig (dieselbe Überlegung wie ADR-0006).
    private static String versionenSchreiben(List<Version> liste) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < liste.size(); i++) {
            var v = liste.get(i);
            if (i > 0) s.append(',');
            s.append("{\"v\":\"").append(v.nummer()).append("\",\"s\":").append(v.sicherheitsrelevant())
             .append(",\"n\":").append(v.neuerAlsLaufende()).append(",\"t\":\"")
             .append(v.hinweis() == null ? "" : v.hinweis().replace("\"", "'")).append("\"}");
        }
        return s.append(']').toString();
    }

    private static List<Version> versionenLesen(String json) {
        List<Version> liste = new ArrayList<>();
        if (json == null || json.isBlank()) return liste;
        var m = Pattern.compile("\\{\"v\":\"([^\"]*)\",\"s\":(true|false),\"n\":(true|false),"
                + "\"t\":\"([^\"]*)\"\\}").matcher(json);
        while (m.find()) {
            liste.add(new Version(m.group(1), Boolean.parseBoolean(m.group(2)),
                    m.group(4).isEmpty() ? null : m.group(4), Boolean.parseBoolean(m.group(3))));
        }
        return liste;
    }
}
