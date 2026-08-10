package de.pinroboter.simulator;

import java.util.*;
import java.util.concurrent.*;

/**
 * Ein simuliertes eHealth-Kartenterminal mit PEviM-Verhalten.
 *
 * <p>Bildet die Prüfkette aus [gemSpec_KT] A_24081 nach: Trifft ein
 * SICCT PERFORM VERIFICATION ein, nimmt das Terminal den PIN-Wert nur dann über
 * die Managementschnittstelle entgegen, wenn <em>alle</em> Bedingungen erfüllt
 * sind — PEviM aktiv, SMC-B im Slot, netzwerkbasierte Managementschnittstelle
 * aktiv, Rolle SMC-B_Nutzer authentisiert. Sonst PIN-Pad, und das gibt es hier
 * nicht: dann läuft die Verifikation in eine Zeitüberschreitung, genau wie in
 * der Wirklichkeit ohne anwesenden Menschen.
 */
public class SimuliertesTerminal {

    private final String id;
    private final String name;
    private final String modell;
    private String firmware;

    private volatile boolean pevimUnterstuetzt;
    private volatile boolean pevimAktiv;
    private volatile boolean erreichbar = true;
    private volatile boolean aktualisierend = false;
    private volatile int antwortverzoegerungMs = 0;

    /** Angemeldete Sitzungen der Rolle SMC-B_Nutzer (A_24075). */
    private final Set<String> smcbNutzerSitzungen = ConcurrentHashMap.newKeySet();

    /** Karten je Steckplatz. */
    private final Map<Integer, SimulierteKarte> karten = new ConcurrentHashMap<>();

    /** Offene Aufforderung: Das Terminal wartet auf einen PIN-Wert. */
    private final AtomicReferenceHolder offeneAufforderung = new AtomicReferenceHolder();

    public SimuliertesTerminal(String id, String name, String modell, String firmware,
                               boolean pevimUnterstuetzt, boolean pevimAktiv) {
        this.id = id; this.name = name; this.modell = modell; this.firmware = firmware;
        this.pevimUnterstuetzt = pevimUnterstuetzt;
        this.pevimAktiv = pevimUnterstuetzt && pevimAktiv;
    }

    static class AtomicReferenceHolder {
        volatile SynchronousQueue<String> warteschlange;
        volatile String kartenhandle;
    }

    // --- Rollenanmeldung an der Managementschnittstelle ---

    public String anmeldenAlsSmcbNutzer() {
        String sitzung = UUID.randomUUID().toString();
        smcbNutzerSitzungen.add(sitzung);
        return sitzung;
    }

    public void abmelden(String sitzung) { smcbNutzerSitzungen.remove(sitzung); }
    public boolean sitzungGueltig(String s) { return s != null && smcbNutzerSitzungen.contains(s); }
    public boolean smcbNutzerAngemeldet() { return !smcbNutzerSitzungen.isEmpty(); }

    /**
     * Prüfkette nach A_24081: Darf der PIN-Wert über die Managementschnittstelle
     * entgegengenommen werden?
     *
     * <p><b>Zwei Wege erfüllen die Bedingung „Rolle SMC-B_Nutzer authentisiert".</b>
     * Der eine ist die angemeldete Verbindung zur Remote SMCB API — so löst der
     * Hersteller es tatsächlich, und so arbeitet der Agent seit ADR-0011. Der
     * andere ist die alte HTTP-Sitzung; sie bleibt als Gegenprobe stehen, damit
     * ein Nachweis prüfen kann, was ohne Remote SMCB API passiert.
     */
    public boolean pevimGreift(SimulierteKarte karte) {
        if (!pevimAktiv || karte == null || !"SMC-B".equals(karte.kartentyp())) {
            return false;   // A_24080: nur SMC-B
        }
        var sitzung = SmcbApiNachbildung.sitzung(id);
        return (sitzung != null && sitzung.angemeldet()) || smcbNutzerAngemeldet();
    }

    /**
     * Wird vom simulierten Konnektor aufgerufen, wenn er ein
     * SICCT PERFORM VERIFICATION sendet. Wartet auf den PIN-Wert von der
     * Managementschnittstelle.
     *
     * @return der gelieferte PIN-Wert, oder {@code null} bei Zeitüberschreitung
     */
    public String pinAnfordern(String kartenhandle, long timeoutSekunden) throws InterruptedException {
        // Der Weg nach ADR-0011: Besteht eine angemeldete Verbindung zur
        // Remote SMCB API, FRAGT das Terminal dort — es wartet nicht darauf,
        // dass jemand einen Wert vorbeibringt.
        //
        // Der alte Weg darunter bleibt bestehen, aber nur noch als Gegenprobe:
        // Er bildet ein Terminal OHNE Remote SMCB API nach. Der Agent geht ihn
        // seit dem Umbau nicht mehr — verhält er sich doch so, fällt es auf.
        var sitzung = SmcbApiNachbildung.sitzung(id);
        if (sitzung != null && sitzung.angemeldet()) {
            int slot = kartenhandle == null ? 1 : slotVon(kartenhandle);
            char[] pin = SmcbApiNachbildung.pinAnfordern(id, "Slot" + (slot < 0 ? 1 : slot),
                    "3b8f", 6, 8, (int) timeoutSekunden);
            return pin == null ? null : new String(pin);
        }

        SynchronousQueue<String> q = new SynchronousQueue<>();
        offeneAufforderung.warteschlange = q;
        offeneAufforderung.kartenhandle = kartenhandle;
        try {
            return q.poll(timeoutSekunden, TimeUnit.SECONDS);
        } finally {
            offeneAufforderung.warteschlange = null;
            offeneAufforderung.kartenhandle = null;
        }
    }

    /** Liefert einen PIN-Wert an eine offene Aufforderung. */
    public boolean pinLiefern(String pin) throws InterruptedException {
        SynchronousQueue<String> q = offeneAufforderung.warteschlange;
        if (q == null) return false;
        return q.offer(pin, 5, TimeUnit.SECONDS);
    }

    public boolean wartetAufPin() { return offeneAufforderung.warteschlange != null; }

    // --- Karten ---

    public void karteEinlegen(int slot, SimulierteKarte k) { karten.put(slot, k); }
    public Collection<SimulierteKarte> alleKarten() { return karten.values(); }
    public Optional<SimulierteKarte> karte(String handle) {
        return karten.values().stream().filter(k -> k.kartenhandle().equals(handle)).findFirst();
    }

    /**
     * Der Steckplatz einer Karte.
     *
     * <p>Gebraucht, seit das Terminal die PIN selbst anfragt (ADR-0011): Die
     * Anfrage nennt einen Slot, der Konnektor kennt Kartenhandles. Ohne diese
     * Zuordnung wüsste der Agent bei zwei Karten im Gerät nicht, welche PIN
     * gemeint ist — und eine falsch zugeordnete PIN kostet einen Fehlversuch.
     *
     * @return die Slotnummer, oder -1 wenn die Karte nicht in diesem Gerät steckt
     */
    public int slotVon(String kartenhandle) {
        return karten.entrySet().stream()
                .filter(e -> e.getValue().kartenhandle().equals(kartenhandle))
                .mapToInt(Map.Entry::getKey).findFirst().orElse(-1);
    }

    /** Firmware-Update: Neustart entzieht der SMC-B den verifizierten Zustand. */
    public void firmwareEinspielen(String neueVersion) throws InterruptedException {
        firmwareEinspielen(neueVersion, 2000);
    }

    /**
     * @param dauerMs wie lange der Zustand „aktualisierend" anhält. Für Tests
     *                bewusst einstellbar: Nur wenn das Update länger dauert als
     *                das Prüfintervall des Agenten, trifft dieser den kritischen
     *                Moment zwangsläufig — sonst ist ein bestandener Test bloß
     *                Zufall.
     */
    public void firmwareEinspielen(String neueVersion, long dauerMs) throws InterruptedException {
        aktualisierend = true;
        try {
            Thread.sleep(dauerMs);
            this.firmware = neueVersion;
            karten.values().forEach(SimulierteKarte::verifikationEntziehen);
        } finally {
            aktualisierend = false;
        }
    }

    // --- Zugriff ---
    public String id() { return id; }
    public String name() { return name; }
    public String modell() { return modell; }
    public String firmware() { return firmware; }
    public boolean pevimUnterstuetzt() { return pevimUnterstuetzt; }
    public boolean pevimAktiv() { return pevimAktiv; }
    public void pevimAktivSetzen(boolean a) { this.pevimAktiv = pevimUnterstuetzt && a; }
    public boolean erreichbar() { return erreichbar; }
    public void erreichbarSetzen(boolean e) { this.erreichbar = e; }
    public boolean aktualisierend() { return aktualisierend; }

    /**
     * Setzt den Zustand „aktualisierend" von außen.
     *
     * <p>Für den <b>konnektorweiten</b> KSR-Lauf ({@code [gemSpec_Kon] 4.3.10}):
     * Dort aktualisiert der Konnektor über seinen KSR-Client mehrere Terminals
     * nacheinander, und der Kartenterminaldienst meldet währenddessen für die
     * betroffenen Geräte diesen Zustand. Das ist etwas anderes als ein
     * einzelnes Firmware-Update über {@link #firmwareEinspielen} — und der
     * Roboter muss beides gleich behandeln: <b>keine PIN-Verifikation</b>,
     * sonst belastet er im ungünstigen Fall den Fehlversuchszähler der SMC-B.
     */
    public void aktualisierendSetzen(boolean an) { this.aktualisierend = an; }
    public int antwortverzoegerungMs() { return antwortverzoegerungMs; }
    public void antwortverzoegerungSetzen(int ms) { this.antwortverzoegerungMs = ms; }
}
