package de.pinroboter.agent;

import de.pinroboter.gemeinsam.Ereignisart;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Der vollständige PEviM-Ablauf für eine SMC-B (FA-PIN-020).
 *
 * <h2>Was sich am 10.08.2026 geändert hat</h2>
 *
 * Bis dahin tat diese Klasse zwei Dinge nebenläufig: den Konnektor anstoßen
 * <i>und</i> die PIN ans Terminal schieben. Das zweite gibt es an echten Geräten
 * nicht (ADR-0011). Die PIN liefert seither {@link SmcbVerbindung} — auf Anfrage
 * des Terminals, über eine Verbindung, die schon vorher steht.
 *
 * <p>Übrig bleibt hier <b>ein</b> Schritt und seine Kontrolle: den Konnektor
 * auffordern, dann prüfen, ob es gewirkt hat. Die Klasse ist damit kürzer und
 * ehrlicher — sie tut nicht mehr so, als hätte sie die Übergabe in der Hand.
 *
 * <h2>Was gleich geblieben ist</h2>
 *
 * Die Vorbedingung aus [gemSpec_KT] {@code A_24081}: Die Rolle
 * {@code SMC-B_Nutzer} muss <b>authentisiert sein, bevor</b> das SICCT-Kommando
 * eintrifft. Bei der Remote SMCB API ist genau das die stehende, angemeldete
 * WebSocket-Verbindung. Fehlt sie, erwartet das Terminal die Eingabe am PIN-Pad
 * — und dort steht nachts niemand.
 *
 * <p>Und der letzte Schritt: Erst die erneute Abfrage von {@code GetPinStatus}
 * belegt den Erfolg. Ein {@code PinResult=OK} ist die Antwort auf einen Aufruf,
 * kein bestätigter Zustand.
 */
public class Verifikationsablauf {

    private final KonnektorClient konnektor;
    private final Consumer<Meldung> protokoll;

    /** Ob für ein Terminal eine angemeldete Verbindung zur Remote SMCB API besteht. */
    private final Predicate<String> verbindungSteht;

    public record Meldung(String vorgang, Ereignisart art, String text, boolean erfolg) {}

    public record Ergebnis(boolean erfolgreich, String vorgang, String meldung,
                           String handlung, boolean automatikAnhalten) {}

    public Verifikationsablauf(KonnektorClient konnektor, HttpClient http,
                               URI terminalBasis, Consumer<Meldung> protokoll,
                               Predicate<String> verbindungSteht) {
        this.konnektor = konnektor;
        this.protokoll = protokoll;
        this.verbindungSteht = verbindungSteht;
    }

    /**
     * Löst die Verifikation aus und wertet sie aus.
     *
     * <p>Die {@link Sicherungspruefung} muss vorher bestanden sein — und sie
     * greift ein zweites Mal, wenn das Terminal tatsächlich fragt (siehe
     * {@link SmcbVerbindungen}). Zweimal zu prüfen ist kein Versehen: Die erste
     * Prüfung entscheidet, ob wir überhaupt anstoßen; die zweite gilt für jede
     * Anfrage — auch für die, die jemand anders ausgelöst hat.
     *
     * <p><b>Die PIN kommt in dieser Methode nicht mehr vor.</b> Sie liegt in der
     * Pinablage und wird ausschließlich von der Verbindung geholt, wenn das
     * Terminal danach fragt. Das ist nicht nur Umbau, sondern auch weniger
     * Fläche: Ein Geheimnis, das eine Methode nie sieht, kann sie auch nicht
     * verlieren.
     */
    public Ergebnis ausfuehren(String terminalId, String kartenhandle) {
        String vorgang = UUID.randomUUID().toString().substring(0, 8);

        // Vorbedingung nach A_24081: ohne angemeldete Verbindung keine
        // PIN-Übergabe. Das Kommando liefe am Gerät auf das PIN-Pad und dort in
        // die Zeitüberschreitung.
        if (!verbindungSteht.test(terminalId)) {
            protokoll.accept(new Meldung(vorgang, Ereignisart.PEVIM_NICHT_AKTIV,
                    "Keine angemeldete Verbindung zur Remote SMCB API", false));
            return new Ergebnis(false, vorgang,
                    "Das Kartenterminal nimmt zurzeit keine PIN entgegen.",
                    "Bitte prüfen, ob die Funktion „PIN-Eingabe via Managementschnittstelle“ "
                    + "am Gerät aktiviert ist. Die Aktivierung ist nur durch einen "
                    + "Administrator direkt am Terminal möglich.",
                    false);
        }

        try {
            // Der Konnektor sendet daraufhin das SICCT-Kommando. Das Terminal
            // fragt dann über die stehende Verbindung nach der PIN; die Antwort
            // gibt SmcbVerbindung, nebenläufig zu diesem blockierenden Aufruf.
            KonnektorClient.VerifyAntwort antwort = konnektor.verifyPin(kartenhandle);
            return auswerten(vorgang, kartenhandle, antwort);

        } catch (Exception e) {
            protokoll.accept(new Meldung(vorgang, Ereignisart.VERIFIKATION_FEHLGESCHLAGEN,
                    "Unerwarteter Fehler: " + e, false));
            return new Ergebnis(false, vorgang,
                    "Die Freischaltung konnte nicht abgeschlossen werden.",
                    "Bitte das technische Protokoll prüfen. Vorgang " + vorgang, false);
        }
    }

    private Ergebnis auswerten(String vorgang, String kartenhandle,
                               KonnektorClient.VerifyAntwort antwort) throws Exception {
        switch (antwort.ergebnis()) {
            case OK -> {
                // Schritt 4 — Bestätigung. Ohne sie gilt die Verifikation als
                // nicht nachgewiesen (FA-PIN-020, Akzeptanzkriterium 5).
                var status = konnektor.pinStatus(kartenhandle);
                boolean bestaetigt = status.status() == de.pinroboter.gemeinsam.PinStatus.VERIFIED;
                protokoll.accept(new Meldung(vorgang,
                        bestaetigt ? Ereignisart.VERIFIKATION_ERFOLGREICH : Ereignisart.VERIFIKATION_FEHLGESCHLAGEN,
                        bestaetigt ? "Karte freigeschaltet und bestätigt"
                                   : "Freischaltung gemeldet, aber nicht bestätigt", bestaetigt));
                return bestaetigt
                        ? new Ergebnis(true, vorgang, "Die Karte ist freigeschaltet.", null, false)
                        : new Ergebnis(false, vorgang,
                                "Das Kartenterminal meldete Erfolg, die Nachprüfung zeigt aber keinen "
                                + "freigeschalteten Zustand.",
                                "Bitte den Zustand am Gerät prüfen.", true);
            }
            case REJECTED -> {
                // FA-PIN-030: genau ein Versuch, danach Halt. Kein zweiter Anlauf.
                protokoll.accept(new Meldung(vorgang, Ereignisart.AUTOMATIK_ANGEHALTEN,
                        "PIN abgelehnt, noch %d Versuche — Automatik angehalten"
                                .formatted(antwort.verbleibendeVersuche()), false));
                return new Ergebnis(false, vorgang,
                        "Die hinterlegte PIN wurde vom Kartenterminal abgelehnt. Noch %d Versuche, "
                        .formatted(antwort.verbleibendeVersuche())
                        + "danach sperrt die Karte.",
                        "Die Automatik wurde für diese Karte angehalten. Bitte die hinterlegte PIN prüfen "
                        + "und anschließend erneut freigeben.", true);
            }
            case NOWBLOCKED, WASBLOCKED -> {
                protokoll.accept(new Meldung(vorgang, Ereignisart.KARTE_GESPERRT,
                        "Karte gesperrt (" + antwort.ergebnis() + ")", false));
                return new Ergebnis(false, vorgang, "Die Karte ist gesperrt.",
                        "Die Karte lässt sich nur mit der PUK entsperren.", true);
            }
            case TRANSPORT_PIN -> {
                return new Ergebnis(false, vorgang, "Für diese Karte ist noch die Transport-PIN gesetzt.",
                        "Die Erst-PIN muss einmalig am Kartenterminal geändert werden.", true);
            }
            default -> {
                protokoll.accept(new Meldung(vorgang, Ereignisart.VERIFIKATION_FEHLGESCHLAGEN,
                        "Fehler " + antwort.fehlercode() + ": " + antwort.fehlertext(), false));
                return new Ergebnis(false, vorgang,
                        uebersetze(antwort.fehlercode(), antwort.fehlertext()),
                        "Bitte die Verbindung zu Konnektor und Kartenterminal prüfen.", false);
            }
        }
    }

    /**
     * Übersetzt Konnektor-Fehlercodes in Klartext (NFA-USA-020).
     * Ein Code wie {@code 4078} ist für eine Praxismitarbeiterin bedeutungslos.
     */
    static String uebersetze(String code, String text) {
        if (code == null) return text == null ? "Unbekannter Fehler" : text;
        return switch (code) {
            case "4078" -> "Der Konnektor lässt die PIN-Eingabe über dieses System nicht zu. "
                    + "Am Kartenterminal ist die Funktion „PIN-Eingabe via Managementschnittstelle“ "
                    + "vermutlich nicht aktiviert.";
            case "4047" -> "Das Kartenterminal hat nicht rechtzeitig geantwortet.";
            case "4209" -> "Dieser Kartentyp wird nicht unterstützt. Der PIN-Roboter arbeitet "
                    + "ausschließlich mit der SMC-B.";
            case "4000" -> "Der Konnektor hat die Anfrage abgelehnt (Syntaxfehler). "
                    + "Vermutlich stimmt der Aufrufkontext nicht.";
            default -> (text == null || text.isBlank() ? "Fehler" : text) + " (Code " + code + ")";
        };
    }
}
