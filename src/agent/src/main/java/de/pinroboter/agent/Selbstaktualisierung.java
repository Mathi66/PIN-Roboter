package de.pinroboter.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Stößt die Aktualisierung dieses Agenten an, wenn die Zentrale es verlangt
 * (FA-AGT-100, PO 13.08.2026).
 *
 * <h2>Warum der Agent das nicht selbst kann</h2>
 *
 * Ein Container kann sich nicht selbst austauschen — er müsste sich dafür
 * beenden und danach noch etwas tun. Diese Arbeit macht ein zweiter Dienst
 * neben ihm (Watchtower). Der Agent <b>bittet</b> ihn nur darum.
 *
 * <p>Bewusst kein Zugriff auf den Docker-Dienst: Wer ihn hat, ist faktisch
 * Administrator des Praxisrechners. Der Agent kennt nur eine einzige Adresse
 * mit einer einzigen Wirkung — „sieh jetzt nach neuen Abbildern“.
 *
 * <h2>Was danach passiert</h2>
 *
 * Im Erfolgsfall ersetzt der Aktualisierungsdienst den Container dieses
 * Agenten. Der laufende Vorgang wird also <b>mitten im Satz</b> beendet.
 * Deshalb quittiert der Agent den Auftrag, <b>bevor</b> er anstößt: Danach kann
 * er nichts mehr sagen.
 *
 * <p>Ob es geklappt hat, entscheidet folglich nicht diese Klasse, sondern die
 * Zentrale — an der Version, mit der der Agent wiederkommt.
 *
 * <h2>Wenn die Aktualisierung nicht mitläuft</h2>
 *
 * Manche Häuser wollen jede Änderung selbst bestimmen und haben die beiden
 * Dienste aus dem Paket entfernt. Dann ist hier nichts erreichbar — und das ist
 * kein Fehler, sondern eine Entscheidung. Gemeldet wird es trotzdem, sonst
 * wartete in der Zentrale jemand auf ein Update, das niemand ausführt.
 */
final class Selbstaktualisierung {

    private final URI dienst;
    private final String token;
    private final HttpClient http;

    /**
     * @param adresse Basisadresse des Aktualisierungsdienstes im Compose-Netz,
     *                etwa {@code http://aktualisierung:8080}. Leer, wenn er
     *                nicht mitläuft.
     * @param token   gemeinsames Kennwort aus der {@code .env}. Ohne ihn nimmt
     *                der Dienst keine Anfragen an — sonst könnte jeder im
     *                Container-Netz Neustarts auslösen.
     */
    Selbstaktualisierung(String adresse, String token) {
        this.dienst = adresse == null || adresse.isBlank() ? null : URI.create(adresse.trim());
        this.token = token;
        // Eigener Client: kurz angebunden, ohne TLS-Aufbau. Der Dienst steht im
        // selben Compose-Netz, nicht im Internet.
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Ist überhaupt ein Aktualisierungsdienst eingerichtet? */
    boolean eingerichtet() { return dienst != null && token != null && !token.isBlank(); }

    /**
     * Bittet den Dienst, jetzt nach einem neuen Abbild zu sehen.
     *
     * @return {@code null} bei Erfolg, sonst der Grund im Klartext
     */
    String anstossen() {
        if (dienst == null) {
            return "Auf diesem Rechner läuft kein Aktualisierungsdienst. "
                 + "Die Aktualisierung muss dort von Hand erfolgen: "
                 + "docker compose pull && docker compose up -d";
        }
        if (token == null || token.isBlank()) {
            return "Für den Aktualisierungsdienst ist kein Kennwort hinterlegt "
                 + "(AKTUALISIERUNG_TOKEN in der .env). Ohne es nimmt er keine "
                 + "Anfragen an.";
        }
        try {
            var a = http.send(HttpRequest.newBuilder(dienst.resolve("/v1/update"))
                            .header("Authorization", "Bearer " + token)
                            // Großzügig: Der Dienst antwortet erst, wenn er das
                            // Abbild geholt hat. Über eine schwache Leitung
                            // dauert das. Bricht die Antwort ab, läuft die
                            // Aktualisierung trotzdem weiter — und im
                            // Erfolgsfall endet dieser Vorgang ohnehin damit,
                            // dass der Container verschwindet.
                            .timeout(Duration.ofMinutes(5))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (a.statusCode() == 401 || a.statusCode() == 403) {
                return "Der Aktualisierungsdienst hat das Kennwort abgelehnt. "
                     + "AKTUALISIERUNG_TOKEN muss bei beiden Diensten gleich sein.";
            }
            if (a.statusCode() != 200) {
                return "Der Aktualisierungsdienst antwortete mit HTTP " + a.statusCode() + ".";
            }
            return null;
        } catch (java.net.http.HttpTimeoutException e) {
            // Kein Fehlschlag: Der Dienst arbeitet, nur länger als wir warten.
            // Ihn hier als gescheitert zu melden hieße, ein laufendes Update
            // als fehlgeschlagen anzuzeigen.
            return null;
        } catch (Exception e) {
            return "Der Aktualisierungsdienst ist nicht erreichbar: "
                 + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                 + ". Läuft der Dienst „aktualisierung“ auf diesem Rechner?";
        }
    }
}
