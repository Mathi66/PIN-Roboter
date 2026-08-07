package de.pinroboter.simulator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Gesamtzustand der simulierten Umgebung. */
public class Zustand {

    private final Map<String, SimuliertesTerminal> terminals = new ConcurrentHashMap<>();

    public record Fund(SimuliertesTerminal terminal, SimulierteKarte karte) {}

    public void terminalHinzufuegen(SimuliertesTerminal t) { terminals.put(t.id(), t); }
    public Collection<SimuliertesTerminal> terminals() { return terminals.values(); }
    public Optional<SimuliertesTerminal> terminal(String id) { return Optional.ofNullable(terminals.get(id)); }

    public Optional<Fund> findeKarte(String kartenhandle) {
        for (SimuliertesTerminal t : terminals.values()) {
            var k = t.karte(kartenhandle);
            if (k.isPresent()) return Optional.of(new Fund(t, k.get()));
        }
        return Optional.empty();
    }

    /**
     * Baut eine Ausgangslage auf, die die wichtigsten Fälle abdeckt — nicht nur
     * den Erfolgsfall. Ein Simulator, der nur gelingende Abläufe kennt, erzeugt
     * trügerische Sicherheit (ADR-0007).
     */
    public static Zustand standardaufbau() {
        Zustand z = new Zustand();

        // Regelfall: modernes Terminal, PEviM vorhanden und aktiviert
        SimuliertesTerminal t1 = new SimuliertesTerminal(
                "KT-EMPFANG-01", "Empfang", "CHERRY ST-1506", "4.2.1", true, true);
        t1.karteEinlegen(1, new SimulierteKarte("card-smcb-001", "80276001011699901101",
                "SMC-B", "123456", "2027-06-30"));
        // eGK und HBA stecken in der Praxis regelmaessig mit im Terminal. Sie
        // sind fuer PEviM AUSDRUECKLICH unzulaessig (A_24080) - der Roboter
        // muss sie erkennen und in Ruhe lassen. Ein Simulator ohne sie wuerde
        // diesen Fall nie pruefen.
        t1.karteEinlegen(2, new SimulierteKarte("card-egk-001", "80276883110000123456",
                "EGK", null, "2030-03-31"));
        t1.karteEinlegen(3, new SimulierteKarte("card-hba-001", "80276001081234567890",
                "HBA", null, "2028-09-30"));
        z.terminalHinzufuegen(t1);

        // Zweites Terminal, ebenfalls betriebsbereit — für Nebenläufigkeitstests
        SimuliertesTerminal t2 = new SimuliertesTerminal(
                "KT-LABOR-01", "Labor", "CHERRY ST-1506", "4.2.1", true, true);
        t2.karteEinlegen(1, new SimulierteKarte("card-smcb-002", "80276001011699901102", "SMC-B", "654321"));
        z.terminalHinzufuegen(t2);

        // PEviM wird unterstützt, ist aber nicht aktiviert: A_24076/A_24077 —
        // im Auslieferungszustand und nach Werksreset deaktiviert. Die Oberfläche
        // muss das erklären und zur Aktivierung anleiten (FA-UEB-040).
        SimuliertesTerminal t3 = new SimuliertesTerminal(
                "KT-BEHANDLUNG-02", "Behandlungsraum 2", "CHERRY ST-1506", "4.1.0", true, false);
        t3.karteEinlegen(1, new SimulierteKarte("card-smcb-003", "80276001011699901103", "SMC-B", "111222"));
        z.terminalHinzufuegen(t3);

        // Älteres Gerät ohne PEviM: wird überwacht, aber nicht automatisiert (A_24074)
        SimuliertesTerminal t4 = new SimuliertesTerminal(
                "KT-ARCHIV-01", "Archiv", "Ingenico ORGA 6141", "3.7.4", false, false);
        t4.karteEinlegen(1, new SimulierteKarte("card-smcb-004", "80276001011699901104", "SMC-B", "333444"));
        z.terminalHinzufuegen(t4);

        return z;
    }
}
