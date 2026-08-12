package de.pinroboter.zentrale;

import java.sql.*;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Löst den Konflikt zwischen Unveränderlichkeit und Löschbegehren.
 *
 * <h2>Der Konflikt</h2>
 *
 * Das Fachprotokoll ist <b>unveränderlich</b> (`FA-PRO-010`) — es ist der
 * Nachweis über das selbsttätige Handeln des Roboters. Gleichzeitig enthält es
 * einen Personenbezug: den Anzeigenamen dessen, der eine Handlung ausgelöst hat.
 *
 * <p>Art. 17 DSGVO gibt dieser Person ein Recht auf Löschung. Ein Protokoll, das
 * sich nicht ändern lässt, kann das nicht erfüllen. In
 * `docs/05-sicherheit-datenschutz.md` stand der Konflikt bis zum 07.08.2026 als
 * <i>real und ungelöst</i>.
 *
 * <h2>Die Auflösung</h2>
 *
 * <b>Pseudonymisierung statt Löschung.</b> Nach einer konfigurierbaren Frist
 * wird der Anzeigename durch eine Kennung ersetzt:
 *
 * <pre>
 * vorher:  ausloeser = "Anna Beispiel"
 * nachher: ausloeser = "Person 7"
 * </pre>
 *
 * Die <b>Handlung</b> bleibt vollständig nachvollziehbar — was geschah, wann, an
 * welchem Gerät, mit welchem Ergebnis. Verloren geht nur die Zuordnung zu einer
 * natürlichen Person, und genau das ist der Zweck.
 *
 * <p><b>Warum eine Kennung und kein leeres Feld:</b> Bliebe das Feld leer, wäre
 * nicht mehr unterscheidbar, ob zwei Einträge von derselben Person stammen. Die
 * Kennung erhält diese Information, ohne die Person zu benennen. Sie ist aus der
 * Benutzer-Kennung abgeleitet, nicht aus dem Namen — ein Rückschluss über eine
 * Namensliste ist damit ausgeschlossen.
 *
 * <h2>Der privilegierte Weg</h2>
 *
 * Die Änderung läuft über dieselbe Ausnahme wie die Rotation
 * ({@code pinroboter.rotation = 'an'}). Das ist der <b>einzige</b> zulässige
 * Weg, das Protokoll zu ändern, und er ist damit an einer Stelle
 * nachvollziehbar.
 *
 * <p>Die Pseudonymisierung selbst wird protokolliert — mit Anzahl, nicht mit
 * Namen.
 */
public class Pseudonymisierung {

    /**
     * Vorgabefrist. Sechs Monate: lang genug, dass ein Vorgang noch mit der
     * handelnden Person besprochen werden kann, kurz genug, dass ein
     * Personenbezug nicht auf Jahre bestehen bleibt.
     *
     * <p>Über {@code PSEUDONYM_FRIST_TAGE} anpassbar. {@code 0} schaltet ab —
     * das ist eine bewusste Entscheidung des Betreibers, kein Versehen, und die
     * Oberfläche zeigt sie an.
     */
    public static final int VORGABE_TAGE = 183;

    private final Datenbank db;
    private final Fachprotokoll protokoll;
    private final int fristTage;
    private ScheduledExecutorService waechter;

    public Pseudonymisierung(Datenbank db, Fachprotokoll protokoll, int fristTage) {
        this.db = db;
        this.protokoll = protokoll;
        this.fristTage = Math.max(0, fristTage);
    }

    public boolean aktiv()   { return fristTage > 0; }
    public int fristTage()   { return fristTage; }

    /** Liest die Frist aus der Umgebung. Unlesbare Angaben führen zur Vorgabe, nicht zum Abschalten. */
    public static int fristAusUmgebung() {
        String s = System.getenv("PSEUDONYM_FRIST_TAGE");
        if (s == null || s.isBlank()) return VORGABE_TAGE;
        try {
            int t = Integer.parseInt(s.trim());
            return Math.max(0, t);
        } catch (NumberFormatException e) {
            System.err.println("PSEUDONYM_FRIST_TAGE ist unlesbar (\"" + s + "\"), "
                    + "es gilt die Vorgabe von " + VORGABE_TAGE + " Tagen.");
            return VORGABE_TAGE;
        }
    }

    /** Startet die tägliche Prüfung. Ein Neustart holt Versäumtes sofort nach. */
    public void starten() {
        if (!aktiv()) {
            System.out.println("  Pseudonymisierung: abgeschaltet (PSEUDONYM_FRIST_TAGE=0)");
            return;
        }
        System.out.println("  Pseudonymisierung: nach " + fristTage + " Tagen");
        waechter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pseudonymisierung");
            t.setDaemon(true);
            return t;
        });
        // Erster Lauf sofort: Nach einem Neustart koennte die Frist laengst
        // abgelaufen sein, und ein Tag Verzug waere ohne Not.
        waechter.scheduleAtFixedRate(this::laufen, 30, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
    }

    public void beenden() {
        if (waechter != null) waechter.shutdownNow();
    }

    private void laufen() {
        try {
            int n = jetztPseudonymisieren();
            if (n > 0) {
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null,
                        de.pinroboter.gemeinsam.Ereignisart.PROTOKOLL_PSEUDONYMISIERT, true,
                        "System", null,
                        "%d Protokolleinträge älter als %d Tage pseudonymisiert"
                                .formatted(n, fristTage),
                        "Die Handlungen bleiben nachvollziehbar; nur der Personenbezug "
                        + "wurde durch eine Kennung ersetzt.");
            }
        } catch (Exception e) {
            System.err.println("Pseudonymisierung fehlgeschlagen: " + e.getMessage());
        }
    }

    /**
     * Führt die Pseudonymisierung sofort aus.
     *
     * @return Anzahl der geänderten Einträge
     */
    public int jetztPseudonymisieren() throws SQLException {
        if (!aktiv()) return 0;

        try (Statement s = db.verbindung().createStatement()) {
            // Derselbe privilegierte Weg wie die Rotation - der einzige
            // zulaessige, das Protokoll zu aendern.
            s.execute("SET pinroboter.rotation = 'an'");
            try (PreparedStatement p = db.verbindung().prepareStatement("""
                    UPDATE fachprotokoll f
                    SET ausloeser = 'Person ' || coalesce(
                            (SELECT b.id::text FROM benutzer b
                             WHERE b.anzeigename = f.ausloeser LIMIT 1), 'unbekannt')
                    WHERE f.zeitpunkt < now() - make_interval(days => ?)
                      AND f.ausloeser NOT LIKE 'Person %'
                      -- Vorgaenge ohne Personenbezug bleiben unberuehrt: Sie
                      -- tragen keinen Namen, sondern eine Rolle.
                      AND f.ausloeser NOT IN ('System', 'Standort-Agent', 'Registrierung',
                                              'Einladung', 'Unbekannt')""")) {
                p.setInt(1, fristTage);
                return p.executeUpdate();
            } finally {
                s.execute("SET pinroboter.rotation = 'aus'");
            }
        }
    }

    /** Wie viele Einträge derzeit noch einen Personenbezug tragen, der die Frist überschritten hat. */
    public int faellig() {
        if (!aktiv()) return 0;
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                SELECT count(*) FROM fachprotokoll
                WHERE zeitpunkt < now() - make_interval(days => ?)
                  AND ausloeser NOT LIKE 'Person %'
                  AND ausloeser NOT IN ('System', 'Standort-Agent', 'Registrierung',
                                        'Einladung', 'Unbekannt')""")) {
            p.setInt(1, fristTage);
            try (ResultSet rs = p.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { return 0; }
    }

    /** Für die Anzeige: „nach 183 Tagen" oder „abgeschaltet". */
    public String beschreibung() {
        if (!aktiv()) return "abgeschaltet";
        return "nach " + fristTage + " Tagen (" + Duration.ofDays(fristTage).toDays() / 30 + " Monaten)";
    }
}
