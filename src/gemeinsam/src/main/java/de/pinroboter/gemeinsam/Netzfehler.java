package de.pinroboter.gemeinsam;

/**
 * Übersetzt Netzwerkfehler in Sätze, mit denen jemand etwas anfangen kann.
 *
 * <h2>Der Anlass</h2>
 *
 * Viele Netzwerkausnahmen tragen <b>keine</b> Meldung. Aus
 * {@code getMessage()} wird dann {@code null}, und in der Oberfläche stand am
 * 13.08.2026 wörtlich:
 *
 * <pre>Die Registry konnte nicht abgefragt werden: null</pre>
 *
 * <p>Das ist keine Fehlermeldung, sondern ein Achselzucken. Der Klassenname der
 * Ausnahme sagt mehr — er ist aber englisch, technisch und steht in keinem
 * Verhältnis zu dem, was ein Praxisbetreiber wissen muss: <em>Ist das Kabel
 * ab, ist der Name falsch, oder passt das Zertifikat nicht?</em>
 *
 * <h2>Warum das hier liegt</h2>
 *
 * Dieselbe Übersetzung wurde am 13.08.2026 im Agenten geschrieben
 * ({@code Zentralenadresse.grund}) und in der Zentrale <b>vergessen</b>. Zwei
 * Kopien laufen auseinander; eine Stelle nicht. Genau derselbe Grund, aus dem
 * die Version des Agenten monatelang „unbekannt“ war — ein Eintrag, den es an
 * einer Stelle gab und an der anderen nicht.
 */
public final class Netzfehler {

    private Netzfehler() { }

    /**
     * Warum die Verbindung scheiterte — in Klartext.
     *
     * @return immer ein brauchbarer Satz, nie {@code null} und nie „null“
     */
    public static String klartext(Throwable e) {
        if (e == null) return "Unbekannte Ursache.";

        // Die eigentliche Ursache liegt oft eine Ebene tiefer: Der HttpClient
        // verpackt sie in eine IOException ohne eigenen Text.
        Throwable ursache = e;
        while (ursache.getMessage() == null && ursache.getCause() != null
                                            && ursache.getCause() != ursache) {
            ursache = ursache.getCause();
        }

        String art = ursache.getClass().getSimpleName();
        String text = ursache.getMessage();
        String klartext = switch (art) {
            case "UnknownHostException" ->
                    "Der Rechnername ist im Netz nicht auflösbar";
            case "ConnectException" ->
                    "Es kam keine Verbindung zustande — falscher Port, "
                    + "oder dort läuft nichts";
            case "NoRouteToHostException" ->
                    "Zu diesem Rechner führt kein Weg — Netz oder Firewall prüfen";
            case "HttpConnectTimeoutException", "ConnectTimeoutException",
                 "SocketTimeoutException" ->
                    "Keine Antwort innerhalb der Wartezeit";
            case "SSLHandshakeException" ->
                    "Die verschlüsselte Verbindung kam nicht zustande "
                    + "(passt das Zertifikat der Gegenstelle?)";
            case "SSLPeerUnverifiedException" ->
                    "Die Gegenstelle hat sich nicht ausgewiesen";
            case "SSLException" -> "Fehler in der verschlüsselten Verbindung";
            case "InterruptedIOException", "IOException" ->
                    "Die Verbindung brach ab";
            default -> null;
        };

        if (klartext == null) {
            // Kein bekannter Fall: Lieber der Klassenname als „null“ — er ist
            // zwar technisch, benennt aber wenigstens etwas.
            return (text == null || text.isBlank()) ? art : text;
        }
        return (text == null || text.isBlank()) ? klartext + "."
                                                : klartext + " (" + text + ")";
    }
}
