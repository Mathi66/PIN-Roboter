package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Ereignisart;

import java.time.Instant;

/**
 * Ein Eintrag des Fachprotokolls (ADR-0005).
 *
 * <p>Strukturiert, nicht Freitext: Nur feste Felder lassen sich filtern,
 * auswerten und übersetzen. Die {@code vorgang}-Nummer ist der Schlüssel zur
 * Nachvollziehbarkeit — sie klammert die vielen Einzelereignisse eines Ablaufs
 * zu einem Vorgang, den man aufklappen und verstehen kann.
 *
 * <p>Ein Feld für die PIN gibt es nicht und wird es nie geben (FA-PRO-020).
 */
public record Ereignis(
        long id,
        Instant zeitpunkt,
        String einrichtung,
        String geraet,
        Ereignisart art,
        boolean erfolg,
        String ausloeser,
        String vorgang,
        String text,
        String handlung
) {
    public String zeitLokal() {
        return java.time.LocalDateTime.ofInstant(zeitpunkt, java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
    }
}
