package de.pinroboter.gemeinsam;

import java.util.List;

/**
 * Das Demoszenario: ein medizinisches Versorgungszentrum, wie es sie gibt.
 *
 * <h2>Warum diese Beschreibung in {@code gemeinsam} liegt</h2>
 *
 * Zwei Bausteine brauchen dieselben Angaben: Die <b>Zentrale</b> legt daraus
 * den Bestand an (Organisationen, Konnektoren, Terminals), der <b>Simulator</b>
 * bildet dieselben Geräte nach, damit sie antworten. Stimmten die Kennungen
 * nicht überein, meldete der Agent Geräte, die zu keiner Betriebsstätte
 * gehören — und neben jedem Demogerät stünde ein zweites, unbekanntes.
 *
 * <p>Zwei gepflegte Listen laufen irgendwann auseinander. Eine geteilte kann es
 * nicht. Dieselbe Überlegung wie bei {@link SmcbProtokoll}.
 *
 * <h2>Was das Szenario abbilden soll</h2>
 *
 * Ein MVZ ist kein Verbund gleichförmiger Praxen. Es hat einen Träger, mehrere
 * <b>Betriebsstätten</b> an verschiedenen Standorten, gemischte Fachrichtungen
 * und sehr unterschiedliche Größen — von der Zweigstelle mit zwei Geräten bis
 * zur hausärztlichen Hauptbetriebsstätte mit sechs.
 *
 * <p><b>Fachlich entscheidend:</b> Die SMC-B ist die Institutionskarte einer
 * Betriebsstätte, nicht die Karte eines Arztes. Jede Betriebsstätte hat deshalb
 * genau <b>eine</b>, und sie steckt am Empfang. Die Geräte in den
 * Behandlungsräumen lesen eGK und brauchen keine. Für die PIN-Automatik ist
 * damit je Betriebsstätte genau ein Terminal von Belang — die übrigen werden
 * überwacht, haben aber nichts freizuschalten.
 *
 * <p>Alle Namen sind erfunden. „Musterstadt" macht das an jeder Stelle sichtbar.
 */
public final class Demoszenario {

    private Demoszenario() { }

    /** Der Träger — die oberste Ebene der Organisationsstruktur. */
    public static final String TRAEGER = "MVZ Gesundheitszentrum Musterstadt";

    /**
     * Ein Fachbereich innerhalb einer Betriebsstätte (ADR-0012).
     *
     * <p>Er hat <b>keine eigene SMC-B</b> — er teilt sich die seiner
     * Betriebsstätte. Er ist eine Gliederung des Geräteparks, keine eigene
     * Leistungserbringerinstitution.
     *
     * @param name  Anzeigename
     * @param von   erster Behandlungsraum, der dazugehört (1-basiert)
     * @param bis   letzter Behandlungsraum, einschließlich
     */
    public record Fachbereich(String name, int von, int bis) { }

    /**
     * Eine Betriebsstätte des MVZ.
     *
     * @param kuerzel     eindeutige Kurzform, geht in Terminalkennungen und
     *                    Kartenhandles ein ({@code KT-NHAU-EMP},
     *                    {@code card-smcb-NHAU})
     * @param standort    organisatorische Zwischenebene (Nord, Mitte, Süd)
     * @param name        Anzeigename, wie ihn das Personal kennt
     * @param fach        Fachrichtung, für die Anzeige und den Demo-Benutzer
     * @param behandlungsraeume Zahl der Terminals ohne SMC-B. Bewusst
     *                    unterschiedlich: Eine Oberfläche, die mit vier Geräten
     *                    je Einheit gut aussieht, sagt nichts über eine
     *                    Betriebsstätte mit sieben und eine mit einem.
     * @param laufnummer  1-basiert, geht in BSNR und Telematik-ID ein. Abgeleitet
     *                    statt einzeln gepflegt: Zwei Listen laufen auseinander.
     * @param imRechenzentrum {@code true}, wenn diese Betriebsstätte keinen
     *                    eigenen Konnektor hat, sondern den zentralen des
     *                    Trägers mitnutzt (ADR-0012, „Konnektor as a Service")
     * @param fachbereiche Gliederung der Behandlungsräume. Meist leer — die
     *                    Ebene ist optional, und die meisten Betriebsstätten
     *                    brauchen sie nicht.
     */
    public record Betriebsstaette(String kuerzel, String standort, String name,
                                  String fach, int behandlungsraeume, int laufnummer,
                                  boolean imRechenzentrum, List<Fachbereich> fachbereiche) {

        /** Kurzform ohne Fachbereiche und ohne Rechenzentrum. */
        public Betriebsstaette(String kuerzel, String standort, String name, String fach,
                               int behandlungsraeume, int laufnummer) {
            this(kuerzel, standort, name, fach, behandlungsraeume, laufnummer, false, List.of());
        }

        /**
         * Betriebsstättennummer — neun Ziffern, frei erfunden.
         *
         * <p>Die führende {@code 72} steht in der Wirklichkeit für einen
         * KV-Bezirk; welcher, spielt hier keine Rolle. Die Form stimmt, der
         * Wert nicht — genau das soll ein Demoaufbau leisten.
         */
        public String bsnr() { return "7299%05d".formatted(laufnummer); }

        /** Telematik-ID der SMC-B dieser Betriebsstätte — ebenfalls erfunden. */
        public String telematikId() {
            return "1-SMC-B-Testkarte-8831100%06d".formatted(laufnummer);
        }

        /** Der Fachbereich eines Behandlungsraums, sofern es welche gibt. */
        public String fachbereichVon(int raum) {
            return fachbereiche.stream()
                    .filter(f -> raum >= f.von() && raum <= f.bis())
                    .map(Fachbereich::name).findFirst().orElse(null);
        }

        /** Kennung des Empfangsterminals — hier steckt die SMC-B. */
        public String empfangsterminal() { return "KT-" + kuerzel + "-EMP"; }

        /** Kennung eines Behandlungsterminals (1-basiert). */
        public String behandlungsterminal(int nummer) {
            return "KT-" + kuerzel + "-B" + nummer;
        }

        /** Kartenhandle der SMC-B dieser Betriebsstätte. */
        public String kartenhandle() { return "card-smcb-" + kuerzel; }

        /** Terminals insgesamt: Empfang plus Behandlungsräume. */
        public int terminals() { return 1 + behandlungsraeume; }

        /** Benutzername des Demo-Kontos für diese Betriebsstätte. */
        public String benutzername() { return "praxis." + kuerzel.toLowerCase(); }
    }

    /**
     * Die neun Betriebsstätten.
     *
     * <p>Die Mischung ist bewusst gewählt: zwei hausärztliche Betriebsstätten
     * als größte Einheiten (so sieht es in der Wirklichkeit aus), daneben
     * Fachrichtungen mit kleinem Gerätebestand wie Radiologie. Zusammen
     * <b>36 Terminals</b> — genug, um Suche, Filter und Paginierung zu
     * beurteilen, und wenig genug, um im Kopf nachvollziehbar zu bleiben.
     */
    public static final List<Betriebsstaette> BETRIEBSSTAETTEN = List.of(
            // ── Standort Nord ────────────────────────────────────────────────
            new Betriebsstaette("NHAU", "Nord", "Hausarztpraxis Nordstadt",
                    "Allgemeinmedizin", 5, 1),
            new Betriebsstaette("NKAR", "Nord", "Kardiologie am Nordring",
                    "Kardiologie", 3, 2),
            // Zwei Geraete, kein eigener Konnektor: Diese Betriebsstaette haengt
            // am zentralen Konnektor des Traegers (ADR-0012).
            new Betriebsstaette("NORT", "Nord", "Orthopädie Nordpark",
                    "Orthopädie", 2, 3, true, List.of()),

            // ── Standort Mitte ───────────────────────────────────────────────
            new Betriebsstaette("MHAU", "Mitte", "Hausärztliche Praxis am Markt",
                    "Allgemeinmedizin", 4, 4),
            new Betriebsstaette("MINN", "Mitte", "Innere Medizin Stadtmitte",
                    "Innere Medizin", 3, 5),
            new Betriebsstaette("MRAD", "Mitte", "Radiologie Musterstadt",
                    "Radiologie", 1, 6, true, List.of()),

            // ── Standort Süd ─────────────────────────────────────────────────
            new Betriebsstaette("SHAU", "Süd", "Hausarztpraxis Südhang",
                    "Allgemeinmedizin", 4, 7),
            new Betriebsstaette("SGYN", "Süd", "Gynäkologie Südpark",
                    "Gynäkologie", 2, 8, true, List.of()),
            // Die einzige Betriebsstaette mit Fachbereichen. Bewusst nur eine:
            // Die Ebene ist optional, und ein Demoaufbau, in dem sie ueberall
            // vorkommt, taeuscht eine Pflicht vor, die es nicht gibt.
            new Betriebsstaette("SCHI", "Süd", "Chirurgie und Unfallambulanz Süd",
                    "Chirurgie", 3, 9, false,
                    List.of(new Fachbereich("Unfallambulanz", 1, 2),
                            new Fachbereich("Sprechstunde Chirurgie", 3, 3))));

    /**
     * Der zentrale Konnektor des Trägers.
     *
     * <p>Drei kleine Betriebsstätten teilen ihn sich — der Fall, für den
     * ADR-0012 die n:m-Beziehung eingeführt hat. Wirtschaftlich lohnt ein
     * eigener Konnektor für zwei Kartenterminals nicht.
     */
    public static final String RZ_KONNEKTOR = "Konnektor Rechenzentrum (zentral)";

    /** Die Betriebsstätten, die den zentralen Konnektor mitnutzen. */
    public static List<Betriebsstaette> imRechenzentrum() {
        return BETRIEBSSTAETTEN.stream().filter(Betriebsstaette::imRechenzentrum).toList();
    }

    /** Die Standorte in fester Reihenfolge — die Zwischenebene über den Praxen. */
    public static final List<String> STANDORTE = List.of("Nord", "Mitte", "Süd");

    /** Betriebsstätten eines Standorts. */
    public static List<Betriebsstaette> anStandort(String standort) {
        return BETRIEBSSTAETTEN.stream().filter(b -> b.standort().equals(standort)).toList();
    }

    /** Terminals insgesamt — die Zahl, gegen die Nachweise messen. */
    public static int terminalsGesamt() {
        return BETRIEBSSTAETTEN.stream().mapToInt(Betriebsstaette::terminals).sum();
    }

    /** Die neueste Firmware im Demoaufbau. Alle Demogeräte laufen darauf. */
    public static final String FIRMWARE = "4.3.0";

    /** Modell aller Demogeräte — eines mit Remote-PIN-Unterstützung. */
    public static final String MODELL = "CHERRY ST-1506";

    /**
     * Die PIN aller Demokarten.
     *
     * <p>Kein Geheimnis: Diese Karten gibt es nur im Simulator. Sie steht
     * trotzdem an genau einer Stelle, damit sie nicht an mehreren gepflegt
     * werden muss.
     */
    public static final String PIN = "123456";
}
