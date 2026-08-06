package de.pinroboter.agent;

import de.pinroboter.gemeinsam.Ereignisart;
import de.pinroboter.gemeinsam.PinErgebnis;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Der vollständige PEviM-Ablauf für eine SMC-B (FA-PIN-020).
 *
 * <p>Die Reihenfolge ist durch [gemSpec_KT] {@code A_24081} vorgegeben und nicht
 * verhandelbar: Die Sitzung als {@code SMC-B_Nutzer} muss <b>bestehen, bevor</b>
 * das SICCT-Kommando eintrifft. Trifft es ein, ohne dass die Rolle angemeldet
 * ist, erwartet das Terminal die Eingabe am PIN-Pad — und dort steht nachts
 * niemand.
 *
 * <p>Ebenso verpflichtend ist der letzte Schritt: Erst die erneute Abfrage von
 * {@code GetPinStatus} belegt den Erfolg. Ein {@code PinResult=OK} ist die
 * Antwort auf einen Aufruf, kein bestätigter Zustand.
 */
public class Verifikationsablauf {

    private final KonnektorClient konnektor;
    private final HttpClient http;
    private final URI terminalBasis;
    private final Consumer<Meldung> protokoll;

    public record Meldung(String vorgang, Ereignisart art, String text, boolean erfolg) {}

    public record Ergebnis(boolean erfolgreich, String vorgang, String meldung,
                           String handlung, boolean automatikAnhalten) {}

    public Verifikationsablauf(KonnektorClient konnektor, HttpClient http,
                               URI terminalBasis, Consumer<Meldung> protokoll) {
        this.konnektor = konnektor; this.http = http;
        this.terminalBasis = terminalBasis; this.protokoll = protokoll;
    }

    /**
     * Führt die Verifikation durch. Die {@link Sicherungspruefung} muss vorher
     * bestanden sein — diese Methode prüft sie nicht erneut.
     *
     * @param pin wird nach Gebrauch überschrieben; der Aufrufer gibt eine Kopie
     */
    public Ergebnis ausfuehren(String terminalId, String kartenhandle, char[] pin) {
        String vorgang = UUID.randomUUID().toString().substring(0, 8);

        try (TerminalClient terminal = new TerminalClient(http, terminalBasis, terminalId)) {

            // Schritt 1 — Sitzung als SMC-B_Nutzer aufbauen (A_24081, Vorbedingung)
            var anmeldung = terminal.anmelden();
            if (!anmeldung.erfolgreich()) {
                protokoll.accept(new Meldung(vorgang, Ereignisart.PEVIM_NICHT_AKTIV,
                        "Anmeldung am Kartenterminal nicht möglich: " + anmeldung.fehler(), false));
                return new Ergebnis(false, vorgang,
                        "Das Kartenterminal nimmt keine PIN über die Verwaltungsschnittstelle entgegen.",
                        anmeldung.hinweis() != null ? anmeldung.hinweis()
                                : "Bitte prüfen, ob die Funktion \u201ePIN-Eingabe via Managementschnittstelle\u201c "
                                  + "am Gerät aktiviert ist. Die Aktivierung ist nur durch einen "
                                  + "Administrator direkt am Terminal möglich.",
                        false);
            }

            // Schritt 2 — VerifyPin auslösen. Der Konnektor sendet daraufhin das
            // SICCT-Kommando; das Terminal wartet dann auf den PIN-Wert.
            // Beides läuft nebenläufig: Der Aufruf kehrt erst zurück, wenn die
            // PIN geliefert wurde, also muss die Lieferung parallel erfolgen.
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {

                Future<KonnektorClient.VerifyAntwort> verify =
                        pool.submit(() -> konnektor.verifyPin(kartenhandle));

                // Schritt 3 — PIN über die Managementschnittstelle liefern,
                // sobald das Terminal darauf wartet.
                Future<Boolean> lieferung = pool.submit(() -> {
                    for (int i = 0; i < 100; i++) {           // bis zu 10 s warten
                        if (terminal.wartetAufPin()) return terminal.pinLiefern(pin);
                        Thread.sleep(100);
                    }
                    return false;
                });

                boolean geliefert = lieferung.get(20, TimeUnit.SECONDS);
                KonnektorClient.VerifyAntwort antwort = verify.get(60, TimeUnit.SECONDS);

                if (!geliefert && antwort.ergebnis() == PinErgebnis.ERROR) {
                    protokoll.accept(new Meldung(vorgang, Ereignisart.VERIFIKATION_FEHLGESCHLAGEN,
                            "PIN konnte nicht übergeben werden: " + antwort.fehlertext(), false));
                    return new Ergebnis(false, vorgang,
                            uebersetze(antwort.fehlercode(), antwort.fehlertext()),
                            "Bitte die Verbindung zum Kartenterminal prüfen.", false);
                }

                return auswerten(vorgang, kartenhandle, antwort);
            }

        } catch (Exception e) {
            protokoll.accept(new Meldung(vorgang, Ereignisart.VERIFIKATION_FEHLGESCHLAGEN,
                    "Unerwarteter Fehler: " + e, false));
            return new Ergebnis(false, vorgang,
                    "Die Freischaltung konnte nicht abgeschlossen werden.",
                    "Bitte das technische Protokoll prüfen. Vorgang " + vorgang, false);
        } finally {
            java.util.Arrays.fill(pin, '\0');   // PIN im Speicher überschreiben
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
                    + "Am Kartenterminal ist die Funktion \u201ePIN-Eingabe via Managementschnittstelle\u201c "
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
