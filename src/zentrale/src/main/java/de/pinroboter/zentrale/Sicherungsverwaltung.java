package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Ereignisart;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Datensicherung der Zentrale (FA-BAK-010 bis 040, ADR-0010).
 *
 * <h2>Warum eingebaut und nicht im Betriebshandbuch</h2>
 *
 * Bis zum 10.08.2026 war die Sicherung ein Kapitel mit Befehlen zum Abtippen.
 * Das setzt jemanden voraus, der sie regelmäßig ausführt — in einer Praxis ohne
 * IT-Personal gibt es den nicht.
 *
 * <p>Was verloren geht, wenn die Datenbank verschwindet: Bestand,
 * Organisationsstruktur, Konten, verschlüsselte Zugangsdaten und das gesamte
 * Fachprotokoll. Letzteres ist der Nachweis darüber, was der Roboter mit den
 * SMC-B getan hat, und lässt sich nicht rekonstruieren.
 *
 * <h2>Drei Pläne, unabhängig voneinander</h2>
 *
 * <table>
 *   <caption>Aufbewahrung nach FA-BAK-010</caption>
 *   <tr><th>Plan</th><th>behält</th><th>Rotation</th></tr>
 *   <tr><td>täglich</td><td>7</td><td>wöchentlich rollierend</td></tr>
 *   <tr><td>wöchentlich</td><td>5</td><td>monatlich rollierend</td></tr>
 *   <tr><td>monatlich</td><td>12</td><td>jährlich rollierend</td></tr>
 * </table>
 *
 * <p>Ein Stand zählt zu genau einem Plan. Der wöchentliche ist keine Kopie des
 * täglichen, sondern ein eigener Auszug — sonst hinge die längere Aufbewahrung
 * daran, dass der kürzere Plan nicht ausfällt.
 *
 * <h2>Was hier bewusst nicht passiert</h2>
 *
 * <b>Der älteste Stand wird entfernt, nie der neueste</b> — auch nicht bei
 * knappem Speicher. Eine Rotation, die im Zweifel den frischesten Stand
 * wegwirft, hat den Zweck verfehlt.
 *
 * <p><b>Eine fehlgeschlagene Sicherung ist ein Alarm</b>, keine Logzeile. Eine
 * Sicherung, von der niemand weiß, dass sie ausblieb, ist keine.
 */
public class Sicherungsverwaltung {

    /** Wie viele Stände je Plan aufbewahrt werden (FA-BAK-010). */
    public enum Plan {
        TAEGLICH("täglich", 7, Duration.ofHours(24)),
        WOECHENTLICH("wöchentlich", 5, Duration.ofDays(7)),
        MONATLICH("monatlich", 12, Duration.ofDays(30));

        public final String anzeige;
        public final int behaelt;
        public final Duration abstand;

        Plan(String anzeige, int behaelt, Duration abstand) {
            this.anzeige = anzeige; this.behaelt = behaelt; this.abstand = abstand;
        }

        static Plan aus(String s) {
            for (Plan p : values()) if (p.name().equalsIgnoreCase(s)) return p;
            return null;
        }
    }

    /** Eine vorhandene Sicherung. */
    public record Stand(String dateiname, Plan plan, Instant zeitpunkt, long bytes) {
        public String anzeige() {
            return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    .withZone(ZoneId.systemDefault()).format(zeitpunkt);
        }
        public String groesse() {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
            return String.format(Locale.GERMANY, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
    }

    private static final DateTimeFormatter STEMPEL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);

    private final Path ablage;
    private final Fachprotokoll protokoll;
    private final Alarmverwaltung alarme;
    private final String dbHost, dbPort, dbName, dbUser, dbPasswort;
    private final boolean planLaeuft;
    private ScheduledExecutorService takt;

    /** Letzter Fehler je Plan — für die Anzeige, nicht nur fürs Log. */
    private final Map<Plan, String> letzterFehler = new EnumMap<>(Plan.class);

    public Sicherungsverwaltung(Fachprotokoll protokoll, Alarmverwaltung alarme,
                                String dbUrl, String dbUser, String dbPasswort) {
        this.protokoll = protokoll;
        this.alarme = alarme;
        this.ablage = Path.of(env("SICHERUNG_ABLAGE", "/sicherungen"));
        this.planLaeuft = !"aus".equalsIgnoreCase(env("SICHERUNG_PLAN", "an"));
        this.dbUser = dbUser;
        this.dbPasswort = dbPasswort;

        // jdbc:postgresql://host:port/name — Host, Port und Name herausziehen.
        // Der Port MUSS abgetrennt werden: pg_dump nimmt ihn über -p, und
        // "-h datenbank:5432" versucht es mit einem Rechnernamen, den es nicht
        // gibt ("could not translate host name").
        String rest = dbUrl.replaceFirst("^jdbc:postgresql://", "");
        int schraeg = rest.indexOf('/');
        String hostTeil = schraeg < 0 ? rest : rest.substring(0, schraeg);
        this.dbName = schraeg < 0 ? "pinroboter" : rest.substring(schraeg + 1).replaceAll("\\?.*", "");
        int doppel = hostTeil.lastIndexOf(':');
        this.dbHost = doppel < 0 ? hostTeil : hostTeil.substring(0, doppel);
        this.dbPort = doppel < 0 ? "5432" : hostTeil.substring(doppel + 1);

        try {
            Files.createDirectories(ablage);
        } catch (IOException e) {
            System.err.println("Sicherungsablage " + ablage + " nicht nutzbar: " + e.getMessage());
        }
    }

    private static String env(String name, String vorgabe) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? vorgabe : v;
    }

    // ------------------------------------------------------------------ Takt

    /**
     * Startet die geplanten Sicherungen.
     *
     * <p>Geprüft wird <b>stündlich</b>, nicht zur vollen Uhrzeit: Eine Instanz,
     * die nachts aus ist, würde sonst nie sichern. Fällig ist ein Plan, wenn
     * sein letzter Stand älter als sein Abstand ist — damit holt er nach dem
     * Einschalten auf, statt einen Tag zu überspringen.
     */
    void taktStarten() {
        if (!planLaeuft) {
            System.out.println("  Geplante Sicherung: ausgeschaltet (SICHERUNG_PLAN=aus)");
            return;
        }
        takt = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sicherung");
            t.setDaemon(true);
            return t;
        });
        // Erste Prüfung nach zwei Minuten: Der Start soll nicht mit einer
        // Sicherung zusammenfallen, und die Migrationen sollen durch sein.
        takt.scheduleWithFixedDelay(this::faelligePruefen, 2, 60, TimeUnit.MINUTES);
        System.out.println("  Geplante Sicherung: aktiv (täglich, wöchentlich, monatlich)");
    }

    void taktBeenden() {
        if (takt != null) takt.shutdownNow();
    }

    /** Prüft alle drei Pläne und sichert, was fällig ist. */
    void faelligePruefen() {
        for (Plan p : Plan.values()) {
            try {
                if (faellig(p)) sichern(p, "Zeitplan");
            } catch (Exception e) {
                // Ein Fehler in einem Plan darf die anderen nicht mitnehmen.
                System.err.println("Sicherung " + p + " fehlgeschlagen: " + e.getMessage());
            }
        }
    }

    boolean faellig(Plan p) {
        return staende(p).stream().findFirst()
                .map(s -> Duration.between(s.zeitpunkt(), Instant.now()).compareTo(p.abstand) >= 0)
                .orElse(true);   // noch nie gesichert
    }

    // ------------------------------------------------------------------ Sichern

    /**
     * Erzeugt eine Sicherung.
     *
     * <p>Der Auszug geht zuerst in eine Datei mit der Endung {@code .teil} und
     * wird erst nach Erfolg umbenannt. Sonst läge nach einem Abbruch eine
     * unvollständige Datei in der Ablage, die aussieht wie eine Sicherung —
     * und im Ernstfall zur Wiederherstellung gewählt würde.
     *
     * @return der entstandene Stand
     */
    public synchronized Stand sichern(Plan plan, String ausloeser) throws IOException {
        String name = "pinroboter_" + plan.name().toLowerCase() + "_"
                + STEMPEL.format(Instant.now()) + ".dump";
        Path ziel = ablage.resolve(name);
        Path teil = ablage.resolve(name + ".teil");

        try {
            ProcessBuilder pb = new ProcessBuilder("pg_dump",
                    "-h", dbHost, "-p", dbPort, "-U", dbUser, "-d", dbName, "-Fc", "-Z", "6");
            pb.environment().put("PGPASSWORD", dbPasswort);
            pb.redirectOutput(teil.toFile());
            pb.redirectErrorStream(false);
            Process pr = pb.start();
            String fehler = new String(pr.getErrorStream().readAllBytes()).trim();
            boolean fertig = pr.waitFor(30, TimeUnit.MINUTES);
            if (!fertig) { pr.destroyForcibly(); throw new IOException("Zeitüberschreitung"); }
            if (pr.exitValue() != 0) {
                throw new IOException(fehler.isEmpty() ? "pg_dump meldete " + pr.exitValue() : fehler);
            }
            if (Files.size(teil) == 0) throw new IOException("Der Auszug ist leer.");

            Files.move(teil, ziel, StandardCopyOption.ATOMIC_MOVE);
            letzterFehler.remove(plan);

            Stand s = new Stand(name, plan, Instant.now(), Files.size(ziel));
            protokoll.anfuegen("Praxis", null, Ereignisart.SICHERUNG_ERSTELLT, true,
                    ausloeser, null,
                    "Sicherung (" + plan.anzeige + ") erstellt: " + s.groesse(), null);
            alarme.beheben("Praxis", "Sicherung " + plan.anzeige,
                    Alarmverwaltung.Art.SICHERUNG_FEHLGESCHLAGEN);
            rotieren(plan);
            return s;

        } catch (Exception e) {
            try { Files.deleteIfExists(teil); } catch (IOException ignored) { }
            String grund = e.getMessage() == null ? e.toString() : e.getMessage();
            letzterFehler.put(plan, grund);

            // Ein Alarm, keine Logzeile: Eine Sicherung, von der niemand weiß,
            // dass sie ausblieb, ist keine Sicherung (FA-BAK-010).
            alarme.melden("Praxis", "Sicherung " + plan.anzeige,
                    Alarmverwaltung.Art.SICHERUNG_FEHLGESCHLAGEN,
                    "Die " + plan.anzeige + "e Sicherung ist fehlgeschlagen: " + grund,
                    "Prüfen Sie den freien Speicherplatz und die Erreichbarkeit der "
                    + "Datenbank. Unter Einstellungen lässt sich eine Sicherung von "
                    + "Hand auslösen.");
            protokoll.anfuegen("Praxis", null, Ereignisart.SICHERUNG_FEHLGESCHLAGEN, false,
                    ausloeser, null,
                    "Sicherung (" + plan.anzeige + ") fehlgeschlagen: " + grund,
                    "Freien Speicherplatz und Datenbankverbindung prüfen.");
            throw new IOException(grund, e);
        }
    }

    /**
     * Entfernt die ältesten Stände eines Plans.
     *
     * <p>Nie den neuesten — auch dann nicht, wenn die Platte voll ist. Eine
     * Rotation, die im Zweifel den frischesten Stand wegwirft, hat ihren Zweck
     * verfehlt.
     */
    private void rotieren(Plan plan) {
        var alle = staende(plan);
        for (int i = plan.behaelt; i < alle.size(); i++) {
            Stand alt = alle.get(i);
            try {
                Files.deleteIfExists(ablage.resolve(alt.dateiname()));
                protokoll.anfuegen("Praxis", null, Ereignisart.SICHERUNG_ROTIERT, true,
                        "Automatik", null,
                        "Ältester " + plan.anzeige + "er Stand entfernt (" + alt.anzeige() + ")",
                        null);
            } catch (IOException e) {
                System.err.println("Rotation: " + alt.dateiname() + " nicht löschbar: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ Lesen

    /** Alle Stände eines Plans, neueste zuerst. */
    public List<Stand> staende(Plan plan) {
        return alleStaende().stream().filter(s -> s.plan() == plan).toList();
    }

    /** Alle Stände, neueste zuerst. */
    public List<Stand> alleStaende() {
        if (!Files.isDirectory(ablage)) return List.of();
        try (var s = Files.list(ablage)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".dump"))
                    .map(this::alsStand).filter(Objects::nonNull)
                    .sorted(Comparator.comparing(Stand::zeitpunkt).reversed())
                    .toList();
        } catch (IOException e) {
            System.err.println("Sicherungen nicht lesbar: " + e.getMessage());
            return List.of();
        }
    }

    private Stand alsStand(Path p) {
        String name = p.getFileName().toString();
        String[] t = name.split("_");
        if (t.length < 3) return null;
        Plan plan = Plan.aus(t[1]);
        if (plan == null) return null;
        try {
            FileTime ft = Files.getLastModifiedTime(p);
            return new Stand(name, plan, ft.toInstant(), Files.size(p));
        } catch (IOException e) {
            return null;
        }
    }

    public Optional<Stand> stand(String dateiname) {
        // Kein Pfad, nur ein Name: Sonst wäre "../../etc/passwd" ein gültiger
        // Wunsch. Der Name muss außerdem einem vorhandenen Stand entsprechen.
        if (dateiname == null || dateiname.contains("/") || dateiname.contains("\\")
                || dateiname.contains("..")) {
            return Optional.empty();
        }
        return alleStaende().stream().filter(s -> s.dateiname().equals(dateiname)).findFirst();
    }

    public Path pfad(Stand s) { return ablage.resolve(s.dateiname()); }

    public String fehler(Plan p) { return letzterFehler.get(p); }

    /** Belegter Platz aller Sicherungen. */
    public long belegt() {
        return alleStaende().stream().mapToLong(Stand::bytes).sum();
    }

    /**
     * Ist ein Plan überfällig?
     *
     * <p>Grundlage für den Handlungsbedarf in der Oberfläche (FA-BAK-040).
     * Überfällig heißt: älter als der doppelte Abstand — einmal ausgefallen ist
     * ein Aussetzer, zweimal ist ein Problem.
     */
    public boolean ueberfaellig(Plan p) {
        if (!planLaeuft) return false;
        return staende(p).stream().findFirst()
                .map(s -> Duration.between(s.zeitpunkt(), Instant.now())
                        .compareTo(p.abstand.multipliedBy(2)) > 0)
                .orElse(false);   // noch nie gesichert ist kein Versäumnis, sondern ein neuer Start
    }

    public boolean planLaeuft() { return planLaeuft; }

    // ------------------------------------------------------------ Wiederherstellen

    /**
     * Spielt einen Stand ein (FA-BAK-030).
     *
     * <p><b>Vorher entsteht eine Sicherung des aktuellen Zustands.</b> Eine
     * versehentliche Wiederherstellung darf nicht der letzte Stand sein.
     *
     * <p>Schlägt das Einspielen fehl, bleibt der bisherige Stand unverändert:
     * {@code pg_restore} arbeitet mit {@code --single-transaction}, also ganz
     * oder gar nicht. Ein halb eingespieltes Backup wäre schlimmer als keines.
     *
     * @return Fehlermeldung im Klartext, oder {@code null} bei Erfolg
     */
    public synchronized String wiederherstellen(Stand s, String durch) {
        try {
            sichern(Plan.TAEGLICH, "vor Wiederherstellung durch " + durch);
        } catch (IOException e) {
            return "Die Sicherheitskopie vor der Wiederherstellung ist fehlgeschlagen: "
                 + e.getMessage() + " Die Wiederherstellung wurde deshalb nicht begonnen.";
        }

        // Der Protokolleintrag muss VOR dem Einspielen geschrieben werden —
        // danach ist er im wiederhergestellten Stand nicht mehr vorhanden.
        // Deshalb steht er zusätzlich im Betriebsprotokoll, das der
        // Wiederherstellung nicht unterliegt.
        protokoll.anfuegen("Praxis", null, Ereignisart.WIEDERHERSTELLUNG, true, durch, null,
                "Wiederherstellung des Standes vom " + s.anzeige() + " begonnen",
                "Die Zentrale startet danach neu. Angemeldete Benutzer werden abgemeldet.");
        System.out.println("[Wiederherstellung] Stand vom " + s.anzeige()
                + " wird eingespielt, ausgelöst durch " + durch);

        try {
            ProcessBuilder pb = new ProcessBuilder("pg_restore",
                    "-h", dbHost, "-p", dbPort, "-U", dbUser, "-d", dbName,
                    "--clean", "--if-exists", "--single-transaction", "--no-owner",
                    pfad(s).toString());
            pb.environment().put("PGPASSWORD", dbPasswort);
            Process pr = pb.start();
            String fehler = new String(pr.getErrorStream().readAllBytes()).trim();
            if (!pr.waitFor(30, TimeUnit.MINUTES)) {
                pr.destroyForcibly();
                return "Die Wiederherstellung hat zu lange gedauert und wurde abgebrochen. "
                     + "Der bisherige Stand ist unverändert.";
            }
            if (pr.exitValue() != 0) {
                System.err.println("[Wiederherstellung] fehlgeschlagen: " + fehler);
                return "Die Wiederherstellung ist fehlgeschlagen. Der bisherige Stand ist "
                     + "unverändert. Ursache: " + kurz(fehler);
            }
            System.out.println("[Wiederherstellung] erfolgreich, Zentrale startet neu");
            return null;
        } catch (Exception e) {
            return "Die Wiederherstellung ist fehlgeschlagen: " + e.getMessage()
                 + " Der bisherige Stand ist unverändert.";
        }
    }

    /** Nimmt eine hochgeladene Sicherung in die Ablage auf (FA-BAK-020). */
    public synchronized Stand aufnehmen(byte[] inhalt, Plan plan, String durch) throws IOException {
        String name = "pinroboter_" + plan.name().toLowerCase() + "_"
                + STEMPEL.format(Instant.now()) + ".dump";
        Path ziel = ablage.resolve(name);
        Files.write(ziel, inhalt, StandardOpenOption.CREATE_NEW);
        protokoll.anfuegen("Praxis", null, Ereignisart.SICHERUNG_HOCHGELADEN, true, durch, null,
                "Sicherung hochgeladen (" + (inhalt.length / 1024) + " KB)",
                "Die Datei wurde geprüft und entschlüsselt. Sie ist noch nicht eingespielt.");
        return new Stand(name, plan, Instant.now(), inhalt.length);
    }

    private static String kurz(String s) {
        if (s == null || s.isBlank()) return "unbekannt";
        String[] zeilen = s.split("\n");
        return zeilen[zeilen.length - 1].trim();
    }

    /** Kopiert einen Stand in einen Strom — für den Download. */
    public void ausgeben(Stand s, OutputStream ziel) throws IOException {
        try (InputStream in = Files.newInputStream(pfad(s))) {
            in.transferTo(ziel);
        }
    }
}
