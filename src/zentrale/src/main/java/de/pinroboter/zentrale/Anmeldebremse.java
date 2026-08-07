package de.pinroboter.zentrale;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bremst wiederholte Anmeldeversuche eines Absenders.
 *
 * <h2>Warum die Kontosperre allein nicht genügt</h2>
 *
 * Es gibt bereits eine Sperre je Konto nach mehreren Fehlversuchen. Die schützt
 * das <b>einzelne Konto</b> — aber nicht gegen einen Angreifer, der <b>viele
 * Konten</b> mit je einem naheliegenden Kennwort durchprobiert. Bei 1.000
 * Benutzern ist das der aussichtsreichere Weg, und keine Kontosperre der Welt
 * bemerkt ihn.
 *
 * <p>Diese Klasse zählt deshalb je <b>Absender</b>, nicht je Konto.
 *
 * <h2>Bremsen, nicht sperren</h2>
 *
 * Nach {@link #FREIVERSUCHE} Fehlversuchen wird jede weitere Antwort
 * <b>verzögert</b> — anfangs kaum spürbar, dann zunehmend. Eine harte Sperre
 * wäre die schlechtere Wahl: Sie träfe eine ganze Praxis hinter einem
 * gemeinsamen Anschluss, und ein Angreifer könnte sie gezielt auslösen, um
 * Personal auszusperren.
 *
 * <p>Eine Verzögerung dagegen macht das Durchprobieren unwirtschaftlich, ohne
 * jemanden auszuschließen. Wer sein Kennwort wirklich vergessen hat, wartet ein
 * paar Sekunden — wer zehntausend Kombinationen probieren will, Jahre.
 *
 * <h2>Grenze, ehrlich benannt</h2>
 *
 * Die Zählung erfolgt <b>im Arbeitsspeicher</b> und je Instanz. Ein Neustart
 * setzt sie zurück, und hinter einem Lastverteiler mit mehreren Zentralen
 * zählte jede für sich. Für die Zielgröße dieses Produkts — eine Zentrale je
 * Einrichtung oder Serviceorganisation — ist das angemessen. Eine Ablage in der
 * Datenbank wäre ein Schreibvorgang je Anmeldeversuch, also genau das, was ein
 * Angreifer auslösen möchte.
 */
public class Anmeldebremse {

    /** So viele Fehlversuche bleiben ohne Verzögerung — Tippfehler sind normal. */
    public static final int FREIVERSUCHE = 5;

    /** Zuwachs je weiterem Fehlversuch. */
    static final Duration SCHRITT = Duration.ofSeconds(2);

    /** Obergrenze. Länger warten hilft nicht mehr und blockiert nur einen Bearbeitungsstrang. */
    static final Duration HOECHSTENS = Duration.ofSeconds(30);

    /** Nach dieser Ruhezeit ohne Fehlversuch beginnt die Zählung von vorn. */
    static final Duration VERFALL = Duration.ofMinutes(15);

    private record Zaehler(int fehlversuche, Instant letzter) {}

    private final Map<String, Zaehler> je = new ConcurrentHashMap<>();

    /**
     * Wie lange dieser Absender vor der nächsten Antwort warten muss.
     *
     * @return Wartezeit, oft {@link Duration#ZERO}
     */
    public Duration wartezeit(String absender) {
        var z = je.get(schluessel(absender));
        if (z == null) return Duration.ZERO;
        if (Duration.between(z.letzter(), Instant.now()).compareTo(VERFALL) > 0) {
            je.remove(schluessel(absender));
            return Duration.ZERO;
        }
        int ueber = z.fehlversuche() - FREIVERSUCHE;
        if (ueber <= 0) return Duration.ZERO;
        Duration d = SCHRITT.multipliedBy(ueber);
        return d.compareTo(HOECHSTENS) > 0 ? HOECHSTENS : d;
    }

    /** Vermerkt einen Fehlversuch. @return die Zahl der Fehlversuche dieses Absenders */
    public int fehlversuch(String absender) {
        String s = schluessel(absender);
        var neu = je.compute(s, (k, alt) -> {
            if (alt == null || Duration.between(alt.letzter(), Instant.now()).compareTo(VERFALL) > 0) {
                return new Zaehler(1, Instant.now());
            }
            return new Zaehler(alt.fehlversuche() + 1, Instant.now());
        });
        return neu.fehlversuche();
    }

    /** Eine erfolgreiche Anmeldung räumt den Zähler ab. */
    public void erfolg(String absender) {
        je.remove(schluessel(absender));
    }

    /** Ob dieser Absender gerade gebremst wird — für Protokoll und Anzeige. */
    public boolean gebremst(String absender) {
        return !wartezeit(absender).isZero();
    }

    /** Anzahl beobachteter Absender. Für Diagnose und Tests. */
    public int beobachtet() { return je.size(); }

    /** Nur für Tests. */
    void zuruecksetzen() { je.clear(); }

    /**
     * Der Schlüssel.
     *
     * <p>Die Adresse wird <b>nicht</b> im Klartext gehalten: Sie ist ein
     * personenbezogenes Datum, und dieser Zähler braucht sie nicht — er muss nur
     * unterscheiden können. Gehalten wird deshalb ein Hash.
     */
    private static String schluessel(String absender) {
        String a = absender == null || absender.isBlank() ? "unbekannt" : absender.trim();
        return Integer.toHexString(a.hashCode());
    }
}
