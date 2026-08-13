package de.pinroboter.gemeinsam;

/**
 * Feste Liste der Ereignisarten des Fachprotokolls (ADR-0005).
 *
 * <p>Freitext als alleiniger Inhalt ist unzulässig — nur eine feste Liste lässt
 * sich filtern, auswerten und übersetzen. Neue Funktionen bringen neue
 * Ereignisarten mit; das ist Teil der Definition of Done.
 *
 * <p>Die Stufe steuert die gestaffelte Rotation bei knappem Speicher
 * (FA-PRO-070): technische Diagnose zuerst, Sicherheitsereignisse zuletzt.
 */
public enum Ereignisart {
    // --- PIN-Verwaltung ---
    PIN_STATUS_GEAENDERT("PIN-Status geändert", Stufe.ROUTINE),
    VERIFIKATION_ERFOLGREICH("Karte freigeschaltet", Stufe.ROUTINE),
    VERIFIKATION_FEHLGESCHLAGEN("Freischaltung fehlgeschlagen", Stufe.SICHERHEIT),
    AUTOMATIK_ANGEHALTEN("Automatik angehalten", Stufe.SICHERHEIT),
    KARTE_GESPERRT("Karte gesperrt", Stufe.SICHERHEIT),
    PIN_HINTERLEGT("PIN hinterlegt", Stufe.SICHERHEIT),
    PIN_GELOESCHT("PIN gelöscht", Stufe.SICHERHEIT),
    PIN_GEAENDERT("PIN geändert", Stufe.SICHERHEIT),
    HANDLUNGSBEDARF("Handlungsbedarf erkannt", Stufe.ROUTINE),

    // Seit ADR-0011 fragt das Kartenterminal die PIN an, statt sie
    // entgegenzunehmen. Die Übergabe ist damit eine eigene Handlung und nicht
    // dasselbe wie eine erfolgreiche Verifikation: Sie kann gelingen, während
    // die Karte die PIN anschließend ablehnt. Sicherheitsstufe, weil hier ein
    // Geheimnis das Haus verlässt — und weil die Anfrage nicht von uns
    // ausgelöst worden sein muss.
    PIN_UEBERGEBEN("PIN an das Kartenterminal übergeben", Stufe.SICHERHEIT),
    PIN_UEBERGABE_VERWEIGERT("PIN-Anfrage des Kartenterminals abgelehnt", Stufe.SICHERHEIT),

    // --- Geräte und Verbindungen ---
    GERAET_ERKANNT("Gerät erkannt", Stufe.ROUTINE),
    GERAET_ZUGEORDNET("Gerät einer Gruppe zugeordnet", Stufe.ROUTINE),
    // Aenderungen an der Organisationsstruktur veraendern Geltungsbereiche und
    // damit, wer was sieht. Sie gehoeren auf die Sicherheitsstufe, nicht in die
    // Routine (FA-ORG-020).
    ORGANISATION_ANGELEGT("Organisationseinheit angelegt", Stufe.SICHERHEIT),
    ORGANISATION_UMBENANNT("Organisationseinheit umbenannt", Stufe.ROUTINE),
    ORGANISATION_UMGEHAENGT("Organisationseinheit umgehängt", Stufe.SICHERHEIT),
    ORGANISATION_GELOESCHT("Organisationseinheit gelöscht", Stufe.SICHERHEIT),
    // BSNR und Telematik-ID bestimmen, welche Leistungserbringerinstitution
    // hinter einer Betriebsstaette steht (ADR-0012). Eine falsche Angabe fuehrt
    // zur falschen SMC-B-Zuordnung - deshalb nachvollziehbar.
    ORGANISATION_TI_IDENTITAET("Angaben zur Betriebsstätte geändert", Stufe.SICHERHEIT),
    GRUPPE_ANGELEGT("Gerätegruppe angelegt", Stufe.ROUTINE),
    GRUPPE_GEAENDERT("Gerätegruppe geändert", Stufe.ROUTINE),
    GRUPPE_GELOESCHT("Gerätegruppe gelöscht", Stufe.ROUTINE),
    // Bestandspflege (FA-GER-010, FA-GER-020). Sicherheitsstufe, weil hier
    // Zugangsdaten hinterlegt werden und weil eine geloeschte Erfassung
    // bedeutet, dass ein Geraet nicht mehr ueberwacht wird - beides muss
    // nachvollziehbar bleiben, auch wenn das Protokoll rotiert.
    KONNEKTOR_ANGELEGT("Konnektor erfasst", Stufe.SICHERHEIT),
    KONNEKTOR_GEAENDERT("Konnektor geändert", Stufe.SICHERHEIT),
    KONNEKTOR_GELOESCHT("Konnektor gelöscht", Stufe.SICHERHEIT),
    // Wer einen Konnektor nutzen darf, sieht dessen Geraete (ADR-0012).
    KONNEKTOR_NUTZUNG_GEAENDERT("Konnektor-Nutzung geändert", Stufe.SICHERHEIT),
    TERMINAL_ANGELEGT("Kartenterminal erfasst", Stufe.SICHERHEIT),
    TERMINAL_GEAENDERT("Kartenterminal geändert", Stufe.SICHERHEIT),
    TERMINAL_GELOESCHT("Kartenterminal gelöscht", Stufe.SICHERHEIT),
    ERREICHBARKEIT_GEPRUEFT("Erreichbarkeit geprüft", Stufe.ROUTINE),

    // Bestandsermittlung (FA-GER-030, FA-GER-035). Die Ermittlung selbst ist
    // Routine; die Uebernahme in den Bestand ist es nicht - sie entscheidet
    // darueber, welche Geraete ueberwacht werden.
    BESTAND_ERMITTELT("Bestand beim Konnektor ermittelt", Stufe.ROUTINE),
    GERAET_UEBERNOMMEN("Gerät aus der Ermittlung übernommen", Stufe.SICHERHEIT),

    GERAET_ERREICHBAR("Gerät wieder erreichbar", Stufe.ROUTINE),
    GERAET_NICHT_ERREICHBAR("Gerät nicht erreichbar", Stufe.ROUTINE),
    KONNEKTOR_NICHT_ERREICHBAR("Konnektor nicht erreichbar", Stufe.ROUTINE),
    PEVIM_NICHT_AKTIV("PEviM am Gerät nicht aktiv", Stufe.ROUTINE),
    FIRMWARE_GEAENDERT("Firmware-Stand geändert", Stufe.ROUTINE),
    TERMINAL_AKTUALISIEREND("Terminal wird gerade aktualisiert", Stufe.ROUTINE),

    // --- Betrieb ---
    ANMELDUNG_ERFOLGREICH("Anmeldung", Stufe.SICHERHEIT),
    ANMELDUNG_FEHLGESCHLAGEN("Anmeldung fehlgeschlagen", Stufe.SICHERHEIT),
    ABMELDUNG("Abmeldung", Stufe.ROUTINE),
    BENUTZER_ANGELEGT("Benutzer angelegt", Stufe.SICHERHEIT),
    BENUTZER_GEAENDERT("Benutzer geändert", Stufe.SICHERHEIT),
    TOTP_EINGERICHTET("Zweiter Faktor eingerichtet", Stufe.SICHERHEIT),
    TOTP_ZURUECKGESETZT("Zweiter Faktor zurückgesetzt", Stufe.SICHERHEIT),

    PROTOKOLL_EXPORTIERT("Protokoll exportiert", Stufe.SICHERHEIT),
    DEMODATEN_ANGELEGT("Demodatensatz angelegt", Stufe.SICHERHEIT),
    DEMODATEN_GELOESCHT("Demodatensatz entfernt", Stufe.SICHERHEIT),
    PROTOKOLL_ROTIERT("Protokoll rotiert", Stufe.SICHERHEIT),
    PROTOKOLL_PSEUDONYMISIERT("Protokoll pseudonymisiert", Stufe.SICHERHEIT),
    ANMELDUNG_GEBREMST("Anmeldeversuche gebremst", Stufe.SICHERHEIT),

    ALARM_AUSGELOEST("Alarm ausgelöst", Stufe.ROUTINE),
    ALARM_BEHOBEN("Alarm behoben", Stufe.ROUTINE),
    ALARM_QUITTIERT("Alarm quittiert", Stufe.ROUTINE),
    // Von Hand geschlossen - im Unterschied zu ALARM_BEHOBEN, das der Agent
    // ausloest. Ein Mensch entscheidet hier, dass ein Alarm nicht mehr in der
    // Liste stehen soll; das muss nachvollziehbar bleiben.
    ALARM_GESCHLOSSEN("Alarm von Hand geschlossen", Stufe.SICHERHEIT),
    BENACHRICHTIGUNG_VERSENDET("Benachrichtigung versendet", Stufe.ROUTINE),
    // Wer benachrichtigt wird und wie der Mailserver erreicht wird, gehoert auf
    // die Sicherheitsstufe: Eine geaenderte Empfaengeradresse leitet Meldungen
    // ueber Betriebszustaende an eine andere Stelle um.
    EINSTELLUNG_GEAENDERT("Einstellung geändert", Stufe.SICHERHEIT),

    NOT_AUS_AUSGELOEST("Not-Aus ausgelöst", Stufe.SICHERHEIT),
    NOT_AUS_AUFGEHOBEN("Not-Aus aufgehoben", Stufe.SICHERHEIT),
    AGENT_VERBUNDEN("Standort-Agent verbunden", Stufe.ROUTINE),
    AGENT_VERSION("Agentenversion gemeldet", Stufe.ROUTINE),
    AGENT_REGISTRIERT("Standort-Agent registriert", Stufe.SICHERHEIT),
    AGENT_REGISTRIERUNG_ABGELEHNT("Registrierung abgelehnt", Stufe.SICHERHEIT),
    AGENT_GEHEIMNIS_ERZEUGT("Registrierungsgeheimnis erzeugt", Stufe.SICHERHEIT),
    AGENT_GETRENNT("Standort-Agent getrennt", Stufe.ROUTINE),
    // Verschiebt, wohin ein Agent meldet - und damit, in wessen Sicht seine
    // Geraete landen. Deshalb Sicherheitsstufe, nicht Routine.
    AGENT_ZUGEORDNET("Standort-Agent einer Einrichtung zugeordnet", Stufe.SICHERHEIT),
    // Eine Aktualisierung tauscht die Software an einem Standort aus. Wer sie
    // wann angestossen hat und was dabei herauskam, ist die erste Frage, wenn
    // sich das Verhalten dort ab einem bestimmten Tag geaendert hat.
    AGENT_AKTUALISIERUNG("Aktualisierung des Standort-Agenten", Stufe.SICHERHEIT),

    // --- Datensicherung (FA-BAK, ADR-0010) ---
    // Durchweg Sicherheitsstufe: Diese Einträge überleben die Rotation als
    // letzte. Ob und wann gesichert wurde, ist die Frage, die nach einem
    // Datenverlust zuerst gestellt wird.
    SICHERUNG_ERSTELLT("Sicherung erstellt", Stufe.SICHERHEIT),
    SICHERUNG_FEHLGESCHLAGEN("Sicherung fehlgeschlagen", Stufe.SICHERHEIT),
    SICHERUNG_ROTIERT("Alte Sicherung entfernt", Stufe.SICHERHEIT),
    SICHERUNG_HERUNTERGELADEN("Sicherung heruntergeladen", Stufe.SICHERHEIT),
    SICHERUNG_HOCHGELADEN("Sicherung hochgeladen", Stufe.SICHERHEIT),
    SICHERUNG_GELOESCHT("Sicherung gelöscht", Stufe.SICHERHEIT),
    WIEDERHERSTELLUNG("Wiederherstellung", Stufe.SICHERHEIT);

    public enum Stufe { TECHNISCH, ROUTINE, SICHERHEIT }

    /**
     * Themenbereich für die Filterung in der Oberfläche.
     *
     * <p>Am 10.08.2026 zeigte der Zeitstrahl <b>59 Ankreuzfelder, alle
     * angehakt</b>. Das ist kein Filter: Der Vorgabezustand „alles an" verlangt
     * abzuwählen, was man nicht will, und bei 59 Möglichkeiten tut das niemand.
     *
     * <p>Der Bereich gehört zur Ereignisart, nicht zur Darstellung — sonst
     * müsste jede Seite die Zuordnung erneut treffen und könnte sie anders
     * treffen.
     */
    public enum Bereich {
        PIN("PIN und Freischaltung"),
        GERAET("Geräte und Verbindungen"),
        BETRIEB("Betrieb und Verwaltung");

        private final String anzeige;
        Bereich(String anzeige) { this.anzeige = anzeige; }
        public String anzeige() { return anzeige; }
    }

    private final String anzeige;
    private final Stufe stufe;

    Ereignisart(String anzeige, Stufe stufe) { this.anzeige = anzeige; this.stufe = stufe; }
    public String anzeige() { return anzeige; }
    public Stufe stufe() { return stufe; }

    /**
     * Zu welchem Themenbereich diese Art gehört.
     *
     * <p>Abgeleitet aus der Reihenfolge im Enum, die den drei Blöcken oben
     * folgt. Eine zweite Liste zu pflegen hieße, sie irgendwann zu vergessen —
     * die Grenzen sind {@code GERAET_ERKANNT} und {@code ANMELDUNG_ERFOLGREICH}.
     */
    public Bereich bereich() {
        if (ordinal() < GERAET_ERKANNT.ordinal()) return Bereich.PIN;
        if (ordinal() < ANMELDUNG_ERFOLGREICH.ordinal()) return Bereich.GERAET;
        return Bereich.BETRIEB;
    }

    /**
     * Meldet diese Art ein Problem?
     *
     * <p>Die Grundlage der Sicht „nur Probleme" — der häufigsten Frage an ein
     * Protokoll. Bewusst eine ausdrückliche Aufzählung statt einer Ableitung
     * aus dem Namen: „Alarm behoben" enthält „Alarm", ist aber eine gute
     * Nachricht, und „Karte gesperrt" enthält kein Fehlerwort.
     */
    public boolean istProblem() {
        return switch (this) {
            case VERIFIKATION_FEHLGESCHLAGEN, KARTE_GESPERRT, HANDLUNGSBEDARF,
                 GERAET_NICHT_ERREICHBAR, KONNEKTOR_NICHT_ERREICHBAR,
                 PEVIM_NICHT_AKTIV, ANMELDUNG_FEHLGESCHLAGEN, ANMELDUNG_GEBREMST,
                 AGENT_REGISTRIERUNG_ABGELEHNT, AGENT_GETRENNT, ALARM_AUSGELOEST,
                 AUTOMATIK_ANGEHALTEN, NOT_AUS_AUSGELOEST,
                 SICHERUNG_FEHLGESCHLAGEN -> true;
            default -> false;
        };
    }

    /**
     * Handelt der Roboter hier selbsttätig?
     *
     * <p>Grundlage der Sicht „was der Roboter getan hat". Sie beantwortet die
     * Frage, für die dieses Produkt gebaut wurde: Hat er die Karte
     * freigeschaltet, und wann?
     */
    public boolean istAutomatik() {
        return bereich() == Bereich.PIN || switch (this) {
            case GERAET_ERKANNT, GERAET_ERREICHBAR, GERAET_NICHT_ERREICHBAR,
                 KONNEKTOR_NICHT_ERREICHBAR, ERREICHBARKEIT_GEPRUEFT,
                 FIRMWARE_GEAENDERT, TERMINAL_AKTUALISIEREND -> true;
            default -> false;
        };
    }
}
