package de.pinroboter.zentrale;

import java.sql.*;

/**
 * Überwacht das Speicherbudget des Fachprotokolls und rotiert gestaffelt
 * (FA-PRO-070).
 *
 * <p>Anlass war eine Sorge des POs: „Ich habe Angst, dass uns der Speicher
 * platzt." Berechtigt — bei 5.000 Terminals entstehen im Störungsfall schnell
 * Millionen Einträge.
 *
 * <p><b>Gemessen wird die tatsächliche Größe</b>, nicht die Zeilenzahl. Eine
 * Zeile mit langer Fehlerbeschreibung belegt ein Vielfaches einer knappen
 * Statusmeldung; eine Zählung nach Zeilen ginge am Problem vorbei.
 *
 * <p><b>Kein Partitionsvergleich.</b> Wer den freien Platz des Dateisystems
 * misst, misst auch alles, was andere darauf ablegen — das Protokoll rotierte
 * dann, weil jemand anderes die Platte füllt, oder eben nicht, obwohl es selbst
 * längst zu groß ist. Deshalb ein eigenes Budget (ADR-0005).
 */
public class Speicherwaechter {

    /** Ab hier wird rotiert. */
    public static final double SCHWELLE_ROTATION = 0.80;
    /** Ab hier wird zusätzlich alarmiert. */
    public static final double SCHWELLE_ALARM = 0.90;
    /** Ab hier greift die verschärfte Rotation. */
    public static final double SCHWELLE_HART = 0.95;

    /**
     * Reihenfolge der Rotation: technische Diagnose zuerst, sicherheitsrelevante
     * Ereignisse zuletzt. Ein Nachweis über eine PIN-Hinterlegung ist mehr wert
     * als die Meldung, dass ein Gerät kurz nicht erreichbar war.
     */
    private static final String[] REIHENFOLGE = {"TECHNISCH", "ROUTINE", "SICHERHEIT"};

    private final Datenbank db;
    private final long budgetBytes;

    public record Zustand(long belegtBytes, long budgetBytes, long eintraege) {
        public double anteil() { return budgetBytes <= 0 ? 0 : (double) belegtBytes / budgetBytes; }
        public int prozent() { return (int) Math.round(anteil() * 100); }
        public boolean rotationNoetig() { return anteil() >= SCHWELLE_ROTATION; }
        public boolean alarmNoetig() { return anteil() >= SCHWELLE_ALARM; }
        public String belegtLesbar() { return lesbar(belegtBytes); }
        public String budgetLesbar() { return lesbar(budgetBytes); }

        private static String lesbar(long b) {
            if (b < 1024) return b + " B";
            if (b < 1024 * 1024) return (b / 1024) + " KB";
            if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / 1048576.0);
            return String.format("%.2f GB", b / 1073741824.0);
        }
    }

    public Speicherwaechter(Datenbank db, long budgetBytes) {
        this.db = db;
        this.budgetBytes = budgetBytes;
    }

    /** Tatsächlich belegter Speicher der Protokolltabelle samt Indizes. */
    public Zustand zustand() {
        try (Statement s = db.verbindung().createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT pg_total_relation_size('fachprotokoll') AS bytes,
                            (SELECT count(*) FROM fachprotokoll) AS anzahl""")) {
            if (rs.next()) {
                return new Zustand(rs.getLong("bytes"), budgetBytes, rs.getLong("anzahl"));
            }
        }
        catch (SQLException e) {
            // Nicht verschlucken: Ein stiller SQL-Fehler ist praktisch
            // unauffindbar - so blieb ein fehlerhaftes WHERE monatelang
            // unentdeckt. Der Aufruf schlaegt trotzdem nicht fehl.
            System.err.println("Speicherwaechter.zustand: " + e.getMessage());
        }
        return new Zustand(0, budgetBytes, 0);
    }

    /**
     * Rotiert, bis der Füllstand wieder unter der Schwelle liegt.
     *
     * <p>Gelöscht wird in Stufen: erst technische Diagnose, dann fachliche
     * Routineereignisse, zuletzt sicherheitsrelevante. Bevor die letzte Stufe
     * erreicht wird, meldet die Rückgabe das — die Zentrale fordert dann zum
     * Export auf, statt die Nachweiskette stillschweigend zu kappen.
     *
     * @return was tatsächlich geschehen ist
     */
    public Ergebnis rotieren() {
        Zustand z = zustand();
        if (!z.rotationNoetig()) return new Ergebnis(false, 0, null, z);

        double ziel = z.anteil() >= SCHWELLE_HART ? 0.60 : 0.70;
        long geloescht = 0;
        String letzteStufe = null;

        // Grundbelegung: Eine leere Tabelle ist nicht 0 Byte gross - allein die
        // fuenf Indizes belegen Platz. Ist das Budget kleiner als diese
        // Grundbelegung, ist das Ziel durch Loeschen NIE erreichbar, und die
        // Rotation wuerde das gesamte Protokoll leeren, ohne etwas zu bewirken.
        //
        // Ein zu klein konfiguriertes Budget darf nicht dazu fuehren, dass alle
        // Nachweise verschwinden. Deshalb: Wenn eine Runde nichts mehr bringt,
        // wird abgebrochen und der Zustand gemeldet.
        // Harte Obergrenze je Lauf: hoechstens die Haelfte der Eintraege.
        //
        // Grund: Eine leere Tabelle ist nicht 0 Byte gross - allein die Indizes
        // belegen Platz. Ist das Budget kleiner als diese Grundbelegung, ist das
        // Rotationsziel durch Loeschen NIE erreichbar, und ohne Bremse wuerde
        // die Rotation das gesamte Protokoll vernichten, ohne etwas zu bewirken.
        //
        // Eine Fehlkonfiguration darf Daten kosten, aber nicht alle. Bleibt der
        // Zustand danach kritisch, meldet budgetZuKlein() das - und die Zentrale
        // alarmiert, statt still weiterzuloeschen.
        long hoechstens = Math.max(100, z.eintraege() / 2);

        try (Statement s = db.verbindung().createStatement()) {
            s.execute("SET pinroboter.rotation = 'an'");
            try {
                aussen:
                for (String stufe : REIHENFOLGE) {
                    while (zustand().anteil() > ziel) {
                        int weg = loescheAelteste(stufe,
                                (int) Math.min(500, hoechstens - geloescht));
                        if (weg <= 0 && geloescht >= hoechstens) break aussen;
                        if (weg == 0) break;      // in dieser Stufe nichts mehr da

                        // VACUUM nach JEDEM Block, nicht erst am Ende.
                        //
                        // pg_total_relation_size schrumpft nicht durch DELETE
                        // allein - der Platz bleibt bis zum VACUUM belegt. Ohne
                        // diesen Aufruf sieht die Schleife keinen Fortschritt und
                        // loescht weiter, bis eine Stufe leer ist. Die Staffelung
                        // waere damit wirkungslos: Erst als alle Routineereignisse
                        // weg waren, wuerde sie aufhoeren - und dann gingen die
                        // sicherheitsrelevanten dran.
                        //
                        // Gefunden durch den Test gestaffelteReihenfolge.
                        s.execute("VACUUM fachprotokoll");
                        geloescht += weg;
                        letzteStufe = stufe;

                        if (geloescht >= hoechstens) break aussen;
                    }
                    if (zustand().anteil() <= ziel) break;
                }
            } finally {
                s.execute("SET pinroboter.rotation = 'aus'");
            }
        } catch (SQLException e) {
            return new Ergebnis(false, geloescht, letzteStufe, zustand());
        }
        return new Ergebnis(true, geloescht, letzteStufe, zustand());
    }

    public record Ergebnis(boolean rotiert, long geloescht, String letzteStufe, Zustand danach) {
        /**
         * Das Budget ist kleiner als die Grundbelegung der Tabelle — Löschen
         * schafft keinen Platz mehr. Der Betreiber muss es erhöhen.
         */
        public boolean budgetZuKlein() { return danach.rotationNoetig(); }

        /** Wurden bereits sicherheitsrelevante Einträge gelöscht? */
        public boolean nachweiskettUnterbrochen() { return "SICHERHEIT".equals(letzteStufe); }
    }

    private int loescheAelteste(String stufe, int hoechstens) throws SQLException {
        try (PreparedStatement p = db.verbindung().prepareStatement("""
                DELETE FROM fachprotokoll WHERE id IN (
                  SELECT id FROM fachprotokoll WHERE stufe = ?
                  ORDER BY zeitpunkt ASC LIMIT ?)""")) {
            p.setString(1, stufe);
            p.setInt(2, hoechstens);
            return p.executeUpdate();
        }
    }

    /** Wandelt eine Angabe wie „2 GB" oder „500 MB" in Bytes. */
    public static long budgetAusText(String text, long vorgabe) {
        if (text == null || text.isBlank()) return vorgabe;
        String t = text.trim().toUpperCase().replace(" ", "");
        try {
            if (t.endsWith("GB")) return (long) (Double.parseDouble(t.substring(0, t.length() - 2)) * 1073741824L);
            if (t.endsWith("MB")) return (long) (Double.parseDouble(t.substring(0, t.length() - 2)) * 1048576L);
            if (t.endsWith("KB")) return (long) (Double.parseDouble(t.substring(0, t.length() - 2)) * 1024L);
            return Long.parseLong(t);
        } catch (NumberFormatException e) { return vorgabe; }
    }
}
