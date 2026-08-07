package de.pinroboter.zentrale;

import java.sql.*;
import java.util.*;

/**
 * Welche Organisationseinheiten ein Benutzer sehen und bearbeiten darf.
 *
 * <p><b>Das ist die wichtigste Sicherheitsklasse dieses Produkts.</b> CLAUDE.md
 * sagt es unmissverständlich: <i>„Mandantentrennung wird zentral erzwungen,
 * nicht in jeder einzelnen Abfrage von Hand — sonst wird sie irgendwann
 * vergessen. Das ist die häufigste schwere Sicherheitslücke in mandantenfähigen
 * Anwendungen."</i>
 *
 * <h2>Wie das Vergessen verhindert wird</h2>
 *
 * Nicht durch Sorgfalt, sondern durch den Übersetzer. Jede Abfrage, die
 * mandantengebundene Daten liest oder ändert, verlangt einen
 * {@code Geltungsbereich} als <b>Pflichtparameter</b>. Wer ihn weglässt,
 * bekommt keinen unsicheren Zugriff — er bekommt einen Übersetzungsfehler.
 *
 * <p>Die Klasse liefert ein SQL-Fragment und die zugehörigen Werte. Der Aufrufer
 * kann die Einschränkung nicht „vergessen einzusetzen", ohne dass es auffällt:
 * Die Bedingung steht in jeder Abfrage sichtbar an derselben Stelle.
 *
 * <h2>Die Hierarchie</h2>
 *
 * <pre>
 * Verbund            → sieht alles darunter
 *  └── Standort      → sieht seine Einrichtungen
 *       └── Einrichtung → sieht nur sich selbst
 * </pre>
 *
 * Die sichtbaren Einheiten werden <b>transitiv nach unten</b> ermittelt, mit
 * einer rekursiven Abfrage. Nach oben gibt es keine Sicht: Eine Praxis sieht
 * ihren Standort nicht, und schon gar nicht die Nachbarpraxis.
 *
 * <h2>Der Instanzbetreiber</h2>
 *
 * {@link #alles()} umgeht die Einschränkung — für den Betreiber der Instanz und
 * für Hintergrundaufgaben ohne angemeldeten Benutzer (Agentenmeldungen,
 * Rotation). Diese Methode ist bewusst auffällig benannt, damit jeder Aufruf
 * bei einer Durchsicht ins Auge fällt.
 */
public final class Geltungsbereich {

    /** Die sichtbaren Organisations-Kennungen, oder {@code null} für „alles". */
    private final Set<Long> sichtbar;
    private final String bezeichnung;

    private Geltungsbereich(Set<Long> sichtbar, String bezeichnung) {
        this.sichtbar = sichtbar;
        this.bezeichnung = bezeichnung;
    }

    /**
     * Uneingeschränkter Zugriff.
     *
     * <p>Nur für den Instanzbetreiber und für Vorgänge ohne angemeldeten
     * Benutzer. <b>Jeder Aufruf gehört bei einer Sicherheitsdurchsicht
     * angesehen.</b>
     */
    public static Geltungsbereich alles() {
        return new Geltungsbereich(null, "alle Einrichtungen");
    }

    /** Sieht nichts. Rückfallwert, wenn ein Benutzer keiner Einheit zugeordnet ist. */
    public static Geltungsbereich keiner() {
        return new Geltungsbereich(Set.of(), "kein Bereich");
    }

    /**
     * Der Bereich eines Benutzers: seine Organisationseinheit und alles darunter.
     *
     * <p>Ein Benutzer <b>ohne</b> Zuordnung bekommt {@link #keiner()} — nicht
     * {@link #alles()}. Diese Richtung ist entscheidend: Ein Fehler in der
     * Zuordnung darf zu „sieht nichts" führen, niemals zu „sieht alles".
     */
    public static Geltungsbereich fuerBenutzer(Datenbank db, Long organisationId,
                                               boolean istInstanzbetreiber) {
        if (istInstanzbetreiber) return alles();
        if (organisationId == null) return keiner();

        Set<Long> ids = new LinkedHashSet<>();
        String name = null;
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                WITH RECURSIVE unterbau AS (
                    SELECT id, bezeichnung, 0 AS tiefe FROM organisation WHERE id = ?
                    UNION ALL
                    SELECT o.id, o.bezeichnung, u.tiefe + 1
                    FROM organisation o JOIN unterbau u ON o.eltern_id = u.id
                )
                SELECT id, bezeichnung, tiefe FROM unterbau ORDER BY tiefe, id""")) {
            p.setLong(1, organisationId);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                    if (name == null) name = rs.getString("bezeichnung");
                }
            }
        } catch (SQLException e) {
            // Im Zweifel nichts sehen. Ein Datenbankfehler darf keine
            // Mandantengrenze aufheben.
            return keiner();
        }
        return ids.isEmpty() ? keiner()
                : new Geltungsbereich(ids, name + " und " + (ids.size() - 1) + " untergeordnete");
    }

    /** Für Tests und Sonderfälle: ein Bereich aus einer festen Menge. */
    static Geltungsbereich ausIds(Collection<Long> ids, String bezeichnung) {
        return new Geltungsbereich(new LinkedHashSet<>(ids), bezeichnung);
    }

    public boolean unbeschraenkt() { return sichtbar == null; }
    public boolean leer()          { return sichtbar != null && sichtbar.isEmpty(); }
    public String bezeichnung()    { return bezeichnung; }
    public Set<Long> ids()         { return sichtbar == null ? Set.of() : Set.copyOf(sichtbar); }

    /** Ob eine bestimmte Einheit im Bereich liegt — für Prüfungen auf einzelne Datensätze. */
    public boolean umfasst(Long organisationId) {
        if (sichtbar == null) return true;
        return organisationId != null && sichtbar.contains(organisationId);
    }

    /**
     * Die SQL-Bedingung für eine Abfrage.
     *
     * @param spalte voll qualifizierter Spaltenname, z. B. {@code t.organisation_id}
     * @return ein Ausdruck, der immer mit {@code AND } beginnt — er lässt sich
     *         also an jedes {@code WHERE 1=1} anhängen, ohne dass jemand über
     *         die Verknüpfung nachdenken muss.
     */
    public String bedingung(String spalte) {
        if (sichtbar == null) return "";
        if (sichtbar.isEmpty()) return " AND FALSE";   // sieht nichts, und zwar wirklich
        return " AND " + spalte + " = ANY (?)";
    }

    /**
     * Setzt den Wert zur Bedingung, falls einer nötig ist.
     *
     * @param stelle Position des nächsten Parameters
     * @return die nächste freie Position
     */
    public int wertSetzen(Connection c, PreparedStatement p, int stelle) throws SQLException {
        if (sichtbar == null || sichtbar.isEmpty()) return stelle;
        p.setArray(stelle, c.createArrayOf("bigint", sichtbar.toArray(new Long[0])));
        return stelle + 1;
    }

    @Override public String toString() {
        return "Geltungsbereich[" + (sichtbar == null ? "alles" : sichtbar.size() + " Einheiten") + "]";
    }
}
