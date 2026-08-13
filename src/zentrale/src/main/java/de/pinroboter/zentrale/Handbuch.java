package de.pinroboter.zentrale;

import java.util.ArrayList;
import java.util.List;

/**
 * Das Handbuch als Teil der Oberfläche (PO, 13.08.2026).
 *
 * <h2>Warum es hier steht und nicht als PDF beiliegt</h2>
 *
 * Ein Handbuch, das man erst suchen muss, wird nicht gelesen. In einer Praxis
 * unter Zeitdruck entscheidet sich in Sekunden, ob jemand nachschlägt oder rät.
 * Deshalb steht es <b>im Produkt</b>, an derselben Stelle wie alles andere,
 * durchsuchbar und mit denselben Begriffen wie die Oberfläche.
 *
 * <p>Ein zweiter Grund wiegt schwerer: Ein beiliegendes PDF veraltet ab dem
 * Tag der Auslieferung. Dieses Handbuch liegt im selben Quellbaum wie der Code
 * und wird mit ihm zusammen ausgeliefert.
 *
 * <h2>Aufbau</h2>
 *
 * Drei Teile, weil drei verschiedene Menschen hier lesen:
 *
 * <ul>
 *   <li><b>Verstehen</b> — was das Produkt tut und warum. Für jemanden, der
 *       entscheidet, ob es eingesetzt wird.</li>
 *   <li><b>Bedienen</b> — der Arbeitsalltag. Für das Personal in der Praxis.</li>
 *   <li><b>Betreiben</b> — Einrichtung, Sicherung, Störungen. Für den
 *       Administrator oder den betreuenden Dienstleister.</li>
 * </ul>
 *
 * <h2>Die Suche</h2>
 *
 * Serverseitig, nicht im Browser. Das hat zwei Gründe: Sie funktioniert ohne
 * JavaScript — Barrierefreiheit ist kein Zusatz (WCAG 2.2 AA) —, und sie
 * zwingt nicht dazu, den gesamten Text jeder Seite mitzuladen.
 */
public final class Handbuch {

    /** Ein Kapitel. Der Rumpf ist fertiges HTML; Suche arbeitet auf dem Klartext. */
    public record Kapitel(String kennung, String titel, String kurz,
                          Teil teil, String symbol, String inhalt) {

        /** Der Text ohne Markup — Grundlage für Suche und Trefferauszug. */
        public String klartext() {
            return inhalt.replaceAll("<[^>]*>", " ")
                         .replaceAll("&[a-z]+;", " ")
                         .replaceAll("\\s+", " ")
                         .trim();
        }
    }

    public enum Teil {
        VERSTEHEN("Verstehen", "Was der PIN-Roboter tut — und warum"),
        BEDIENEN("Bedienen", "Der Arbeitsalltag in der Praxis"),
        BETREIBEN("Betreiben", "Einrichten, sichern, Störungen beheben");

        private final String titel, unter;
        Teil(String t, String u) { titel = t; unter = u; }
        public String titel() { return titel; }
        public String unter() { return unter; }
    }

    /** Ein Suchtreffer mit Fundstelle im Text. */
    public record Treffer(Kapitel kapitel, String auszug, int stellen) { }

    private static final List<Kapitel> KAPITEL = new ArrayList<>();

    static {
        HandbuchInhalt.fuellen(KAPITEL);
    }

    private Handbuch() { }

    public static List<Kapitel> alle() { return List.copyOf(KAPITEL); }

    public static List<Kapitel> desTeils(Teil t) {
        return KAPITEL.stream().filter(k -> k.teil() == t).toList();
    }

    public static Kapitel finden(String kennung) {
        return KAPITEL.stream().filter(k -> k.kennung().equals(kennung))
                .findFirst().orElse(null);
    }

    /** Das erste Kapitel — Einstieg, wenn keines gewählt wurde. */
    public static Kapitel erstes() { return KAPITEL.get(0); }

    /** Das Kapitel davor und danach, für „Weiter“-Verweise. */
    public static Kapitel nachbar(String kennung, int richtung) {
        for (int i = 0; i < KAPITEL.size(); i++) {
            if (KAPITEL.get(i).kennung().equals(kennung)) {
                int j = i + richtung;
                return j >= 0 && j < KAPITEL.size() ? KAPITEL.get(j) : null;
            }
        }
        return null;
    }

    /**
     * Sucht in Titeln und Text.
     *
     * <h2>Warum kein ausgefeiltes Verfahren</h2>
     *
     * Es sind knapp zwei Dutzend Kapitel. Eine Volltextsuche mit Index wäre
     * mehr Technik, als der Inhalt trägt, und brächte eine Abhängigkeit für
     * ein Problem, das nicht existiert.
     *
     * <p>Zwei Dinge sind trotzdem nötig, damit die Suche taugt: <b>Treffer im
     * Titel wiegen schwerer</b> als solche im Fließtext, und jeder Treffer
     * zeigt seine <b>Umgebung</b>. Eine Trefferliste ohne Kontext zwingt dazu,
     * jeden Eintrag zu öffnen.
     */
    public static List<Treffer> suchen(String begriff) {
        if (begriff == null || begriff.isBlank()) return List.of();
        String b = begriff.trim().toLowerCase();
        if (b.length() < 2) return List.of();

        var treffer = new ArrayList<Treffer>();
        for (Kapitel k : KAPITEL) {
            String text = k.klartext();
            String klein = text.toLowerCase();
            boolean imTitel = k.titel().toLowerCase().contains(b)
                           || k.kurz().toLowerCase().contains(b);
            int stelle = klein.indexOf(b);
            if (!imTitel && stelle < 0) continue;

            int anzahl = 0;
            for (int i = klein.indexOf(b); i >= 0; i = klein.indexOf(b, i + 1)) anzahl++;

            treffer.add(new Treffer(k,
                    stelle < 0 ? kuerzen(text) : auszug(text, stelle, b.length()),
                    imTitel ? anzahl + 100 : anzahl));
        }
        treffer.sort((x, y) -> Integer.compare(y.stellen(), x.stellen()));
        return treffer;
    }

    /** Ein Stück Text um die Fundstelle, an Wortgrenzen abgeschnitten. */
    private static String auszug(String text, int stelle, int laenge) {
        int von = Math.max(0, stelle - 90);
        int bis = Math.min(text.length(), stelle + laenge + 130);
        // Nicht mitten im Wort beginnen — das liest sich wie ein Fehler.
        if (von > 0) {
            int leer = text.indexOf(' ', von);
            if (leer > 0 && leer < stelle) von = leer + 1;
        }
        if (bis < text.length()) {
            int leer = text.lastIndexOf(' ', bis);
            if (leer > stelle + laenge) bis = leer;
        }
        return (von > 0 ? "… " : "") + text.substring(von, bis)
             + (bis < text.length() ? " …" : "");
    }

    private static String kuerzen(String text) {
        return text.length() <= 200 ? text : text.substring(0, 197) + "…";
    }
}
