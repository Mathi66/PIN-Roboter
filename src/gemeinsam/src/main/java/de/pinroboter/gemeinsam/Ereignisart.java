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
    GRUPPE_ANGELEGT("Gerätegruppe angelegt", Stufe.ROUTINE),
    GRUPPE_GEAENDERT("Gerätegruppe geändert", Stufe.ROUTINE),
    GRUPPE_GELOESCHT("Gerätegruppe gelöscht", Stufe.ROUTINE),
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

    ALARM_AUSGELOEST("Alarm ausgelöst", Stufe.ROUTINE),
    ALARM_BEHOBEN("Alarm behoben", Stufe.ROUTINE),
    ALARM_QUITTIERT("Alarm quittiert", Stufe.ROUTINE),

    NOT_AUS_AUSGELOEST("Not-Aus ausgelöst", Stufe.SICHERHEIT),
    NOT_AUS_AUFGEHOBEN("Not-Aus aufgehoben", Stufe.SICHERHEIT),
    AGENT_VERBUNDEN("Standort-Agent verbunden", Stufe.ROUTINE),
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
