package de.pinroboter.simulator;

import de.pinroboter.gemeinsam.Demoszenario;

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

    // ------------------------------------------------------------- Demoaufbau

    /**
     * Bildet die Geräte des Demoszenarios nach (Vorgabe PO, 11.08.2026).
     *
     * <p>Ein MVZ mit neun Betriebsstätten unterschiedlicher Größe. Welche das
     * sind, steht in {@link de.pinroboter.gemeinsam.Demoszenario} — derselben
     * Beschreibung, aus der die Zentrale ihren Bestand anlegt. Zwei gepflegte
     * Listen liefen irgendwann auseinander; eine geteilte kann es nicht.
     *
     * <p>Am Empfang jeder Betriebsstätte steckt die SMC-B, die
     * Behandlungsterminals lesen nur eGK. Alle Geräte sind erreichbar, laufen
     * auf der neuesten Firmware und haben die Managementschnittstelle
     * freigeschaltet.
     *
     * <p><b>Statt des Standardaufbaus, nicht zusätzlich.</b> Eingeschaltet wird
     * über {@code DEMO_TERMINALS=an}; das setzt {@code docker-compose.yml}, der
     * Prüfstand nicht. Zwei Gründe:
     *
     * <ul>
     *   <li>Die Systemnachweise messen gegen den Standardaufbau und dessen
     *       Fehlerfälle, die es hier bewusst nicht gibt. 36 weitere Geräte
     *       würden diese Zählungen verschieben.</li>
     *   <li>Umgekehrt landen die vier Problemgeräte des Standardaufbaus in der
     *       Einrichtung, für die der Agent registriert ist. Sie stünden dort
     *       ohne Bezug herum und widersprächen dem, was das Szenario zeigen
     *       soll.</li>
     * </ul>
     */
    public static Zustand mitDemoteilnehmern(Zustand z) {
        int lauf = 0;
        for (var b : Demoszenario.BETRIEBSSTAETTEN) {
            lauf++;
            SimuliertesTerminal empfang = new SimuliertesTerminal(
                    b.empfangsterminal(), "Empfang",
                    Demoszenario.MODELL, Demoszenario.FIRMWARE, true, true);
            empfang.karteEinlegen(1, new SimulierteKarte(
                    b.kartenhandle(), iccsn(lauf), "SMC-B", Demoszenario.PIN, "2028-12-31"));
            z.terminalHinzufuegen(empfang);

            for (int i = 1; i <= b.behandlungsraeume(); i++) {
                // Keine SMC-B — hier gibt es nichts freizuschalten. Das Gerät
                // wird überwacht und gemeldet, mehr nicht.
                z.terminalHinzufuegen(new SimuliertesTerminal(
                        b.behandlungsterminal(i), "Behandlung " + i,
                        Demoszenario.MODELL, Demoszenario.FIRMWARE, true, true));
            }
        }
        return z;
    }

    /** Eine ICCSN, die es zweimal nicht gibt: 20 Stellen, je Karte verschieden. */
    private static String iccsn(int lauf) {
        return "8027600101169990%04d".formatted(1100 + lauf);
    }
}
