package de.pinroboter.zentrale;

/**
 * Die Piktogramme der Oberfläche — an einer Stelle, nicht verstreut.
 *
 * <h2>Warum eine eigene Klasse</h2>
 *
 * Vor dem 12.08.2026 standen SVG-Pfade dort, wo man sie gerade brauchte: in
 * {@code Zentrale}, in {@code Bedienelemente}, in einzelnen Vorlagen. Dieselbe
 * Überlegung wie bei {@link Bedienelemente}: Was an jeder Stelle einzeln
 * gepflegt wird, weicht an jeder Stelle ein wenig ab — andere Strichstärke,
 * anderes Raster, mal mit und mal ohne {@code aria-hidden}.
 *
 * <h2>Die Regeln</h2>
 *
 * <ul>
 *   <li><b>Raster 24×24</b>, Strichstärke 1.7, runde Enden. Ein Symbol, das aus
 *       der Reihe fällt, sieht auch dann falsch aus, wenn es für sich genommen
 *       schön ist.</li>
 *   <li><b>{@code currentColor}</b> — die Farbe kommt vom Text, damit ein Symbol
 *       im hellen wie im dunklen Modus und in einer farbigen Marke stimmt.</li>
 *   <li><b>Immer {@code aria-hidden="true"}</b>. Ein Piktogramm ist Schmuck; die
 *       Bedeutung steht daneben im Text. Ein Screenreader, der „Grafik Haus"
 *       vorliest, stört nur — und WCAG 1.1.1 verlangt keine Beschreibung für
 *       rein dekorative Bilder.</li>
 *   <li><b>Keine Bilddateien, keine Icon-Schrift.</b> Das Produkt läuft
 *       on-premise ohne Internet; ein Symbol von einem fremden Server wäre
 *       zugleich ein Datenabfluss und ein Ausfallrisiko.</li>
 * </ul>
 *
 * <p>Die Symbole sind bewusst <b>gegenständlich</b> gewählt: Ein Kartenterminal
 * sieht aus wie ein Kartenterminal. Wer in einer Praxis arbeitet, erkennt seine
 * Geräte wieder, ohne eine Legende zu lernen.
 */
final class Symbole {

    private Symbole() { }

    /** Gemeinsamer Rahmen aller Symbole. */
    private static String svg(String inhalt) {
        return "<svg class=\"sinnbild\" viewBox=\"0 0 24 24\" fill=\"none\" "
                + "stroke=\"currentColor\" stroke-width=\"1.7\" stroke-linecap=\"round\" "
                + "stroke-linejoin=\"round\" aria-hidden=\"true\">" + inhalt + "</svg>";
    }

    // ---------------------------------------------------- Organisationsebenen

    /** Trägergesellschaft — mehrere Häuser unter einem Dach. */
    static String verbund() {
        return svg("""
                <path d="M3 21h18"/>
                <path d="M5 21V9l4-2.5V21"/>
                <path d="M9 21V4.5L15 8v13"/>
                <path d="M15 21v-8l4 2.5V21"/>
                <path d="M11.6 11.5h1.8M12.5 10.6v1.8"/>""");
    }

    /** Leistungserbringer — die zugelassene Organisation, Haus mit Ausweis. */
    static String leistungserbringer() {
        return svg("""
                <path d="M4 21V8.5L12 4l8 4.5V21"/>
                <path d="M3 21h18"/>
                <rect x="9" y="12" width="6" height="5" rx="1"/>
                <path d="M10.6 14.5h2.8"/>""");
    }

    /** Standort — die Ortsmarke, wie auf jeder Karte. */
    static String standort() {
        return svg("""
                <path d="M12 21s7-5.6 7-11a7 7 0 1 0-14 0c0 5.4 7 11 7 11Z"/>
                <circle cx="12" cy="10" r="2.6"/>""");
    }

    /** Betriebsstätte — die Praxis: Haus mit medizinischem Kreuz. */
    static String betriebsstaette() {
        return svg("""
                <path d="M4 21V9.6L12 4l8 5.6V21"/>
                <path d="M3 21h18"/>
                <path d="M12 11.4v5.2M9.4 14h5.2"/>""");
    }

    /** Fachbereich — eine Abteilung innerhalb der Praxis. */
    static String fachbereich() {
        return svg("""
                <path d="M6 21V6a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v15"/>
                <path d="M4 21h16"/>
                <path d="M10 8.6h4M12 6.6v4"/>
                <circle cx="15.2" cy="15.5" r="1.1"/>""");
    }

    // ------------------------------------------------------------ Technik

    /** Konnektor — das Gerät im Serverschrank. */
    static String konnektor() {
        return svg("""
                <rect x="3" y="8" width="18" height="8" rx="2"/>
                <path d="M7 12h.01M10 12h.01"/>
                <path d="M15 12h3"/>
                <path d="M7 16v3M17 16v3"/>""");
    }

    /** Highspeed-Konnektor — dasselbe, aber im Rechenzentrum. */
    static String hsk() {
        return svg("""
                <path d="M7.5 10.5a4.5 4.5 0 1 1 8.7-1.6A3.6 3.6 0 0 1 18 16H8a4 4 0 0 1-.5-5.5Z"/>
                <rect x="8" y="17.5" width="8" height="3.5" rx="1"/>
                <path d="M10.5 19.2h.01M13 19.2h.01"/>""");
    }

    /** Kartenterminal — Display, Tastenfeld, Kartenschlitz. */
    static String kartenterminal() {
        return svg("""
                <rect x="6" y="2.6" width="12" height="18.8" rx="2.2"/>
                <rect x="8.4" y="5.2" width="7.2" height="4.6" rx="1"/>
                <path d="M9 13h.01M12 13h.01M15 13h.01M9 16h.01M12 16h.01M15 16h.01"/>
                <path d="M9.6 19h4.8"/>""");
    }

    /** SMC-B — die Institutionskarte mit ihrem Chip. */
    static String smcb() {
        return svg("""
                <rect x="2.6" y="5" width="18.8" height="14" rx="2.2"/>
                <rect x="5.6" y="8.4" width="5.4" height="4.4" rx="1"/>
                <path d="M8.3 8.4v4.4M5.6 10.6h5.4"/>
                <path d="M14 9.6h4.4M14 12.6h4.4M14 15.4h3"/>""");
    }

    /** Gerätegruppe — mehrere Geräte mit einem gemeinsamen Takt. */
    static String gruppe() {
        return svg("""
                <rect x="3" y="3.6" width="7" height="7" rx="1.6"/>
                <rect x="14" y="3.6" width="7" height="7" rx="1.6"/>
                <rect x="3" y="13.4" width="7" height="7" rx="1.6"/>
                <path d="M17.5 13.4v7M14 16.9h7"/>""");
    }

    /** Eine Liste — für den Umschalter zwischen den Ansichten. */
    static String liste() {
        return svg("""
                <path d="M8.5 6.5h11M8.5 12h11M8.5 17.5h11"/>
                <path d="M4.5 6.5h.01M4.5 12h.01M4.5 17.5h.01"/>""");
    }

    /** Herunterladen — Pfeil in eine Ablage. */
    static String herunterladen() {
        return svg("""
                <path d="M12 3.6v11.2"/>
                <path d="m7.6 10.4 4.4 4.4 4.4-4.4"/>
                <path d="M4.5 17.4v1.6a1.6 1.6 0 0 0 1.6 1.6h11.8a1.6 1.6 0 0 0 1.6-1.6v-1.6"/>""");
    }

    /** Benutzerkonto. */
    static String benutzer() {
        return svg("""
                <circle cx="12" cy="8" r="3.6"/>
                <path d="M4.8 20.4a7.2 7.2 0 0 1 14.4 0"/>""");
    }

    // ------------------------------------------------------------ Zustände

    /** Alles in Ordnung. */
    static String ok() {
        return svg("<path d=\"m4.5 12.5 5 5 10-11\"/>");
    }

    /** Aufmerksamkeit nötig, aber kein Ausfall. */
    static String warnung() {
        return svg("""
                <path d="M12 3.6 1.8 20.4h20.4L12 3.6Z"/>
                <path d="M12 10v4.4M12 17.3h.01"/>""");
    }

    /** Etwas ist ausgefallen. */
    static String fehler() {
        return svg("""
                <circle cx="12" cy="12" r="9"/>
                <path d="M12 7.4v5.2M12 16.2h.01"/>""");
    }

    /** Zustand noch nicht bekannt. */
    static String unbekannt() {
        return svg("""
                <circle cx="12" cy="12" r="9" stroke-dasharray="3 3"/>
                <path d="M9.6 9.6a2.5 2.5 0 1 1 3.4 2.3c-.7.3-1 .9-1 1.6"/>
                <path d="M12 16.6h.01"/>""");
    }

    // ------------------------------------------------------------ Zugriff

    /**
     * Das Symbol zu einer Organisationsart.
     *
     * <p>Unbekannte Arten bekommen das Symbol der Betriebsstätte statt einer
     * Lücke: Eine fehlende Grafik sieht aus wie ein Fehler, obwohl nur eine
     * Ebene dazugekommen ist.
     */
    static String zuArt(String art) {
        return switch (art == null ? "" : art) {
            case "VERBUND"            -> verbund();
            case "LEISTUNGSERBRINGER" -> leistungserbringer();
            case "STANDORT"           -> standort();
            case "FACHBEREICH"        -> fachbereich();
            default                   -> betriebsstaette();
        };
    }

    /** Das Symbol zu einer Ampelfarbe. */
    static String zuAmpel(de.pinroboter.gemeinsam.Ampel a) {
        return switch (a) {
            case OK      -> ok();
            case WARNUNG -> warnung();
            case FEHLER  -> fehler();
            default      -> unbekannt();
        };
    }
}
