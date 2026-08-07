package de.pinroboter.simulator;

import java.util.*;

/**
 * Informationsmodell des simulierten Konnektors (FA-GER-035).
 *
 * <p><b>Wichtige Einordnung:</b> Anders als die Fachschnittstelle ist diese
 * Schnittstelle <b>von niemandem spezifiziert</b> — sie ist herstellerspezifisch
 * (CLAUDE.md 8, „Unterstützte Konnektoren"). Der Simulator bildet hier nach, was
 * ein KoCo-Konnektor über seine Webadmin-Oberfläche zugänglich macht. Das ist
 * nach ADR-0007 Regel 1 eine <b>Annahme</b> und gehört auf die Prüfliste für den
 * ersten Hardwarekontakt.
 *
 * <p><b>Warum das Modell trotzdem gebraucht wird:</b> Jeder Aufruf der
 * Fachschnittstelle benötigt einen Aufrufkontext aus Mandanten-ID,
 * Clientsystem-ID und Arbeitsplatz-ID ({@code TUC_KON_000}). Werden diese Werte
 * abgetippt, ist das die häufigste und am schwersten zu diagnostizierende
 * Fehlerquelle bei der Inbetriebnahme — ein Tippfehler äußert sich als
 * Zugriffsfehler ohne erkennbaren Zusammenhang.
 *
 * <p>Das Modell wird deshalb ausgelesen und <b>zur Auswahl angeboten</b>.
 */
public final class Informationsmodell {

    public record Arbeitsplatz(String id, String name, List<String> terminalIds) {}
    public record Clientsystem(String id, String name) {}
    public record Mandant(String id, String name, List<String> clientsystemIds,
                          List<String> arbeitsplatzIds) {}

    private final List<Mandant> mandanten;
    private final List<Clientsystem> clientsysteme;
    private final List<Arbeitsplatz> arbeitsplaetze;

    private Informationsmodell(List<Mandant> m, List<Clientsystem> c, List<Arbeitsplatz> a) {
        this.mandanten = m; this.clientsysteme = c; this.arbeitsplaetze = a;
    }

    /**
     * Baut ein Modell, das zu den simulierten Terminals passt.
     *
     * <p>Absichtlich <b>nicht</b> vollständig deckungsgleich mit dem, was ein
     * Bestand später enthält: Ein Arbeitsplatz führt zwei Terminals, ein
     * Terminal hängt an keinem Arbeitsplatz. Ein Simulator, der nur saubere
     * Fälle kennt, erzeugt trügerische Sicherheit (ADR-0007).
     */
    public static Informationsmodell standardaufbau(Zustand zustand) {
        List<String> alle = zustand.terminals().stream().map(SimuliertesTerminal::id).sorted().toList();

        List<Arbeitsplatz> ap = new ArrayList<>();
        ap.add(new Arbeitsplatz("AP-EMPFANG", "Empfang",
                alle.stream().filter(id -> id.contains("EMPFANG")).toList()));
        // Ein Arbeitsplatz mit ZWEI Terminals - der Fall existiert in der Praxis
        // und bricht jede Annahme "ein Arbeitsplatz, ein Geraet".
        ap.add(new Arbeitsplatz("AP-LABOR", "Labor",
                alle.stream().filter(id -> id.contains("LABOR") || id.contains("BEHANDLUNG")).toList()));
        // Und einer ganz ohne Terminal.
        ap.add(new Arbeitsplatz("AP-VERWALTUNG", "Verwaltung", List.of()));

        return new Informationsmodell(
                List.of(new Mandant("Mandant-1", "Praxis Hauptstandort",
                                List.of("CS-PVS", "CS-ROBOTER"),
                                List.of("AP-EMPFANG", "AP-LABOR", "AP-VERWALTUNG"))),
                List.of(new Clientsystem("CS-PVS", "Praxisverwaltungssystem"),
                        new Clientsystem("CS-ROBOTER", "PIN-Roboter")),
                ap);
    }

    /**
     * Liefert das Modell als JSON.
     *
     * <p>Von Hand erzeugt statt über eine Bibliothek — die Struktur ist klein
     * und fest, und eine zusätzliche Abhängigkeit im Simulator wäre nicht zu
     * rechtfertigen (CLAUDE.md 3.2).
     */
    public String alsJson() {
        StringBuilder s = new StringBuilder("{\"mandanten\":[");
        for (int i = 0; i < mandanten.size(); i++) {
            var m = mandanten.get(i);
            if (i > 0) s.append(',');
            s.append("{\"id\":\"").append(m.id()).append("\",\"name\":\"").append(m.name())
             .append("\",\"clientsysteme\":").append(liste(m.clientsystemIds()))
             .append(",\"arbeitsplaetze\":").append(liste(m.arbeitsplatzIds())).append('}');
        }
        s.append("],\"clientsysteme\":[");
        for (int i = 0; i < clientsysteme.size(); i++) {
            var c = clientsysteme.get(i);
            if (i > 0) s.append(',');
            s.append("{\"id\":\"").append(c.id()).append("\",\"name\":\"").append(c.name()).append("\"}");
        }
        s.append("],\"arbeitsplaetze\":[");
        for (int i = 0; i < arbeitsplaetze.size(); i++) {
            var a = arbeitsplaetze.get(i);
            if (i > 0) s.append(',');
            s.append("{\"id\":\"").append(a.id()).append("\",\"name\":\"").append(a.name())
             .append("\",\"terminals\":").append(liste(a.terminalIds())).append('}');
        }
        return s.append("]}").toString();
    }

    private static String liste(List<String> werte) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < werte.size(); i++) {
            if (i > 0) s.append(',');
            s.append('"').append(werte.get(i)).append('"');
        }
        return s.append(']').toString();
    }
}
