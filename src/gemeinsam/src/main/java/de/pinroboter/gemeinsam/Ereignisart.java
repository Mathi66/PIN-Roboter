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
    HANDLUNGSBEDARF("Handlungsbedarf erkannt", Stufe.ROUTINE),

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
    AGENT_GETRENNT("Standort-Agent getrennt", Stufe.ROUTINE);

    public enum Stufe { TECHNISCH, ROUTINE, SICHERHEIT }

    private final String anzeige;
    private final Stufe stufe;

    Ereignisart(String anzeige, Stufe stufe) { this.anzeige = anzeige; this.stufe = stufe; }
    public String anzeige() { return anzeige; }
    public Stufe stufe() { return stufe; }
}
