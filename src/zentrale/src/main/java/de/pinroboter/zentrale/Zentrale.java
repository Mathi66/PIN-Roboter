package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Ampel;
import de.pinroboter.gemeinsam.Ereignisart;
import de.pinroboter.gemeinsam.PinStatus;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zentrale: Oberfläche, Fachprotokoll, Zustandsübersicht.
 *
 * <p>Kennt bewusst <b>keine SMC-B-PIN</b> — die liegt ausschließlich beim
 * Standort-Agenten (ADR-0002). Die Zentrale steuert, protokolliert und zeigt an;
 * der Agent besitzt die PIN und handelt.
 *
 * <p>Seit der Bestandsverwaltung hält die Zentrale sehr wohl <b>Geräte-Zugänge</b>
 * (Konnektor-Anmeldung, Terminal-Administrationszugang), verschlüsselt und nie
 * wieder anzeigbar. Diese Abgrenzung zwischen „PIN" und „Geräte-Zugang" ist
 * bewusst und begründet: {@link Bestandsverwaltung}, ADR-0008.
 *
 * <p>Die Oberfläche wird server-seitig erzeugt. Grund ist die Nachweispflicht
 * für WCAG 2.2 AA und der Umstand, dass große Listen ohnehin serverseitig
 * gefiltert werden müssen (ADR-0006).
 */
public class Zentrale {

    private final Map<String, Geraetezustand> geraete = new ConcurrentHashMap<>();
    private final Fachprotokoll protokoll;
    private final Datenbank db;
    private final Benutzerverwaltung benutzer;
    private final Agentenverwaltung agenten;
    private final Gruppenverwaltung gruppen;
    private final Alarmverwaltung alarme;
    private final Speicherwaechter speicher;
    private final Demodaten demodaten;
    private final Bestandsverwaltung bestand;
    private final Ermittlung ermittlung;
    private final Pinverwaltung pins;
    private final Organisationsverwaltung organisationen;
    private final Benachrichtigung benachrichtigung;
    private final Versionspruefung versionen;
    private final Lasterzeuger last;
    private final Sicherungsverwaltung sicherung;
    private final Einladungen einladungen;
    private final Anmeldebremse bremse = new Anmeldebremse();
    private final Pseudonymisierung pseudonym;
    /** Name der Sitzungs-Kennung im Cookie. */
    private static final String SITZUNGSCOOKIE = "pinroboter_sitzung";
    private volatile boolean notAus = false;
    private volatile Instant agentGesehen = null;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8090"));
        String url  = env("DB_URL",  "jdbc:postgresql://datenbank:5432/pinroboter");
        String nutz = env("DB_USER", "pinroboter");
        String kenn = env("DB_PASSWORD", null);
        if (kenn == null || kenn.isBlank()) {
            // NFA-BET-010: Fehlende Pflichtangaben fuehren zu einer verstaendlichen
            // Meldung, nicht zu einem Absturz mit Stapelueberwachung.
            System.err.println("""
                    Die Umgebungsvariable DB_PASSWORD fehlt.
                    Die Zentrale benoetigt Zugangsdaten zur Datenbank. Bitte in der
                    Konfigurationsdatei ergaenzen und den Stack neu starten.""");
            System.exit(2);
        }
        // Das Speicherbudget begrenzt den einzigen unbegrenzt wachsenden Anteil
        // (FA-PRO-070). 200.000 Eintraege entsprechen grob 100 MB.
        // Budget in Bytes, nicht in Zeilen: Eine Zeile mit langer
        // Fehlerbeschreibung belegt ein Vielfaches einer knappen Statusmeldung.
        long budget = Speicherwaechter.budgetAusText(
                env("PROTOKOLL_BUDGET", null), 2L * 1024 * 1024 * 1024);   // 2 GB

        Datenbank db = Datenbank.verbinden(url, nutz, kenn, 30);
        Zentrale z = new Zentrale(db, budget);
        // Nach den Migrationen, vor dem Zuhoeren: Das Konto muss stehen, bevor
        // sich jemand anmelden kann (FA-BEN-035).
        Startkonto.anlegenFallsNoetig(db, z.benutzer, z.protokoll);
        z.sicherung.taktStarten();
        z.starten(port);
    }

    Zentrale(Datenbank db, long protokollBudgetBytes) {
        this.db = db;
        this.speicher = new Speicherwaechter(db, protokollBudgetBytes);
        this.protokoll = new Fachprotokoll(db, speicher);
        this.benutzer = new Benutzerverwaltung(db);
        this.agenten = new Agentenverwaltung(db);
        this.gruppen = new Gruppenverwaltung(db);
        this.alarme = new Alarmverwaltung(db);
        // Der Tresor wird gleich unten aus der Umgebung geholt; hier wird er
        // vorgezogen, damit der Demodatensatz die Zugangsdaten seiner Geräte
        // verschlüsselt ablegen kann — auf demselben Weg wie echte.
        var demoTresor = de.pinroboter.gemeinsam.Geheimnisspeicher.ausUmgebung();
        this.demodaten = new Demodaten(db, demoTresor);
        // Ein Tresor fuer beide: Geraete-Zugangsdaten (ADR-0008) und das
        // Mailserver-Kennwort (FA-ALA-020) liegen unter demselben Schluessel.
        var tresor = de.pinroboter.gemeinsam.Geheimnisspeicher.ausUmgebung();
        this.bestand = new Bestandsverwaltung(db, tresor);
        this.ermittlung = new Ermittlung(db);
        this.pins = new Pinverwaltung(db);
        this.organisationen = new Organisationsverwaltung(db);
        this.benachrichtigung = new Benachrichtigung(db, tresor);
        this.versionen = new Versionspruefung(db, tresor);
        this.last = new Lasterzeuger(db);
        this.sicherung = new Sicherungsverwaltung(protokoll, alarme,
                env("DB_URL", "jdbc:postgresql://datenbank:5432/pinroboter"),
                env("DB_USER", "pinroboter"), env("DB_PASSWORD", ""));
        this.einladungen = new Einladungen(db, benutzer);
        this.pseudonym = new Pseudonymisierung(db, protokoll,
                Pseudonymisierung.fristAusUmgebung());
    }

    private static String env(String name, String vorgabe) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? vorgabe : v;
    }

    void starten(int port) {
        Javalin app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            // Das Wurzelverzeichnis "/" findet Javalin im Classpath nicht — die
            // Dateien müssen in einem echten Unterordner liegen. Ohne diesen
            // Ordner werden Stylesheet und HTMX mit 404 beantwortet, und die
            // Oberfläche erscheint als nacktes HTML. Der Start meldete das zwar
            // ("Static resource directory ... does not exist"), aber die
            // Anwendung lief weiter — deshalb fiel es beim Bauen nicht auf.
            cfg.staticFiles.add(s -> {
                s.hostedPath = "/statisch";
                s.directory = "/statisch";
                s.location = io.javalin.http.staticfiles.Location.CLASSPATH;
            });
        });

        // ------------------------------------------------------------------
        // Zugriffsschutz an EINER Stelle (FA-BEN-020, NFA-SIC-050).
        //
        // Bewusst als Sperre mit Ausnahmeliste, nicht als Schutz je Seite: Eine
        // neue Seite ist damit von sich aus geschützt. Andersherum wäre sie
        // offen, bis jemand daran denkt — und irgendwann denkt niemand daran.
        // Dasselbe Prinzip wie bei der Mandantentrennung.
        app.before(c -> {
            String pfad = c.path();
            if (pfad.startsWith("/statisch/")) return;

            // Registrierung eines neuen Agenten: Der Schutz liegt im
            // Registrierungsgeheimnis selbst (einmalig gültig, zeitlich
            // begrenzt), nicht in einer Sitzung — der Agent hat noch keine.
            if (pfad.equals("/api/registrieren")) return;

            // Schnittstellen des Standort-Agenten (FA-AGT-020). Er ist kein
            // Browser-Benutzer und weist sich mit seinem Token aus.
            if (pfad.startsWith("/api/agent/")) {
                var agent = agenten.pruefeToken(kopfWert(c, "X-Agent-Token"));
                if (agent.isEmpty()) {
                    c.status(401).json(java.util.Map.of("fehler",
                            "Kein gültiges Agent-Token. Bitte den Agenten neu registrieren."));
                    c.skipRemainingHandlers();
                    return;
                }
                c.attribute("agent", agent.get());
                return;
            }

            // Das Einloesen einer Einladung braucht naturgemaess keine
            // Anmeldung - wer sie einloest, hat noch kein Konto.
            if (pfad.equals("/einladung")) return;

            if (benutzer.ersteinrichtungNoetig()) {
                if (pfad.equals("/ersteinrichtung") || pfad.equals("/totp-bestaetigen")) return;
                if (pfad.startsWith("/teil/") || pfad.startsWith("/api/")) {
                    c.status(401).header("HX-Redirect", "/ersteinrichtung").result("");
                    c.skipRemainingHandlers();
                    return;
                }
                c.redirect("/ersteinrichtung");
                c.skipRemainingHandlers();
                return;
            }
            if (pfad.equals("/anmelden") || pfad.equals("/ersteinrichtung")
                    || pfad.equals("/totp-bestaetigen")) return;

            var angemeldet = benutzer.sitzungPruefen(c.cookie(SITZUNGSCOOKIE));
            if (angemeldet.isEmpty()) {
                if (pfad.startsWith("/api/")) {
                    c.status(401).json(java.util.Map.of("fehler", "Nicht angemeldet"));
                    c.skipRemainingHandlers();
                    return;
                }
                if (pfad.startsWith("/teil/")) {
                    // 401 statt 200: Der HX-Redirect-Header schickt den Browser
                    // zur Anmeldung, der Statuscode sagt die Wahrheit.
                    c.status(401).header("HX-Redirect", "/anmelden").result("");
                }
                else c.redirect("/anmelden");
                c.skipRemainingHandlers();
                return;
            }
            c.attribute("benutzer", angemeldet.get());
        });


































        // --------------------------------------------------------- SMC-B-PIN
        // FA-PIN-010 / FA-PIN-060 nach ADR-0009. Hier kommt ausschliesslich ein
        // Chiffrat an, das nur der Standort-Agent entschluesseln kann.





        // ------------------------------------------------ Benachrichtigung
        // FA-ALA-020. Der Mailserver gilt instanzweit, die Regeln je Einheit.






        // ------------------------------------------------------- Organisation
        // FA-ORG-020. Wer den Baum aendert, aendert Geltungsbereiche - deshalb
        // ist jede Aenderung protokollpflichtig und auf Standortebene begrenzt.

        // ── Routen ───────────────────────────────────────────────────────
        // Bis zum 09.08.2026 standen alle 81 Routen in dieser einen Methode:
        // 1450 Zeilen, in denen die Anmeldung neben der PIN-Ablage und der
        // Agentenschnittstelle lag. Eine Änderung an einer Route erforderte,
        // sich durch alles andere zu lesen — und ein Blick auf "was kann die
        // Zentrale eigentlich" war unmöglich.
        //
        // Jetzt bündelt jede Methode einen Fachbereich. Die Reihenfolge der
        // AUFRUFE ist frei, weil sich die Pfadpräfixe nicht überschneiden;
        // die Reihenfolge INNERHALB eines Bündels ist es nicht.
        routenAnmeldung(app);
        routenUebersicht(app);
        routenProtokoll(app);
        routenGruppen(app);
        routenBestand(app);
        routenOrganisation(app);
        routenBenutzer(app);
        routenBenachrichtigung(app);
        routenVerwaltung(app);
        routenEinstellungen(app);
        routenAgentSchnittstelle(app);
































        app.start("0.0.0.0", port);
        System.out.println("""
                ────────────────────────────────────────────────────────────
                  PIN-Roboter · Zentrale
                  Oberfläche:  http://localhost:%d
                  Protokoll:   %d Einträge, %s von %s belegt
                ────────────────────────────────────────────────────────────
                """.formatted(port, speicher.zustand().eintraege(),
                        speicher.zustand().belegtLesbar(), speicher.zustand().budgetLesbar()));

        // Art. 17 DSGVO: Der Personenbezug im Protokoll verfaellt nach Frist.
        pseudonym.starten();

        // FA-PRO-070: Der Füllstand wird regelmäßig geprüft, nicht nur beim
        // Schreiben. Ein Protokoll, das nachts vollläuft, muss auch nachts
        // rotieren.
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    Thread.sleep(60_000);
                    speicherPruefen();
                } catch (InterruptedException e) { return; }
                  catch (Exception ignored) { }
            }
        });

        // FA-UPD-010: regelmaessig nach neuen Versionen sehen. Beim Start
        // einmal sofort, damit der Betreiber nicht bis zum ersten Takt wartet.
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    Thread.sleep(20_000);
                    var z = versionen.pruefen();
                    if (z.sicherheitsupdateOffen()) {
                        protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.EINSTELLUNG_GEAENDERT,
                                false, "Automatik", null,
                                "Ein sicherheitsrelevantes Update steht bereit",
                                "Unter „Instanz“ steht, welche Version und was sich ändert.");
                    }
                    Thread.sleep(Versionspruefung.TAKT.toMillis());
                } catch (InterruptedException e) { return; }
                  catch (Exception ignored) { }
            }
        });

        // FA-ALA-020: Sammelfenster abarbeiten. Der Takt ist absichtlich
        // kuerzer als das kuerzeste sinnvolle Fenster — sonst haette ein
        // Fenster von 30 s effektiv 60 s.
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    Thread.sleep(15_000);
                    var d = benachrichtigung.versendeFaellige();
                    if (d.mails() > 0) {
                        protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.BENACHRICHTIGUNG_VERSENDET,
                                true, "Automatik", null,
                                "%d Benachrichtigung(en) versendet, %d Meldung(en) zusammengefasst"
                                        .formatted(d.mails(), d.alarme()), null);
                    }
                    for (String f : d.fehler()) {
                        protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.BENACHRICHTIGUNG_VERSENDET,
                                false, "Automatik", null,
                                "Benachrichtigung konnte nicht zugestellt werden", f);
                    }
                } catch (InterruptedException e) { return; }
                  catch (Exception ignored) { }
            }
        });
    }

    /**
     * Die Organisationseinheit eines Geräts — für die Zuordnung einer
     * Benachrichtigung.
     *
     * <p>Ohne bekanntes Gerät (etwa beim Speicheralarm) gibt es keine Einheit;
     * dann wird nicht benachrichtigt. Eine Mail an alle wäre die falsche
     * Antwort auf eine fehlende Zuordnung.
     */
    /**
     * Merkt sich die Version eines Agenten und protokolliert einen Wechsel.
     *
     * <p>Nur den Wechsel, nicht jede Meldung — sonst stünde bei 1.000 Agenten
     * im Minutentakt nichts anderes mehr im Protokoll (ADR-0005).
     */
    private void versionMerken(long agentId, Object protokoll_, Object baustein) {
        int version;
        try { version = ((Number) protokoll_).intValue(); }
        catch (Exception e) { version = 1; }   // ohne Angabe: die erste Fassung
        String bausteinText = baustein == null ? null : String.valueOf(baustein);

        Integer vorher = null;
        try (var p = db.verbindung().prepareStatement(
                "SELECT protokollversion FROM agent WHERE id = ?")) {
            p.setLong(1, agentId);
            try (var rs = p.executeQuery()) {
                if (rs.next()) {
                    int v = rs.getInt(1);
                    vorher = rs.wasNull() ? null : v;   // sofort auswerten
                }
            }
        } catch (java.sql.SQLException ignored) { }

        if (vorher != null && vorher == version) return;   // nichts geändert

        try (var p = db.verbindung().prepareStatement(
                "UPDATE agent SET protokollversion = ?, bausteinversion = ? WHERE id = ?")) {
            p.setInt(1, version);
            p.setString(2, bausteinText);
            p.setLong(3, agentId);
            p.executeUpdate();
        } catch (java.sql.SQLException e) {
            System.err.println("Agentenversion nicht speicherbar: " + e.getMessage());
        }

        String bewertung = de.pinroboter.gemeinsam.Protokollversion.bewertung(version);
        protokoll.anfuegen(einrichtungVonAgent(agentId), null, Ereignisart.AGENT_VERSION,
                bewertung == null,
                "Standort-Agent", null,
                "Standort-Agent %d meldet Protokoll %d (Baustein %s)"
                        .formatted(agentId, version, bausteinText == null ? "unbekannt" : bausteinText),
                bewertung);
    }

    /** Die Einrichtung des Agenten, der gerade meldet. */
    /**
     * Die Einrichtung des Agenten, der gerade meldet.
     *
     * <p>Der Rückfall ist {@link Fachprotokoll#INSTANZ}, nicht „Praxis": Ein
     * Agent ohne Einrichtung ist ein Datenfehler, und sein Ereignis gehört dann
     * in die Instanzverwaltung — nicht in eine Praxis, die es womöglich gibt.
     */
    private String einrichtungDesAgenten(Context c) {
        var a = (Agentenverwaltung.Agent) c.attribute("agent");
        return a == null || a.einrichtung() == null || a.einrichtung().isBlank()
                ? Fachprotokoll.INSTANZ : a.einrichtung();
    }

    private String einrichtungVonAgent(long agentId) {
        try (var p = db.verbindung().prepareStatement(
                "SELECT einrichtung FROM agent WHERE id = ?")) {
            p.setLong(1, agentId);
            try (var rs = p.executeQuery()) {
                if (!rs.next()) return Fachprotokoll.INSTANZ;
                String e = rs.getString(1);
                return e == null || e.isBlank() ? Fachprotokoll.INSTANZ : e;
            }
        } catch (java.sql.SQLException e) { return Fachprotokoll.INSTANZ; }
    }

    /**
     * Die Einrichtung eines Kartenterminals — aus dem Bestand.
     *
     * <p>Fällt auf {@link Fachprotokoll#INSTANZ} zurück, nicht auf „Praxis":
     * Ein Gerät ohne bekannte Einrichtung ist ein Datenfehler, und sein
     * Ereignis gehört dann in die Instanzverwaltung. Ein Rückfall auf einen
     * Praxisnamen würde es einer echten Einrichtung zuschlagen, die damit
     * nichts zu tun hat (Befund 12.08.2026).
     */
    private String einrichtungVonTerminal(String terminalId) {
        if (terminalId == null || terminalId.isBlank()) return Fachprotokoll.INSTANZ;
        // Dieselbe deterministische Form wie in Fachprotokoll: COALESCE statt
        // UNION ALL + LIMIT. Ohne ORDER BY ist die Reihenfolge einer Union
        // nicht festgelegt.
        try (var p = db.verbindung().prepareStatement("""
                SELECT coalesce(
                    (SELECT o.bezeichnung FROM kartenterminal t
                       JOIN organisation o ON o.id = t.organisation_id
                      WHERE t.terminal_id = ? LIMIT 1),
                    (SELECT g.einrichtung FROM geraet g WHERE g.terminal_id = ? LIMIT 1))""")) {
            p.setString(1, terminalId);
            p.setString(2, terminalId);
            try (var rs = p.executeQuery()) {
                if (rs.next()) {
                    String e = rs.getString(1);
                    if (e != null && !e.isBlank()) return e;
                }
            }
        } catch (java.sql.SQLException ignored) {
            // Kein Abbruch: Das Ereignis gehoert protokolliert, notfalls ohne
            // Einrichtungsbezug.
        }
        return Fachprotokoll.INSTANZ;
    }

    /** Die Einrichtung eines Kartenterminals über seine Datenbankkennung. */
    private String einrichtungVonTerminalId(long id) {
        try (var p = db.verbindung().prepareStatement(
                "SELECT o.bezeichnung FROM kartenterminal t "
                + "JOIN organisation o ON o.id = t.organisation_id WHERE t.id = ?")) {
            p.setLong(1, id);
            try (var rs = p.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (java.sql.SQLException e) { return null; }
    }

    private Long organisationVon(String terminalId) {
        if (terminalId == null) return null;
        try (var p = db.verbindung().prepareStatement(
                "SELECT organisation_id FROM kartenterminal WHERE terminal_id = ? LIMIT 1")) {
            p.setString(1, terminalId);
            try (var rs = p.executeQuery()) {
                if (!rs.next()) return null;
                long id = rs.getLong(1);
                return rs.wasNull() ? null : id;      // sofort auswerten
            }
        } catch (java.sql.SQLException e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private void agentMeldung(Context c) {
        Map<String, Object> m = c.bodyAsClass(Map.class);
        agentGesehen = Instant.now();
        String id = String.valueOf(m.get("terminalId"));

        Geraetezustand g = geraete.computeIfAbsent(id, k -> {
            Geraetezustand neu = new Geraetezustand();
            neu.terminalId = k;
            // Neu erkannte Geräte landen in der Vorgabegruppe (FA-GER-050),
            // damit sie überwacht werden, bevor jemand sie zuordnet.
            // Schlaegt das fehl, ist das Geraet nirgends erfasst und bekommt kein
            // Pruefintervall. Das darf nicht still passieren (Stand 09.08.2026).
            if (!gruppen.geraetSicherstellen(k, einrichtungDesAgenten(c),
                                             String.valueOf(m.get("name")))) {
                System.err.println("Geraet " + k + " konnte der Einrichtung "
                        + einrichtungDesAgenten(c) + " nicht zugeordnet werden.");
            }
            protokoll.anfuegen(einrichtungDesAgenten(c), k, Ereignisart.GERAET_ERKANNT, true, "Automatik", null,
                    "Kartenterminal erstmals erkannt", null);
            return neu;
        });

        PinStatus vorher = g.pinStatus;
        boolean vorherErreichbar = g.erreichbar;
        String vorherFirmware = g.firmware;
        // Aus der Registrierung, nicht aus der Meldung: Ein Agent koennte sonst
        // Geraete einer fremden Einrichtung unterschieben.
        g.einrichtung = einrichtungDesAgenten(c);

        g.name = str(m, "name", g.name);
        g.modell = str(m, "modell", g.modell);
        g.firmware = str(m, "firmware", g.firmware);
        g.kartenhandle = str(m, "kartenhandle", g.kartenhandle);
        g.erreichbar = bool(m, "erreichbar");
        g.pevimUnterstuetzt = bool(m, "pevimUnterstuetzt");
        g.pevimAktiv = bool(m, "pevimAktiv");
        g.aktualisierend = bool(m, "aktualisierend");
        g.automatikAngehalten = bool(m, "automatikAngehalten");
        g.verbleibendeVersuche = zahl(m, "verbleibendeVersuche");
        g.hinweis = str(m, "hinweis", null);
        g.handlung = str(m, "handlung", null);
        g.zuletztGeprueft = Instant.now();
        try { g.pinStatus = PinStatus.valueOf(String.valueOf(m.get("pinStatus"))); }
        catch (Exception e) { g.pinStatus = PinStatus.UNBEKANNT; }

        // Grundregel ADR-0005: nur Zustandsänderungen protokollieren, keine Abfragen.
        if (vorher != g.pinStatus && vorher != null) {
            protokoll.anfuegen(g.einrichtung, id, Ereignisart.PIN_STATUS_GEAENDERT, true, "Automatik", null,
                    "Status: %s → %s".formatted(vorher.anzeige(), g.pinStatus.anzeige()), null);
        }
        if (vorherErreichbar != g.erreichbar) {
            protokoll.anfuegen(g.einrichtung, id,
                    g.erreichbar ? Ereignisart.GERAET_ERREICHBAR : Ereignisart.GERAET_NICHT_ERREICHBAR,
                    g.erreichbar, "Automatik", null,
                    g.erreichbar ? "Gerät wieder erreichbar" : "Gerät nicht erreichbar", null);
        }

        // FA-UEB-030: Ein Versionswechsel wird protokolliert, UNABHAENGIG
        // davon, wer ihn ausgeloest hat. Das ist der Punkt: Aktualisiert der
        // Konnektor ein Terminal ueber seinen KSR-Client, erfaehrt der
        // Betreiber es sonst nirgends — und eine Firmware, die sich
        // unbemerkt aendert, macht jede Fehlersuche zum Ratespiel.
        if (vorherFirmware != null && !vorherFirmware.isBlank()
                && !vorherFirmware.equals(g.firmware) && g.firmware != null) {
            protokoll.anfuegen(g.einrichtung, id, Ereignisart.FIRMWARE_GEAENDERT, true,
                    "Automatik", null,
                    "Firmware: %s → %s".formatted(vorherFirmware, g.firmware),
                    "Nach einem Firmware-Wechsel prüft der Roboter Erreichbarkeit und "
                    + "PIN-Status erneut und stellt den verifizierten Zustand wieder her.");
        }

        // Vom Agenten gemeldete Ereignisse übernehmen
        Object roh = m.get("ereignisse");
        if (roh instanceof List<?> liste) {
            for (Object o : liste) {
                if (o instanceof Map<?, ?> e) {
                    Ereignisart art;
                    try { art = Ereignisart.valueOf(String.valueOf(e.get("art"))); }
                    catch (Exception ex) { continue; }
                    // Die Einrichtung DES GERAETS, nicht ein fester Name: Sonst
                    // stehen die Ereignisse der Automatik unter einer Praxis,
                    // die damit nichts zu tun hat — und fehlen dem
                    // Zustaendigen (Befund 12.08.2026).
                    protokoll.anfuegen(einrichtungVonTerminal(id), id, art,
                            Boolean.TRUE.equals(e.get("erfolg")), "Automatik",
                            e.get("vorgang") == null ? null : String.valueOf(e.get("vorgang")),
                            String.valueOf(e.get("text")),
                            e.get("handlung") == null ? null : String.valueOf(e.get("handlung")));
                }
            }
        }
        alarmzustaendeAbleiten(g);
        c.json(Map.of("ok", true, "notAus", notAus));
    }

    // ---------------------------------------------------------------- Ansichten

    /**
     * NFA-SIC-050: Die Sitzungskennung ist gegen Auslesen durch Skripte
     * geschützt (HttpOnly) und wird bei seitenübergreifenden Anfragen nicht
     * mitgeschickt (SameSite=Strict). Das Secure-Kennzeichen setzt der
     * Reverse-Proxy in der Auslieferung; im Prototyp läuft die Oberfläche
     * unverschlüsselt auf localhost.
     */
    /**
     * Leitet aus dem gemeldeten Gerätezustand die Alarme ab.
     *
     * <p>Jeder Zustand wird in beide Richtungen behandelt: Liegt er vor, wird
     * gemeldet; liegt er nicht mehr vor, wird der Alarm geschlossen. Nur so
     * verschwindet ein Alarm von selbst, wenn sich das Problem erledigt hat —
     * und nur dann bedeutet ein offener Alarm wirklich noch etwas.
     */
    private void alarmzustaendeAbleiten(Geraetezustand g) {
        String o = g.terminalId;

        pruefe(g.einrichtung, g.automatikAngehalten, o, Alarmverwaltung.Art.AUTOMATIK_ANGEHALTEN,
                "Die Automatik wurde für dieses Gerät angehalten.",
                g.handlung != null ? g.handlung
                        : "Bitte die hinterlegte PIN prüfen und die Automatik wieder freigeben.");

        pruefe(g.einrichtung, !g.erreichbar, o, Alarmverwaltung.Art.GERAET_NICHT_ERREICHBAR,
                "Das Kartenterminal antwortet nicht.",
                "Bitte prüfen, ob das Gerät eingeschaltet und mit dem Netzwerk verbunden ist.");

        pruefe(g.einrichtung, g.pinStatus == de.pinroboter.gemeinsam.PinStatus.BLOCKED, o,
                Alarmverwaltung.Art.KARTE_GESPERRT,
                "Die SMC-B ist gesperrt. Der TI-Zugang über dieses Gerät ist unterbrochen.",
                "Die Karte lässt sich nur mit der PUK entsperren. Ein weiterer PIN-Versuch hilft nicht.");

        pruefe(g.einrichtung, g.pinStatus.handlungsbedarf()
                        && g.pinStatus != de.pinroboter.gemeinsam.PinStatus.BLOCKED,
                o, Alarmverwaltung.Art.HANDLUNGSBEDARF_KARTE,
                g.pinStatus.erklaerung(),
                "Dieser Zustand lässt sich nur von Hand am Kartenterminal auflösen.");

        pruefe(g.einrichtung, g.erreichbar && g.pevimUnterstuetzt && !g.pevimAktiv, o,
                Alarmverwaltung.Art.PEVIM_NICHT_AKTIV,
                "Die automatische PIN-Eingabe ist an diesem Gerät nicht aktiviert.",
                "Ein Administrator muss die Funktion direkt am Kartenterminal freischalten. "
                + "Bis dahin wird das Gerät überwacht, aber nicht automatisch freigeschaltet.");
    }

    /**
     * @param einrichtung Pflichtparameter, kein Vorgabewert. Ein Alarm ohne
     *        Einrichtung ist für einen Mandanten unsichtbar — und ein
     *        unsichtbarer Alarm ist schlimmer als keiner.
     */
    private void pruefe(String einrichtung, boolean liegtVor, String objekt,
                        Alarmverwaltung.Art art, String meldung, String handlung) {
        if (liegtVor) {
            if (alarme.melden(einrichtung, objekt, art, meldung, handlung)) {
                protokoll.anfuegen(einrichtung, objekt, Ereignisart.ALARM_AUSGELOEST, false,
                        "Automatik", null, meldung, handlung);
                // FA-ALA-020: nur bei NEUEN Alarmen einreihen. Ein
                // wiederkehrender Alarm desselben Zustands wuerde die Flut
                // sonst auf anderem Weg erzeugen.
                benachrichtigung.einreihen(organisationVon(objekt), art.schweregrad().name(),
                        objekt, meldung, handlung);
            }
        } else if (alarme.beheben(einrichtung, objekt, art)) {
            protokoll.anfuegen(einrichtung, objekt, Ereignisart.ALARM_BEHOBEN, true,
                    "Automatik", null, "Zustand behoben: " + art.name(), null);
        }
    }

    /**
     * Prüft den Füllstand und rotiert bei Bedarf.
     *
     * <p><b>Die Kernfunktion wird dabei nie angehalten.</b> Eine Praxis darf
     * nicht den TI-Zugang verlieren, weil eine Protokolldatei voll ist — der
     * Zustand wird alarmiert, nicht durchgereicht (ADR-0005).
     */
    void speicherPruefen() {
        var z = speicher.zustand();

        if (z.alarmNoetig()) {
            // Instanzweit: Das Protokoll gehoert keiner Einrichtung.
            alarme.melden(Fachprotokoll.INSTANZ, null, Alarmverwaltung.Art.SPEICHER_KNAPP,
                    "Das Protokoll belegt %s von %s (%d %%)."
                            .formatted(z.belegtLesbar(), z.budgetLesbar(), z.prozent()),
                    "Ältere Einträge werden automatisch gelöscht. Wenn Sie sie behalten "
                    + "möchten, exportieren Sie das Protokoll und erhöhen Sie das "
                    + "Speicherbudget in der Konfiguration.");
        } else if (z.anteil() < Speicherwaechter.SCHWELLE_ROTATION) {
            alarme.beheben(Fachprotokoll.INSTANZ, null, Alarmverwaltung.Art.SPEICHER_KNAPP);
        }

        if (z.rotationNoetig()) {
            var e = speicher.rotieren();
            if (e.rotiert() && e.geloescht() > 0) {
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.PROTOKOLL_ROTIERT, true,
                        "Automatik", null,
                        "%d Einträge gelöscht, Füllstand jetzt %d %%"
                                .formatted(e.geloescht(), e.danach().prozent()),
                        e.nachweiskettUnterbrochen()
                            ? "Es wurden auch sicherheitsrelevante Einträge gelöscht. "
                              + "Bitte das Speicherbudget erhöhen und künftig regelmäßig "
                              + "exportieren, damit die Nachweiskette lückenlos bleibt."
                            : null);
            }
        }
    }

    private static String leerAls(String wert, String ersatz) {
        return wert == null || wert.isBlank() ? ersatz : wert;
    }

    /**
     * Baut die Bestandsseite mit den Filtern aus der Anfrage.
     *
     * <p>Der Platzhalter {@code #bestandsmeldung} nimmt die Rückmeldung der
     * Erreichbarkeitsprüfung auf, ohne die Seite neu zu laden — sonst ginge die
     * Position in einer langen Geräteliste verloren.
     */
    /**
     * Baut die Terminalmaske einschließlich PIN-Zustand.
     *
     * <p>An einer Stelle, damit die Maske überall denselben Zustand zeigt —
     * eine zweite Aufbaustelle wäre eine zweite Gelegenheit, den PIN-Teil zu
     * vergessen.
     */
    private String terminalMaske(Bestandsverwaltung.Terminal t, String fehler) {
        return terminalMaske(t, fehler, null);
    }

    private String terminalMaske(Bestandsverwaltung.Terminal t, String fehler,
                                 Geltungsbereich bereich) {
        var karten = ermittlung.karten(t.terminalId());
        String smcb = karten.stream().filter(Ermittlung.Karte::verwaltet)
                .map(Ermittlung.Karte::kartenhandle).findFirst().orElse(null);
        return Bestandsseiten.terminalMaske(t, karten, fehler, smcb,
                smcb == null ? null : pins.schluesselFuerTerminal(t.terminalId()).orElse(null),
                smcb != null && pins.hinterlegtFuer(smcb).isPresent(),
                smcb != null && pins.auftragOffen(smcb),
                bereich == null ? null : bestand.konnektoren(bereich));
    }

    private String benachrichtigungsSeite(io.javalin.http.Context c, String meldung,
                                          boolean istFehler, String verlauf) {
        return Benachrichtigungsseiten.seite(benachrichtigung.server().orElse(null),
                benachrichtigung.regeln(bereich(c)), bestand.organisationen(bereich(c)),
                rechte(c).darfInstanzVerwalten(), rechte(c).darfBenutzerVerwalten(),
                meldung, istFehler, verlauf);
    }

    private String organisationsSeite(io.javalin.http.Context c, String meldung,
                                      boolean istFehler) {
        return organisationsSeite(c, meldung, istFehler, strukturGewuenscht(c));
    }

    private String organisationsSeite(io.javalin.http.Context c, String meldung,
                                      boolean istFehler, boolean struktur) {
        var baum = organisationen.baum(bereich(c));
        return Organisationsseiten.seite(baum, rechte(c).darfOrganisationVerwalten(),
                meldung, istFehler, struktur,
                struktur ? bilanzen(baum, bereich(c)) : java.util.Map.of(),
                struktur ? strukturgeraete(bereich(c)) : java.util.Map.of(),
                struktur ? strukturkonnektoren(bereich(c)) : java.util.Map.of());
    }

    /**
     * Welche Ansicht gewünscht ist — Struktur oder Liste.
     *
     * <p>Die Wahl steht im Kekserl, nicht in der Adresse: Wer die Struktur
     * bevorzugt, will sie beim nächsten Aufruf wieder sehen, auch nach einem
     * Umweg über eine Detailseite. Ein Parameter in der Adresse wäre nach der
     * ersten Weiterleitung verloren.
     */
    private boolean strukturGewuenscht(io.javalin.http.Context c) {
        String q = c.queryParam("ansicht");
        if (q != null) {
            c.cookie("pr-organisationsansicht", q, 60 * 60 * 24 * 365);
            return !"liste".equals(q);
        }
        return !"liste".equals(c.cookie("pr-organisationsansicht"));
    }

    /**
     * Die Kartenterminals je Organisationseinheit, mit ihrem aktuellen Zustand.
     *
     * <p>Der Bestand sagt, welches Gerät wohin gehört; der Arbeitsspeicher, wie
     * es ihm geht. Ein Gerät, von dem noch keine Meldung kam, gilt als
     * „unbekannt" — nicht als in Ordnung. Das ist der Unterschied zwischen
     * „geprüft und gut" und „noch nie gesehen".
     */
    private java.util.Map<Long, java.util.List<Strukturansicht.Geraet>> strukturgeraete(
            Geltungsbereich bereich) {
        var nach = new java.util.HashMap<Long, java.util.List<Strukturansicht.Geraet>>();
        for (var g : bestand.strukturgeraete(bereich)) {
            var z = geraete.get(g.terminalId());
            nach.computeIfAbsent(g.organisationId(), k -> new java.util.ArrayList<>())
                .add(new Strukturansicht.Geraet(g.terminalId(),
                        g.bezeichnung() == null || g.bezeichnung().isBlank()
                                ? g.terminalId() : g.bezeichnung(),
                        g.hatSmcb(), z == null ? Ampel.UNBEKANNT : z.ampel()));
        }
        return nach;
    }

    /** Die Konnektoren je Organisationseinheit — betriebene und mitgenutzte. */
    private java.util.Map<Long, java.util.List<Strukturansicht.Konnektor>> strukturkonnektoren(
            Geltungsbereich bereich) {
        var nach = new java.util.HashMap<Long, java.util.List<Strukturansicht.Konnektor>>();
        for (var k : bestand.strukturkonnektoren(bereich)) {
            nach.computeIfAbsent(k.organisationId(), x -> new java.util.ArrayList<>())
                .add(new Strukturansicht.Konnektor(k.id(), k.bezeichnung(), k.art(),
                        k.betreibt()));
        }
        return nach;
    }

    /**
     * Zahlen und Gerätezustand je Organisationseinheit, über den Teilbaum
     * summiert.
     *
     * <p>Der Zustand liegt im Arbeitsspeicher (Meldungen der Agenten), die
     * Zuordnung in der Datenbank. Beides wird hier einmal je Seitenaufruf
     * zusammengeführt — nicht je Einheit, sonst wären es bei einem
     * Klinikverbund hunderte Abfragen für eine Seite.
     *
     * <p>Das Summieren über den Teilbaum macht
     * {@link Strukturansicht#summieren} — dort ist es ohne Datenbank prüfbar.
     */
    private java.util.Map<Long, Strukturansicht.Bilanz> bilanzen(
            java.util.List<Organisationsverwaltung.Einheit> baum, Geltungsbereich bereich) {
        var zuordnung = bestand.terminalOrganisationen(bereich);
        var eigene = new java.util.HashMap<Long, Strukturansicht.Bilanz>();

        // Erst die eigenen Geräte je Einheit.
        for (var eintrag : zuordnung.entrySet()) {
            var z = geraete.get(eintrag.getKey());
            Ampel a = z == null ? Ampel.UNBEKANNT : z.ampel();
            var alt = eigene.getOrDefault(eintrag.getValue(), Strukturansicht.Bilanz.LEER);
            eigene.put(eintrag.getValue(), alt.plus(new Strukturansicht.Bilanz(
                    1, 0, 0,
                    a == Ampel.OK ? 1 : 0, a == Ampel.WARNUNG ? 1 : 0,
                    a == Ampel.FEHLER ? 1 : 0, a == Ampel.UNBEKANNT ? 1 : 0)));
        }
        // Konnektoren und Konten stehen schon in der Einheit selbst.
        for (var e : baum) {
            var alt = eigene.getOrDefault(e.id(), Strukturansicht.Bilanz.LEER);
            eigene.put(e.id(), alt.plus(new Strukturansicht.Bilanz(
                    0, e.konnektoren(), e.benutzer(), 0, 0, 0, 0)));
        }

        // Dann von unten nach oben aufsummieren.
        return Strukturansicht.summieren(baum, eigene);
    }

    private String organisationsDetail(io.javalin.http.Context c, long id,
                                       String meldung, boolean istFehler) {
        var e = organisationen.einheit(id, bereich(c));
        if (e.isEmpty()) {
            // Absichtlich dieselbe Antwort wie fuer eine Einheit ausserhalb des
            // Bereichs: Ob es sie gibt, geht einen Fremden nichts an.
            c.status(404);
            return organisationsSeite(c, "Diese Organisationseinheit steht Ihnen nicht "
                    + "zur Verfügung.", true);
        }
        return Organisationsseiten.detailseite(e.get(),
                organisationen.pfad(id, bereich(c)),
                organisationen.beziehungen(id, bereich(c)),
                organisationen.baum(bereich(c)),
                rechte(c).darfOrganisationVerwalten(), meldung, istFehler);
    }

    /** Zahl aus einem Formularfeld, oder {@code null}. Ein Tippfehler darf nicht abstürzen. */
    private static Long zahlOderNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private String bestandsSeite(Context c, String meldung, boolean istFehler) {
        var ansicht = ansichtAus(c);
        int seitenNummer = zahl(c.queryParam("seite"), 1);

        return "<div id=\"bestandsmeldung\" aria-live=\"polite\"></div>"
                + Bestandsseiten.seite(
                        bestand.konnektoren(bereich(c)),
                        bestand.terminals(ansicht.filter(), ansicht.sortierung(),
                                ansicht.absteigend(), seitenNummer, Bestandsseiten.SEITENGROESSE,
                                bereich(c)),
                        bestand.organisationen(bereich(c)), ansicht,
                        bestand.zugangsdatenMoeglich(), meldung, istFehler,
                        param(c, "ksuche"),
                        param(c, "ksortier").isBlank() ? "bezeichnung" : param(c, "ksortier"),
                        "ab".equals(param(c, "krichtung")));
    }

    /**
     * Liest Filter und Sortierung aus der Anfrage.
     *
     * <p>Alle Werte werden geprüft und auf feste Aufzählungen abgebildet — ein
     * unbekannter Wert führt zur Vorgabe, nicht zu einem Fehler. Der Benutzer
     * soll eine kaputte Verknüpfung nicht als Absturz erleben.
     */
    private Bestandsseiten.Ansicht ansichtAus(Context c) {
        Long konnektor = null;
        try {
            String k = c.queryParam("konnektor");
            if (k != null && !k.isBlank()) konnektor = Long.parseLong(k);
        } catch (NumberFormatException ignored) { }

        Boolean aktiv = switch (String.valueOf(c.queryParam("status"))) {
            case "aktiv"   -> Boolean.TRUE;
            case "inaktiv" -> Boolean.FALSE;
            default        -> null;
        };
        Boolean erreichbar = switch (String.valueOf(c.queryParam("erreichbar"))) {
            case "ja"   -> Boolean.TRUE;
            case "nein" -> Boolean.FALSE;
            default     -> null;
        };
        // Absprung von der Organisations-Detailseite: ?einrichtung=<id>
        Long einrichtung = zahlOderNull(c.queryParam("einrichtung"));
        Boolean smcb = "ja".equals(c.queryParam("smcb")) ? Boolean.TRUE : null;
        var filter = new Bestandsverwaltung.Filter(c.queryParam("suche"), konnektor, aktiv,
                erreichbar, c.queryParam("modell"), c.queryParam("firmware"), smcb, einrichtung);
        var sortierung = Bestandsverwaltung.Sortierung.aus(c.queryParam("sortierung"));
        boolean absteigend = "ab".equals(c.queryParam("richtung"));

        return new Bestandsseiten.Ansicht(filter, sortierung, absteigend,
                bestand.vorhandeneWerte("modell", bereich(c)), bestand.vorhandeneWerte("firmware", bereich(c)),
                // Alle Gruppen im Geltungsbereich. Vorher stand hier ein fester
                // Einrichtungsname — die Auswahl „Gerätegruppe" der
                // Massenaktion blieb damit in einem MVZ leer.
                gruppen.alle(bereich(c)), ermittlung.aufrufkontextAuswahl());
    }

    private static int zahl(String s, int vorgabe) {
        try { return s == null || s.isBlank() ? vorgabe : Integer.parseInt(s); }
        catch (NumberFormatException e) { return vorgabe; }
    }

    /** Alle Werte eines Formularfelds als Kennungen — für Mehrfachauswahl. */
    private static List<Long> kennungen(Context c, String feld) {
        List<Long> ids = new ArrayList<>();
        for (String w : c.formParams(feld)) {
            try { ids.add(Long.parseLong(w.trim())); } catch (NumberFormatException ignored) { }
        }
        return ids;
    }

    /**
     * Der Geltungsbereich des angemeldeten Benutzers.
     *
     * <p>Einzige Stelle, an der er entsteht. Jede mandantengebundene Abfrage
     * bekommt ihn von hier — und verlangt ihn als Pflichtparameter, damit er
     * nicht vergessen werden kann ({@link Geltungsbereich}).
     *
     * <p>Ohne angemeldeten Benutzer: {@link Geltungsbereich#keiner()}. Nicht
     * „alles". Diese Richtung ist die ganze Sicherheit dieser Methode.
     */
    private Geltungsbereich bereich(Context c) {
        var b = angemeldeter(c);
        if (b == null) return Geltungsbereich.keiner();
        return Geltungsbereich.fuerBenutzer(db, b.organisationId(), b.istInstanzbetreiber());
    }

    /**
     * Baut die Benutzerseite mit Filtern aus der Anfrage.
     *
     * <p>Filterung und Blättern laufen serverseitig — bei bis zu 1.000 Benutzern
     * ist alles andere unbrauchbar. Der Geltungsbereich schränkt zusätzlich ein:
     * Ein Praxisadministrator sieht nur die Konten seiner Praxis.
     */
    private String benutzerSeite(Context c, String meldung, boolean istFehler,
                                 String neuesGeheimnis, String neuerName) {
        var ansicht = new Benutzerseiten.Ansicht(c.queryParam("suche"), c.queryParam("rolle"),
                c.queryParam("status"), Math.max(1, zahl(c.queryParam("seite"), 1)));

        // Absprung von der Organisations-Detailseite: ?organisation=<id>.
        // Eingeschraenkt wird auf den Teilbaum, nicht nur die Einheit selbst —
        // sonst zeigte ein Standort keine Konten, weil sie an den
        // Einrichtungen darunter haengen.
        Long orgFilter = zahlOderNull(c.queryParam("organisation"));
        java.util.Set<Long> imTeilbaum = orgFilter == null ? null
                : organisationen.baum(Geltungsbereich.fuerBenutzer(db, orgFilter, false))
                        .stream().map(Organisationsverwaltung.Einheit::id)
                        .collect(java.util.stream.Collectors.toSet());

        var alle = benutzer.alle(bereich(c)).stream()
                .filter(b -> imTeilbaum == null
                        || (b.organisationId() != null && imTeilbaum.contains(b.organisationId())))
                .filter(b -> passt(b, ansicht))
                .sorted(java.util.Comparator.comparing(
                        x -> x.benutzername().toLowerCase()))
                .toList();
        int von = Math.min(alle.size(), (ansicht.seite() - 1) * Benutzerseiten.SEITENGROESSE);
        int bis = Math.min(alle.size(), von + Benutzerseiten.SEITENGROESSE);

        return Benutzerseiten.seite(alle.subList(von, bis), alle.size(), ansicht,
                einladungen.offene(bereich(c)), bestand.organisationen(bereich(c)),
                rechte(c).darfBenutzerVerwalten(), meldung, istFehler,
                neuesGeheimnis, neuerName);
    }

    /**
     * Filtert ein Konto gegen die Ansicht.
     *
     * <p>Im Speicher, nicht in der Abfrage: Bei 1.000 Konten ist das
     * vertretbar, und die Alternative wäre eine weitere Abfrage mit
     * Mandantengrenze — eine Stelle mehr, an der sie fehlen könnte. Sollte die
     * Zahl je deutlich wachsen, gehört das in die Datenbank.
     */
    private static boolean passt(Benutzerverwaltung.Benutzer b, Benutzerseiten.Ansicht a) {
        String suche = a.suche() == null ? "" : a.suche().trim().toLowerCase();
        if (!suche.isEmpty()
                && !b.benutzername().toLowerCase().contains(suche)
                && !b.anzeigename().toLowerCase().contains(suche)) return false;
        if (a.rolle() != null && !a.rolle().isBlank() && !b.rolle().name().equals(a.rolle())) return false;
        return switch (a.status() == null ? "" : a.status()) {
            case "aktiv"     -> b.aktiv();
            case "inaktiv"   -> !b.aktiv();
            case "ohne-totp" -> !b.totpBestaetigt();
            default          -> true;
        };
    }

    /**
     * Vermerkt einen Fehlversuch für die Absenderbremse.
     *
     * <p>Protokolliert wird erst, wenn die Bremse <b>greift</b> — nicht jeder
     * einzelne Fehlversuch. Sonst wäre ein Angriff mit tausend Versuchen
     * gleichbedeutend mit tausend Protokolleinträgen, und genau das würde die
     * Protokoll-Grundregel aushebeln: Zustandsänderungen protokollieren, keine
     * Abfragen.
     *
     * <p>Die Adresse selbst steht <b>nicht</b> im Protokoll. Sie ist ein
     * personenbezogenes Datum, und für die Aussage „hier läuft etwas
     * Auffälliges" wird sie nicht gebraucht.
     */
    private void zaehleFehlversuch(String absender, String benutzername) {
        boolean vorherFrei = !bremse.gebremst(absender);
        int n = bremse.fehlversuch(absender);
        if (vorherFrei && bremse.gebremst(absender)) {
            protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.ANMELDUNG_GEBREMST, false,
                    "System", null,
                    "Anmeldeversuche von einem Absender werden gebremst (%d Fehlversuche)"
                            .formatted(n),
                    "Weitere Versuche dieses Absenders werden zunehmend verzögert. "
                    + "Bei anhaltenden Versuchen prüfen, ob ein Angriff vorliegt.");
        }
    }

    /** Ein nicht angekreuztes Kästchen wird vom Browser gar nicht gesendet. */
    private static boolean angekreuzt(Context c, String name) {
        return c.formParam(name) != null;
    }

    /**
     * Die Standort-Agenten mit ihrer Version (FA-AGT-040).
     *
     * <p>Bei 1.000 Standorten ist eine gemischte Landschaft der Normalfall —
     * Watchtower rollt nicht überall gleichzeitig aus. Ohne diese Ansicht
     * zeigt sich ein Versionsunterschied als seltsames Verhalten, dessen
     * Ursache niemand findet.
     */
    /**
     * Verfügbare Versionen und der Zustand der Prüfung (FA-UPD-010).
     *
     * <p>Der <b>Fehlerzustand steht oben</b>, nicht unten: Ein abgelaufenes
     * Token bedeutet, dass gar nicht mehr geprüft wird — und das sieht sonst
     * aus wie „es gibt keine Updates".
     */
    private String versionstafel() {
        var z = versionen.zustand();
        StringBuilder s = new StringBuilder("<div class=\"tafel\"><h3>Produktversion");
        if (z.sicherheitsupdateOffen()) {
            s.append(" <span class=\"marke fehler\"><span class=\"sym\">!</span>"
                    + "Sicherheitsupdate verfügbar</span>");
        } else if (z.neueVerfuegbar()) {
            s.append(" <span class=\"marke warnung\">neue Version verfügbar</span>");
        } else if (z.fehler() == null && z.zuletztGeprueft() != null) {
            s.append(" <span class=\"marke ok\"><span class=\"sym\">✓</span>aktuell</span>");
        }
        s.append("</h3><div class=\"karte-leib\">");

        if (z.fehler() != null) {
            s.append("<div class=\"hinweis warnend\"><div><strong>Die Registry lässt sich "
                    + "nicht abfragen.</strong> ").append(esc(z.fehler()))
             .append("</div></div>");
        }
        s.append("<p class=\"hilfe\">Laufende Version: <code>").append(esc(z.laufendeVersion()))
         .append("</code>")
         .append(z.zuletztGeprueft() == null ? " · noch nicht geprüft"
                 : " · zuletzt geprüft " + esc(java.time.format.DateTimeFormatter
                        .ofPattern("dd.MM.yyyy HH:mm").withZone(java.time.ZoneId.systemDefault())
                        .format(z.zuletztGeprueft())))
         .append("</p></div>");

        if (!z.verfuegbare().isEmpty()) {
            s.append("<div class=\"rollbar\"><table><caption class=\"nurLeser\">"
                    + "Verfügbare Versionen</caption><thead><tr>"
                    + "<th scope=\"col\">Version</th><th scope=\"col\">Art</th>"
                    + "<th scope=\"col\">Was sich ändert</th></tr></thead><tbody>");
            for (var v : z.verfuegbare()) {
                s.append("<tr><td><code>").append(esc(v.nummer())).append("</code></td><td>")
                 .append(!v.neuerAlsLaufende()
                         ? "<span class=\"marke\">bereits installiert oder älter</span>"
                         : v.sicherheitsrelevant()
                            ? "<span class=\"marke fehler\"><span class=\"sym\">!</span>Sicherheit</span>"
                            : "<span class=\"marke warnung\">Verbesserung</span>")
                 .append("</td><td>").append(esc(v.hinweis() == null
                         ? "Keine Änderungshinweise hinterlegt." : v.hinweis()))
                 .append("</td></tr>");
            }
            s.append("</tbody></table></div>");
        }

        s.append("""
                <div class="maske" style="border-top:1px solid var(--rand)">
                <form method="post" action="/verwaltung/registry" class="maske karte-blank">
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="rg-host">Registry</label>
                      <input id="rg-host" name="registry" required maxlength="200" value="%s"
                             placeholder="https://ghcr.io"></div>
                    <div class="feldgruppe"><label for="rg-abb">Abbild</label>
                      <input id="rg-abb" name="abbild" required maxlength="200" value="%s"
                             placeholder="nieting/pin-roboter"></div>
                  </div>
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="rg-ben">Benutzername <span class="optional">(optional)</span></label>
                      <input id="rg-ben" name="benutzer" maxlength="200" value="%s" autocomplete="off"></div>
                    <div class="feldgruppe"><label for="rg-tok">Zugriffstoken</label>
                      <input id="rg-tok" name="token" type="password" maxlength="400"
                             autocomplete="new-password" aria-describedby="rg-tok-hilfe"></div>
                  </div>
                  <p id="rg-tok-hilfe" class="hilfe">%s Es wird verschlüsselt gespeichert und
                     lässt sich nicht wieder anzeigen. Ein leeres Feld lässt das gespeicherte
                     Token unverändert. Der Roboter <strong>liest nur</strong> — er braucht
                     keinen Zugriff auf den Docker-Dienst und führt kein Update selbst aus
                     (ADR-0001).</p>
                  <div class="reihe-frei">
                    <button class="knopf haupt" type="submit" title="Speichert die Registry-Zugangsdaten verschlüsselt" aria-label="Speichert die Registry-Zugangsdaten verschlüsselt">Zugang speichern</button>
                  </div>
                </form>
                <form method="post" action="/verwaltung/registry/pruefen" class="abstand-oben">
                  <button class="knopf" type="submit" title="Fragt die Registry sofort ab, statt auf die tägliche Prüfung zu warten" aria-label="Fragt die Registry sofort ab, statt auf die tägliche Prüfung zu warten">Jetzt auf neue Versionen prüfen</button>
                </form>
                </div>""".formatted(esc(nz(registryFeld("registry"))), esc(nz(registryFeld("abbild"))),
                        esc(nz(registryFeld("benutzer"))),
                        z.zugangHinterlegt() ? "Ein Token ist hinterlegt."
                                             : "Es ist kein Token hinterlegt."));
        return s.append("</div>").toString();
    }

    private String registryFeld(String spalte) {
        try (var p = db.verbindung().prepareStatement(
                "SELECT " + spalte + " FROM registry_zugang WHERE nur_eine")) {
            try (var rs = p.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (java.sql.SQLException e) { return null; }
    }

    /**
     * Formular zum Einrichten eines Agenten (FA-AGT-010).
     *
     * <p>Bis zum 09.08.2026 gab es das nur über die Kommandozeile — in der
     * Oberfläche fehlte es ganz. Ein Betreiber ohne IT-Personal konnte damit
     * keinen zweiten Standort anbinden.
     *
     * <p><b>Die Einrichtung wird hier gewählt</b>, nicht später vom Agenten
     * gemeldet. Das ist der einzige Zeitpunkt, an dem ein Mensch weiß, wo das
     * Gerät steht — und es ist die sichere Richtung: Ein Agent könnte sonst
     * Geräte einer fremden Einrichtung unterschieben.
     */
    private String agentAnlegenFormular(Geltungsbereich bereich) {
        var einheiten = bestand.organisationen(bereich);

        // Ohne Organisationseinheit kein Agent — und das gehoert gesagt, nicht
        // durch eine leere Auswahlliste angedeutet.
        //
        // Bis zum 11.08.2026 stand hier ersatzweise „Praxis (ohne Zuordnung)".
        // Ein damit registrierter Agent meldete Geraete unter einem Namen, zu
        // dem es keine Organisationseinheit gibt: Solche Daten sind fuer jede
        // mandantengebundene Abfrage unsichtbar und lassen sich niemandem
        // zeigen. „Ohne Zuordnung" klang nach einer Wahlmoeglichkeit und war in
        // Wahrheit eine Sackgasse.
        if (einheiten.isEmpty()) {
            return """
                    <div class="hinweis abstand-oben">
                      <strong>Zuerst eine Einrichtung anlegen</strong>
                      Ein Agent arbeitet für genau eine Einrichtung — alles, was er meldet,
                      gehört dorthin. Solange keine angelegt ist, ließen sich seine Geräte
                      niemandem zuordnen.
                      <a href="/organisation">Zur Organisationsstruktur &rarr;</a>
                    </div>""";
        }

        StringBuilder auswahl = new StringBuilder();
        for (var o : einheiten) {
            auswahl.append("<option value=\"").append(esc(o.bezeichnung())).append("\">")
                   .append(esc(o.bezeichnung())).append("</option>");
        }
        return """
                <div class="maske" style="border-top:1px solid var(--rand)">
                <form method="post" action="/agenten/geheimnis" class="maske karte-blank"
                      hx-post="/agenten/geheimnis" hx-target="#agentGeheimnis" hx-swap="innerHTML">
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="ag-bez">Bezeichnung</label>
                      <input id="ag-bez" name="bezeichnung" required maxlength="120"
                             placeholder="z. B. Agent Praxis Nord"></div>
                    <div class="feldgruppe"><label for="ag-einr">Einrichtung</label>
                      <select id="ag-einr" name="einrichtung" aria-describedby="ag-hilfe">%s</select></div>
                    <div class="feldaktion">
                      <button id="ag-k" class="knopf haupt" type="submit" title="Erzeugt einen einmaligen Code, mit dem sich ein Standort-Agent anmeldet" aria-label="Erzeugt einen einmaligen Code, mit dem sich ein Standort-Agent anmeldet">Zugangscode erzeugen</button></div>
                  </div>
                  <p id="ag-hilfe" class="hilfe">Alles, was dieser Agent meldet — Geräte, Alarme,
                     Protokolleinträge — gehört zu dieser Einrichtung. Sie lässt sich später nicht
                     ändern; ein Agent steht in genau einer Praxis.</p>
                </form>
                <div id="agentGeheimnis"></div>
                </div>""".formatted(auswahl);
    }

    private String agententafel(Geltungsbereich bereich) {
        var liste = agenten.alle();
        StringBuilder s = new StringBuilder("<div class=\"tafel\"><h3>Standort-Agenten "
                + "<span class=\"zaehler\">" + liste.size() + "</span></h3>");
        if (liste.isEmpty()) {
            return s.append("""
                    <p class="leer">Noch kein Agent registriert. Unter „Bestand" lässt sich ein
                       Registrierungsgeheimnis erzeugen.</p></div>""").toString();
        }
        s.append("<div class=\"rollbar\"><table><caption class=\"nurLeser\">"
                + "Registrierte Standort-Agenten mit Protokollversion</caption><thead><tr>"
                + "<th scope=\"col\">Agent</th><th scope=\"col\">Einrichtung</th>"
                + "<th scope=\"col\">Protokoll</th><th scope=\"col\">Baustein</th>"
                + "<th scope=\"col\">Zuletzt gesehen</th>"
                + "<th scope=\"col\">Zuordnung ändern</th></tr></thead><tbody>");
        for (var a : liste) {
            String bewertung = a.versionsbewertung();
            s.append("<tr><td class=\"geraet\">").append(esc(a.bezeichnung())).append("</td>")
             .append("<td>").append(esc(nz(a.einrichtung())))
             // Der Agent speichert den NAMEN der Einrichtung. Wird sie
             // umbenannt oder geloescht, meldet er ins Leere: Seine Geraete
             // landen in keiner Gruppe. Bis zum 12.08.2026 stand das nur im
             // technischen Protokoll - in der Oberflaeche sah alles normal aus.
             .append(a.einrichtungBekannt() ? "" : """
                     <div class="handlung"><span class="marke warnung">Einrichtung unbekannt</span>
                       <div>Es gibt keine Organisationseinheit dieses Namens. Dieser Agent kann
                          seine Geräte keiner Gruppe zuordnen. Bitte unten die richtige
                          Einrichtung wählen.</div></div>""")
             .append("</td><td>")
             .append(a.protokollversion() == null
                     ? "<span class=\"marke unbekannt\">noch nicht gemeldet</span>"
                     : a.versionUnterstuetzt() && bewertung == null
                        ? "<span class=\"marke ok\"><span class=\"sym\">✓</span>Version "
                          + a.protokollversion() + "</span>"
                        : "<span class=\"marke warnung\">Version " + a.protokollversion() + "</span>")
             .append(bewertung == null ? ""
                     : "<div class=\"handlung\">" + esc(bewertung) + "</div>")
             .append("</td><td><code>").append(esc(nz(a.bausteinversion()))).append("</code></td>")
             .append("<td class=\"zeit\">")
             .append(a.zuletztGesehen() == null ? "nie"
                     : esc(java.time.format.DateTimeFormatter.ofPattern("dd.MM. HH:mm")
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(a.zuletztGesehen())))
             .append("</td><td>")
             .append("""
                     <form method="post" action="/agenten/%d/einrichtung" class="inlineform">
                       <label class="nurLeser" for="ag-e-%d">Einrichtung von %s</label>
                       <select id="ag-e-%d" name="einrichtung" required>%s</select>
                       <button class="knopf klein" type="submit"
                               title="Ordnet diesen Standort-Agenten einer anderen Einrichtung zu"
                               aria-label="Einrichtung von %s ändern">Zuordnen</button>
                     </form>"""
                     .formatted(a.id(), a.id(), esc(a.bezeichnung()), a.id(),
                             einrichtungsoptionen(bereich, a.einrichtung()),
                             esc(a.bezeichnung())))
             .append("</td></tr>");
        }
        s.append("</tbody></table></div>");
        s.append(agentAnlegenFormular(bereich));
        s.append("""
                <div class="karte-leib">
                  <p class="hilfe">Die Zentrale unterstützt die Protokollversionen %d bis %d.
                     Ein Agent außerhalb dieser Spanne wird <strong>nicht abgewiesen</strong> —
                     das hieße: keine PIN-Verifikation an diesem Standort. Er arbeitet weiter,
                     nur neuere Funktionen wirken dort nicht.</p>
                </div>""".formatted(de.pinroboter.gemeinsam.Protokollversion.AELTESTE_UNTERSTUETZTE,
                        de.pinroboter.gemeinsam.Protokollversion.AKTUELL));
        return s.append("</div>").toString();
    }

    /**
     * Welche PINs nach einer Wiederherstellung neu zu hinterlegen sind
     * (FA-INS-040).
     *
     * <p>Erscheint nur, wenn tatsächlich etwas fehlt. Eine dauerhaft leere
     * Tafel „alles in Ordnung" erzieht dazu, sie zu übersehen.
     */
    private String fehlendePinsTafel() {
        var fehlen = pins.neuZuHinterlegen();
        if (fehlen.isEmpty()) return "";
        StringBuilder s = new StringBuilder(
                "<div class=\"tafel\"><h3>PINs neu zu hinterlegen "
                + "<span class=\"zaehler\">" + fehlen.size() + "</span></h3>"
                + "<div class=\"karte-leib\">"
                + "<div class=\"hinweis warnend\"><div><strong>Der Standort-Agent hat einen "
                + "neuen Schlüssel.</strong> Das geschieht, wenn er neu aufgesetzt oder sein "
                + "Datenverzeichnis geleert wurde. Die zuvor hinterlegten PINs kann er nicht "
                + "mehr lesen — sie müssen erneut eingegeben werden. Bis dahin bleiben diese "
                + "Karten unverifiziert.</div></div></div>"
                + "<div class=\"rollbar\"><table><caption class=\"nurLeser\">"
                + "Karten, deren PIN neu zu hinterlegen ist</caption>"
                + "<thead><tr><th scope=\"col\">Karte</th>"
                + "<th scope=\"col\">zuletzt hinterlegt</th></tr></thead><tbody>");
        for (var h : fehlen) {
            s.append("<tr><td><code>").append(esc(h.kartenhandle())).append("</code></td>")
             .append("<td class=\"zeit\">").append(esc(h.seit())).append("</td></tr>");
        }
        return s.append("</tbody></table></div></div>").toString();
    }

    private String verwaltungsSeite(String fehler, java.util.List<Demodaten.Konto> neueKonten) {
        return verwaltungsSeite(fehler, neueKonten, Geltungsbereich.alles());
    }

    /**
     * Demodatensatz und Leistungsmessung — die Rubrik „Systemtest".
     *
     * <p>Bis zum 10.08.2026 standen beide unter „Instanz", zwischen Agenten,
     * Speicher und Produktversion. Dort geht es um den <b>laufenden Betrieb</b>;
     * hier um Dinge, die man selten und bewusst tut. Vorgabe des PO
     * (FA-BAK-050).
     */
    private String systemtestTafeln() {
        var b = demodaten.bestand();
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"tafel\"><h3>Demodatensatz</h3><div class=\"karte-leib\">");
        if (b.vorhanden()) {
            s.append("""
                    <p>Ein Demodatensatz ist eingerichtet:</p>
                    <div class="lage">
                      <div class="kachel"><div class="zahl">%d</div><div class="bez">Organisationseinheiten</div></div>
                      <div class="kachel"><div class="zahl">%d</div><div class="bez">Konnektoren</div></div>
                      <div class="kachel"><div class="zahl">%d</div><div class="bez">Arbeitsplätze</div></div>
                      <div class="kachel"><div class="zahl">%d</div><div class="bez">Kartenterminals</div></div>
                      <div class="kachel ok"><div class="zahl">%d</div><div class="bez">davon mit SMC-B</div></div>
                      <div class="kachel"><div class="zahl">%d</div><div class="bez">Benutzerkonten</div></div>
                    </div>
                    <div class="hinweis">
                      <strong>Beim Entfernen werden ausschließlich Demodaten gelöscht.</strong>
                      Jeder erzeugte Datensatz ist technisch als Demo gekennzeichnet; echte
                      Praxen, Geräte und Konten bleiben unberührt.
                    </div>
                    <form method="post" action="/verwaltung/demodaten/loeschen"
                          onsubmit="return confirm('Demodatensatz entfernen?\\n\\n%d Organisationseinheiten, %d Konnektoren, %d Kartenterminals und %d Benutzerkonten werden gelöscht.\\n\\nEchte Daten sind nicht betroffen.')">
                      <button class="knopf warnend" type="submit" title="Löscht alle Demodaten samt Konten, Organisationen und Geräten" aria-label="Löscht alle Demodaten samt Konten, Organisationen und Geräten">Demodatensatz entfernen</button>
                    </form>"""
                    .formatted(b.organisationen(), b.konnektoren(), b.arbeitsplaetze(),
                               b.terminals(), b.terminalsMitSmcb(), b.benutzer(),
                               b.organisationen(), b.konnektoren(), b.terminals(), b.benutzer()));
        } else {
            s.append("""
                    <p class="unterzeile">
                      Legt einen vollständigen MVZ-Verbund zum Ausprobieren an:
                      3 Standorte mit je 2 Facharztpraxen und 1 Allgemeinpraxis,
                      je Praxis ein Konnektor mit eigener SMC-B, ein Empfangs-Arbeitsplatz
                      mit SMC-B-Terminal und drei Behandler-Arbeitsplätze mit je einem
                      Terminal ohne SMC-B.
                      Dazu Benutzerkonten auf allen drei Berechtigungsebenen und
                      3 Administratoren.
                    </p>
                    <form method="post" action="/verwaltung/demodaten/anlegen">
                      <button class="knopf haupt" type="submit"
                              title="Legt einen vollständigen MVZ-Verbund mit Praxen, Geräten und Konten an"
                              aria-label="Demodatensatz anlegen">Demodatensatz anlegen</button>
                    </form>""");
        }
        s.append("</div></div>");

        // NFA-LEI-010: Der Betreiber soll die Leistungsmessung auf seiner
        // eigenen Hardware wiederholen koennen. Ein Zielwert, der nur auf der
        // Entwicklungsmaschine gilt, sagt ueber einen Raspberry Pi nichts aus.
        int lastVorhanden = last.vorhandene();
        s.append("""
                <div class="tafel"><h3>Leistungsmessung
                  <span class="zaehler">%s</span></h3>
                <div class="karte-leib">
                  <p class="unterzeile">Legt einen Bestand in der Größe des Mengengerüsts an
                     (%d Kartenterminals), damit sich die Zielwerte aus NFA-LEI-010 auf
                     dieser Hardware nachmessen lassen — statt sie zu schätzen.</p>
                  <div class="hinweis">Alle erzeugten Datensätze sind als Demo gekennzeichnet
                     und lassen sich rückstandsfrei entfernen. Echte Bestandsdaten bleiben
                     unberührt.</div>
                  <div class="reihe reihe-frei">
                    <form method="post" action="/verwaltung/lasttest/anlegen">
                      <input type="hidden" name="anzahl" value="%d">
                      <button class="knopf" type="submit" title="Legt die angegebene Zahl an Testgeräten an, um das Verhalten unter Last zu messen" aria-label="Testgeräte anlegen">%d Testgeräte anlegen</button>
                    </form>
                    %s
                  </div>
                </div></div>"""
                .formatted(lastVorhanden == 0 ? "kein Testbestand"
                                : lastVorhanden + " Testgeräte vorhanden",
                        Lasterzeuger.TERMINALS_ZIEL, Lasterzeuger.TERMINALS_ZIEL,
                        Lasterzeuger.TERMINALS_ZIEL,
                        lastVorhanden == 0 ? "" : """
                        <form method="post" action="/verwaltung/lasttest/entfernen">
                          <button class="knopf warnend" type="submit" title="Löscht die für den Lasttest angelegten Testgeräte" aria-label="Löscht die für den Lasttest angelegten Testgeräte">Testbestand entfernen</button>
                        </form>"""));
        return s.toString();
    }

    private String verwaltungsSeite(String fehler, java.util.List<Demodaten.Konto> neueKonten,
                                    Geltungsbereich bereich) {
        StringBuilder s = new StringBuilder(seitenkopf("Instanz",
                "Werkzeuge für den Betreiber dieser Instanz.", null));
        if (fehler != null) s.append(Anmeldeseiten.meldung(fehler, "warnend"));

        s.append(versionstafel());
        s.append(agententafel(bereich));
        s.append(fehlendePinsTafel());
        if (neueKonten != null && !neueKonten.isEmpty()) {
            s.append("""
                    <div class="tafel"><h3>Angelegte Konten
                      <span class="zaehler">werden nur jetzt angezeigt</span></h3>
                    <div class="hinweis"><strong>Kennwort für alle Demo-Konten: <code>%s</code></strong>
                    Die Einmalkennwort-Schlüssel erscheinen nur auf dieser Seite. Notieren Sie,
                    was Sie brauchen — danach lassen sie sich nur über einen neuen
                    Demodatensatz wieder erzeugen.</div>
                    <div class="rollbar"><table><thead><tr>
                    <th>Benutzername</th><th>Name</th><th>Rolle</th>
                    <th>Zuständig für</th><th>TOTP-Schlüssel</th></tr></thead><tbody>"""
                    .formatted(esc(Demodaten.DEMO_KENNWORT)));
            for (var k : neueKonten) {
                s.append("<tr><td class=\"geraet\">%s</td><td>%s</td><td>%s</td><td>%s</td>"
                        .formatted(esc(k.benutzername()), esc(k.anzeigename()),
                                esc(k.rolle().equals("ADMINISTRATOR") ? "Administrator" : "Benutzer"),
                                esc(k.geltungsbereich()))
                        + "<td><code style=\"font-size:.8em\">" + esc(k.totpGeheimnis())
                        + "</code></td></tr>");
            }
            s.append("</tbody></table></div></div>");
        }
        return s.toString();
    }

    private boolean istAdministrator(Context c) {
        var b = angemeldeter(c);
        return b != null && b.rolle() == Benutzerverwaltung.Rolle.ADMINISTRATOR;
    }

    /**
     * Die Rechte des angemeldeten Benutzers — Rolle <b>und</b> Ebene.
     *
     * <p>Einzige Stelle, an der sie entstehen. Der {@link Geltungsbereich}
     * regelt, <i>welche Daten</i> jemand sieht; {@link Rechte} regelt,
     * <i>welche Art von Handlung</i> ihm zusteht. Erst beides zusammen ergibt
     * die Berechtigung.
     */
    private Rechte rechte(Context c) {
        return Rechte.fuer(db, angemeldeter(c));
    }

    /**
     * Weist eine Handlung ab, für die die Rechte nicht reichen.
     *
     * <p>Die Meldung sagt, was fehlt — nicht nur, dass etwas fehlt. Ein
     * blankes „Nur Administratoren" lässt jemanden mit Administratorrolle
     * ratlos zurück, wenn in Wahrheit die Ebene nicht reicht.
     */
    private boolean abgewiesen(Context c, boolean erlaubt, String was) {
        if (erlaubt) return false;
        var r = rechte(c);
        c.status(403).result(was + " ist Ihnen nicht gestattet. "
                + "Ihre Berechtigung: " + r.beschreibung() + ".");
        return true;
    }

    private String gruppenSeite(Context c, String fehler) {
        return gruppenSeite(c, fehler, null);
    }

    /**
     * Die Gruppenseite.
     *
     * @param erfolg Rückmeldung nach einer gelungenen Änderung. Bis zum
     *        11.08.2026 gab es keine: Wer das Prüfintervall änderte, sah die
     *        Seite neu aufgebaut und musste in der Tabelle nachsehen, ob der
     *        Wert angekommen war. UX-Vorgabe 3 verlangt sichtbaren Zustand für
     *        jeden Vorgang.
     */
    private String gruppenSeite(Context c, String fehler, String erfolg) {
        return seitenkopf("Gerätegruppen",
                "Gruppen tragen das Prüfintervall — und später die Zeitfenster für "
                + "Firmware-Updates. Neu erkannte Geräte landen zunächst in der Vorgabegruppe.",
                null) + """
                %s
                <div class="tafel">
                  <h3>Neue Gruppe</h3>
                  <div class="filter">
                    <form method="post" action="/gruppen" class="reihe-enden">
                      <div class="feldgruppe"><label for="bez">Bezeichnung</label>
                        <input id="bez" name="bezeichnung" type="text" required
                               placeholder="z. B. Notaufnahme Nordstadt"
                               aria-describedby="bez-hilfe"></div>
                      <div class="feldgruppe"><label for="ge">Einrichtung</label>
                        <select id="ge" name="einrichtung">%s</select></div>
                      <div class="feldgruppe zahlenfeld"><label for="iv" title="Wie oft der Agent die Geräte dieser Gruppe prüft">Prüfintervall (s)</label>
                        <input id="iv" name="intervall" type="text" inputmode="numeric"
                               value="60" size="6"></div>
                      <div class="feldaktion">%s</div>
                    </form>
                    <p id="bez-hilfe" class="hilfe">Die Bezeichnung sollte auch
                       <strong>ohne</strong> die Spalte „Einrichtung" erkennbar sein — sie
                       erscheint auch in Auswahlfeldern und im Protokoll, wo die Einrichtung
                       nicht danebensteht. „Notaufnahme Nordstadt" ist brauchbar,
                       „Notaufnahme" in neun Betriebsstätten nicht.</p>
                  </div>
                </div>
                <div class="tafel"><h3>Gruppen <span class="zaehler">%d</span></h3>
                  %s
                  <div id="gruppen">%s</div>
                </div>"""
                .formatted(fehler != null ? Anmeldeseiten.meldung(fehler, "warnend")
                                 : erfolg != null ? Anmeldeseiten.meldung(erfolg, "gut") : "",
                           einrichtungsauswahl(c),
                           Bedienelemente.absenden("Gruppe anlegen",
                                   "Legt eine neue Gerätegruppe mit diesem Prüfintervall an",
                                   Bedienelemente.Art.HAUPT, null),
                           gruppen.alle(bereich(c)).size(),
                           Bedienelemente.suchleiste("/teil/gruppen", "#gruppen",
                                   param(c, "suche"),
                                   "Gruppe oder Einrichtung", "Gruppen durchsuchen",
                                   sortierfelder(c)),
                           gruppenTabelle(c));
    }

    /** Die Einrichtungen im Geltungsbereich, als Auswahlliste (FA-GER-050). */
    /**
     * Die Konnektor-Detailseite — mit Mitnutzung und Betreiber-Unterscheidung.
     *
     * <p>An einer Stelle gebündelt, damit die Frage „darf der das ändern?"
     * nicht an vier Routen einzeln beantwortet werden muss (ADR-0012).
     */
    private String konnektorMaske(Context c, Bestandsverwaltung.Konnektor k, String fehler) {
        return Bestandsseiten.konnektorMaske(k, ermittlung.aufrufkontextAuswahl(), fehler,
                bestand.darfKonnektorVerwalten(k.id(), bereich(c)),
                bestand.konnektorNutzer(k.id()), bestand.betriebsstaetten(bereich(c)));
    }

    /**
     * Auswahl der Organisationseinheiten mit Vorbelegung — für die Zuordnung
     * eines Standort-Agenten.
     *
     * <p>Ist die gespeicherte Einrichtung unbekannt (umbenannt oder gelöscht),
     * steht sie als <b>nicht wählbarer</b> erster Eintrag darin. Sonst zeigte
     * das Feld eine beliebige Einrichtung, und ein Klick auf „Zuordnen" würde
     * den Agenten stillschweigend woandershin hängen.
     */
    private String einrichtungsoptionen(Geltungsbereich bereich, String gewaehlt) {
        var liste = bestand.organisationen(bereich);
        boolean bekannt = gewaehlt != null
                && liste.stream().anyMatch(o -> o.bezeichnung().equals(gewaehlt));
        StringBuilder s = new StringBuilder();
        if (!bekannt) {
            s.append("<option value=\"\" disabled selected>— unbekannt: ")
             .append(esc(nz(gewaehlt))).append(" —</option>");
        }
        for (var o : liste) {
            s.append("<option value=\"").append(esc(o.bezeichnung())).append("\"")
             .append(o.bezeichnung().equals(gewaehlt) ? " selected" : "").append(">")
             .append(esc(o.bezeichnung())).append("</option>");
        }
        return s.toString();
    }

    private String einrichtungsauswahl(Context c) {
        StringBuilder s = new StringBuilder();
        for (var o : bestand.organisationen(bereich(c))) {
            s.append("<option value=\"").append(esc(o.bezeichnung())).append("\">")
             .append(esc(o.bezeichnung())).append("</option>");
        }
        // "Praxis" ist die Einrichtung der Vorgabegruppe — bei einer
        // Einzelpraxis ohne gepflegte Struktur die einzige sinnvolle Wahl.
        s.append("<option value=\"Praxis\">Praxis (ohne Zuordnung)</option>");
        return s.toString();
    }

    /**
     * Sortier- und Suchzustand als versteckte Felder.
     *
     * <p>Sie hängen in der Suchleiste, damit {@code hx-include} sie bei jeder
     * Tastatureingabe mitschickt. Ohne das würde die Suche die Sortierung
     * zurücksetzen — ein Verhalten, das man erst bemerkt, wenn man beides
     * zugleich benutzt.
     */
    /**
     * Ein Wert aus der Adresszeile oder dem Formular.
     *
     * <p>Beides ist nötig: Sortierlinks und die HTMX-Live-Suche schicken per
     * {@code GET}, gewöhnliche Masken per {@code POST}. Bis zum 09.08.2026 las
     * die Gruppensuche nur Formularfelder — sie lief fehlerfrei und filterte
     * nichts.
     */
    private static String param(Context c, String name) {
        String v = c.queryParam(name);
        if (v == null || v.isBlank()) v = c.formParam(name);
        return v == null ? "" : v.trim();
    }

    private String sortierfelder(Context c) {
        return """
                <input type="hidden" name="sortier" value="%s">
                <input type="hidden" name="richtung" value="%s">"""
                .formatted(esc(sortierSchluessel(c)), sortierAb(c) ? "ab" : "auf");
    }

    private String sortierSchluessel(Context c) {
        String s = param(c, "sortier");
        // Vorgabe „einrichtung": Nach Gruppennamen sortiert stehen neun Zeilen
        // „Alle Geräte" untereinander — die Vorgabegruppe jeder Einrichtung
        // heisst so.
        return s == null || s.isBlank() ? "einrichtung" : s;
    }

    private boolean sortierAb(Context c) {
        return "ab".equals(param(c, "richtung"));
    }

    /**
     * Die Gruppenliste.
     *
     * <p>Drei Dinge sind am 09.08.2026 verschwunden, und jedes aus einem Grund:
     *
     * <ul>
     *   <li><b>Der „Speichern"-Knopf je Zeile.</b> Bei zehn Gruppen waren das
     *       zehn Knöpfe, die alle dasselbe taten. Das Intervallfeld speichert
     *       jetzt, sobald es den Fokus verlässt.</li>
     *   <li><b>Die Selbstaktualisierung alle 10 s.</b> Eine Verwaltungsliste,
     *       die sich unter der Hand austauscht, verliert Tastaturfokus und
     *       halb getippte Eingaben. Gruppen ändern sich ohnehin nur durch eine
     *       Handlung — und danach wird die Liste ohnehin neu geladen.</li>
     *   <li><b>Der stumme Zeilenanfang.</b> Der Gruppenname ist jetzt ein Link
     *       auf die Geräte dieser Gruppe, und die ganze Zeile folgt ihm.</li>
     * </ul>
     *
     * <p>Sortiert wird im Speicher, nicht in der Datenbank. Das ist hier
     * ehrlich, weil die Liste vollständig angezeigt wird: Es gibt keine
     * Blätterung, die eine Sortierung über nur die sichtbaren Zeilen zur Lüge
     * machen könnte. Bei den Geräten (5.000) ist das anders — dort sortiert
     * die Datenbank.
     */
    private String gruppenTabelle(Context c) {
        String suche = param(c, "suche");
        String nach = sortierSchluessel(c);
        boolean ab = sortierAb(c);

        var liste = new java.util.ArrayList<>(gruppen.alle(bereich(c)));
        if (suche != null && !suche.isBlank()) {
            String s = suche.trim().toLowerCase();
            liste.removeIf(g -> !g.bezeichnung().toLowerCase().contains(s)
                             && !g.einrichtung().toLowerCase().contains(s));
        }
        // Vorgabe ist die EINRICHTUNG, nicht die Bezeichnung: Die Vorgabegruppe
        // jeder Einrichtung heisst „Alle Geräte", und danach sortiert stehen
        // neun gleichnamige Zeilen untereinander, waehrend die Einrichtung
        // daneben springt (Befund PO, 11.08.2026). Dieselbe Ueberlegung wie bei
        // der Geraeteliste.
        java.util.Comparator<Gruppenverwaltung.Gruppe> ord = switch (nach) {
            case "gruppe"  -> java.util.Comparator.comparing(
                    Gruppenverwaltung.Gruppe::bezeichnung, String.CASE_INSENSITIVE_ORDER);
            case "geraete" -> java.util.Comparator.comparingInt(Gruppenverwaltung.Gruppe::geraetezahl);
            case "takt"    -> java.util.Comparator.comparingInt(
                    Gruppenverwaltung.Gruppe::pruefintervallSekunden);
            default -> java.util.Comparator.comparing(
                    Gruppenverwaltung.Gruppe::einrichtung, String.CASE_INSENSITIVE_ORDER);
        };
        // Zweites Kriterium ist Pflicht: Ohne eindeutige Reihenfolge wechseln
        // Zeilen bei jeder Aktualisierung die Plaetze, sobald zwei Gruppen
        // denselben Wert haben — und „Alle Geräte" haben sie fast alle.
        ord = ord.thenComparing(Gruppenverwaltung.Gruppe::einrichtung, String.CASE_INSENSITIVE_ORDER)
                 .thenComparing(Gruppenverwaltung.Gruppe::bezeichnung, String.CASE_INSENSITIVE_ORDER);
        liste.sort(ab ? ord.reversed() : ord);

        String basis = "/teil/gruppen?suche=" + urlKodiert(suche) + "&";
        StringBuilder s = new StringBuilder("<div class=\"rollbar\"><table><thead><tr>");
        s.append(Bedienelemente.sortkopf("Gruppe", "gruppe", nach, ab, basis));
        s.append(Bedienelemente.sortkopf("Einrichtung", "einrichtung", nach, ab, basis));
        s.append(Bedienelemente.sortkopf("Geräte", "geraete", nach, ab, basis));
        s.append(Bedienelemente.sortkopf("Prüfintervall", "takt", nach, ab, basis));
        s.append("<th scope=\"col\"><span class=\"nurLeser\">Aktionen</span></th>");
        s.append("</tr></thead><tbody>");

        if (liste.isEmpty()) {
            s.append("<tr><td colspan=\"5\" class=\"leer\">")
             .append(suche == null || suche.isBlank()
                     ? "Noch keine Gruppe angelegt."
                     : "Keine Gruppe passt zu „" + esc(suche) + "“.")
             .append("</td></tr>");
        }
        for (var g : liste) {
            s.append("<tr class=\"klickbar\">");
            // Die Zeile fuehrt auf die Detailseite der Gruppe — dort wird
            // umbenannt, das Intervall gesetzt und die Vorgabe gewechselt.
            //
            // Ein Eingabefeld IN der Zeile waere der naheliegende Weg gewesen,
            // vertruege sich aber nicht mit einer klickbaren Zeile: Ein Klick
            // ins Feld wuerde navigieren. Und eine Liste ist der falsche Ort
            // fuer alles, was mehr als einen Wert hat.
            s.append(Bedienelemente.zeilenziel(g.bezeichnung(), "/gruppen/" + g.id(),
                    "Öffnet die Gruppe „" + g.bezeichnung() + "“ zum Bearbeiten"));
            s.setLength(s.length() - "</td>".length());
            if (g.istVorgabe()) {
                // Die Marke steht IN der Namenszelle, nicht in einer eigenen
                // Spalte: Eine Spalte, die bei neun von zehn Zeilen leer ist,
                // kostet Breite und trägt nichts.
                s.append(" <span class=\"marke unbekannt\" title=\"Neu erkannte Geräte "
                       + "dieser Einrichtung landen hier\">Vorgabe</span>");
            }
            s.append("</td>");
            s.append("""
                    <td>%s</td>
                    <td class="zahl-zelle"><a href="/bestand?gruppe=%d"
                        title="Zeigt die Geräte dieser Gruppe im Bestand">%d</a></td>
                    <td><form method="post" action="/gruppen/%d/intervall" class="reihe-eng">
                          <label class="nurLeser" for="iv-%d">Prüfintervall für %s in Sekunden</label>
                          <input id="iv-%d" name="intervall" type="text" inputmode="numeric"
                                 size="5" value="%d"
                                 title="Wie oft der Agent die Geräte dieser Gruppe prüft. Nach der Eingabe wird automatisch gespeichert."
                                 hx-post="/gruppen/%d/intervall" hx-trigger="change"
                                 hx-target="#gruppen" hx-select="#gruppen" hx-swap="outerHTML">
                          <span class="feldhinweis" aria-hidden="true">s</span>
                          <noscript><button class="knopf klein" type="submit" title="Übernimmt die Eingaben" aria-label="Übernimmt die Eingaben">Speichern</button></noscript>
                        </form></td>
                    <td class="aktionen">%s</td></tr>"""
                    .formatted(esc(g.einrichtung()), g.id(), g.geraetezahl(), g.id(),
                            g.id(), esc(g.bezeichnung()), g.id(), g.pruefintervallSekunden(),
                            g.id(),
                            g.istVorgabe() ? "" :
                              ("<form method=\"post\" action=\"/gruppen/" + g.id() + "/loeschen\" "
                               + "data-nachfrage=\"Gruppe &bdquo;" + esc(g.bezeichnung())
                               + "&ldquo; wirklich löschen? Die Geräte ziehen in die Vorgabegruppe um.\">"
                               + Bedienelemente.absenden("Löschen",
                                   "Löscht die Gruppe „" + g.bezeichnung()
                                   + "“; ihre Geräte ziehen in die Vorgabegruppe um",
                                   Bedienelemente.Art.KLEIN, null)
                                 .replace("class=\"knopf klein\"", "class=\"knopf klein warnend\"")
                               + "</form>")));
        }
        return s.append("</tbody></table></div>").toString();
    }

    /**
     * Die Detailseite einer Gerätegruppe (Befund PO, 11.08.2026).
     *
     * <p>Es gab sie nicht — als einziger Bereich hatte die Gruppenverwaltung
     * keine. Alles musste in die Listenzeile passen, und was nicht hineinpasste,
     * fehlte schlicht: Umbenennen, Vorgabe wechseln, die Geräte sehen.
     *
     * <p>Die Seite folgt derselben Ordnung wie Gerät und Organisationseinheit:
     * oben die Stammdaten zum Bearbeiten, darunter die Beziehungen, unten das
     * Löschen.
     */
    private String gruppenDetail(Context c, long id, String fehler, String erfolg) {
        var g = gruppen.gruppe(id, bereich(c));
        if (g.isEmpty()) {
            return seitenkopf("Gerätegruppe", "Diese Gruppe gibt es nicht mehr.", null)
                 + "<div class=\"tafel\"><p class=\"leer\">Die Gruppe wurde gelöscht oder liegt "
                 + "außerhalb Ihres Zuständigkeitsbereichs. "
                 + "<a href=\"/gruppen\">Zurück zur Übersicht</a></p></div>";
        }
        var gr = g.get();
        StringBuilder s = new StringBuilder(detailkopf("/gruppen", "Gerätegruppen",
                gr.bezeichnung(), "in " + esc(gr.einrichtung()), null));

        if (fehler != null) s.append(Anmeldeseiten.meldung(fehler, "warnend"));
        else if (erfolg != null) s.append(Anmeldeseiten.meldung(erfolg, "gut"));

        s.append("""
                <div class="tafel"><h3>Stammdaten</h3>
                <form method="post" action="/gruppen/%d/aendern" class="maske">
                  <div class="feldreihe">
                    <div class="feldgruppe"><label for="d-bez">Bezeichnung</label>
                      <input id="d-bez" name="bezeichnung" type="text" required maxlength="120"
                             value="%s"></div>
                    <div class="feldgruppe zahlenfeld">
                      <label for="d-iv" title="Wie oft der Agent die Geräte dieser Gruppe prüft">Prüfintervall (s)</label>
                      <input id="d-iv" name="intervall" type="text" inputmode="numeric"
                             size="6" value="%d"></div>
                  </div>
                  <p class="hilfe">Die Einrichtung <strong>%s</strong> lässt sich nicht ändern:
                     Eine Gruppe liegt in genau einer Einrichtung (FA-GER-050), und ein Wechsel
                     würde ihre Geräte über die Mandantengrenze tragen. Für einen anderen
                     Standort bitte dort eine eigene Gruppe anlegen.</p>
                  %s
                </form></div>""".formatted(gr.id(), esc(gr.bezeichnung()),
                        gr.pruefintervallSekunden(), esc(gr.einrichtung()),
                        Bedienelemente.absenden("Änderungen speichern",
                                "Übernimmt Bezeichnung und Prüfintervall dieser Gruppe",
                                Bedienelemente.Art.HAUPT, null)));

        // Vorgabe: der Ort, an dem neu erkannte Geraete landen.
        s.append("<div class=\"tafel\"><h3>Vorgabegruppe</h3>");
        if (gr.istVorgabe()) {
            s.append("""
                    <p class="hilfe">Neu erkannte Geräte dieser Einrichtung landen in dieser
                       Gruppe. Um das zu ändern, öffnen Sie die Gruppe, die es künftig sein
                       soll — jede Einrichtung hat genau eine Vorgabegruppe.</p>""");
        } else {
            s.append("""
                    <form method="post" action="/gruppen/%d/vorgabe" class="maske">
                      <p class="hilfe">Neu erkannte Geräte dieser Einrichtung landen zurzeit in
                         einer anderen Gruppe. Diese hier zur Vorgabe zu machen, ändert nichts
                         an den vorhandenen Zuordnungen — nur daran, wo künftige Geräte
                         auftauchen.</p>
                      %s
                    </form>""".formatted(gr.id(),
                            Bedienelemente.absenden("Zur Vorgabegruppe machen",
                                    "Neu erkannte Geräte dieser Einrichtung landen künftig hier",
                                    Bedienelemente.Art.NEBEN, null)));
        }
        s.append("</div>");

        // Die Geraete der Gruppe — nicht nur ihre Zahl.
        var geraeteDerGruppe = gruppen.geraeteDerGruppe(gr.id());
        s.append("<div class=\"tafel\"><h3>Geräte <span class=\"zaehler\">")
         .append(geraeteDerGruppe.size()).append("</span></h3>");
        if (geraeteDerGruppe.isEmpty()) {
            s.append("<p class=\"leer\">Dieser Gruppe ist noch kein Gerät zugeordnet. "
                   + "Im <a href=\"/bestand\">Bestand</a> lassen sich Geräte auswählen und "
                   + "einer Gruppe zuweisen.</p>");
        } else {
            s.append("<div class=\"rollbar\"><table><thead><tr><th scope=\"col\">Gerät</th>"
                   + "<th scope=\"col\">Kennung</th></tr></thead><tbody>");
            for (var eintrag : geraeteDerGruppe) {
                s.append("<tr class=\"klickbar\">")
                 .append(Bedienelemente.zeilenziel(eintrag.bezeichnung(),
                         "/geraete/" + eintrag.terminalId() + "/verlauf",
                         "Öffnet den Zeitstrahl dieses Geräts"))
                 .append("<td><code>").append(esc(eintrag.terminalId())).append("</code></td></tr>");
            }
            s.append("</tbody></table></div>");
            s.append("<p class=\"hilfe\">Zuordnen und Umhängen geschieht im "
                   + "<a href=\"/bestand?gruppe=").append(gr.id())
             .append("\">Bestand</a> — dort lassen sich mehrere Geräte auf einmal auswählen.</p>");
        }
        s.append("</div>");

        if (!gr.istVorgabe()) {
            s.append("""
                    <div class="tafel"><h3>Gruppe löschen</h3>
                    <p class="hilfe">Die %d zugeordneten Geräte ziehen in die Vorgabegruppe von
                       „%s" um — kein Gerät fällt durch eine Löschung aus der Überwachung.</p>
                    <form method="post" action="/gruppen/%d/loeschen"
                          data-nachfrage="Gruppe &bdquo;%s&ldquo; wirklich löschen?">%s</form>
                    </div>""".formatted(gr.geraetezahl(), esc(gr.einrichtung()), gr.id(),
                            esc(gr.bezeichnung()),
                            Bedienelemente.absenden("Gruppe löschen",
                                    "Löscht diese Gruppe; ihre Geräte ziehen in die Vorgabegruppe um",
                                    Bedienelemente.Art.WARNEND, null)));
        }
        return s.toString();
    }

    private static String urlKodiert(String s) {
        return s == null ? "" : java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * „3 von 4 Agenten verbunden" — die Zahl statt eines Ja/Nein.
     *
     * <p>Ein Standort-Agent gilt als verbunden, wenn er sich in den letzten
     * zwei Minuten gemeldet hat. Der Grundtakt beträgt 10 s; zwei Minuten
     * lassen einen ausgefallenen Takt zu, ohne einen abgehängten Agenten als
     * verbunden auszuweisen.
     */
    private String agentZeile() {
        var alle = agenten.alle();
        if (alle.isEmpty()) return "kein Agent eingerichtet";
        Instant grenze = Instant.now().minusSeconds(120);
        long verbunden = alle.stream()
                .filter(a -> a.zuletztGesehen() != null && a.zuletztGesehen().isAfter(grenze))
                .count();
        if (alle.size() == 1) {
            return verbunden == 1 ? "Agent verbunden" : "Agent nicht erreichbar";
        }
        return verbunden + " von " + alle.size() + " Agenten verbunden";
    }

    /**
     * Die laufende Version, wie sie im Manifest steht.
     *
     * <p>Im Entwicklungslauf aus der Entwicklungsumgebung heraus gibt es kein
     * Manifest; dann steht „Entwicklungsstand" statt einer erfundenen Nummer.
     * Eine Versionsangabe, die nicht stimmt, ist schlimmer als keine — nach ihr
     * wird gefragt, wenn etwas nicht funktioniert.
     */
    private static String versionsanzeige() {
        String v = Versionspruefung.laufendeVersion();
        return "unbekannt".equals(v) ? "Entwicklungsstand" : "Version " + v;
    }

    private static String kopfWert(Context c, String name) {
        String v = c.header(name);
        return v == null ? "" : v.trim();
    }

    private String geheimnisAnzeige(String geheimnis) {
        return """
                <div class="hinweis">
                  <strong>Registrierungsgeheimnis erzeugt</strong>
                  Es gilt <b>einmalig</b> und läuft nach 24 Stunden ab.
                  Tragen Sie es beim Standort-Agenten als <code>REGISTRIERUNG</code> ein.
                  <div class="geheimnis"><code>%s</code></div>
                  Dieser Wert wird <b>nicht noch einmal angezeigt</b>. Wenn er verloren
                  geht, erzeugen Sie einfach ein neues.
                </div>""".formatted(esc(geheimnis));
    }

    private Benutzerverwaltung.Benutzer angemeldeter(Context c) {
        Object o = c.attribute("benutzer");
        return o instanceof Benutzerverwaltung.Benutzer b ? b : null;
    }

    private void setzeSitzungscookie(Context c, String kennung) {
        c.header("Set-Cookie", SITZUNGSCOOKIE + "=" + kennung
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (8 * 3600));
    }

    /**
     * Die Lage-Seite.
     *
     * <p><b>Sie tauscht sich nicht mehr als Ganzes aus.</b> Bis zum 07.08.2026
     * lag der gesamte Seiteninhalt in einem Container mit
     * {@code hx-trigger="every 3s"} — alle drei Sekunden wurde alles ersetzt,
     * und dabei konnten Scrollposition, Tastaturfokus und eine getroffene
     * Auswahl springen. Das war die Ursache des unruhigen Eindrucks, den der PO
     * gemeldet hat.
     *
     * <p>Jetzt aktualisieren sich zwei Einschübe getrennt: die Alarme und die
     * Lage. Der Rahmen bleibt stehen. Der Takt ist auf 5 s gesenkt — der
     * Zustand ändert sich ohnehin nur im Prüfintervall der Gerätegruppe
     * (im Betrieb 60 s).
     */
    private String uebersichtsSeite(Context c) {
        return """
                <div class="seitenkopf">
                  <div>
                    <h2 tabindex="-1">Lage</h2>
                    <p class="unterzeile">Was gerade Aufmerksamkeit braucht — und was von selbst läuft.</p>
                  </div>
                  <div class="kopf-rechts">%s</div>
                </div>
                <div id="alarme" hx-get="/teil/alarme" hx-trigger="every 5s" hx-swap="innerHTML">%s</div>
                <div id="lage" hx-get="/teil/lage" hx-trigger="every 5s" hx-swap="innerHTML">%s</div>
                """.formatted(automatikMarkeGesamt(), alarmblock(c), lageblock(c));
    }

    /** Zustand der Automatik im Seitenkopf — Farbe UND Text (WCAG 1.4.1). */
    private String automatikMarkeGesamt() {
        return notAus
                ? "<span class=\"marke fehler\"><span class=\"sym\">×</span>Not-Aus aktiv</span>"
                : "<span class=\"marke ok\"><span class=\"puls\"></span>Automatik läuft</span>";
    }

    private String uebersicht(Context c) {
        return alarmblock(c) + lageblock(c);
    }

    private String lageblock(Context c) {
        var alle = geraete.values();
        long ok = alle.stream().filter(g -> g.ampel() == Ampel.OK).count();
        long warn = alle.stream().filter(g -> g.ampel() == Ampel.WARNUNG).count();
        long fehler = alle.stream().filter(g -> g.ampel() == Ampel.FEHLER).count();
        long ohnePevim = alle.stream().filter(g -> !g.pevimAktiv).count();

        // Der Alarmblock steht in einem EIGENEN Einschub (siehe uebersichtsSeite)
        // und gehoert deshalb nicht mehr hierher.
        StringBuilder s = new StringBuilder();
        if (notAus) s.append("""
                <div class="hinweis" style="border-left-color:var(--fehler);background:var(--fehler-hg)">
                  <strong>Not-Aus ist aktiv</strong>
                  Die Automatik ruht. Es werden keine Karten freigeschaltet, bis der Not-Aus aufgehoben wird.
                </div>""");

        s.append("""
                <div class="lage">
                  <a class="kachel ok" href="/geraete"><div class="bez">Karten freigeschaltet</div><div class="zahl">%d</div>%s</a>
                  <a class="kachel warnung" href="/geraete"><div class="bez">brauchen Aufmerksamkeit</div><div class="zahl">%d</div></a>
                  <a class="kachel fehler" href="/geraete"><div class="bez">Handlungsbedarf</div><div class="zahl">%d</div></a>
                  <a class="kachel unbekannt" href="/geraete"><div class="bez">ohne Automatik (PEviM)</div><div class="zahl">%d</div></a>
                </div>""".formatted(ok, verlaufsbalken(ok, warn, fehler), warn, fehler, ohnePevim));

        var sp = speicher.zustand();
        if (sp.anteil() >= 0.5) {
            s.append("""
                    <div class="hinweis"><strong>Protokollspeicher: %d %% belegt</strong>
                    %s von %s. Ab 80 %% werden die ältesten Einträge automatisch gelöscht —
                    technische Diagnose zuerst, sicherheitsrelevante Ereignisse zuletzt.
                    </div>""".formatted(sp.prozent(), sp.belegtLesbar(), sp.budgetLesbar()));
        }

        // Nur was WIRKLICH Aufmerksamkeit braucht. Bis zum 11.08.2026 stand
        // hier "alles ausser OK" — damit landete jedes Behandlungsterminal
        // ohne SMC-B in der Liste, mit "Der Zustand konnte nicht ermittelt
        // werden". Bei einem MVZ mit 36 Geraeten waren das 27 Zeilen, hinter
        // denen keine einzige Handlung stand. Eine Liste, in der fast alles
        // steht, kann niemand mehr lesen.
        // Einmal je Seitenaufbau, nicht je Zeile: Bei 5.000 Geraeten waeren
        // das sonst 5.000 Abfragen fuer eine Tabelle mit 20 Zeilen.
        Map<String, String> einrichtungen = gruppen.einrichtungJeTerminal();

        var auffaellig = alle.stream()
                .filter(g -> g.ampel() == Ampel.WARNUNG || g.ampel() == Ampel.FEHLER)
                .sorted(Comparator.<Geraetezustand, String>comparing(
                                g -> einrichtungen.getOrDefault(g.terminalId, g.einrichtung))
                        .thenComparing(g -> g.terminalId)).toList();

        s.append("<div class=\"tafel\"><h3>Was zu tun ist <span class=\"zaehler\">")
         .append(auffaellig.isEmpty() ? "nichts offen" : auffaellig.size() + " Punkte").append("</span></h3>");
        if (auffaellig.isEmpty()) {
            s.append("<p class=\"leer\">Alle überwachten Karten sind freigeschaltet. Es ist nichts zu tun.</p>");
        } else {
            s.append("<div class=\"rollbar\"><table><thead><tr><th>Gerät</th><th>Zustand</th><th>Was zu tun ist</th></tr></thead><tbody>");
            for (Geraetezustand g : auffaellig) {
                // Die Einrichtung gehoert in die ZWEITE Zeile, nicht in eine
                // eigene Spalte: In einem MVZ heissen neun Geraete "Behandlung
                // 1". Ohne die Betriebsstaette daneben ist die Zeile wertlos —
                // man weiss, dass etwas ist, aber nicht wo (Vorgabe PO,
                // 11.08.2026). Die technische Kennung steht dahinter, weil sie
                // beim Anruf beim Dienstleister gebraucht wird, im Alltag aber
                // niemandem etwas sagt.
                s.append("""
                        <tr><td class="geraet"><a href="/geraete/%s/verlauf" style="color:inherit">%s</a><br><span style="font-weight:400;color:var(--gedaempft);font-size:.85em">%s</span></td>
                        <td>%s</td><td>%s%s</td></tr>"""
                        .formatted(esc(g.terminalId), esc(anzeigename(g)),
                                esc(einrichtungen.getOrDefault(g.terminalId, g.einrichtung)
                                        + " · " + g.terminalId),
                                marke(g),
                                esc(g.zustandserklaerung()),
                                g.handlung == null ? "" : "<div class=\"handlung\">" + esc(g.handlung) + "</div>"));
            }
            s.append("</tbody></table></div>");
        }
        s.append("</div>");
        return s.toString();
    }

    /**
     * Verlaufsbalken unter der Hauptkennzahl.
     *
     * <p>„34 freigeschaltet" sagt weniger als „34 von 36, davon 1 mit
     * Handlungsbedarf". Der Balken trägt keine eigene Information — er macht
     * das Verhältnis auf einen Blick lesbar und ist deshalb
     * {@code aria-hidden}; die Zahlen daneben stehen im Text.
     */
    private static String verlaufsbalken(long ok, long warn, long fehler) {
        if (ok + warn + fehler == 0) return "";
        StringBuilder s = new StringBuilder("<div class=\"balken\" aria-hidden=\"true\">");
        if (ok > 0)     s.append("<i class=\"b-ok\" style=\"flex:").append(ok).append("\"></i>");
        if (warn > 0)   s.append("<i class=\"b-warn\" style=\"flex:").append(warn).append("\"></i>");
        if (fehler > 0) s.append("<i class=\"b-krit\" style=\"flex:").append(fehler).append("\"></i>");
        s.append("</div><span class=\"fuss\">von ").append(ok + warn + fehler)
         .append(" überwachten Karten</span>");
        return s.toString();
    }

    /** Offene Alarme ganz oben — sie sind das Erste, was jemand sehen muss. */
    private String alarmblock(Context c) {
        // ALLE Einrichtungen im Geltungsbereich, nicht fest „Praxis".
        //
        // Bis zum 11.08.2026 stand hier ein Einrichtungsname. In einer
        // Installation mit mehreren Betriebsstätten zeigte die Lage-Übersicht
        // damit keinen einzigen Alarm — die wichtigste Seite des Produkts war
        // im Mehrmandantenbetrieb blind. Aufgefallen ist es erst, als die
        // Prüfeinrichtung nicht mehr „Praxis" hieß.
        var offen = alarme.offene(bereich(c));
        if (offen.isEmpty()) return "";
        boolean darfSchliessen = rechte(c).darfBestandPflegen();
        StringBuilder s = new StringBuilder(
                "<div class=\"tafel\"><h3>Alarme <span class=\"zaehler\">"
                + offen.size() + " offen</span>"
                + (darfSchliessen ? alleSchliessenKnopf(offen.size()) : "")
                + "</h3><div class=\"rollbar\"><table><tbody>");
        for (var a : offen) {
            s.append("""
                    <tr class="%s"><td style="width:9rem">
                      <span class="marke %s"><span class="sym">%s</span>%s</span><br>
                      <span class="feldhinweis">%s</span></td>
                      <td class="geraet">%s</td>
                      <td>%s%s</td>
                      <td style="width:9rem">%s</td></tr>"""
                    .formatted(a.quittiert() ? "quittiert" : "",
                            a.schweregrad().css(),
                            a.schweregrad() == Alarmverwaltung.Schweregrad.KRITISCH ? "×" : "!",
                            esc(a.schweregrad().anzeige()), esc(a.dauer()),
                            esc(a.objekt() == null ? "—" : a.objekt()),
                            esc(a.meldung()),
                            a.handlung() == null ? ""
                                : "<div class=\"handlung\">" + esc(a.handlung()) + "</div>",
                            (a.quittiert()
                                ? "<span class=\"feldhinweis\">quittiert von "
                                  + esc(a.quittiertVon()) + "</span>"
                                : "<button class=\"knopf\" title=\"Bestätigt, dass Sie sich "
                                  + "um diesen Alarm gekümmert haben; er bleibt im Protokoll\" "
                                  + "aria-label=\"Alarm quittieren\" hx-post=\"/alarme/" + a.id()
                                  + "/quittieren\" hx-target=\"body\">Quittieren</button>")
                            + (darfSchliessen
                                ? "<button class=\"knopf klein\" title=\"Nimmt diesen Alarm "
                                  + "aus der Liste. Er bleibt im Protokoll; besteht die Ursache "
                                  + "weiter, meldet der Roboter ihn erneut\" "
                                  + "aria-label=\"Alarm schließen\" hx-post=\"/alarme/" + a.id()
                                  + "/schliessen\" hx-target=\"body\">Schließen</button>"
                                : "")));
        }
        return s.append("</tbody></table></div></div>").toString();
    }

    /**
     * Die Sammelaktion im Alarmkopf.
     *
     * <p>Mit Rückfrage, und die Rückfrage <b>nennt die Zahl</b>: „Alle 11
     * Alarme schließen?" ist etwas anderes als „Alle Alarme schließen?", wenn
     * man dreihundert hat. Destruktive Sammelaktionen ohne Mengenangabe sind
     * die häufigste Ursache für ein „das wollte ich nicht".
     */
    private String alleSchliessenKnopf(int zahl) {
        return """
                <button class="knopf" hx-post="/alarme/alle-schliessen" hx-target="body"
                        hx-confirm="Alle %d Alarme schließen? Sie verschwinden aus der Liste und bleiben im Protokoll. Störungen, die weiter bestehen, meldet der Roboter erneut."
                        title="Nimmt alle Alarme Ihres Zuständigkeitsbereichs aus der Liste; sie bleiben im Protokoll"
                        aria-label="Alle %d Alarme schließen">Alle schließen</button>"""
                .formatted(zahl, zahl);
    }

    /**
     * Die Verteilung der Firmware-Stände je Modell (FA-UEB-030).
     *
     * <p>Bei 5.000 Terminals ist die Frage nicht „welche Firmware hat Gerät
     * X", sondern „wie viele Geräte hängen zurück und welche". Eine Liste
     * beantwortet das nicht; eine Verteilung schon.
     *
     * <p>Der jüngste Stand je Modell gilt als der aktuelle — nicht ein fest
     * eingetragener Sollstand. Den kennen wir nicht: Die Firmware kommt vom
     * Hersteller, nicht von uns (ADR-0004).
     */
    private String flottenuebersicht() {
        // Modell -> (Firmware -> Anzahl)
        var jeModell = new java.util.TreeMap<String, java.util.TreeMap<String, Integer>>();
        for (var g : geraete.values()) {
            String modell = nz(g.modell).isBlank() ? "unbekanntes Modell" : g.modell;
            String fw = nz(g.firmware).isBlank() ? "nicht gemeldet" : g.firmware;
            jeModell.computeIfAbsent(modell, k -> new java.util.TreeMap<>())
                    .merge(fw, 1, Integer::sum);
        }
        if (jeModell.isEmpty()) return "";

        StringBuilder s = new StringBuilder(
                "<div class=\"tafel\"><h3>Firmware-Stände <span class=\"zaehler\">"
                + jeModell.size() + " Modell(e)</span></h3><div class=\"rollbar\"><table>"
                + "<caption class=\"nurLeser\">Verteilung der Firmware-Stände je Modell</caption>"
                + "<thead><tr><th scope=\"col\">Modell</th><th scope=\"col\">Stand</th>"
                + "<th scope=\"col\">Geräte</th><th scope=\"col\">Bewertung</th>"
                + "</tr></thead><tbody>");

        for (var m : jeModell.entrySet()) {
            // Der hoechste vorkommende Stand gilt als der aktuelle.
            String juengster = m.getValue().descendingKeySet().stream()
                    .filter(f -> !f.equals("nicht gemeldet")).findFirst().orElse(null);
            boolean erste = true;
            for (var f : m.getValue().descendingMap().entrySet()) {
                boolean aktuell = f.getKey().equals(juengster);
                boolean unbekannt = f.getKey().equals("nicht gemeldet");
                s.append("<tr><td class=\"geraet\">")
                 .append(erste ? esc(m.getKey()) : "").append("</td>")
                 .append("<td><code>").append(esc(f.getKey())).append("</code></td>")
                 .append("<td style=\"font-variant-numeric:tabular-nums\">")
                 .append(f.getValue()).append("</td><td>")
                 .append(unbekannt
                         ? "<span class=\"marke unbekannt\">kein Stand gemeldet</span>"
                         : aktuell
                            ? "<span class=\"marke ok\"><span class=\"sym\">✓</span>aktuellster Stand</span>"
                            : "<span class=\"marke warnung\">liegt zurück</span>")
                 .append("</td></tr>");
                erste = false;
            }
        }
        s.append("</tbody></table></div>");
        s.append("""
                <div class="karte-leib">
                  <p class="hilfe">Als „aktuellster Stand" gilt die höchste vorkommende Version
                     je Modell. Einen Sollstand kennt der Roboter nicht — die Firmware kommt vom
                     Hersteller des Geräts, nicht von uns.</p>
                </div>""");
        return s.append("</div>").toString();
    }

    /**
     * Sortierfelder der Betriebs-Geräteliste.
     *
     * <p><b>Vorgabe ist die Praxis</b> (PO, 11.08.2026). In einem MVZ heißen die
     * Geräte in jeder Betriebsstätte gleich; nach Kennung sortiert stand
     * „Behandlung 1" viermal untereinander, ohne dass erkennbar war, zu wem.
     */
    private enum Geraetesortierung {
        // ACHTUNG: Diese Reihenfolge IST die Spaltenreihenfolge der Tabelle —
        // die Koepfe entstehen aus values(). Wer hier umsortiert, verschiebt die
        // Ueberschriften gegen die Zellen. Genau das ist am 11.08.2026 passiert:
        // Ueber "Behandlung 1" stand "Praxis". Beim Aendern also IMMER auch die
        // Zellenreihenfolge in geraeteTabelle() mitziehen.
        GERAET("Gerät"), PRAXIS("Praxis"), MODELL("Modell"), FIRMWARE("Firmware"),
        ZUSTAND("Zustand"), AUTOMATIK("Automatik"), VERSUCHE("Versuche"), GEPRUEFT("geprüft");

        final String anzeige;
        Geraetesortierung(String a) { anzeige = a; }

        static Geraetesortierung aus(String name) {
            if (name == null) return PRAXIS;
            try { return valueOf(name.toUpperCase()); }
            catch (IllegalArgumentException e) { return PRAXIS; }
        }
    }

    private String geraeteSeite(Context c) {
        String suche = c.queryParam("suche");
        var sort = Geraetesortierung.aus(c.queryParam("sortierung"));
        boolean ab = "ab".equals(c.queryParam("richtung"));

        // Suchfeld und Sortierung stehen AUSSERHALB des Bereichs, der alle 5 s
        // ausgetauscht wird. Sonst verlöre das Feld bei jeder Aktualisierung
        // Inhalt und Schreibmarke — dieselbe Überlegung wie in der
        // Bestandsliste. Die aktuellen Werte reisen über hx-include mit, damit
        // die Aktualisierung dieselbe Sicht zeigt wie der Seitenaufbau.
        String filter = """
                <div class="filter geraetefilter">
                  <label class="nurLeser" for="g-suche">Geräte durchsuchen</label>
                  <input id="g-suche" name="suche" type="search" value="%s"
                         placeholder="Gerät, Kennung, Praxis oder Modell suchen …"
                         title="Sucht in Gerätename, Kennung, Praxis und Modell"
                         hx-get="/teil/geraete" hx-target="#geraete" hx-swap="innerHTML"
                         hx-include=".geraetefilter" hx-trigger="input changed delay:200ms">
                  <input type="hidden" name="sortierung" value="%s">
                  <input type="hidden" name="richtung" value="%s">
                </div>""".formatted(esc(suche == null ? "" : suche), sort.name(), ab ? "ab" : "auf");

        // Takt von 3 s auf 5 s: Der Zustand aendert sich ohnehin nur im
        // Pruefintervall der Gruppe (im Betrieb 60 s). Haeufiger zu fragen
        // erzeugt nur Unruhe und Last.
        return seitenkopf("Kartenterminals",
                "Zustand aller überwachten Geräte. Aktualisiert sich selbsttätig.", null) + """
                <div class="tafel"><h3>Geräte <span class="zaehler"><span class="puls"></span> live</span></h3>
                %s
                <div id="geraete" hx-get="/teil/geraete" hx-trigger="load, every 5s"
                     hx-include=".geraetefilter" hx-swap="innerHTML">%s</div></div>
                %s
                """.formatted(filter, geraeteTabelle(suche, sort, ab), flottenuebersicht());
    }

    /**
     * Was bei einem Zustand mit Handlungsbedarf zu tun ist — direkt in der
     * Geräteliste (FA-PIN-040).
     *
     * <p>Bis zum 08.08.2026 stand die Erklärung nur auf der Lage-Seite. Wer die
     * Geräteliste filtert — und bei 5.000 Terminals tut man das —, las dort
     * „Transport-PIN" und war so klug wie zuvor. Der Zustand allein ist keine
     * Meldung; die Anforderung verlangt ausdrücklich die <b>erforderliche
     * Handlung</b> dazu.
     *
     * <p>Nur bei Handlungsbedarf, nicht bei jedem Zustand: Eine Zeile „alles in
     * Ordnung, nichts zu tun" unter jedem funktionierenden Gerät macht die
     * Liste unlesbar.
     */
    private static String handlungshinweis(Geraetezustand g) {
        if (!g.erreichbar || g.aktualisierend) return "";
        if (!g.pinStatus.handlungsbedarf()) return "";
        String text = g.handlung != null && !g.handlung.isBlank()
                ? g.handlung : g.pinStatus.erklaerung();
        return "<div class=\"handlung\">" + esc(text) + "</div>";
    }

    private String geraeteTabelle(String suche, Geraetesortierung sort, boolean absteigend) {
        if (geraete.isEmpty()) return "<p class=\"leer\">Noch keine Geräte gemeldet. "
                + "Sobald der Standort-Agent verbunden ist, erscheinen sie hier.</p>";

        // Die Praxis kommt aus dem BESTAND, nicht aus der Meldung des Agenten:
        // Ein Agent kennt nur EINE Einrichtung, betreut in einem MVZ aber
        // Geraete mehrerer Betriebsstaetten. Einmal je Aufbau geholt, nicht je
        // Zeile — bei 5.000 Geraeten waeren das sonst 5.000 Abfragen.
        Map<String, String> praxen = gruppen.einrichtungJeTerminal();
        java.util.function.Function<Geraetezustand, String> praxis =
                g -> praxen.getOrDefault(g.terminalId, g.einrichtung);

        String suchwort = suche == null ? "" : suche.trim().toLowerCase(java.util.Locale.ROOT);
        var treffer = geraete.values().stream()
                .filter(g -> suchwort.isEmpty()
                        || enthaelt(anzeigename(g), suchwort)
                        || enthaelt(g.terminalId, suchwort)
                        || enthaelt(praxis.apply(g), suchwort)
                        || enthaelt(g.modell, suchwort))
                .sorted(geraeteordnung(sort, absteigend, praxis))
                .toList();

        if (treffer.isEmpty()) {
            return "<p class=\"leer\">Kein Gerät passt zu „" + esc(suche) + "“. "
                 + "Gesucht wird in Gerätename, Kennung, Praxis und Modell.</p>";
        }

        StringBuilder s = new StringBuilder();
        s.append("<p class=\"suchstand\" role=\"status\" aria-live=\"polite\">")
         .append(treffer.size()).append(treffer.size() == 1 ? " Gerät" : " Geräte")
         .append(suchwort.isEmpty() ? "" : " gefunden").append("</p>");
        s.append("<div class=\"rollbar\"><table><thead><tr>");
        for (Geraetesortierung sp : Geraetesortierung.values()) s.append(geraetekopf(sp, sort, absteigend, suche));
        s.append("</tr></thead><tbody>");

        for (Geraetezustand g : treffer) {
            s.append("""
                <tr><td class="geraet"><a href="/geraete/%s/verlauf" style="color:inherit">%s</a><br><span style="font-weight:400;color:var(--gedaempft);font-size:.85em">%s</span></td>
                <td>%s</td>
                <td>%s</td><td><code>%s</code></td><td>%s%s</td><td>%s</td>
                <td style="font-variant-numeric:tabular-nums">%s</td><td class="zeit">%s</td></tr>"""
                .formatted(esc(g.terminalId), esc(anzeigename(g)), esc(g.terminalId),
                        esc(praxis.apply(g)),
                        esc(nz(g.modell)), esc(nz(g.firmware)),
                        marke(g), handlungshinweis(g), automatikMarke(g),
                        g.verbleibendeVersuche < 0 ? "—" : String.valueOf(g.verbleibendeVersuche),
                        g.zuletztGeprueftText()));
        }
        return s.append("</tbody></table></div>").toString();
    }

    private static boolean enthaelt(String wert, String suchwort) {
        return wert != null && wert.toLowerCase(java.util.Locale.ROOT).contains(suchwort);
    }

    /**
     * Die Reihenfolge der Geräteliste.
     *
     * <p>Jede Sortierung endet auf der <b>Kennung</b>. Ohne dieses zweite
     * Kriterium wechseln Zeilen bei jeder Aktualisierung die Plätze, sobald zwei
     * Geräte denselben Wert haben — und die Liste aktualisiert sich alle fünf
     * Sekunden von selbst. Eine springende Liste ist unbedienbar.
     */
    private Comparator<Geraetezustand> geraeteordnung(
            Geraetesortierung sort, boolean absteigend,
            java.util.function.Function<Geraetezustand, String> praxis) {
        Comparator<Geraetezustand> c = switch (sort) {
            case PRAXIS    -> Comparator.comparing(g -> praxis.apply(g), String.CASE_INSENSITIVE_ORDER);
            case GERAET    -> Comparator.comparing(g -> anzeigename(g), String.CASE_INSENSITIVE_ORDER);
            case MODELL    -> Comparator.comparing(g -> nz(g.modell), String.CASE_INSENSITIVE_ORDER);
            case FIRMWARE  -> Comparator.comparing(g -> nz(g.firmware), String.CASE_INSENSITIVE_ORDER);
            case ZUSTAND   -> Comparator.comparing(Geraetezustand::zustandstext, String.CASE_INSENSITIVE_ORDER);
            case AUTOMATIK -> Comparator.comparing(g -> g.pevimAktiv ? 0 : 1);
            case VERSUCHE  -> Comparator.comparingInt(g -> g.verbleibendeVersuche);
            case GEPRUEFT  -> Comparator.comparing(g -> g.zuletztGeprueft,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if (absteigend) c = c.reversed();
        return c.thenComparing(g -> g.terminalId);
    }

    /** Ein sortierender Spaltenkopf. Ein zweiter Klick dreht die Richtung um. */
    private static String geraetekopf(Geraetesortierung sp, Geraetesortierung aktiv,
                                      boolean absteigend, String suche) {
        boolean istAktiv = sp == aktiv;
        String richtung = istAktiv && !absteigend ? "ab" : "auf";
        String pfeil = !istAktiv ? "" : (absteigend ? " ▾" : " ▴");
        String q = "?sortierung=" + sp.name() + "&richtung=" + richtung
                 + (suche == null || suche.isBlank() ? ""
                    : "&suche=" + java.net.URLEncoder.encode(suche, java.nio.charset.StandardCharsets.UTF_8));
        // Dieselbe Klasse wie in der Bestandsliste: "sortlink". Ein eigener
        // Name haette eine zweite Stelle im Stylesheet gebraucht — und
        // Sortierkoepfe, die auf zwei Seiten verschieden aussehen.
        // aria-sort teilt Screenreadern mit, wonach gerade sortiert ist (WCAG).
        return ("<th scope=\"col\" aria-sort=\"%s\"><a class=\"sortlink\" href=\"/geraete%s\" "
                + "title=\"Nach %s sortieren\">%s%s</a></th>")
                .formatted(!istAktiv ? "none" : (absteigend ? "descending" : "ascending"),
                        q, esc(sp.anzeige), esc(sp.anzeige), pfeil);
    }

    private String protokollSeite(Context c) {
        StringBuilder arten = new StringBuilder("<option value=\"\">alle Ereignisarten</option>");
        for (Ereignisart a : Ereignisart.values())
            arten.append("<option value=\"%s\">%s</option>".formatted(a.name(), esc(a.anzeige())));
        return seitenkopf("Protokoll",
                "Jede Handlung des Roboters ist hier nachvollziehbar. Protokolliert werden "
                + "Zustandsänderungen und Handlungen — keine Abfragen.", null) + """
                <div class="tafel">
                  <div class="filter">
                    <label class="nurLeser" for="f-art">Ereignisart</label>
                    <select id="f-art" name="art" hx-get="/teil/protokoll" hx-target="#protokoll" hx-include=".filter">%s</select>
                    <label class="nurLeser" for="f-erg">Ergebnis</label>
                    <select id="f-erg" name="ergebnis" hx-get="/teil/protokoll" hx-target="#protokoll" hx-include=".filter">
                      <option value="">alle Ergebnisse</option><option value="erfolg">erfolgreich</option>
                      <option value="fehler">fehlgeschlagen</option></select>
                    <button class="knopf" hx-get="/teil/protokoll" hx-target="#protokoll" hx-include=".filter" title="Lädt die Ansicht mit dem aktuellen Stand neu" aria-label="Lädt die Ansicht mit dem aktuellen Stand neu">Aktualisieren</button>
                    <a class="knopf" href="/protokoll/export" download title="Exportiert die gefilterten Protokolleinträge als Excel-Datei" aria-label="Exportiert die gefilterten Protokolleinträge als Excel-Datei">Als Excel herunterladen</a>
                    <!-- Traegt die Seitennummer durch jede Abfrage. Ohne sie
                         wuerde die Auffrischung alle 5 s auf Seite 1
                         zurueckspringen, sobald jemand zurueckblaettert. -->
                    <input type="hidden" id="f-seite" name="seite" value="1">
                  </div>
                  <!-- Gesucht wird ueber die GERAETEKENNUNG, nicht ueber den
                       Beschreibungstext. Das ist eine bewusste Grenze: Auf
                       geraet liegt ein Index (fachprotokoll_geraet_idx), auf
                       text nicht. Eine Volltextsuche ueber Millionen Zeilen
                       ohne Index waere ein Suchfeld, das die Oberflaeche
                       anhaelt — angeboten, aber unbenutzbar. -->
                  <div class="filter filter-suche">
                    <div class="suchfeld">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                           aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.6-3.6"/></svg>
                      <label class="nurLeser" for="f-geraet">Nach Gerätekennung filtern</label>
                      <input id="f-geraet" type="search" name="geraet" value="%s"
                             placeholder="Gerätekennung, z. B. KT-001"
                             title="Zeigt nur Einträge zu diesem Kartenterminal"
                             hx-get="/teil/protokoll" hx-target="#protokoll" hx-include=".filter"
                             hx-trigger="input changed delay:250ms, search">
                    </div>
                    <label class="nurLeser" for="f-richtung">Zeitliche Reihenfolge</label>
                    <select id="f-richtung" name="richtung" title="Neueste zuerst oder älteste zuerst"
                            hx-get="/teil/protokoll" hx-target="#protokoll" hx-include=".filter">
                      <option value="ab">neueste zuerst</option>
                      <option value="auf"%s>älteste zuerst</option>
                    </select>
                  </div>
                  <div id="protokoll" hx-get="/teil/protokoll" hx-trigger="every 5s" hx-include=".filter" hx-swap="innerHTML">%s</div>
                </div>""".formatted(arten, esc(param(c, "geraet")),
                        "auf".equals(param(c, "richtung")) ? " selected" : "",
                        protokollTabelle(c));
    }

    /** Wie viele Protokollzeilen eine Seite zeigt (ADR-0005, Blättern). */
    static final int PROTOKOLL_SEITENGROESSE = 50;

    private String protokollTabelle(Context c) {
        int nr = 1;
        try {
            String s = c.queryParam("seite");
            if (s != null && !s.isBlank()) nr = Integer.parseInt(s);
        } catch (NumberFormatException ignored) { }

        var seite = protokoll.seite(c.queryParam("geraet"), c.queryParam("art"),
                c.queryParam("ergebnis"), nr, PROTOKOLL_SEITENGROESSE, bereich(c),
                "auf".equals(param(c, "richtung")));

        // Hinter das Ende geblaettert - etwa weil ein Filter enger wurde.
        // Statt einer leeren Seite die letzte vorhandene zeigen.
        if (seite.eintraege().isEmpty() && seite.gesamt() > 0 && nr > seite.seitenZahl()) {
            seite = protokoll.seite(c.queryParam("geraet"), c.queryParam("art"),
                    c.queryParam("ergebnis"), seite.seitenZahl(), PROTOKOLL_SEITENGROESSE, bereich(c));
        }
        var liste = seite.eintraege();
        if (liste.isEmpty()) return "<p class=\"leer\">Keine Einträge für diese Auswahl.</p>";
        StringBuilder s = new StringBuilder("<div class=\"rollbar\"><table><thead><tr>"
                + "<th>Zeitpunkt</th><th>Gerät</th><th>Ereignis</th><th>Ergebnis</th>"
                + "<th>Beschreibung</th><th>Vorgang</th></tr></thead><tbody>");
        for (Ereignis e : liste) {
            s.append("""
                    <tr><td class="zeit">%s</td><td class="geraet">%s</td><td>%s</td>
                    <td><span class="marke %s"><span class="sym">%s</span>%s</span></td>
                    <td>%s%s</td><td><code>%s</code></td></tr>"""
                    .formatted(e.zeitLokal(), esc(nz(e.geraet())), esc(e.art().anzeige()),
                            e.erfolg() ? "ok" : "fehler", e.erfolg() ? "✓" : "×",
                            e.erfolg() ? "erfolgreich" : "fehlgeschlagen",
                            esc(nz(e.text())),
                            e.handlung() == null ? "" : "<div class=\"handlung\">" + esc(e.handlung()) + "</div>",
                            esc(e.vorgang() == null ? "—" : e.vorgang())));
        }
        s.append("</tbody></table></div>");
        return s.append(protokollBlaetterleiste(seite)).toString();
    }

    /**
     * Blätterleiste des Protokolls.
     *
     * <p>Die Knöpfe setzen das versteckte Feld {@code f-seite} und lösen dann
     * die Abfrage aus — dadurch bleibt die Seitennummer auch bei der
     * automatischen Auffrischung erhalten.
     *
     * <p>Auf Seite 2 und weiter steht ein Hinweis: Das Protokoll wächst
     * weiter, und neue Einträge erscheinen oben. Wer zurückblättert, sieht
     * deshalb Einträge langsam nach hinten wandern. Das ist unvermeidbar bei
     * einer Live-Ansicht — aber es soll niemanden überraschen.
     */
    private String protokollBlaetterleiste(Fachprotokoll.Seite seite) {
        String knopf = """
                <button class="knopf %s" %s title="%s" aria-label="%s"
                  hx-get="/teil/protokoll" hx-target="#protokoll" hx-include=".filter"
                  onclick="document.getElementById('f-seite').value=%d">%s</button>""";
        return """
                <nav class="blaettern" aria-label="Seiten des Protokolls">
                  %s
                  <span aria-live="polite">Eintrag %d bis %d von %d · Seite %d von %d%s</span>
                  %s
                </nav>"""
                .formatted(
                        seite.hatVorherige()
                                ? knopf.formatted("", "", "Zeigt die nächstneueren Einträge",
                                        "Neuere Einträge anzeigen", seite.seitenNummer() - 1, "Neuere")
                                : knopf.formatted("gesperrt", "disabled",
                                        "Sie sehen bereits die neuesten Einträge",
                                        "Keine neueren Einträge vorhanden", 1, "Neuere"),
                        seite.von(), seite.bis(), seite.gesamt(),
                        seite.seitenNummer(), seite.seitenZahl(),
                        seite.seitenNummer() > 1
                                ? " · neue Einträge erscheinen auf Seite 1" : "",
                        seite.hatNaechste()
                                ? knopf.formatted("", "", "Zeigt die nächstälteren Einträge",
                                        "Ältere Einträge anzeigen", seite.seitenNummer() + 1, "Ältere")
                                : knopf.formatted("gesperrt", "disabled",
                                        "Sie sehen bereits die ältesten vorhandenen Einträge",
                                        "Keine älteren Einträge vorhanden",
                                        seite.seitenNummer(), "Ältere"));
    }

    // ---------------------------------------------------------------- Rahmen

    private String seite(String aktiv, String inhalt) {
        return seite(aktiv, inhalt, null);
    }

    private String seite(String aktiv, String inhalt, Benutzerverwaltung.Benutzer angemeldet) {
        return """
                <!doctype html><html lang="de"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>PIN-Roboter</title><link rel="stylesheet" href="/statisch/stil.css">
                <script src="/statisch/htmx.min.js" defer></script>
                <script src="/statisch/oberflaeche.js" defer></script></head><body>
                <div class="schein" aria-hidden="true"></div>
                <a href="#inhalt" class="nurLeser">Zum Inhalt springen</a>
                <div class="rahmen">
                %s
                <main id="inhalt">%s</main>
                </div></body></html>"""
                .formatted(seitenleiste(aktiv, angemeldet), inhalt);
    }

    /**
     * Die Seitenleiste (UX-Konzept vom 07.08.2026).
     *
     * <p>Sie ersetzt das Reiterband im Kopf. Bei acht Arbeitsbereichen wurde das
     * Band zu voll; senkrecht ist Platz, und die Bereiche lassen sich
     * gruppieren — Betrieb und Verwaltung sind zwei verschiedene Anlässe, die
     * Oberfläche zu öffnen.
     */
    /**
     * Der Kopf einer Seite: Titel, Unterzeile, optionale Knöpfe rechts.
     *
     * <p>An einer Stelle, damit jede Seite gleich aussieht. Vorher trug jede
     * Seite ihre Überschrift selbst — mit leicht unterschiedlichen Abständen,
     * und das war Teil des unruhigen Eindrucks.
     *
     * <p>Die Überschrift ist mit {@code tabindex="-1"} fokussierbar: Nach einem
     * Seitenwechsel bekommt sie den Fokus, damit Screenreader den Wechsel
     * ansagen. Ein Rahmen erscheint dabei nicht — sie ist kein Bedienelement.
     */
    static String seitenkopf(String titel, String unterzeile, String rechts) {
        return """
                <div class="seitenkopf">
                  <div><h2 tabindex="-1">%s</h2>%s</div>
                  %s
                </div>"""
                .formatted(esc(titel),
                        unterzeile == null ? "" : "<p class=\"unterzeile\">" + unterzeile + "</p>",
                        rechts == null || rechts.isBlank() ? ""
                                : "<div class=\"kopf-rechts\">" + rechts + "</div>");
    }

    /** Seitenkopf mit Rückweg — für Detailseiten (UX-Konzept, Punkt 5). */
    static String detailkopf(String zurueckZiel, String zurueckText, String titel,
                             String unterzeile, String rechts) {
        String pfeil = "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" "
                + "stroke=\"currentColor\" stroke-width=\"2\" aria-hidden=\"true\"><path d=\"M10 3 5 8l5 5\"/></svg>";
        return """
                <div class="seitenkopf">
                  <div>
                    <a class="zurueck" href="%s">%s%s</a>
                    <h2 tabindex="-1">%s</h2>%s
                  </div>
                  %s
                </div>"""
                .formatted(zurueckZiel, pfeil, esc(zurueckText), esc(titel),
                        unterzeile == null ? "" : "<p class=\"unterzeile\">" + unterzeile + "</p>",
                        rechts == null || rechts.isBlank() ? ""
                                : "<div class=\"kopf-rechts\">" + rechts + "</div>");
    }

    private String seitenleiste(String aktiv, Benutzerverwaltung.Benutzer angemeldet) {
        // Wie viele Agenten melden sich gerade? Bis zum 10.08.2026 stand hier
        // nur "Agent verbunden" — bei einer Serviceorganisation mit dreißig
        // Standorten sagt das nichts. Jetzt: wie viele von wie vielen.
        //
        // "Verbunden" heißt: innerhalb der letzten zwei Minuten gemeldet. Der
        // Grundtakt liegt bei 10 s und das längste Prüfintervall im Betrieb bei
        // 60 s — zwei Minuten lassen einen ausgefallenen Takt zu, ohne einen
        // wirklich abgehängten Agenten als verbunden auszuweisen.
        String agentText = agentZeile();
        // Ueber alle Einrichtungen des Geltungsbereichs — sonst zeigte die
        // Kopfzeile in einem MVZ dauerhaft „0 Alarme", waehrend die Lage-Seite
        // welche auflistet.
        int offeneAlarme = alarme.offene(Geltungsbereich.alles()).size();

        StringBuilder s = new StringBuilder();
        s.append("""
                <nav class="leiste" aria-label="Hauptnavigation">
                  <div class="marke-kopf">
                    <div class="marke-zeichen" aria-hidden="true">PR</div>
                    <div class="marke-text">PIN-Roboter<small>%s</small><small
                         class="version" title="Laufende Version dieser Zentrale">%s</small></div>
                  </div>
                  <nav class="bereiche" aria-label="Arbeitsbereiche">
                    <div class="bereich-titel">Betrieb</div>
                """.formatted(esc(agentText), esc(versionsanzeige())));

        s.append(bereichsLink(aktiv, "uebersicht", "/", "Lage",
                "<path d=\"M3 10a7 7 0 0 1 14 0M10 10l3.5-3\"/><circle cx=\"10\" cy=\"10\" r=\"1.3\" fill=\"currentColor\" stroke=\"none\"/>",
                offeneAlarme));
        s.append(bereichsLink(aktiv, "geraete", "/geraete", "Kartenterminals",
                "<rect x=\"2.5\" y=\"4\" width=\"15\" height=\"10\" rx=\"2\"/><path d=\"M6.5 17h7M10 14v3\"/>", 0));
        s.append(bereichsLink(aktiv, "gruppen", "/gruppen", "Gerätegruppen",
                "<rect x=\"2.5\" y=\"3\" width=\"6\" height=\"6\" rx=\"1.5\"/><rect x=\"11.5\" y=\"3\" width=\"6\" height=\"6\" rx=\"1.5\"/><rect x=\"2.5\" y=\"11\" width=\"6\" height=\"6\" rx=\"1.5\"/><rect x=\"11.5\" y=\"11\" width=\"6\" height=\"6\" rx=\"1.5\"/>", 0));
        s.append(bereichsLink(aktiv, "benachrichtigung", "/benachrichtigung", "Benachrichtigung",
                "<path d=\"M3 6.5 10 11l7-4.5\"/><rect x=\"3\" y=\"4.5\" width=\"14\" height=\"11\" rx=\"2\"/>", 0));
        s.append(bereichsLink(aktiv, "protokoll", "/protokoll", "Protokoll",
                "<path d=\"M5 3h7l3 3v11a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z\"/><path d=\"M7 9h6M7 12h6\"/>", 0));

        s.append("<div class=\"bereich-titel\">Verwaltung</div>");
        s.append(bereichsLink(aktiv, "bestand", "/bestand", "Bestand",
                "<path d=\"M3 6.5 10 3l7 3.5-7 3.5-7-3.5Z\"/><path d=\"M3 10 10 13.5 17 10M3 13.5 10 17l7-3.5\"/>", 0));
        s.append(bereichsLink(aktiv, "organisation", "/organisation", "Organisation",
                "<rect x=\"7.5\" y=\"2.5\" width=\"5\" height=\"4\" rx=\"1\"/><rect x=\"2\" y=\"13.5\" width=\"5\" height=\"4\" rx=\"1\"/><rect x=\"13\" y=\"13.5\" width=\"5\" height=\"4\" rx=\"1\"/><path d=\"M10 6.5v4M4.5 13.5v-3h11v3\"/>", 0));
        s.append(bereichsLink(aktiv, "benutzer", "/benutzer", "Konten",
                "<circle cx=\"10\" cy=\"7\" r=\"3\"/><path d=\"M4 16.5a6 6 0 0 1 12 0\"/>", 0));
        s.append(bereichsLink(aktiv, "verwaltung", "/verwaltung", "Instanz",
                "<circle cx=\"10\" cy=\"10\" r=\"2.6\"/><path d=\"M10 2.5v2M10 15.5v2M2.5 10h2M15.5 10h2M4.7 4.7l1.4 1.4M13.9 13.9l1.4 1.4M15.3 4.7l-1.4 1.4M6.1 13.9l-1.4 1.4\"/>", 0));
        // Einstellungen (FA-BAK-050): Datensicherung und Systemtest. Getrennt
        // von "Instanz", weil dort der laufende Betrieb steht — Agenten,
        // Speicher, Version. Hier stehen Dinge, die man selten und bewusst tut.
        s.append(bereichsLink(aktiv, "einstellungen", "/einstellungen", "Einstellungen",
                "<path d=\"M3 5.5h14M3 10h14M3 14.5h14\"/><circle cx=\"7\" cy=\"5.5\" r=\"1.6\" fill=\"var(--karte)\"/><circle cx=\"13\" cy=\"10\" r=\"1.6\" fill=\"var(--karte)\"/><circle cx=\"7\" cy=\"14.5\" r=\"1.6\" fill=\"var(--karte)\"/>", 0));
        s.append("</nav>");

        // Not-Aus und Konto stehen unten: Beides gehoert nicht in den
        // Arbeitsfluss, muss aber jederzeit erreichbar sein.
        s.append("<div class=\"leiste-fuss\">");
        s.append("""
                <button class="knopf-leiste" hx-post="/notaus" style="color:var(--%s)"
                        title="Hält die Automatik sofort an">
                  <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true">
                    <circle cx="10" cy="10" r="7"/><path d="M7.2 7.2h5.6v5.6H7.2z"/>
                  </svg>%s
                </button>""".formatted(notAus ? "ok" : "fehler",
                        notAus ? "Not-Aus aufheben" : "Not-Aus"));

        if (angemeldet != null) {
            s.append("""
                    <div class="angemeldet">
                      <div class="konto-kreis" aria-hidden="true">%s</div>
                      <div class="konto-text"><b>%s</b><span>%s</span></div>
                    </div>
                    <button class="knopf-leiste" hx-post="/abmelden"
                        title="Beendet die Sitzung; für die nächste Anmeldung sind Kennwort und Einmalcode nötig"
                        aria-label="Abmelden">
                      <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true">
                        <path d="M12.5 6V4a1 1 0 0 0-1-1H4.5a1 1 0 0 0-1 1v12a1 1 0 0 0 1 1h7a1 1 0 0 0 1-1v-2"/>
                        <path d="M8 10h8.5M14 7.5 16.5 10 14 12.5"/>
                      </svg>Abmelden
                    </button>""".formatted(esc(initialen(angemeldet.anzeigename())),
                            esc(angemeldet.anzeigename()), esc(angemeldet.rolle().anzeige())));
        }
        return s.append("</div></nav>").toString();
    }

    private static String bereichsLink(String aktiv, String kennung, String ziel, String text,
                                       String symbol, int zahl) {
        return ("<a href=\"%s\" %s>"
              + "<svg viewBox=\"0 0 20 20\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.7\" aria-hidden=\"true\">%s</svg>"
              + "%s%s</a>")
                .formatted(ziel, aktiv.equals(kennung) ? "aria-current=\"page\"" : "", symbol, esc(text),
                        zahl > 0 ? "<span class=\"zahl-bereich\" title=\"offene Alarme\">" + zahl + "</span>" : "");
    }

    /** Initialen für den Kreis in der Seitenleiste. */
    private static String initialen(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] teile = name.trim().split("\\s+");
        if (teile.length == 1) return teile[0].substring(0, 1).toUpperCase();
        return (teile[0].charAt(0) + "" + teile[teile.length - 1].charAt(0)).toUpperCase();
    }

    private String marke(Geraetezustand g) {
        Ampel a = g.ampel();
        return "<span class=\"marke %s\"><span class=\"sym\">%s</span>%s</span>"
                .formatted(a.css(), a.symbol(), esc(g.zustandstext()));
    }

    /** Ein Gerät ohne PEviM ist kein Fehler — es wird überwacht, nur nicht automatisiert. */
    private String automatikMarke(Geraetezustand g) {
        if (!g.pevimUnterstuetzt)
            return "<span class=\"marke unbekannt\"><span class=\"sym\">–</span>Modell ohne PEviM</span>";
        if (!g.pevimAktiv)
            return "<span class=\"marke warnung\"><span class=\"sym\">!</span>nicht aktiviert</span>";
        if (g.automatikAngehalten)
            return "<span class=\"marke fehler\"><span class=\"sym\">×</span>angehalten</span>";
        return "<span class=\"marke ok\"><span class=\"sym\">✓</span>aktiv</span>";
    }

    private static String anzeigename(Geraetezustand g) {
        return g.name != null && !g.name.isBlank() ? g.name : g.terminalId;
    }

    private static String nz(String s) { return s == null ? "—" : s; }
    private static String str(Map<String, Object> m, String k, String vorgabe) {
        Object o = m.get(k); return o == null ? vorgabe : String.valueOf(o);
    }
    private static boolean bool(Map<String, Object> m, String k) { return Boolean.TRUE.equals(m.get(k)); }
    private static int zahl(Map<String, Object> m, String k) {
        Object o = m.get(k); return o instanceof Number n ? n.intValue() : -1;
    }
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Anmelden, Abmelden, Ersteinrichtung, zweiter Faktor, Einladungen (FA-BEN-010/020/030).
     *
     * <p>Die Reihenfolge innerhalb dieser Methode ist bedeutsam: Javalin nimmt
     * die zuerst passende Route, deshalb müssen feste Pfade vor solchen mit
     * Platzhalter stehen.
     */
    private void routenAnmeldung(Javalin app) {
        // ------------------------------------------------------------------ Anmeldung
        app.get("/anmelden", c -> c.html(Anmeldeseiten.anmeldeformular(null)));

        app.post("/anmelden", c -> {
            String name = Anmeldeseiten.feld(c, "benutzer");
            String absender = c.ip();

            // Bremse VOR der Pruefung: Wer schon auffaellig war, wartet - und
            // zwar unabhaengig davon, ob dieses Kennwort stimmt. Sonst liesse
            // sich die Bremse durch einen bekannten Treffer zuruecksetzen.
            var warten = bremse.wartezeit(absender);
            if (!warten.isZero()) {
                try { Thread.sleep(warten.toMillis()); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }

            var ergebnis = benutzer.anmelden(name,
                    Anmeldeseiten.passwortFeld(c, "passwort"), Anmeldeseiten.feld(c, "code"));

            switch (ergebnis) {
                case Benutzerverwaltung.Anmeldung.Erfolg e -> {
                    bremse.erfolg(absender);
                    setzeSitzungscookie(c, e.sitzung());
                    protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.ANMELDUNG_ERFOLGREICH,
                            true, e.benutzer().anzeigename(), null,
                            "Anmeldung erfolgreich", null);
                    c.redirect("/");
                }
                case Benutzerverwaltung.Anmeldung.TotpEinrichten t ->
                    c.html(Anmeldeseiten.totpEinrichten(t.benutzerId(), t.uri(), t.geheimnis(),
                            "Bitte richten Sie zuerst den zweiten Faktor ein."));
                case Benutzerverwaltung.Anmeldung.Gesperrt g -> {
                    zaehleFehlversuch(absender, name);
                    protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.ANMELDUNG_FEHLGESCHLAGEN,
                            false, name, null, "Anmeldung bei gesperrtem Konto", null);
                    c.html(Anmeldeseiten.anmeldeformular(g.meldung()));
                }
                case Benutzerverwaltung.Anmeldung.Abgelehnt a -> {
                    zaehleFehlversuch(absender, name);
                    // Der Benutzername wird protokolliert, das Passwort nie (FA-PRO-020).
                    protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.ANMELDUNG_FEHLGESCHLAGEN,
                            false, name.isBlank() ? "unbekannt" : name, null,
                            "Anmeldung fehlgeschlagen", null);
                    c.html(Anmeldeseiten.anmeldeformular(a.meldung()));
                }
            }
        });

        app.post("/abmelden", c -> {
            benutzer.abmelden(c.cookie(SITZUNGSCOOKIE));
            c.removeCookie(SITZUNGSCOOKIE, "/");
            c.header("HX-Redirect", "/anmelden").redirect("/anmelden");
        });

        // ------------------------------------------------------------ Ersteinrichtung
        app.get("/ersteinrichtung", c -> {
            if (!benutzer.ersteinrichtungNoetig()) { c.redirect("/anmelden"); return; }
            c.html(Anmeldeseiten.ersteinrichtung(null));
        });

        app.post("/ersteinrichtung", c -> {
            if (!benutzer.ersteinrichtungNoetig()) { c.redirect("/anmelden"); return; }
            var e = benutzer.anlegen(Anmeldeseiten.feld(c, "benutzer"),
                    Anmeldeseiten.feld(c, "anzeigename"),
                    Anmeldeseiten.passwortFeld(c, "passwort"),
                    Benutzerverwaltung.Rolle.ADMINISTRATOR);
            if (e instanceof Benutzerverwaltung.Anmeldung.TotpEinrichten t) {
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.BENUTZER_ANGELEGT, true,
                        "Ersteinrichtung", null,
                        "Erstes Administratorkonto angelegt: " + Anmeldeseiten.feld(c, "benutzer"), null);
                c.html(Anmeldeseiten.totpEinrichten(t.benutzerId(), t.uri(), t.geheimnis(), null));
            } else if (e instanceof Benutzerverwaltung.Anmeldung.Abgelehnt a) {
                c.html(Anmeldeseiten.ersteinrichtung(a.meldung()));
            }
        });

        app.post("/totp-bestaetigen", c -> {
            long id = Long.parseLong(Anmeldeseiten.feld(c, "benutzerId"));
            if (benutzer.totpBestaetigen(id, Anmeldeseiten.feld(c, "code"))) {
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.TOTP_EINGERICHTET, true,
                        "Benutzer " + id, null, "Zweiter Faktor eingerichtet", null);
                c.html(Anmeldeseiten.rahmen("Einrichtung abgeschlossen", """
                        <div class="hinweis"><strong>Der zweite Faktor ist eingerichtet.</strong>
                        Sie können sich jetzt anmelden.</div>
                        <a class="knopf haupt" href="/anmelden" title="Öffnet die Anmeldeseite" aria-label="Öffnet die Anmeldeseite">Zur Anmeldung</a>"""));
            } else {
                c.html(Anmeldeseiten.rahmen("Einrichtung", Anmeldeseiten.meldung(
                        "Der Code stimmt nicht. Bitte prüfen Sie die Uhrzeit Ihres Telefons "
                        + "und versuchen Sie es mit dem nächsten Code erneut.", "warnend")
                        + "<a class=\"knopf\" href=\"/anmelden\" title=\"Zurück zur Anmeldeseite\" "
                        + "aria-label=\"Zurück zur Anmeldung\">Zurück</a>"));
            }
        });

        // Einloesen einer Einladung - ohne Anmeldung erreichbar.
        app.get("/einladung", c -> c.html(Benutzerseiten.einloesenSeite(
                c.queryParam("code"), null)));

        app.post("/einladung", c -> {
            String geheimnis = Anmeldeseiten.feld(c, "geheimnis");
            char[] kennwort = Anmeldeseiten.feld(c, "kennwort").toCharArray();
            var e = einladungen.einloesen(geheimnis, kennwort);
            java.util.Arrays.fill(kennwort, '\0');

            if (e instanceof Einladungen.Einloesung.Abgelehnt a) {
                c.html(Benutzerseiten.einloesenSeite(geheimnis, a.grund()));
                return;
            }
            var erfolg = (Einladungen.Einloesung.Erfolg) e;
            protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.BENUTZER_ANGELEGT, true,
                    "Einladung", null,
                    "Einladung eingelöst, Konto eingerichtet (Nummer " + erfolg.benutzerId() + ")",
                    null);
            // Weiter zur TOTP-Einrichtung - derselbe Ablauf wie bei der
            // Ersteinrichtung, damit niemand zwei Wege lernen muss.
            c.html(Anmeldeseiten.totpEinrichten(erfolg.benutzerId(), erfolg.totpUri(),
                    erfolg.totpGeheimnis(), null));
        });
    }


    /**
     * Lage, Geräte, Alarme, Not-Aus und die HTMX-Einschübe, die sich selbst auffrischen.
     *
     * <p>Die Reihenfolge innerhalb dieser Methode ist bedeutsam: Javalin nimmt
     * die zuerst passende Route, deshalb müssen feste Pfade vor solchen mit
     * Platzhalter stehen.
     */
    private void routenUebersicht(Javalin app) {
        app.get("/", c -> c.html(seite("uebersicht", uebersichtsSeite(c), angemeldeter(c))));

        app.get("/geraete", c -> c.html(seite("geraete", geraeteSeite(c), angemeldeter(c))));

        app.get("/teil/uebersicht", c -> c.html(uebersicht(c)));

        // Zwei getrennte Einschuebe statt eines Komplettaustauschs. Siehe
        // uebersichtsSeite() zur Begruendung.
        app.get("/teil/alarme", c -> c.html(alarmblock(c)));

        app.get("/teil/lage",   c -> c.html(lageblock(c)));

        app.get("/teil/geraete",    c -> c.html(geraeteTabelle(c.queryParam("suche"),
                Geraetesortierung.aus(c.queryParam("sortierung")),
                "ab".equals(c.queryParam("richtung")))));

        /**
         * Geräte-Zeitstrahl (FA-PRO-050) — aus der Geräteliste in einem Klick.
         *
         * <p>Das gefilterte Protokoll leistet das nicht: Dort sucht man
         * Einträge, hier liest man die Geschichte eines Geräts.
         */
        app.get("/geraete/{terminalId}/verlauf", c -> {
            String id = c.pathParam("terminalId");
            String zeitraum = c.queryParam("zeitraum");
            if (zeitraum == null || zeitraum.isBlank()) zeitraum = "7t";
            // Die Feinauswahl schlaegt die Sicht: Wer einzelne Arten ankreuzt,
            // hat sich bewusst entschieden. Ohne Haekchen entscheidet die
            // Sicht, und ohne Sicht wird nichts gefiltert.
            // Feinauswahl und Sicht getrennt halten — sie DUERFEN sich nicht
            // vermischen. Vorher wurde die Sicht in Arten uebersetzt und diese
            // Liste wieder in die Ansicht gegeben; dort kreuzte sie die
            // Feinauswahl an, deren Haekchen beim naechsten Absenden die Sicht
            // ueberstimmten. Ergebnis: Man kam aus einer Sicht nicht mehr
            // heraus (Befund PO, 11.08.2026).
            var feinauswahl = c.queryParams("art");
            String sicht = c.queryParam("sicht");
            var arten = feinauswahl.isEmpty() ? Verlaufsseiten.artenDerSicht(sicht) : feinauswahl;
            var seite = protokoll.seite(id, arten, null,
                    Verlaufsseiten.seitFuer(zeitraum), null,
                    Math.max(1, zahl(c.queryParam("seite"), 1)), 200, bereich(c));
            var g = geraete.get(id);
            c.html(seite("geraete", Verlaufsseiten.zeitstrahl(id,
                    g == null ? id : anzeigename(g), seite, zeitraum, sicht, feinauswahl),
                    angemeldeter(c)));
        });

        /** Vorgangsansicht (FA-PRO-060). */
        app.get("/vorgang/{nummer}", c -> {
            String nr = c.pathParam("nummer");
            var seite = protokoll.seite(null, java.util.List.of(), null, null, nr, 1, 200, bereich(c));
            c.html(seite("protokoll", Verlaufsseiten.vorgang(nr, seite), angemeldeter(c)));
        });

        app.get("/teil/vorgang/{nummer}", c -> {
            String nr = c.pathParam("nummer");
            var seite = protokoll.seite(null, java.util.List.of(), null, null, nr, 1, 200, bereich(c));
            // Nur die Verlaufstafel, nicht die ganze Seite — der Kopf mit den
            // Kennzahlen bleibt stehen (UX-Konzept, Stufe 1).
            String ganz = Verlaufsseiten.vorgang(nr, seite);
            int ab = ganz.lastIndexOf("<div class=\"tafel\"");
            c.html(ab < 0 ? ganz : ganz.substring(ab));
        });

        app.get("/teil/protokoll",  c -> c.html(protokollTabelle(c)));

        app.get("/teil/gruppen", c -> c.html(gruppenTabelle(c)));

        app.post("/geraete/{id}/gruppe", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            String fehler = gruppen.zuordnen(c.pathParam("id"),
                    Long.parseLong(Anmeldeseiten.feld(c, "gruppe")));
            if (fehler == null) {
                protokoll.anfuegen(einrichtungVonTerminal(c.pathParam("id")),
                        c.pathParam("id"), Ereignisart.GERAET_ZUGEORDNET,
                        true, angemeldeter(c).anzeigename(), null,
                        "Gerät einer anderen Gruppe zugeordnet", null);
            }
            c.html(gruppenTabelle(c));
        });

        /**
         * FA-ALA-010: Einen Alarm quittieren.
         *
         * <p>Die Oberfläche bot diesen Knopf schon an, bevor es die Route gab —
         * ein Klick lief ins Leere. Gefunden hat das der Systemnachweis, nicht
         * der Modultest: {@code Alarmverwaltung.quittieren} war geprüft, nur nie
         * angebunden. Eine Funktion, die nur in der Klasse existiert, ist für
         * den Benutzer keine.
         */
        app.post("/alarme/{id}/quittieren", c -> {
            long id = Long.parseLong(c.pathParam("id"));
            var b = angemeldeter(c);
            if (alarme.quittieren(id, b.anzeigename(), bereich(c))) {
                protokoll.anfuegen(bereich(c).bezeichnung(), null, Ereignisart.ALARM_QUITTIERT,
                        true, b.anzeigename(), null,
                        "Alarm " + id + " quittiert",
                        "Quittieren heißt „zur Kenntnis genommen“ — der Alarm bleibt bestehen, "
                        + "solange der Zustand besteht.");
            }
            c.header("HX-Refresh", "true").result("");
        });

        // Einen Alarm von Hand schliessen. Anders als Quittieren nimmt das ihn
        // aus der Liste der offenen - noetig fuer Alarme zu Geraeten, die es
        // nicht mehr gibt und fuer die deshalb nie eine Behebungsmeldung kommt.
        app.post("/alarme/{id}/schliessen", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(),
                    "Das Schließen von Alarmen")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var b = angemeldeter(c);
            if (alarme.schliessen(id, bereich(c))) {
                protokoll.anfuegen(bereich(c).bezeichnung(), null,
                        Ereignisart.ALARM_GESCHLOSSEN, true, b.anzeigename(), null,
                        "Alarm " + id + " von Hand geschlossen",
                        "Der Alarm verschwindet aus der Liste, bleibt aber im Protokoll. "
                        + "Besteht die Ursache weiter, meldet der Agent ihn erneut.");
            }
            c.header("HX-Refresh", "true").result("");
        });

        // Sammelaktion: alles wegräumen, was im eigenen Bereich offen ist.
        app.post("/alarme/alle-schliessen", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(),
                    "Das Schließen von Alarmen")) return;
            var b = angemeldeter(c);
            int zahl = alarme.alleSchliessen(bereich(c));
            if (zahl > 0) {
                // Die ZAHL gehoert ins Protokoll: Ohne sie weiss hinterher
                // niemand, ob drei oder dreihundert Alarme verschwunden sind.
                protokoll.anfuegen(bereich(c).bezeichnung(), null,
                        Ereignisart.ALARM_GESCHLOSSEN, true, b.anzeigename(), null,
                        zahl + " Alarm(e) von Hand geschlossen",
                        "Sammelaktion aus der Lage-Übersicht. Die Alarme bleiben im "
                        + "Protokoll; bestehende Störungen melden sich erneut.");
            }
            c.header("HX-Refresh", "true").result("");
        });

        /**
         * Not-Aus (FA-PIN-050).
         *
         * <p><b>Bekannte Einschränkung:</b> Er wirkt <b>instanzweit</b>, nicht
         * je Einrichtung. Bei mehreren Mandanten an einer Zentrale hält ein
         * Praxisadministrator damit auch fremde Standorte an.
         *
         * <p>Das ist bewusst so belassen, nicht übersehen: Die Alternative wäre,
         * ihn für Praxisadministratoren zu sperren — und bei einem
         * Sicherheitsmittel ist das die schlechtere Wahl. Wer den Verdacht hat,
         * dass der Roboter falsch handelt, muss ihn sofort anhalten können.
         *
         * <p>Damit die Wirkung nicht überrascht, wird sie protokolliert
         * <b>mit Namen</b> — bisher stand dort nur „Benutzer". Als offener Punkt
         * vermerkt in {@code docs/05-sicherheit-datenschutz.md}.
         */
        app.post("/notaus", c -> {
            if (abgewiesen(c, rechte(c).darfNotAus(), "Das Auslösen des Not-Aus")) return;
            var b = angemeldeter(c);
            notAus = !notAus;
            protokoll.anfuegen(Fachprotokoll.INSTANZ, null,
                    notAus ? Ereignisart.NOT_AUS_AUSGELOEST : Ereignisart.NOT_AUS_AUFGEHOBEN,
                    true, b == null ? "Unbekannt" : b.anzeigename(), null,
                    notAus ? "Not-Aus ausgelöst — die Automatik ruht."
                           : "Not-Aus aufgehoben — die Automatik arbeitet wieder.",
                    "Der Not-Aus wirkt für alle Einrichtungen dieser Instanz.");
            c.header("HX-Refresh", "true").result("");
        });
    }


    /**
     * Fachprotokoll und Excel-Export (FA-PRO-010 bis 040).
     *
     * <p>Die Reihenfolge innerhalb dieser Methode ist bedeutsam: Javalin nimmt
     * die zuerst passende Route, deshalb müssen feste Pfade vor solchen mit
     * Platzhalter stehen.
     */
    private void routenProtokoll(Javalin app) {
        app.get("/protokoll", c -> c.html(seite("protokoll", protokollSeite(c), angemeldeter(c))));

        // FA-PRO-040: Excel-Export der gefilterten Protokollansicht.
        // Exportiert wird genau das, was auf dem Bildschirm steht - alles andere
        // ueberrascht den Benutzer. Das Deckblatt haelt fest, mit welchen Filtern
        // die Datei entstand; ohne diese Angabe ist ein Export als Nachweis
        // wertlos, weil niemand spaeter weiss, was darin fehlt.
        app.get("/protokoll/export", c -> {
            var b = angemeldeter(c);
            String fArt = c.queryParam("art");
            String fErg = c.queryParam("ergebnis");
            String fGer = c.queryParam("geraet");
            var eintraege = protokoll.filtern(fGer, fArt, fErg, Tabellenmappe.MAX_ZEILEN - 10, bereich(c));

            var aus = new java.io.ByteArrayOutputStream();
            try (Tabellenmappe m = new Tabellenmappe(aus)) {
                m.blattBeginnen("Deckblatt");
                m.zeile("PIN-Roboter - Protokollauszug");
                m.zeile();
                m.zeile("Erstellt am", java.time.Instant.now());
                m.zeile("Erstellt von", b.anzeigename());
                // Die Einrichtungen, die im Auszug wirklich vorkommen — „Praxis"
                // war eine Behauptung, die in einem MVZ schlicht falsch ist.
                m.zeile("Einrichtungen", eintraege.stream()
                        .map(Ereignis::einrichtung).distinct().sorted()
                        .reduce((x, y) -> x + ", " + y).orElse("—"));
                m.zeile();
                m.zeile("Angewendete Filter");
                m.zeile("Ereignisart", leerAls(fArt, "alle"));
                m.zeile("Ergebnis", leerAls(fErg, "alle"));
                m.zeile("Geraet", leerAls(fGer, "alle"));
                m.zeile();
                m.zeile("Zeilen in diesem Auszug", eintraege.size());
                m.zeile();
                m.zeile("Hinweis", "Dieser Auszug enthaelt keine PINs, Kennwoerter oder "
                        + "Zugangsdaten. Er gibt ausschliesslich die oben genannten "
                        + "gefilterten Eintraege wieder.");

                m.blattBeginnen("Protokoll");
                m.kopfzeile("Zeitpunkt", "Gerät", "Ereignis", "Ergebnis",
                            "Ausgelöst durch", "Beschreibung", "Was zu tun ist", "Vorgang");
                for (var e : eintraege) {
                    m.zeile(e.zeitpunkt(),
                            e.geraet() == null ? "" : e.geraet(),
                            e.art().anzeige(),
                            e.erfolg() ? "erfolgreich" : "fehlgeschlagen",
                            e.ausloeser(),
                            e.text() == null ? "" : e.text(),
                            e.handlung() == null ? "" : e.handlung(),
                            e.vorgang() == null ? "" : e.vorgang());
                }
            }

            // Der Export ist selbst protokollpflichtig: Er kann personenbezogene
            // Daten enthalten (FA-PRO-040).
            protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.PROTOKOLL_EXPORTIERT, true,
                    b.anzeigename(), null,
                    "Protokoll exportiert (" + eintraege.size() + " Zeilen)", null);

            String datei = "PIN-Roboter-Protokoll-"
                    + java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))
                    + ".xlsx";
            c.header("Content-Type",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            c.header("Content-Disposition", "attachment; filename=\"" + datei + "\"");
            c.result(aus.toByteArray());
        });
    }


    /**
     * Gerätegruppen und ihr Prüfintervall (FA-GER-050, FA-UEB-010).
     *
     * <p>Die Reihenfolge innerhalb dieser Methode ist bedeutsam: Javalin nimmt
     * die zuerst passende Route, deshalb müssen feste Pfade vor solchen mit
     * Platzhalter stehen.
     */
    private void routenGruppen(Javalin app) {
        // ---------------------------------------------------------- Gruppen
        app.get("/gruppen",      c -> c.html(seite("gruppen", gruppenSeite(c, null), angemeldeter(c))));

        app.post("/gruppen", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            int intervall;
            try { intervall = Integer.parseInt(Anmeldeseiten.feld(c, "intervall")); }
            catch (NumberFormatException e) { intervall = 60; }
            String bez = Anmeldeseiten.feld(c, "bezeichnung");
            // FA-GER-050: Die Einrichtung wird gewaehlt, nicht verdrahtet.
            // Kein Ausweichen auf einen Vorgabenamen mehr: Gruppenverwaltung
            // lehnt eine fehlende Einrichtung mit klarer Begruendung ab. Eine
            // stillschweigend gesetzte „Praxis" waere entweder eine falsche
            // Zuordnung oder — falls es sie nicht gibt — eine Gruppe ohne
            // Mandantenbezug.
            String einrichtung = Anmeldeseiten.feld(c, "einrichtung");
            String fehler = gruppen.anlegen(bez, einrichtung, intervall);
            if (fehler == null) {
                protokoll.anfuegen(einrichtung, null, Ereignisart.GRUPPE_ANGELEGT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Gerätegruppe \"" + bez + "\" für " + einrichtung
                        + " angelegt, Prüfintervall " + intervall + " s", null);
            }
            c.html(seite("gruppen", gruppenSeite(c, fehler), angemeldeter(c)));
        });

        app.post("/gruppen/{id}/intervall", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            long id = Long.parseLong(c.pathParam("id"));
            int sek;
            try { sek = Integer.parseInt(Anmeldeseiten.feld(c, "intervall")); }
            catch (NumberFormatException e) { sek = 60; }
            // Die Gruppe VOR der Aenderung lesen: Danach braucht das Protokoll
            // ihren Namen und ihre Einrichtung — und nach dem Loeschen gibt es
            // sie nicht mehr.
            var vorher = gruppen.gruppe(id, bereich(c));
            String fehler = gruppen.intervallSetzen(id, sek, bereich(c));
            if (fehler == null && vorher.isPresent()) {
                // Die Einrichtung stand hier fest auf „Praxis" — damit landete
                // jede Gruppenaenderung unter einem Namen, der mit der Gruppe
                // nichts zu tun hatte, und war fuer den Zustaendigen unsichtbar.
                protokoll.anfuegen(vorher.get().einrichtung(), null,
                        Ereignisart.GRUPPE_GEAENDERT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Prüfintervall der Gruppe „" + vorher.get().bezeichnung()
                                + "“ auf " + sek + " s gesetzt", null);
            }
            c.html(seite("gruppen", gruppenSeite(c, fehler,
                    fehler == null && vorher.isPresent()
                            ? "Prüfintervall von „" + vorher.get().bezeichnung()
                              + "“ auf " + sek + " Sekunden gesetzt."
                            : null),
                    angemeldeter(c)));
        });

        // Die Detailseite — bis zum 11.08.2026 hatte die Gruppenverwaltung als
        // einziger Bereich keine. Alles musste in die Listenzeile passen, und
        // was nicht hineinpasste, fehlte.
        app.get("/gruppen/{id}", c -> {
            long id = Long.parseLong(c.pathParam("id"));
            c.html(seite("gruppen", gruppenDetail(c, id, null, null), angemeldeter(c)));
        });

        // Bezeichnung UND Intervall in einem Zug: Auf einer Detailseite ändert
        // man beides zusammen; zwei getrennte Speichern-Knöpfe wären eine
        // Zumutung.
        app.post("/gruppen/{id}/aendern", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var vorher = gruppen.gruppe(id, bereich(c));
            String neuerName = Anmeldeseiten.feld(c, "bezeichnung");
            int sek;
            try { sek = Integer.parseInt(Anmeldeseiten.feld(c, "intervall").trim()); }
            catch (Exception e) { sek = -1; }

            String fehler = null;
            var meldungen = new ArrayList<String>();
            if (vorher.isPresent() && !vorher.get().bezeichnung().equals(neuerName)) {
                fehler = gruppen.umbenennen(id, neuerName, bereich(c));
                if (fehler == null) meldungen.add("umbenannt");
            }
            if (fehler == null && vorher.isPresent() && sek != vorher.get().pruefintervallSekunden()) {
                fehler = gruppen.intervallSetzen(id, sek, bereich(c));
                if (fehler == null) meldungen.add("Prüfintervall auf " + sek + " s gesetzt");
            }
            if (fehler == null && !meldungen.isEmpty() && vorher.isPresent()) {
                protokoll.anfuegen(vorher.get().einrichtung(), null,
                        Ereignisart.GRUPPE_GEAENDERT, true, angemeldeter(c).anzeigename(), null,
                        "Gerätegruppe „" + vorher.get().bezeichnung() + "“: "
                                + String.join(", ", meldungen), null);
            }
            c.html(seite("gruppen", gruppenDetail(c, id, fehler,
                    fehler == null
                            ? (meldungen.isEmpty() ? "Nichts geändert."
                               : "Gespeichert: " + String.join(", ", meldungen) + ".")
                            : null),
                    angemeldeter(c)));
        });

        app.post("/gruppen/{id}/vorgabe", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var g = gruppen.gruppe(id, bereich(c));
            String fehler = gruppen.vorgabeSetzen(id, bereich(c));
            if (fehler == null && g.isPresent()) {
                protokoll.anfuegen(g.get().einrichtung(), null,
                        Ereignisart.GRUPPE_GEAENDERT, true, angemeldeter(c).anzeigename(), null,
                        "Gerätegruppe „" + g.get().bezeichnung() + "“ ist jetzt die "
                                + "Vorgabegruppe — neu erkannte Geräte landen dort", null);
            }
            c.html(seite("gruppen", gruppenDetail(c, id, fehler,
                    fehler == null ? "Diese Gruppe ist jetzt die Vorgabegruppe." : null),
                    angemeldeter(c)));
        });

        // Umbenennen als eigener Weg — fuer die Feldbearbeitung ohne Detailseite
        // und fuer Aufrufe von aussen.
        app.post("/gruppen/{id}/umbenennen", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var vorher = gruppen.gruppe(id, bereich(c));
            String neu = Anmeldeseiten.feld(c, "bezeichnung");
            String fehler = gruppen.umbenennen(id, neu, bereich(c));
            if (fehler == null && vorher.isPresent()) {
                protokoll.anfuegen(vorher.get().einrichtung(), null,
                        Ereignisart.GRUPPE_GEAENDERT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Gerätegruppe „" + vorher.get().bezeichnung() + "“ heißt jetzt „"
                                + neu.trim() + "“", null);
            }
            c.html(seite("gruppen", gruppenSeite(c, fehler,
                    fehler == null ? "Gruppe umbenannt." : null), angemeldeter(c)));
        });

        app.post("/gruppen/{id}/loeschen", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var vorher = gruppen.gruppe(id, bereich(c));
            String fehler = gruppen.loeschen(id, bereich(c));
            if (fehler == null && vorher.isPresent()) {
                protokoll.anfuegen(vorher.get().einrichtung(), null,
                        Ereignisart.GRUPPE_GELOESCHT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Gerätegruppe „" + vorher.get().bezeichnung() + "“ gelöscht, "
                                + vorher.get().geraetezahl()
                                + " Gerät(e) in die Vorgabegruppe verschoben", null);
            }
            c.html(seite("gruppen", gruppenSeite(c, fehler,
                    fehler == null && vorher.isPresent()
                            ? "Gruppe „" + vorher.get().bezeichnung() + "“ gelöscht."
                            : null),
                    angemeldeter(c)));
        });
    }


    /**
     * Konnektoren, Kartenterminals, Zugangsdaten und PIN-Ablage (FA-GER-010 bis 040, FA-PIN-010/060).
     *
     * <p>Die Reihenfolge innerhalb dieser Methode ist bedeutsam: Javalin nimmt
     * die zuerst passende Route, deshalb müssen feste Pfade vor solchen mit
     * Platzhalter stehen.
     */
    private void routenBestand(Javalin app) {
        // ------------------------------------------------------------ Bestand
        // FA-GER-010, FA-GER-020: Konnektoren und Kartenterminals erfassen.
        app.get("/bestand", c -> c.html(seite("bestand", bestandsSeite(c, null, false), angemeldeter(c))));

        // FA-GER-030 / FA-GER-035: Vorschlagsliste aus der Ermittlung.
        app.get("/bestand/ermittelt", c -> c.html(seite("bestand",
                Bestandsseiten.ermitteltSeite(ermittlung.abgleich(bereich(c)), ermittlung.juengstesModell(),
                        bestand.konnektoren(bereich(c)), null, false),
                angemeldeter(c))));

        // FA-GER-030 erlaubt die Uebernahme "einzeln oder als Auswahl".
        app.post("/bestand/ermittelt/uebernehmen", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            var ids = kennungen(c, "fund");
            long konnektorId;
            try { konnektorId = Long.parseLong(Anmeldeseiten.feld(c, "konnektorId")); }
            catch (NumberFormatException e) { konnektorId = -1; }

            int uebernommen = 0;
            List<String> fehler = new ArrayList<>();
            for (long id : ids) {
                var fund = ermittlung.fund(id, bereich(c));
                if (fund.isEmpty()) { fehler.add("Ein Fund ist nicht mehr vorhanden."); continue; }
                var e = bestand.uebernehmen(fund.get(), konnektorId, null, bereich(c));
                if (e.geklappt()) {
                    ermittlung.alsUebernommenMarkieren(id);
                    uebernommen++;
                    var t2 = bestand.terminal(e.id(), bereich(c)).orElseThrow();
                    protokoll.anfuegen(t2.organisation(), t2.terminalId(),
                            Ereignisart.GERAET_UEBERNOMMEN, true, angemeldeter(c).anzeigename(), null,
                            "Kartenterminal \u201e" + t2.bezeichnung() + "\u201c aus der Ermittlung "
                                    + "\u00fcbernommen (Sammelauswahl)",
                            "Die Adresse des Ger\u00e4ts ist noch nicht hinterlegt \u2014 ohne sie "
                            + "ist keine Erreichbarkeitspr\u00fcfung m\u00f6glich.");
                } else {
                    fehler.add(e.fehler());
                }
            }

            String meldung = ids.isEmpty()
                    ? "Es war kein Ger\u00e4t ausgew\u00e4hlt."
                    : "%d von %d Ger\u00e4t(en) \u00fcbernommen.%s".formatted(uebernommen, ids.size(),
                            fehler.isEmpty() ? "" : " " + String.join(" ", fehler));
            c.html(seite("bestand", Bestandsseiten.ermitteltSeite(ermittlung.abgleich(bereich(c)),
                    ermittlung.juengstesModell(), bestand.konnektoren(bereich(c)), meldung,
                    uebernommen < ids.size()), angemeldeter(c)));
        });

        app.post("/bestand/ermittelt/{id}/uebernehmen", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var fund = ermittlung.fund(id, bereich(c));
            if (fund.isEmpty()) {
                c.html(seite("bestand", Bestandsseiten.ermitteltSeite(ermittlung.abgleich(bereich(c)),
                        ermittlung.juengstesModell(), bestand.konnektoren(bereich(c)),
                        "Dieser Fund ist nicht mehr vorhanden. Bitte die Seite neu laden.", true),
                        angemeldeter(c)));
                return;
            }
            long konnektorId;
            try { konnektorId = Long.parseLong(Anmeldeseiten.feld(c, "konnektorId")); }
            catch (NumberFormatException e) { konnektorId = -1; }

            var e = bestand.uebernehmen(fund.get(), konnektorId, Anmeldeseiten.feld(c, "adresse"), bereich(c));
            String meldung;
            if (e.geklappt()) {
                ermittlung.alsUebernommenMarkieren(id);
                var t = bestand.terminal(e.id(), bereich(c)).orElseThrow();
                protokoll.anfuegen(t.organisation(), t.terminalId(),
                        Ereignisart.GERAET_UEBERNOMMEN, true, angemeldeter(c).anzeigename(), null,
                        "Kartenterminal \u201e" + t.bezeichnung() + "\u201c aus der Ermittlung "
                                + "in den Bestand \u00fcbernommen",
                        t.adresse() == null ? "Die Adresse des Ger\u00e4ts ist noch nicht "
                                + "hinterlegt \u2014 ohne sie ist keine Erreichbarkeitspr\u00fcfung "
                                + "m\u00f6glich." : null);
                meldung = "Kartenterminal \u201e" + t.bezeichnung() + "\u201c \u00fcbernommen.";
            } else {
                meldung = e.fehler();
            }
            c.html(seite("bestand", Bestandsseiten.ermitteltSeite(ermittlung.abgleich(bereich(c)),
                    ermittlung.juengstesModell(), bestand.konnektoren(bereich(c)), meldung, !e.geklappt()),
                    angemeldeter(c)));
        });

        // FA-GER-060: Massenaktionen auf mehrere Geraete.
        app.post("/bestand/massenaktion", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            var ids = kennungen(c, "geraet");
            String aktion = Anmeldeseiten.feld(c, "aktion");

            Bestandsverwaltung.Massenergebnis e;
            String was;
            switch (aktion) {
                case "aktivieren"   -> { e = bestand.massenAktivSetzen(ids, true, bereich(c));  was = "aktiviert"; }
                case "deaktivieren" -> { e = bestand.massenAktivSetzen(ids, false, bereich(c)); was = "deaktiviert"; }
                case "gruppe"       -> {
                    long gruppeId;
                    try { gruppeId = Long.parseLong(Anmeldeseiten.feld(c, "gruppeId")); }
                    catch (NumberFormatException ex) {
                        c.html(seite("bestand", bestandsSeite(c,
                                "Bitte eine Gerätegruppe auswählen.", true), angemeldeter(c)));
                        return;
                    }
                    e = bestand.massenGruppeZuweisen(ids, gruppeId, bereich(c));
                    was = "einer Gruppe zugeordnet";
                }
                default -> {
                    c.html(seite("bestand", bestandsSeite(c,
                            "Unbekannte Aktion. Bitte die Seite neu laden.", true), angemeldeter(c)));
                    return;
                }
            }

            // Auch eine Massenaktion ist Bestandspflege - sie entscheidet
            // darueber, welche Geraete ueberwacht werden.
            if (e.geaendert() > 0) {
                // Eine Massenaktion kann Geraete mehrerer Einrichtungen
                // betreffen. Nur wenn es genau eine ist, traegt der Eintrag
                // sie; sonst ist der Vorgang instanzweit. Eine willkuerlich
                // herausgegriffene Einrichtung waere schlechter als keine.
                var betroffene = ids.stream().map(this::einrichtungVonTerminalId)
                        .filter(java.util.Objects::nonNull).distinct().toList();
                protokoll.anfuegen(betroffene.size() == 1 ? betroffene.get(0) : Fachprotokoll.INSTANZ,
                        null, Ereignisart.TERMINAL_GEAENDERT, true,
                        angemeldeter(c).anzeigename(), null,
                        "%d Kartenterminal(s) %s".formatted(e.geaendert(), was),
                        e.vollstaendig() ? null : String.join(" ", e.hinweise()));
            }

            String meldung = e.geaendert() == 0
                    ? (e.hinweise().isEmpty() ? "Es wurde nichts geändert."
                                              : String.join(" ", e.hinweise()))
                    : "%d Kartenterminal(s) %s.%s".formatted(e.geaendert(), was,
                            e.hinweise().isEmpty() ? "" : " " + String.join(" ", e.hinweise()));
            c.html(seite("bestand", bestandsSeite(c, meldung, !e.vollstaendig()), angemeldeter(c)));
        });

        app.post("/bestand/konnektor", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            long orgId;
            try { orgId = Long.parseLong(Anmeldeseiten.feld(c, "organisationId")); }
            catch (NumberFormatException e) {
                c.html(seite("bestand", bestandsSeite(c, "Bitte eine Einrichtung auswählen.", true),
                        angemeldeter(c)));
                return;
            }
            var e = bestand.konnektorAnlegen(orgId,
                    Anmeldeseiten.feld(c, "bezeichnung"), Anmeldeseiten.feld(c, "adresse"),
                    Anmeldeseiten.feld(c, "modell"), Anmeldeseiten.feld(c, "mandantId"),
                    Anmeldeseiten.feld(c, "clientsystemId"), Anmeldeseiten.feld(c, "arbeitsplatzId"),
                    Anmeldeseiten.feld(c, "benutzerName"), Anmeldeseiten.feld(c, "passwort"),
                    Anmeldeseiten.feld(c, "webadminAdresse"), bereich(c));
            if (!e.geklappt()) {
                c.html(seite("bestand", bestandsSeite(c, e.fehler(), true), angemeldeter(c)));
                return;
            }
            // Die Erreichbarkeit wird direkt geprueft und gemeldet - aber sie
            // entscheidet nicht ueber das Speichern (FA-GER-010).
            var erreichbar = bestand.konnektorPruefen(e.id(), bereich(c));
            var k = bestand.konnektor(e.id(), bereich(c)).orElseThrow();
            protokoll.anfuegen(k.organisation(), null, Ereignisart.KONNEKTOR_ANGELEGT, true,
                    angemeldeter(c).anzeigename(), null,
                    "Konnektor „" + k.bezeichnung() + "“ erfasst ("
                            + k.adresse() + ", Aufrufkontext " + k.mandantId() + "/"
                            + k.clientsystemId() + "/" + k.arbeitsplatzId() + ")",
                    erreichbar.erreichbar() ? null
                            : "Der Konnektor ist derzeit nicht erreichbar: " + erreichbar.hinweis());
            c.html(seite("bestand", bestandsSeite(c,
                    "Konnektor gespeichert. " + erreichbar.hinweis(), !erreichbar.erreichbar()),
                    angemeldeter(c)));
        });

        app.get("/bestand/konnektor/neu", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            c.html(seite("bestand", Bestandsseiten.konnektorMaske(
                    bestand.organisationen(bereich(c)), ermittlung.aufrufkontextAuswahl()),
                    angemeldeter(c)));
        });

        app.get("/bestand/terminal/neu", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            c.html(seite("bestand", Bestandsseiten.terminalNeuMaske(
                    bestand.konnektoren(bereich(c)),
                    bestand.organisationen(bereich(c))), angemeldeter(c)));
        });

        app.get("/bestand/konnektor/{id}", c -> {
            var k = bestand.konnektor(Long.parseLong(c.pathParam("id")), bereich(c));
            if (k.isEmpty()) { c.status(404).html(seite("bestand",
                    bestandsSeite(c, "Diesen Konnektor gibt es nicht mehr.", true), angemeldeter(c))); return; }
            c.html(seite("bestand", konnektorMaske(c, k.get(), null), angemeldeter(c)));
        });

        app.post("/bestand/konnektor/{id}", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var e = bestand.konnektorAendern(id,
                    Anmeldeseiten.feld(c, "bezeichnung"), Anmeldeseiten.feld(c, "adresse"),
                    Anmeldeseiten.feld(c, "modell"), Anmeldeseiten.feld(c, "mandantId"),
                    Anmeldeseiten.feld(c, "clientsystemId"), Anmeldeseiten.feld(c, "arbeitsplatzId"),
                    Anmeldeseiten.feld(c, "benutzerName"), Anmeldeseiten.feld(c, "passwort"),
                    Anmeldeseiten.feld(c, "webadminAdresse"), angekreuzt(c, "aktiv"), bereich(c));
            if (!e.geklappt()) {
                var k = bestand.konnektor(id, bereich(c));
                c.html(seite("bestand", k.isPresent()
                        ? konnektorMaske(c, k.get(), e.fehler())
                        : bestandsSeite(c, e.fehler(), true), angemeldeter(c)));
                return;
            }
            var k = bestand.konnektor(id, bereich(c)).orElseThrow();
            protokoll.anfuegen(k.organisation(), null, Ereignisart.KONNEKTOR_GEAENDERT, true,
                    angemeldeter(c).anzeigename(), null,
                    "Konnektor „" + k.bezeichnung() + "“ geändert"
                            + (k.aktiv() ? "" : ", Gerät deaktiviert"), null);
            c.html(seite("bestand", bestandsSeite(c, "Änderungen gespeichert.", false), angemeldeter(c)));
        });

        // ADR-0012: Wer den Konnektor betreibt, entscheidet, wer ihn mitnutzt.
        app.post("/bestand/konnektor/{id}/nutzung", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            long id = Long.parseLong(c.pathParam("id"));
            boolean nutzen = "ja".equals(Anmeldeseiten.feld(c, "nutzen"));
            long orgId = Long.parseLong(Anmeldeseiten.feld(c, "organisationId"));
            var e = bestand.konnektorNutzungSetzen(id, orgId, nutzen, bereich(c));
            var k = bestand.konnektor(id, bereich(c));
            if (e.geklappt() && k.isPresent()) {
                protokoll.anfuegen(k.get().organisation(), null,
                        Ereignisart.KONNEKTOR_NUTZUNG_GEAENDERT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Mitnutzung des Konnektors „" + k.get().bezeichnung() + "“ "
                                + (nutzen ? "erlaubt" : "entzogen"),
                        nutzen ? "Die Einrichtung sieht den Konnektor und kann ihm eigene "
                                 + "Kartenterminals zuordnen."
                               : "Bereits zugeordnete Kartenterminals bleiben bestehen.");
            }
            if (k.isEmpty()) {
                c.html(seite("bestand", bestandsSeite(c, e.fehler(), true), angemeldeter(c)));
                return;
            }
            c.html(seite("bestand", konnektorMaske(c, k.get(), e.geklappt() ? null : e.fehler()),
                    angemeldeter(c)));
        });

        app.post("/bestand/konnektor/{id}/kennwort-entfernen", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var vorher = bestand.konnektor(id, bereich(c));
            var e = bestand.konnektorPasswortEntfernen(id, bereich(c));
            if (e.geklappt() && vorher.isPresent()) {
                protokoll.anfuegen(vorher.get().organisation(), null, Ereignisart.KONNEKTOR_GEAENDERT,
                        true, angemeldeter(c).anzeigename(), null,
                        "Hinterlegtes Kennwort des Konnektors „" + vorher.get().bezeichnung()
                                + "“ entfernt",
                        "Ohne Kennwort kann der Roboter sich an diesem Konnektor nicht "
                        + "selbsttätig anmelden.");
            }
            c.html(seite("bestand", bestandsSeite(c,
                    e.geklappt() ? "Kennwort entfernt." : e.fehler(), !e.geklappt()), angemeldeter(c)));
        });

        app.post("/bestand/konnektor/{id}/loeschen", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var vorher = bestand.konnektor(id, bereich(c));
            var e = bestand.konnektorLoeschen(id, bereich(c));
            if (e.geklappt() && vorher.isPresent()) {
                protokoll.anfuegen(vorher.get().organisation(), null, Ereignisart.KONNEKTOR_GELOESCHT,
                        true, angemeldeter(c).anzeigename(), null,
                        "Konnektor „" + vorher.get().bezeichnung() + "“ gelöscht", null);
            }
            c.html(seite("bestand", bestandsSeite(c,
                    e.geklappt() ? "Konnektor gelöscht." : e.fehler(), !e.geklappt()), angemeldeter(c)));
        });

        app.post("/bestand/konnektor/{id}/pruefen", c -> {
            long id = Long.parseLong(c.pathParam("id"));
            var k = bestand.konnektor(id, bereich(c));
            var e = bestand.konnektorPruefen(id, bereich(c));
            c.html(Bestandsseiten.pruefmeldung(k.map(Bestandsverwaltung.Konnektor::bezeichnung)
                    .orElse("Konnektor"), e));
        });

        app.post("/bestand/terminal", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            // Der Konnektor ist seit dem 11.08.2026 optional — ein Geraet kann
            // aufgestellt sein, bevor es am Konnektor angemeldet ist. Die
            // EINRICHTUNG ist stattdessen Pflicht: Ohne sie gaebe es keine
            // Mandantengrenze fuer diesen Datensatz.
            Long konnektorId = null;
            try {
                String roh = Anmeldeseiten.feld(c, "konnektorId");
                if (roh != null && !roh.isBlank()) konnektorId = Long.parseLong(roh);
            } catch (NumberFormatException ex) {
                konnektorId = null;   // wie „keiner gewaehlt"
            }
            Long organisationId = null;
            try {
                String rohOrg = Anmeldeseiten.feld(c, "organisationId");
                if (rohOrg != null && !rohOrg.isBlank()) organisationId = Long.parseLong(rohOrg);
            } catch (NumberFormatException ex) {
                organisationId = null;
            }
            // Fehlt die Einrichtung, ist aber ein Konnektor gewaehlt, ergibt sie
            // sich aus ihm — die Angabe waere dann eine Wiederholung. Das ist
            // KEINE Aufweichung der Mandantengrenze: Der Konnektor wird selbst
            // gegen den Geltungsbereich geprueft, und seine Einrichtung ist
            // damit ebenfalls im Bereich.
            if (organisationId == null && konnektorId != null) {
                organisationId = bestand.konnektor(konnektorId, bereich(c))
                        .map(Bestandsverwaltung.Konnektor::organisationId).orElse(null);
            }
            if (organisationId == null) {
                c.html(seite("bestand", bestandsSeite(c,
                        "Bitte die Einrichtung auswählen, zu der das Kartenterminal gehört.", true),
                        angemeldeter(c)));
                return;
            }
            var e = bestand.terminalAnlegen(Anmeldeseiten.feld(c, "terminalId"), konnektorId,
                    organisationId,
                    Anmeldeseiten.feld(c, "bezeichnung"), Anmeldeseiten.feld(c, "standort"),
                    Anmeldeseiten.feld(c, "adresse"), Anmeldeseiten.feld(c, "modell"),
                    Anmeldeseiten.feld(c, "seriennummer"), Anmeldeseiten.feld(c, "adminBenutzer"),
                    Anmeldeseiten.feld(c, "adminPasswort"),
                    angekreuzt(c, "hatSmcb"), angekreuzt(c, "pevimAktiv"), bereich(c));
            if (!e.geklappt()) {
                c.html(seite("bestand", bestandsSeite(c, e.fehler(), true), angemeldeter(c)));
                return;
            }
            var erreichbar = bestand.terminalPruefen(e.id(), bereich(c));
            var t = bestand.terminal(e.id(), bereich(c)).orElseThrow();
            protokoll.anfuegen(t.organisation(), t.terminalId(), Ereignisart.TERMINAL_ANGELEGT, true,
                    angemeldeter(c).anzeigename(), null,
                    "Kartenterminal „" + t.bezeichnung() + "“ erfasst (" + t.adresse()
                            + (t.hatSmcb() ? ", mit SMC-B" : ", ohne SMC-B")
                            + (t.pevimAktiv() ? ", PIN-Automatik aktiv" : "") + ")",
                    erreichbar.erreichbar() ? null
                            : "Das Terminal ist derzeit nicht erreichbar: " + erreichbar.hinweis());
            c.html(seite("bestand", bestandsSeite(c,
                    "Kartenterminal gespeichert. " + erreichbar.hinweis(), !erreichbar.erreichbar()),
                    angemeldeter(c)));
        });

        app.get("/bestand/terminal/{id}", c -> {
            var t = bestand.terminal(Long.parseLong(c.pathParam("id")), bereich(c));
            if (t.isEmpty()) { c.status(404).html(seite("bestand",
                    bestandsSeite(c, "Dieses Kartenterminal gibt es nicht mehr.", true),
                    angemeldeter(c))); return; }
            c.html(seite("bestand", terminalMaske(t.get(), null, bereich(c)), angemeldeter(c)));
        });

        // Konnektor nachtragen (Vorgabe PO, 11.08.2026). Ein Geraet kann ohne
        // Konnektor erfasst werden; sobald es dort angemeldet ist, gehoert die
        // Zuordnung nachgeholt — sonst bleibt es dauerhaft von der Automatik
        // ausgeschlossen, ohne dass jemand sieht, warum.
        app.post("/bestand/terminal/{id}/konnektor", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            long id = Long.parseLong(c.pathParam("id"));
            Long konnektorId = null;
            String roh = Anmeldeseiten.feld(c, "konnektorId");
            if (roh != null && !roh.isBlank()) {
                try { konnektorId = Long.parseLong(roh); }
                catch (NumberFormatException ex) { konnektorId = null; }
            }
            var e = bestand.terminalKonnektorSetzen(id, konnektorId, bereich(c));
            var t = bestand.terminal(id, bereich(c));
            if (t.isEmpty()) { c.status(404).html(seite("bestand",
                    bestandsSeite(c, "Dieses Kartenterminal gibt es nicht mehr.", true),
                    angemeldeter(c))); return; }
            if (e.geklappt()) {
                protokoll.anfuegen(t.get().organisation(), t.get().terminalId(),
                        Ereignisart.TERMINAL_GEAENDERT, true, angemeldeter(c).anzeigename(), null,
                        konnektorId == null
                                ? "Konnektor-Zuordnung von „" + t.get().bezeichnung() + "“ entfernt"
                                : "Kartenterminal „" + t.get().bezeichnung() + "“ dem Konnektor „"
                                  + t.get().konnektor() + "“ zugeordnet",
                        konnektorId == null
                                ? "Ohne Konnektor nimmt das Gerät nicht an der PIN-Automatik teil."
                                : null);
            }
            c.html(seite("bestand", terminalMaske(t.get(), e.geklappt() ? null : e.fehler(),
                    bereich(c)), angemeldeter(c)));
        });

        app.post("/bestand/terminal/{id}", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var e = bestand.terminalAendern(id,
                    Anmeldeseiten.feld(c, "bezeichnung"), Anmeldeseiten.feld(c, "standort"),
                    Anmeldeseiten.feld(c, "adresse"), Anmeldeseiten.feld(c, "modell"),
                    Anmeldeseiten.feld(c, "seriennummer"), Anmeldeseiten.feld(c, "adminBenutzer"),
                    Anmeldeseiten.feld(c, "adminPasswort"),
                    angekreuzt(c, "hatSmcb"), angekreuzt(c, "pevimAktiv"), angekreuzt(c, "aktiv"), bereich(c));
            if (!e.geklappt()) {
                var t = bestand.terminal(id, bereich(c));
                c.html(seite("bestand", t.isPresent()
                        ? terminalMaske(t.get(), e.fehler())
                        : bestandsSeite(c, e.fehler(), true), angemeldeter(c)));
                return;
            }
            var t = bestand.terminal(id, bereich(c)).orElseThrow();
            protokoll.anfuegen(t.organisation(), t.terminalId(), Ereignisart.TERMINAL_GEAENDERT, true,
                    angemeldeter(c).anzeigename(), null,
                    "Kartenterminal „" + t.bezeichnung() + "“ geändert"
                            + (t.aktiv() ? "" : ", Gerät deaktiviert"), null);
            c.html(seite("bestand", bestandsSeite(c, "Änderungen gespeichert.", false), angemeldeter(c)));
        });

        app.post("/bestand/terminal/{id}/loeschen", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Pflegen des Bestands")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var vorher = bestand.terminal(id, bereich(c));
            var e = bestand.terminalLoeschen(id, bereich(c));
            if (e.geklappt() && vorher.isPresent()) {
                protokoll.anfuegen(vorher.get().organisation(), vorher.get().terminalId(),
                        Ereignisart.TERMINAL_GELOESCHT, true, angemeldeter(c).anzeigename(), null,
                        "Kartenterminal „" + vorher.get().bezeichnung() + "“ gelöscht",
                        "Dieses Gerät wird nicht mehr überwacht.");
            }
            c.html(seite("bestand", bestandsSeite(c,
                    e.geklappt() ? "Kartenterminal gelöscht." : e.fehler(), !e.geklappt()),
                    angemeldeter(c)));
        });

        app.post("/bestand/terminal/{id}/pruefen", c -> {
            long id = Long.parseLong(c.pathParam("id"));
            var t = bestand.terminal(id, bereich(c));
            var e = bestand.terminalPruefen(id, bereich(c));
            c.html(Bestandsseiten.pruefmeldung(t.map(Bestandsverwaltung.Terminal::bezeichnung)
                    .orElse("Kartenterminal"), e));
        });

        app.post("/bestand/terminal/{id}/pin", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Hinterlegen einer PIN")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var t = bestand.terminal(id, bereich(c));
            if (t.isEmpty()) { c.status(404).html(seite("bestand",
                    bestandsSeite(c, "Dieses Kartenterminal gibt es nicht mehr.", true),
                    angemeldeter(c))); return; }

            String handle = Anmeldeseiten.feld(c, "kartenhandle");
            String fehler = pins.hinterlegen(t.get().terminalId(), handle,
                    Anmeldeseiten.feld(c, "chiffrat"), angemeldeter(c).anzeigename());
            if (fehler != null) {
                c.html(seite("bestand", terminalMaske(t.get(), fehler), angemeldeter(c)));
                return;
            }
            // Protokolliert wird DASS, nie WAS. Weder PIN noch Chiffrat -
            // ADR-0009, Festlegung 4.
            protokoll.anfuegen(t.get().organisation(), t.get().terminalId(),
                    Ereignisart.PIN_HINTERLEGT, true, angemeldeter(c).anzeigename(), null,
                    "PIN für die SMC-B in „" + t.get().bezeichnung() + "“ hinterlegt",
                    "Die PIN wurde im Browser verschlüsselt und wird zum Standort-Agenten "
                    + "übertragen. Die Zentrale kann sie nicht lesen.");
            c.html(seite("bestand", terminalMaske(t.get(), null), angemeldeter(c)));
        });

        app.post("/bestand/terminal/{id}/pin-aendern", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Ändern einer PIN")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var t = bestand.terminal(id, bereich(c));
            if (t.isEmpty()) { c.status(404).html(seite("bestand",
                    bestandsSeite(c, "Dieses Kartenterminal gibt es nicht mehr.", true),
                    angemeldeter(c))); return; }

            String fehler = pins.aendern(t.get().terminalId(),
                    Anmeldeseiten.feld(c, "kartenhandle"),
                    Anmeldeseiten.feld(c, "chiffratAlt"), Anmeldeseiten.feld(c, "chiffratNeu"),
                    angemeldeter(c).anzeigename());
            if (fehler == null) {
                // Protokolliert wird DASS geaendert wurde — nie ein Wert,
                // weder der alte noch der neue (ADR-0009).
                protokoll.anfuegen(t.get().organisation(), t.get().terminalId(),
                        Ereignisart.PIN_GEAENDERT, true, angemeldeter(c).anzeigename(), null,
                        "Änderung der SMC-B-PIN in „" + t.get().bezeichnung() + "“ beauftragt",
                        "Die Änderung erfolgt am Kartenterminal selbst (A_24082). Schlägt sie "
                        + "fehl, bleibt die bisher hinterlegte PIN unverändert.");
            }
            c.html(seite("bestand", terminalMaske(t.get(), fehler), angemeldeter(c)));
        });

        app.post("/bestand/terminal/{id}/pin-loeschen", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(), "Das Löschen einer PIN")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var t = bestand.terminal(id, bereich(c));
            if (t.isEmpty()) { c.status(404).html(seite("bestand",
                    bestandsSeite(c, "Dieses Kartenterminal gibt es nicht mehr.", true),
                    angemeldeter(c))); return; }

            String fehler = pins.loeschen(t.get().terminalId(),
                    Anmeldeseiten.feld(c, "kartenhandle"), angemeldeter(c).anzeigename());
            if (fehler == null) {
                protokoll.anfuegen(t.get().organisation(), t.get().terminalId(),
                        Ereignisart.PIN_GELOESCHT, true, angemeldeter(c).anzeigename(), null,
                        "Hinterlegte PIN für „" + t.get().bezeichnung() + "“ gelöscht",
                        "Der Roboter kann diese SMC-B nicht mehr verifizieren, bis eine neue "
                        + "PIN hinterlegt wird.");
            }
            c.html(seite("bestand", terminalMaske(t.get(), fehler), angemeldeter(c)));
        });
    }


    /**
     * Verbünde, Standorte, Einrichtungen — die Mandantenstruktur (FA-ORG-010/020).
     *
     * <p>Die Reihenfolge innerhalb dieser Methode ist bedeutsam: Javalin nimmt
     * die zuerst passende Route, deshalb müssen feste Pfade vor solchen mit
     * Platzhalter stehen.
     */
    private void routenOrganisation(Javalin app) {
        app.get("/organisation", c -> c.html(seite("organisation",
                organisationsSeite(c, null, false), angemeldeter(c))));

        /**
         * Detailseite einer Einheit (UX-Konzept, Stufe 5).
         *
         * <p>Eigene Adresse statt aufklappender Tabellenzeile: Die Seite ist
         * als Lesezeichen speicherbar, verlinkbar, und der Zurück-Knopf des
         * Browsers tut, was man erwartet.
         */
        app.get("/organisation/neu", c -> {
            if (abgewiesen(c, rechte(c).darfOrganisationVerwalten(),
                    "Das Anlegen von Organisationseinheiten")) return;
            c.html(seite("organisation",
                    Organisationsseiten.anlegeseite(organisationen.baum(bereich(c))),
                    angemeldeter(c)));
        });

        app.get("/organisation/{id}", c -> {
            long id = Long.parseLong(c.pathParam("id"));
            c.html(seite("organisation", organisationsDetail(c, id, null, false),
                    angemeldeter(c)));
        });

        app.post("/organisation", c -> {
            if (abgewiesen(c, rechte(c).darfOrganisationVerwalten(),
                    "Das Anlegen von Organisationseinheiten")) return;
            Long eltern = zahlOderNull(Anmeldeseiten.feld(c, "elternId"));
            var e = organisationen.anlegen(Anmeldeseiten.feld(c, "bezeichnung"),
                    String.valueOf(Anmeldeseiten.feld(c, "art")), eltern, bereich(c));
            if (e.geklappt()) {
                protokoll.anfuegen(bereich(c).bezeichnung(), null,
                        Ereignisart.ORGANISATION_ANGELEGT, true, angemeldeter(c).anzeigename(),
                        null, e.meldung(),
                        "Konten und Geräte lassen sich dieser Einheit ab sofort zuordnen.");
            }
            c.html(seite("organisation", organisationsSeite(c,
                    e.geklappt() ? e.meldung() : e.fehler(), !e.geklappt()),
                    angemeldeter(c)));
        });

        app.post("/organisation/{id}/umbenennen", c -> {
            if (abgewiesen(c, rechte(c).darfOrganisationVerwalten(),
                    "Das Ändern von Organisationseinheiten")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var e = organisationen.umbenennen(id, Anmeldeseiten.feld(c, "bezeichnung"), bereich(c));
            if (e.geklappt()) {
                protokoll.anfuegen(bereich(c).bezeichnung(), null,
                        Ereignisart.ORGANISATION_UMBENANNT, true, angemeldeter(c).anzeigename(),
                        null, e.meldung(), null);
            }
            c.html(seite("organisation", organisationsDetail(c, id,
                    e.geklappt() ? e.meldung() : e.fehler(), !e.geklappt()),
                    angemeldeter(c)));
        });

        // ADR-0012: BSNR und Telematik-ID gehoeren zur Betriebsstaette, weil nur
        // sie die Leistungserbringerinstitution ist.
        app.post("/organisation/{id}/ti-identitaet", c -> {
            if (abgewiesen(c, rechte(c).darfOrganisationVerwalten(),
                    "Das Ändern von Organisationseinheiten")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var e = organisationen.tiIdentitaetSetzen(id, Anmeldeseiten.feld(c, "bsnr"),
                    Anmeldeseiten.feld(c, "telematikId"), bereich(c));
            if (e.geklappt()) {
                protokoll.anfuegen(bereich(c).bezeichnung(), null,
                        Ereignisart.ORGANISATION_TI_IDENTITAET, true,
                        angemeldeter(c).anzeigename(), null, e.meldung(),
                        "Betriebsstättennummer und Telematik-ID bestimmen, welche "
                        + "Leistungserbringerinstitution hinter dieser Einheit steht.");
            }
            c.html(seite("organisation", organisationsDetail(c, id,
                    e.geklappt() ? e.meldung() : e.fehler(), !e.geklappt()),
                    angemeldeter(c)));
        });

        app.post("/organisation/{id}/umhaengen", c -> {
            if (abgewiesen(c, rechte(c).darfOrganisationVerwalten(),
                    "Das Umhängen von Organisationseinheiten")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var e = organisationen.umhaengen(id, zahlOderNull(Anmeldeseiten.feld(c, "elternId")),
                    bereich(c));
            if (e.geklappt()) {
                // Sicherheitsstufe: Diese Aenderung verschiebt, wer was sieht.
                protokoll.anfuegen(bereich(c).bezeichnung(), null,
                        Ereignisart.ORGANISATION_UMGEHAENGT, true, angemeldeter(c).anzeigename(),
                        null, e.meldung(),
                        "Mit der Einheit wechseln auch die Zuständigkeiten für ihre Geräte "
                        + "und Konten.");
            }
            c.html(seite("organisation", organisationsDetail(c, id,
                    e.geklappt() ? e.meldung() : e.fehler(), !e.geklappt()),
                    angemeldeter(c)));
        });

        app.post("/organisation/{id}/loeschen", c -> {
            if (abgewiesen(c, rechte(c).darfOrganisationVerwalten(),
                    "Das Löschen von Organisationseinheiten")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var e = organisationen.loeschen(id, bereich(c));
            if (e.geklappt()) {
                protokoll.anfuegen(bereich(c).bezeichnung(), null,
                        Ereignisart.ORGANISATION_GELOESCHT, true, angemeldeter(c).anzeigename(),
                        null, e.meldung(), null);
            }
            // Nach dem Loeschen zurueck zur Struktur — die Einheit, deren
            // Detailseite das war, gibt es nicht mehr.
            c.html(seite("organisation", e.geklappt()
                    ? organisationsSeite(c, e.meldung(), false)
                    : organisationsDetail(c, id, e.fehler(), true), angemeldeter(c)));
        });
    }


    /**
     * Konten, Rollen, Sperren, zweiter Faktor (FA-BEN-030/040).
     *
     * <p>Die Reihenfolge innerhalb dieser Methode ist bedeutsam: Javalin nimmt
     * die zuerst passende Route, deshalb müssen feste Pfade vor solchen mit
     * Platzhalter stehen.
     */
    private void routenBenutzer(Javalin app) {
        // ----------------------------------------------------------- Benutzer
        app.get("/benutzer", c -> c.html(seite("benutzer",
                benutzerSeite(c, null, false, null, null), angemeldeter(c))));

        app.post("/benutzer/einladen", c -> {
            if (abgewiesen(c, rechte(c).darfBenutzerVerwalten(), "Das Einladen von Personen")) return;
            Long orgId = null;
            try {
                String o = Anmeldeseiten.feld(c, "organisationId");
                if (o != null && !o.isBlank()) orgId = Long.parseLong(o);
            } catch (NumberFormatException ignored) { }

            Benutzerverwaltung.Rolle rolle;
            try { rolle = Benutzerverwaltung.Rolle.valueOf(Anmeldeseiten.feld(c, "rolle")); }
            catch (Exception e) { rolle = Benutzerverwaltung.Rolle.BENUTZER; }

            String name = Anmeldeseiten.feld(c, "benutzername");
            var e = einladungen.einladen(name, Anmeldeseiten.feld(c, "anzeigename"), rolle,
                    orgId, angemeldeter(c).anzeigename(), bereich(c));
            if (e.geklappt()) {
                // Das Geheimnis selbst steht NICHT im Protokoll - es waere sonst
                // fuer jeden lesbar, der das Protokoll einsehen darf.
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.BENUTZER_ANGELEGT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Person \u201e" + name + "\u201c eingeladen (Rolle " + rolle.anzeige() + ")",
                        "Der Zugangscode wird nur einmal angezeigt und ist nicht im Protokoll "
                        + "enthalten.");
            }
            c.html(seite("benutzer", benutzerSeite(c,
                    e.geklappt() ? null : e.fehler(), !e.geklappt(),
                    e.geheimnis(), name), angemeldeter(c)));
        });

        app.post("/benutzer/einladung/zurueckziehen", c -> {
            if (abgewiesen(c, rechte(c).darfBenutzerVerwalten(), "Das Zurückziehen von Einladungen")) return;
            String name = Anmeldeseiten.feld(c, "benutzername");
            boolean weg = einladungen.zurueckziehen(name, bereich(c));
            if (weg) {
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.BENUTZER_GEAENDERT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Einladung für \u201e" + name + "\u201c zurückgezogen", null);
            }
            c.html(seite("benutzer", benutzerSeite(c,
                    weg ? "Einladung zurückgezogen." : "Diese Einladung gibt es nicht mehr.",
                    !weg, null, null), angemeldeter(c)));
        });

        app.post("/benutzer/{id}/totp-zuruecksetzen", c -> {
            if (abgewiesen(c, rechte(c).darfBenutzerVerwalten(), "Das Zurücksetzen des zweiten Faktors")) return;
            long id = Long.parseLong(c.pathParam("id"));
            var ziel = benutzer.benutzer(id, bereich(c));
            boolean ok = benutzer.totpZuruecksetzen(id, bereich(c));
            if (ok && ziel.isPresent()) {
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.TOTP_ZURUECKGESETZT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Zweiter Faktor von \u201e" + ziel.get().anzeigename() + "\u201c zurückgesetzt",
                        "Die Person richtet ihn bei der nächsten Anmeldung neu ein. Das neue "
                        + "Geheimnis sieht nur sie selbst.");
            }
            c.html(seite("benutzer", benutzerSeite(c,
                    ok ? "Der zweite Faktor wurde zurückgesetzt. Die Person richtet ihn bei der "
                       + "nächsten Anmeldung neu ein."
                       : "Dieses Konto gibt es nicht mehr.", !ok, null, null), angemeldeter(c)));
        });

        app.post("/benutzer/{id}/aktiv", c -> {
            if (abgewiesen(c, rechte(c).darfBenutzerVerwalten(), "Das Ändern von Konten")) return;
            long id = Long.parseLong(c.pathParam("id"));
            boolean aktiv = "ja".equals(Anmeldeseiten.feld(c, "aktiv"));
            var ziel = benutzer.benutzer(id, bereich(c));
            String fehler = benutzer.aktivSetzen(id, aktiv, bereich(c));
            if (fehler == null && ziel.isPresent()) {
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.BENUTZER_GEAENDERT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Konto \u201e" + ziel.get().anzeigename() + "\u201c "
                                + (aktiv ? "aktiviert" : "deaktiviert"),
                        aktiv ? null : "Das Konto bleibt im Protokoll nachvollziehbar; es kann "
                                     + "sich nur nicht mehr anmelden.");
            }
            c.html(seite("benutzer", benutzerSeite(c,
                    fehler == null ? "Konto " + (aktiv ? "aktiviert." : "deaktiviert.") : fehler,
                    fehler != null, null, null), angemeldeter(c)));
        });
    }


    /**
     * Mailserver, Regeln, Testversand (FA-ALA-020).
     *
     * <p>Die Reihenfolge innerhalb dieser Methode ist bedeutsam: Javalin nimmt
     * die zuerst passende Route, deshalb müssen feste Pfade vor solchen mit
     * Platzhalter stehen.
     */
    private void routenBenachrichtigung(Javalin app) {
        app.get("/benachrichtigung", c -> c.html(seite("benachrichtigung",
                benachrichtigungsSeite(c, null, false, null), angemeldeter(c))));

        app.post("/benachrichtigung/server", c -> {
            if (abgewiesen(c, rechte(c).darfInstanzVerwalten(),
                    "Das Einrichten des Mailservers")) return;
            String fehler = benachrichtigung.serverSpeichern(
                    Anmeldeseiten.feld(c, "host"), Anmeldeseiten.feld(c, "port"),
                    angekreuzt(c, "tls"), Anmeldeseiten.feld(c, "benutzer"),
                    Anmeldeseiten.feld(c, "kennwort"), Anmeldeseiten.feld(c, "absender"));
            if (fehler == null) {
                // Protokolliert wird DASS eingerichtet wurde, nie das Kennwort.
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.EINSTELLUNG_GEAENDERT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Mailserver eingerichtet: " + Anmeldeseiten.feld(c, "host"), null);
            }
            c.html(seite("benachrichtigung", benachrichtigungsSeite(c,
                    fehler == null ? "Mailserver gespeichert." : fehler, fehler != null, null),
                    angemeldeter(c)));
        });

        /**
         * Testversand (FA-ALA-020).
         *
         * <p>Ohne ihn merkt der Betreiber erst beim ersten echten Alarm, dass
         * die Einstellung nicht stimmt — also genau dann, wenn die Nachricht
         * gebraucht wird.
         */
        app.post("/benachrichtigung/test", c -> {
            if (abgewiesen(c, rechte(c).darfInstanzVerwalten(), "Der Testversand")) return;
            String an = Anmeldeseiten.feld(c, "testempfaenger");
            var zugang = benachrichtigung.zugang();
            String verlauf = null, meldung;
            boolean fehler;
            if (zugang.isEmpty()) {
                meldung = "Es ist kein Mailserver eingerichtet.";
                fehler = true;
            } else {
                var e = Mailversand.senden(zugang.get(), Benachrichtigung.adressenZerlegen(an),
                        "PIN-Roboter: Testnachricht",
                        """
                        Diese Nachricht bestätigt, dass der PIN-Roboter Ihren Mailserver
                        erreichen kann.

                        Sie wurde von Hand ausgelöst und bedeutet nicht, dass ein Alarm
                        vorliegt. Echte Meldungen nennen Gerät und Handlungsempfehlung —
                        und niemals Zugangsdaten oder personenbezogene Daten.
                        """);
                fehler = !e.geklappt();
                meldung = e.geklappt()
                        ? "Testnachricht an " + an + " übergeben. Wenn sie nicht ankommt, "
                          + "prüfen Sie den Spam-Ordner und die Protokolle Ihres Mailservers."
                        : e.fehler();
                verlauf = e.verlauf();
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.EINSTELLUNG_GEAENDERT,
                        e.geklappt(), angemeldeter(c).anzeigename(), null,
                        "Testnachricht " + (e.geklappt() ? "versendet" : "fehlgeschlagen"),
                        e.geklappt() ? null : e.fehler());
            }
            c.html(seite("benachrichtigung",
                    benachrichtigungsSeite(c, meldung, fehler, verlauf), angemeldeter(c)));
        });

        app.post("/benachrichtigung/regel", c -> {
            if (abgewiesen(c, rechte(c).darfBenutzerVerwalten(),
                    "Das Einrichten von Benachrichtigungen")) return;
            Long org = zahlOderNull(Anmeldeseiten.feld(c, "organisationId"));
            String fehler = org == null
                    ? "Bitte eine Organisationseinheit wählen."
                    : benachrichtigung.regelSpeichern(org, Anmeldeseiten.feld(c, "empfaenger"),
                            Anmeldeseiten.feld(c, "schwelle"), Anmeldeseiten.feld(c, "fenster"),
                            angekreuzt(c, "aktiv"), bereich(c));
            if (fehler == null) {
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.EINSTELLUNG_GEAENDERT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Benachrichtigungsregel gespeichert", null);
            }
            c.html(seite("benachrichtigung", benachrichtigungsSeite(c,
                    fehler == null ? "Regel gespeichert." : fehler, fehler != null, null),
                    angemeldeter(c)));
        });

        app.post("/benachrichtigung/regel/{id}/loeschen", c -> {
            if (abgewiesen(c, rechte(c).darfBenutzerVerwalten(),
                    "Das Entfernen von Benachrichtigungen")) return;
            String fehler = benachrichtigung.regelLoeschen(
                    Long.parseLong(c.pathParam("id")), bereich(c));
            c.html(seite("benachrichtigung", benachrichtigungsSeite(c,
                    fehler == null ? "Regel entfernt." : fehler, fehler != null, null),
                    angemeldeter(c)));
        });
    }


    /**
     * Instanzverwaltung: Demodaten, Lasttest, Registry, Agenten-Registrierung.
     *
     * <p>Die Reihenfolge innerhalb dieser Methode ist bedeutsam: Javalin nimmt
     * die zuerst passende Route, deshalb müssen feste Pfade vor solchen mit
     * Platzhalter stehen.
     */
    private void routenVerwaltung(Javalin app) {
        // ---------------------------------------------------------- Verwaltung
        app.get("/verwaltung", c -> c.html(seite("verwaltung", verwaltungsSeite(null, null, bereich(c)),
                angemeldeter(c))));

        app.post("/verwaltung/registry", c -> {
            if (abgewiesen(c, rechte(c).darfInstanzVerwalten(),
                    "Das Einrichten der Registry")) return;
            String fehler = versionen.zugangSpeichern(Anmeldeseiten.feld(c, "registry"),
                    Anmeldeseiten.feld(c, "abbild"), Anmeldeseiten.feld(c, "benutzer"),
                    Anmeldeseiten.feld(c, "token"));
            if (fehler == null) {
                // Nie das Token — nur DASS eingerichtet wurde.
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.EINSTELLUNG_GEAENDERT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Registry-Zugang eingerichtet: " + Anmeldeseiten.feld(c, "abbild"), null);
                versionen.pruefen();
            }
            c.html(seite("verwaltung", verwaltungsSeite(fehler, null, bereich(c)), angemeldeter(c)));
        });

        app.post("/verwaltung/registry/pruefen", c -> {
            if (abgewiesen(c, rechte(c).darfInstanzVerwalten(), "Die Versionsprüfung")) return;
            var z = versionen.pruefen();
            c.html(seite("verwaltung", verwaltungsSeite(z.fehler(), null), angemeldeter(c)));
        });

        app.post("/verwaltung/demodaten/anlegen", c -> {
            // Instanzweite Werkzeuge: Sie legen Daten quer ueber alle Mandanten
            // an oder entfernen sie. Ein Praxisadministrator, der den
            // Demodatensatz loescht, traefe damit fremde Einrichtungen.
            if (abgewiesen(c, rechte(c).darfInstanzVerwalten(), "Das Anlegen des Demodatensatzes")) return;
            try {
                var konten = demodaten.anlegen();
                var b = demodaten.bestand();
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.DEMODATEN_ANGELEGT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Demodatensatz angelegt: %d Organisationseinheiten, %d Konnektoren, %d Arbeitsplätze, %d Kartenterminals (%d mit SMC-B), %d Konten"
                                .formatted(b.organisationen(), b.konnektoren(), b.arbeitsplaetze(),
                                           b.terminals(), b.terminalsMitSmcb(), b.benutzer()), null);
                c.html(seite("verwaltung", verwaltungsSeite(null, konten), angemeldeter(c)));
            } catch (IllegalStateException e) {
                c.html(seite("verwaltung", verwaltungsSeite(e.getMessage(), null), angemeldeter(c)));
            }
        });

        // NFA-LEI-010/020: Bestand in Zielgroesse erzeugen, damit die
        // Leistungswerte am echten Mengengeruest gemessen werden koennen -
        // auch beim Betreiber auf dessen Hardware.
        app.post("/verwaltung/lasttest/anlegen", c -> {
            // Instanzweite Werkzeuge: Sie legen Daten quer ueber alle Mandanten
            // an oder entfernen sie. Ein Praxisadministrator, der den
            // Demodatensatz loescht, traefe damit fremde Einrichtungen.
            if (abgewiesen(c, rechte(c).darfInstanzVerwalten(), "Das Anlegen des Lasttestbestands")) return;
            int anzahl = zahl(Anmeldeseiten.feld(c, "anzahl"), Lasterzeuger.TERMINALS_ZIEL);
            try {
                var e = last.erzeugen(anzahl);
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.DEMODATEN_ANGELEGT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Lasttestbestand angelegt: %d Kartenterminals an %d Konnektoren (%d ms)"
                                .formatted(e.terminals(), e.konnektoren(), e.dauerMs()),
                        "Dieser Bestand dient nur der Leistungsmessung und ist als Demo "
                        + "gekennzeichnet. Er sollte nach der Messung wieder entfernt werden.");
                c.html(seite("verwaltung", verwaltungsSeite(
                        "Lasttestbestand angelegt: %d Kartenterminals an %d Konnektoren in %d ms."
                                .formatted(e.terminals(), e.konnektoren(), e.dauerMs()), null),
                        angemeldeter(c)));
            } catch (Exception e) {
                c.html(seite("verwaltung", verwaltungsSeite(
                        "Der Lasttestbestand konnte nicht angelegt werden: " + e.getMessage(),
                        null), angemeldeter(c)));
            }
        });

        app.post("/verwaltung/lasttest/entfernen", c -> {
            // Instanzweite Werkzeuge: Sie legen Daten quer ueber alle Mandanten
            // an oder entfernen sie. Ein Praxisadministrator, der den
            // Demodatensatz loescht, traefe damit fremde Einrichtungen.
            if (abgewiesen(c, rechte(c).darfInstanzVerwalten(), "Das Entfernen des Lasttestbestands")) return;
            try {
                int weg = last.entfernen();
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.DEMODATEN_GELOESCHT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Lasttestbestand entfernt: %d Kartenterminals".formatted(weg), null);
                c.html(seite("verwaltung", verwaltungsSeite(
                        "Lasttestbestand entfernt: %d Kartenterminals.".formatted(weg), null),
                        angemeldeter(c)));
            } catch (Exception e) {
                c.html(seite("verwaltung", verwaltungsSeite(
                        "Der Lasttestbestand konnte nicht entfernt werden: " + e.getMessage(),
                        null), angemeldeter(c)));
            }
        });

        app.post("/verwaltung/demodaten/loeschen", c -> {
            // Instanzweite Werkzeuge: Sie legen Daten quer ueber alle Mandanten
            // an oder entfernen sie. Ein Praxisadministrator, der den
            // Demodatensatz loescht, traefe damit fremde Einrichtungen.
            if (abgewiesen(c, rechte(c).darfInstanzVerwalten(), "Das Entfernen des Demodatensatzes")) return;
            var weg = demodaten.loeschen();
            protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.DEMODATEN_GELOESCHT, true,
                    angemeldeter(c).anzeigename(), null,
                    "Demodatensatz entfernt: %d Organisationseinheiten, %d Konnektoren, %d Arbeitsplätze, %d Kartenterminals, %d Konten"
                            .formatted(weg.organisationen(), weg.konnektoren(),
                                       weg.arbeitsplaetze(), weg.terminals(), weg.benutzer()), null);
            c.html(seite("verwaltung", verwaltungsSeite(null, null, bereich(c)), angemeldeter(c)));
        });

        // Erzeugt ein Registrierungsgeheimnis. Nur für angemeldete Administratoren.
        // Die Einrichtung eines Agenten korrigieren. Bis zum 12.08.2026 war sie
        // unveraenderlich - eine Selbstbeschraenkung, die im Fehlerfall in eine
        // Sackgasse fuehrte: Wird die Einrichtung umbenannt, meldet der Agent
        // ins Leere, und es bliebe nur die Neuregistrierung samt Verlust aller
        // dort hinterlegten PINs.
        app.post("/agenten/{id}/einrichtung", c -> {
            if (abgewiesen(c, rechte(c).darfBestandPflegen(),
                    "Das Zuordnen von Standort-Agenten")) return;
            long id = Long.parseLong(c.pathParam("id"));
            String neuE = Anmeldeseiten.feld(c, "einrichtung");
            String alt = agenten.einrichtungVon(id);
            String fehler = agenten.einrichtungSetzen(id, neuE, bereich(c));
            if (fehler == null) {
                // Sicherheitsstufe: Die Aenderung verschiebt, wohin ein Agent
                // meldet - und damit, in wessen Sicht seine Geraete landen.
                protokoll.anfuegen(neuE, null, Ereignisart.AGENT_ZUGEORDNET, true,
                        angemeldeter(c).anzeigename(), null,
                        "Standort-Agent von „" + nz(alt) + "“ nach „" + neuE.trim()
                                + "“ zugeordnet",
                        "Alles, was dieser Agent meldet, gehört ab sofort zur neuen "
                        + "Einrichtung. Bereits protokollierte Ereignisse bleiben, wo sie sind.");
            }
            c.html(seite("verwaltung", verwaltungsSeite(fehler, null, bereich(c)),
                    angemeldeter(c)));
        });

        app.post("/agenten/geheimnis", c -> {
            var angemeldet = angemeldeter(c);
            if (angemeldet == null || angemeldet.rolle() != Benutzerverwaltung.Rolle.ADMINISTRATOR) {
                c.status(403).result("Nur Administratoren dürfen Agenten einrichten.");
                return;
            }
            String bez = Anmeldeseiten.feld(c, "bezeichnung");
            // Die Einrichtung gehoert zur Registrierung, nicht zur Meldung:
            // Ein Agent steht in genau einer Praxis, und alles, was er meldet,
            // gehoert dorthin. Sie hier zu waehlen ist der einzige Zeitpunkt,
            // an dem ein Mensch es wissen kann.
            String einr = Anmeldeseiten.feld(c, "einrichtung");

            // Die Einrichtung muss eine VORHANDENE Organisationseinheit sein.
            //
            // Die Maske bietet eine Auswahlliste — aber ein Formularwert ist
            // manipulierbar, und eine Mandantengrenze, die nur in der
            // Auswahlliste besteht, ist keine. Bis zum 11.08.2026 ging hier
            // jeder Freitext durch; der Agent meldete dann Geraete unter einem
            // Namen, zu dem es keine Organisationseinheit gibt. Solche Daten
            // sind fuer jede mandantengebundene Abfrage unsichtbar — sie
            // erscheinen nur dem Instanzbetreiber und lassen sich niemandem
            // zuordnen.
            if (einr == null || einr.isBlank() || !bestand.organisationVorhanden(einr)) {
                c.html(Anmeldeseiten.meldung("Bitte eine vorhandene Organisationseinheit "
                        + "auswählen. Der Agent meldet alles unter dieser Einheit; gibt es sie "
                        + "nicht, lassen sich seine Geräte keinem Zuständigen zeigen.", "warnend"));
                return;
            }
            String geheimnis = agenten.geheimnisAnlegen(
                    bez.isBlank() ? "Standort-Agent" : bez, einr, angemeldet.anzeigename());
            protokoll.anfuegen(einr, null, Ereignisart.AGENT_GEHEIMNIS_ERZEUGT, true,
                    angemeldet.anzeigename(), null,
                    "Registrierungsgeheimnis für \"" + bez + "\" erzeugt", null);
            // Der Klartext erscheint genau einmal - danach ist nur der Hash gespeichert.
            c.html(geheimnisAnzeige(geheimnis));
        });
    }


    /**
     * Einstellungen: Datensicherung und Systemtest (FA-BAK-020 bis 050).
     *
     * <p>Durchweg nur für Administratoren. Wer eine Sicherung herunterladen
     * kann, hat den gesamten Datenbestand in der Hand — auch die
     * verschlüsselten Zugangsdaten.
     */
    private void routenEinstellungen(Javalin app) {
        app.get("/einstellungen", c -> {
            if (keinAdministrator(c)) return;
            c.html(seite("einstellungen", Einstellungsseiten.seite(
                    sicherung.alleStaende(), sicherung, systemtestTafeln(),
                    c.queryParam("meldung"), c.queryParam("fehler") != null),
                    angemeldeter(c)));
        });

        app.post("/einstellungen/sicherung/jetzt", c -> {
            if (keinAdministrator(c)) return;
            var plan = Sicherungsverwaltung.Plan.TAEGLICH;
            try {
                var s = sicherung.sichern(plan, benutzername(c));
                c.redirect("/einstellungen?meldung=" + urlKodiert(
                        "Sicherung erstellt (" + s.groesse() + ")."));
            } catch (Exception e) {
                c.redirect("/einstellungen?fehler=1&meldung=" + urlKodiert(
                        "Die Sicherung ist fehlgeschlagen: " + e.getMessage()));
            }
        });

        // Erst die Maske für den Schlüssel, dann der Download. Zwei Schritte,
        // damit der Hinweis "ein verlorener Schlüssel macht die Datei
        // unbrauchbar" gelesen wird, bevor jemand tippt.
        app.get("/einstellungen/sicherung/{datei}/herunterladen", c -> {
            if (keinAdministrator(c)) return;
            var st = sicherung.stand(c.pathParam("datei"));
            if (st.isEmpty()) { c.status(404).result("Diese Sicherung gibt es nicht."); return; }
            c.html(seite("einstellungen",
                    Einstellungsseiten.schluesselMaske(st.get(), null), angemeldeter(c)));
        });

        app.post("/einstellungen/sicherung/{datei}/herunterladen", c -> {
            if (keinAdministrator(c)) return;
            var st = sicherung.stand(c.pathParam("datei"));
            if (st.isEmpty()) { c.status(404).result("Diese Sicherung gibt es nicht."); return; }

            char[] schluessel = Anmeldeseiten.passwortFeld(c, "schluessel");
            char[] zweit = Anmeldeseiten.passwortFeld(c, "schluessel2");
            try {
                if (!java.util.Arrays.equals(schluessel, zweit)) {
                    c.html(seite("einstellungen", Einstellungsseiten.schluesselMaske(st.get(),
                            "Die beiden Eingaben stimmen nicht überein."), angemeldeter(c)));
                    return;
                }
                String schwach = de.pinroboter.gemeinsam.Sicherungsdatei.pruefeSchluessel(schluessel);
                if (schwach != null) {
                    c.html(seite("einstellungen",
                            Einstellungsseiten.schluesselMaske(st.get(), schwach), angemeldeter(c)));
                    return;
                }

                byte[] klar = java.nio.file.Files.readAllBytes(sicherung.pfad(st.get()));
                // verschluesseln() überschreibt den Schlüssel selbst.
                byte[] datei = de.pinroboter.gemeinsam.Sicherungsdatei.verschluesseln(
                        klar, schluessel, st.get().zeitpunkt());
                java.util.Arrays.fill(klar, (byte) 0);

                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.SICHERUNG_HERUNTERGELADEN, true,
                        benutzername(c), null,
                        "Sicherung vom " + st.get().anzeige() + " heruntergeladen",
                        "Die Datei ist verschlüsselt. Ohne den Schlüssel ist sie unbrauchbar.");

                c.header("Content-Disposition", "attachment; filename=\""
                        + st.get().dateiname().replace(".dump", "") + ".prsich\"");
                c.contentType("application/octet-stream");
                c.result(datei);
            } finally {
                java.util.Arrays.fill(schluessel, '\0');
                java.util.Arrays.fill(zweit, '\0');
            }
        });

        app.post("/einstellungen/sicherung/hochladen", c -> {
            if (keinAdministrator(c)) return;
            var datei = c.uploadedFile("datei");
            char[] schluessel = Anmeldeseiten.passwortFeld(c, "schluessel");
            try {
                if (datei == null) {
                    c.redirect("/einstellungen?fehler=1&meldung="
                            + urlKodiert("Es wurde keine Datei ausgewählt."));
                    return;
                }
                byte[] roh = datei.content().readAllBytes();
                var inhalt = de.pinroboter.gemeinsam.Sicherungsdatei.entschluesseln(roh, schluessel);
                var st = sicherung.aufnehmen(inhalt.daten(),
                        Sicherungsverwaltung.Plan.MONATLICH, benutzername(c));
                c.redirect("/einstellungen?meldung=" + urlKodiert(
                        "Sicherung vom " + st.anzeige() + " eingelesen und geprüft. "
                        + "Sie ist noch nicht eingespielt."));
            } catch (de.pinroboter.gemeinsam.Sicherungsdatei.KeinSicherungsformat e) {
                c.redirect("/einstellungen?fehler=1&meldung=" + urlKodiert(e.getMessage()));
            } catch (de.pinroboter.gemeinsam.Sicherungsdatei.NichtLesbar e) {
                c.redirect("/einstellungen?fehler=1&meldung=" + urlKodiert(e.getMessage()));
            } catch (Exception e) {
                c.redirect("/einstellungen?fehler=1&meldung=" + urlKodiert(
                        "Die Datei konnte nicht eingelesen werden."));
            } finally {
                java.util.Arrays.fill(schluessel, '\0');
            }
        });

        app.post("/einstellungen/sicherung/{datei}/einspielen", c -> {
            if (keinAdministrator(c)) return;
            var st = sicherung.stand(c.pathParam("datei"));
            if (st.isEmpty()) { c.status(404).result("Diese Sicherung gibt es nicht."); return; }

            String fehler = sicherung.wiederherstellen(st.get(), benutzername(c));
            if (fehler != null) {
                c.redirect("/einstellungen?fehler=1&meldung=" + urlKodiert(fehler));
                return;
            }
            // Erst antworten, dann beenden: Sonst sieht der Betreiber einen
            // Verbindungsabbruch statt einer Bestätigung. Docker startet die
            // Zentrale neu (restart: unless-stopped, ADR-0010).
            c.html(seite("einstellungen", """
                    <div class="tafel"><div class="karte-leib">
                      <div class="hinweis gut"><strong>Der Stand wurde eingespielt.</strong>
                        Die Zentrale startet jetzt neu, damit keine Reste des alten
                        Datenstandes im Arbeitsspeicher zurückbleiben. Das dauert einige
                        Sekunden. Danach müssen Sie sich neu anmelden.</div>
                      <p class="hilfe">Die Überwachung der Kartenterminals läuft im
                         Standort-Agenten weiter und ist vom Neustart nicht betroffen.</p>
                      <a class="knopf haupt" href="/"
                         title="Lädt die Oberfläche neu; nach dem Neustart erscheint die Anmeldung"
                         aria-label="Zur Anmeldung">Zur Anmeldung</a>
                    </div></div>""", angemeldeter(c)));

            Thread.ofVirtual().start(() -> {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                System.out.println("[Wiederherstellung] Zentrale beendet sich für den Neustart");
                System.exit(0);
            });
        });

        app.post("/einstellungen/sicherung/{datei}/loeschen", c -> {
            if (keinAdministrator(c)) return;
            var st = sicherung.stand(c.pathParam("datei"));
            if (st.isEmpty()) { c.status(404).result("Diese Sicherung gibt es nicht."); return; }
            try {
                java.nio.file.Files.deleteIfExists(sicherung.pfad(st.get()));
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.SICHERUNG_GELOESCHT, true,
                        benutzername(c), null,
                        "Sicherung vom " + st.get().anzeige() + " gelöscht", null);
                c.redirect("/einstellungen?meldung=" + urlKodiert("Sicherung gelöscht."));
            } catch (Exception e) {
                c.redirect("/einstellungen?fehler=1&meldung="
                        + urlKodiert("Die Sicherung ließ sich nicht löschen."));
            }
        });
    }

    /** Wehrt Nicht-Administratoren ab und meldet es dem Aufrufer. */
    private boolean keinAdministrator(Context c) {
        var a = angemeldeter(c);
        if (a != null && a.rolle() == Benutzerverwaltung.Rolle.ADMINISTRATOR) return false;
        c.status(403).result("Diese Funktion ist Administratoren vorbehalten.");
        return true;
    }

    private String benutzername(Context c) {
        var a = angemeldeter(c);
        return a == null ? "unbekannt" : a.benutzername();
    }

    /**
     * Die Schnittstelle zum Standort-Agenten — keine Oberfläche, nur JSON (FA-AGT-010/020).
     *
     * <p>Die Reihenfolge innerhalb dieser Methode ist bedeutsam: Javalin nimmt
     * die zuerst passende Route, deshalb müssen feste Pfade vor solchen mit
     * Platzhalter stehen.
     */
    private void routenAgentSchnittstelle(Javalin app) {
        // Meldeschnittstelle des Agenten. Im Produkt eine gegenseitig
        // authentifizierte WebSocket-Verbindung, ausgehend vom Agenten (ADR-0002).
        app.post("/api/agent/melden", this::agentMeldung);

        app.get("/api/agent/notaus", c -> c.json(Map.of("notAus", notAus)));

        /**
         * FA-UEB-010: Das Prüfintervall je Kartenterminal.
         *
         * <p>Bis zum 08.08.2026 kannte der Agent nur einen globalen Wert aus
         * seiner Umgebung. Die Gerätegruppen trugen zwar ein Intervall, aber
         * es erreichte ihn nie — die Einstellung war wirkungslos. Bei 5.000
         * Terminals ist genau das der Hebel, mit dem die Last steuerbar
         * bleibt: Die Notaufnahme braucht 30 Sekunden, das Archiv kommt mit
         * fünf Minuten aus.
         */
        app.get("/api/agent/intervalle", c -> {
            var werte = new LinkedHashMap<String, Integer>();
            try (var p = db.verbindung().prepareStatement("""
                    SELECT g.terminal_id, gr.pruefintervall_sekunden
                    FROM geraet g JOIN geraetegruppe gr ON gr.id = g.gruppe_id
                    WHERE g.aktiv""")) {
                try (var rs = p.executeQuery()) {
                    while (rs.next()) werte.put(rs.getString(1), rs.getInt(2));
                }
            } catch (java.sql.SQLException e) {
                System.err.println("Prüfintervalle nicht abrufbar: " + e.getMessage());
            }
            c.json(Map.of("intervalle", werte));
        });

        /**
         * FA-GER-030 / FA-GER-035: Der Agent meldet, was er beim Konnektor
         * vorgefunden hat. Die Zentrale legt den Befund ab und übernimmt
         * <b>nichts</b> davon selbsttätig in den Bestand.
         */
        app.post("/api/agent/bestand", c -> {
            var agent = (Agentenverwaltung.Agent) c.attribute("agent");
            Map<String, Object> m = c.bodyAsClass(Map.class);

            @SuppressWarnings("unchecked")
            var terminals = (List<Map<String, Object>>) m.getOrDefault("terminals", List.of());
            int n = ermittlung.aufnehmen(agent.id(), terminals);

            // FA-GER-040: Karten je Terminal, einschliesslich eGK und HBA.
            int karten = 0;
            for (var eintrag : terminals) {
                @SuppressWarnings("unchecked")
                var liste = (List<Map<String, Object>>) eintrag.getOrDefault("karten", List.of());
                karten += ermittlung.kartenAufnehmen(agent.id(),
                        String.valueOf(eintrag.get("terminalId")), liste);
            }

            // ADR-0009: Der oeffentliche Schluessel des Agenten. Er ist nicht
            // geheim - der Browser braucht ihn, um die PIN zu verschluesseln.
            // FA-AGT-040: Version merken und bewerten. Ein Agent ausserhalb der
            // Spanne wird NICHT abgewiesen — das hiesse: keine
            // PIN-Verifikation in dieser Praxis. Ein veralteter Agent, der
            // seine Kernaufgabe noch erfuellt, ist besser als gar keiner.
            versionMerken(agent.id(), m.get("protokollversion"), m.get("bausteinversion"));

            Object schluessel = m.get("oeffentlicherSchluessel");
            if (schluessel instanceof String s && !s.isBlank()) {
                pins.schluesselMerken(agent.id(), s);
            }

            // Welche PINs der Agent tatsaechlich hat (nur Kartenhandles).
            // Bis zum 11.08.2026 leitete die Zentrale das allein aus ihren
            // eigenen Auftraegen ab und zeigte "keine PIN hinterlegt", waehrend
            // der Agent Karten freischaltete.
            if (m.get("pinsVorhanden") instanceof List<?> vorhanden) {
                pins.bestandMelden(agent.id(), vorhanden.stream()
                        .map(String::valueOf).filter(h -> !h.isBlank()).toList());
            }

            boolean modellDa = Boolean.TRUE.equals(m.get("informationsmodellVerfuegbar"));
            ermittlung.modellAblegen(agent.id(), modellDa,
                    modellDa ? String.valueOf(m.get("informationsmodell")) : null);

            int neu = ermittlung.neueAnzahl();
            protokoll.anfuegen(agent.einrichtung(), null, Ereignisart.BESTAND_ERMITTELT, true,
                    "Standort-Agent", null,
                    "Bestand beim Konnektor ermittelt: %d Kartenterminal(s), %d Karte(n)%s"
                            .formatted(n, karten,
                            modellDa ? ", Informationsmodell gelesen"
                                     : ", Informationsmodell nicht verfügbar"),
                    neu == 0 ? null : neu + " Gerät(e) sind noch nicht erfasst. "
                            + "Unter „Bestand → Ermittelte Geräte“ prüfen und übernehmen.");
            c.json(Map.of("aufgenommen", n, "neu", neu));
        });

        /**
         * Der Agent holt seine offenen PIN-Aufträge ab (ADR-0009).
         *
         * <p>Das Chiffrat wird dabei aus der Datenbank gelöscht — es ist
         * ausgeliefert. Was hier durchgeht, kann die Zentrale nicht lesen.
         */
        app.get("/api/agent/pin-auftraege", c -> {
            var agent = (Agentenverwaltung.Agent) c.attribute("agent");
            c.json(Map.of("auftraege", pins.abholen(agent.id())));
        });

        /** Der Agent quittiert einen Auftrag. */
        app.post("/api/agent/pin-quittung", c -> {
            var agent = (Agentenverwaltung.Agent) c.attribute("agent");
            Map<String, Object> m = c.bodyAsClass(Map.class);
            boolean erfolg = Boolean.TRUE.equals(m.get("erfolg"));
            String art = String.valueOf(m.getOrDefault("art", "HINTERLEGEN"));
            String handle = String.valueOf(m.get("kartenhandle"));
            pins.quittieren(agent.id(), ((Number) m.get("auftragId")).longValue(),
                    erfolg, erfolg ? null : String.valueOf(m.get("fehler")), handle, art);

            if (!erfolg) {
                protokoll.anfuegen(agent.einrichtung(), handle,
                        "HINTERLEGEN".equals(art) ? Ereignisart.PIN_HINTERLEGT
                                                  : Ereignisart.PIN_GELOESCHT,
                        false, "Standort-Agent", null,
                        "Der Standort-Agent konnte den PIN-Auftrag nicht ausführen",
                        String.valueOf(m.get("fehler")));
            }
            c.json(Map.of("quittiert", true));
        });

        /**
         * FA-AGT-010: Ein Agent löst hier sein einmalig gültiges
         * Registrierungsgeheimnis gegen ein dauerhaftes Token ein.
         */
        app.post("/api/registrieren", c -> {
            String geheimnis = kopfWert(c, "X-Registrierung");
            var ergebnis = agenten.registrieren(geheimnis);
            if (ergebnis instanceof Agentenverwaltung.Registrierung.Erfolg e) {
                protokoll.anfuegen(einrichtungVonAgent(e.agentId()), null,
                        Ereignisart.AGENT_REGISTRIERT, true,
                        "Registrierung", null,
                        "Standort-Agent registriert (Nummer " + e.agentId() + ")", null);
                c.json(Map.of("token", e.token(), "agentId", e.agentId()));
            } else {
                var a = (Agentenverwaltung.Registrierung.Abgelehnt) ergebnis;
                // Fehlversuche werden protokolliert - sie sind ein Angriffshinweis.
                // Instanzweit abgelegt: Zu einem abgelehnten Versuch gibt es
                // keine belegte Einrichtung, und ein Angreifer duerfte den
                // Ablageort nicht ueber sein Geheimnis waehlen koennen.
                protokoll.anfuegen(Fachprotokoll.INSTANZ, null, Ereignisart.AGENT_REGISTRIERUNG_ABGELEHNT,
                        false, "Registrierung", null, a.grund(),
                        "Falls ein Agent eingerichtet werden soll, bitte ein neues "
                        + "Registrierungsgeheimnis erzeugen.");
                c.status(403).json(Map.of("fehler", a.grund()));
            }
        });
    }

}
