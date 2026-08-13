package de.pinroboter.agent;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Der HTTP-Client für die <b>Zentrale</b> — gewöhnliches Web-TLS.
 *
 * <h2>Warum eine eigene Klasse und nicht eine Methode in TlsClient</h2>
 *
 * Dort stand sie zuerst. Das war falsch: {@link TlsClient} hat einen statischen
 * Initialisierer, der BouncyCastle registriert und die Brainpool-Kurven
 * freischaltet. Schon das Anfassen der Klasse löst ihn aus — auch wenn man nur
 * einen schlichten HttpClient für die eigene Zentrale möchte.
 *
 * <p>Aufgefallen im Test am 13.08.2026: {@code ExceptionInInitializerError},
 * obwohl die geprüfte Sache mit Kryptographie nichts zu tun hatte. Im Betrieb
 * hieße dasselbe: Scheitert die Brainpool-Freischaltung, wäre auch die
 * Verbindung zur Zentrale tot — und damit die Möglichkeit, den Fehler überhaupt
 * zu melden.
 *
 * <h2>Andere Regeln als für die TI-Strecke</h2>
 *
 * Die Einschränkung auf TLS 1.2 und zwei Cipher-Suites stammt aus
 * {@code [gemSpec_Krypt]} und gilt für Konnektor und Kartenterminal mit ihren
 * Brainpool-Chipkartenidentitäten. Für die eigene Zentrale gilt sie nicht: Dort
 * sind TLS 1.3 und der System-Vertrauensspeicher richtig — und sicherer.
 */
final class Zentraleverbindung {

    private Zentraleverbindung() { }

    static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // Ein Umzug der Zentrale wird bewusst eingestellt, nicht
                // stillschweigend gefolgt: Eine Weiterleitung könnte den Agenten
                // sonst unbemerkt woandershin melden lassen.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
