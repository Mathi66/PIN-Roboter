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
    private final Lasterzeuger last;
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
        new Zentrale(db, budget).starten(port);
    }

    Zentrale(Datenbank db, long protokollBudgetBytes) {
        this.db = db;
        this.speicher = new Speicherwaechter(db, protokollBudgetBytes);
        this.protokoll = new Fachprotokoll(db, speicher);
        this.benutzer = new Benutzerverwaltung(db);
        this.agenten = new Agentenverwaltung(db);
        this.gruppen = new Gruppenverwaltung(db);
        this.alarme = new Alarmverwaltung(db);
        this.demodaten = new Demodaten(db);
        this.bestand = new Bestandsverwaltung(db,
                de.pinroboter.gemeinsam.Geheimnisspeicher.ausUmgebung());
        this.ermittlung = new Ermittlung(db);
        this.last = new Lasterzeuger(db);
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

        // ------------------------------------------------------------------ Anmeldung
        app.get("/anmelden", c -> c.html(Anmeldeseiten.anmeldeformular(null)));

        app.post("/anmelden", c -> {
            String name = Anmeldeseiten.feld(c, "benutzer");
            var ergebnis = benutzer.anmelden(name,
                    Anmeldeseiten.passwortFeld(c, "passwort"), Anmeldeseiten.feld(c, "code"));

            switch (ergebnis) {
                case Benutzerverwaltung.Anmeldung.Erfolg e -> {
                    setzeSitzungscookie(c, e.sitzung());
                    protokoll.anfuegen("Praxis", null, Ereignisart.ANMELDUNG_ERFOLGREICH,
                            true, e.benutzer().anzeigename(), null,
                            "Anmeldung erfolgreich", null);
                    c.redirect("/");
                }
                case Benutzerverwaltung.Anmeldung.TotpEinrichten t ->
                    c.html(Anmeldeseiten.totpEinrichten(t.benutzerId(), t.uri(), t.geheimnis(),
                            "Bitte richten Sie zuerst den zweiten Faktor ein."));
                case Benutzerverwaltung.Anmeldung.Gesperrt g -> {
                    protokoll.anfuegen("Praxis", null, Ereignisart.ANMELDUNG_FEHLGESCHLAGEN,
                            false, name, null, "Anmeldung bei gesperrtem Konto", null);
                    c.html(Anmeldeseiten.anmeldeformular(g.meldung()));
                }
                case Benutzerverwaltung.Anmeldung.Abgelehnt a -> {
                    // Der Benutzername wird protokolliert, das Passwort nie (FA-PRO-020).
                    protokoll.anfuegen("Praxis", null, Ereignisart.ANMELDUNG_FEHLGESCHLAGEN,
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
                protokoll.anfuegen("Praxis", null, Ereignisart.BENUTZER_ANGELEGT, true,
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
                protokoll.anfuegen("Praxis", null, Ereignisart.TOTP_EINGERICHTET, true,
                        "Benutzer " + id, null, "Zweiter Faktor eingerichtet", null);
                c.html(Anmeldeseiten.rahmen("Einrichtung abgeschlossen", """
                        <div class="hinweis"><strong>Der zweite Faktor ist eingerichtet.</strong>
                        Sie können sich jetzt anmelden.</div>
                        <a class="knopf haupt" href="/anmelden">Zur Anmeldung</a>"""));
            } else {
                c.html(Anmeldeseiten.rahmen("Einrichtung", Anmeldeseiten.meldung(
                        "Der Code stimmt nicht. Bitte prüfen Sie die Uhrzeit Ihres Telefons "
                        + "und versuchen Sie es mit dem nächsten Code erneut.", "warnend")
                        + "<a class=\"knopf\" href=\"/anmelden\">Zurück</a>"));
            }
        });

        app.get("/", c -> c.html(seite("uebersicht", uebersicht(), angemeldeter(c))));
        app.get("/geraete", c -> c.html(seite("geraete", geraeteSeite(), angemeldeter(c))));
        app.get("/protokoll", c -> c.html(seite("protokoll", protokollSeite(c), angemeldeter(c))));
        app.get("/teil/uebersicht", c -> c.html(uebersicht()));
        app.get("/teil/geraete",    c -> c.html(geraeteTabelle()));
        app.get("/teil/protokoll",  c -> c.html(protokollTabelle(c)));

        // ---------------------------------------------------------- Gruppen
        app.get("/gruppen",      c -> c.html(seite("gruppen", gruppenSeite(null), angemeldeter(c))));
        app.get("/teil/gruppen", c -> c.html(gruppenTabelle()));

        app.post("/gruppen", c -> {
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
            int intervall;
            try { intervall = Integer.parseInt(Anmeldeseiten.feld(c, "intervall")); }
            catch (NumberFormatException e) { intervall = 60; }
            String bez = Anmeldeseiten.feld(c, "bezeichnung");
            String fehler = gruppen.anlegen(bez, "Praxis", intervall);
            if (fehler == null) {
                protokoll.anfuegen("Praxis", null, Ereignisart.GRUPPE_ANGELEGT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Gerätegruppe \"" + bez + "\" angelegt, Prüfintervall " + intervall + " s", null);
            }
            c.html(seite("gruppen", gruppenSeite(fehler), angemeldeter(c)));
        });

        app.post("/gruppen/{id}/intervall", c -> {
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
            long id = Long.parseLong(c.pathParam("id"));
            int sek;
            try { sek = Integer.parseInt(Anmeldeseiten.feld(c, "intervall")); }
            catch (NumberFormatException e) { sek = 60; }
            String fehler = gruppen.intervallSetzen(id, sek);
            if (fehler == null) {
                protokoll.anfuegen("Praxis", null, Ereignisart.GRUPPE_GEAENDERT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Prüfintervall der Gruppe " + id + " auf " + sek + " s gesetzt", null);
            }
            c.html(seite("gruppen", gruppenSeite(fehler), angemeldeter(c)));
        });

        app.post("/gruppen/{id}/loeschen", c -> {
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
            long id = Long.parseLong(c.pathParam("id"));
            String fehler = gruppen.loeschen(id, "Praxis");
            if (fehler == null) {
                protokoll.anfuegen("Praxis", null, Ereignisart.GRUPPE_GELOESCHT, true,
                        angemeldeter(c).anzeigename(), null,
                        "Gerätegruppe " + id + " gelöscht, Geräte in die Vorgabegruppe verschoben", null);
            }
            c.html(seite("gruppen", gruppenSeite(fehler), angemeldeter(c)));
        });

        app.post("/geraete/{id}/gruppe", c -> {
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
            String fehler = gruppen.zuordnen(c.pathParam("id"),
                    Long.parseLong(Anmeldeseiten.feld(c, "gruppe")));
            if (fehler == null) {
                protokoll.anfuegen("Praxis", c.pathParam("id"), Ereignisart.GERAET_ZUGEORDNET,
                        true, angemeldeter(c).anzeigename(), null,
                        "Gerät einer anderen Gruppe zugeordnet", null);
            }
            c.html(gruppenTabelle());
        });

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
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
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
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
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
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
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
                protokoll.anfuegen("Praxis", null, Ereignisart.TERMINAL_GEAENDERT, true,
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
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
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

        app.get("/bestand/konnektor/{id}", c -> {
            var k = bestand.konnektor(Long.parseLong(c.pathParam("id")), bereich(c));
            if (k.isEmpty()) { c.status(404).html(seite("bestand",
                    bestandsSeite(c, "Diesen Konnektor gibt es nicht mehr.", true), angemeldeter(c))); return; }
            c.html(seite("bestand", Bestandsseiten.konnektorMaske(k.get(), ermittlung.aufrufkontextAuswahl(), null), angemeldeter(c)));
        });

        app.post("/bestand/konnektor/{id}", c -> {
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
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
                        ? Bestandsseiten.konnektorMaske(k.get(), ermittlung.aufrufkontextAuswahl(), e.fehler())
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

        app.post("/bestand/konnektor/{id}/kennwort-entfernen", c -> {
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
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
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
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
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
            long konnektorId;
            try { konnektorId = Long.parseLong(Anmeldeseiten.feld(c, "konnektorId")); }
            catch (NumberFormatException ex) {
                c.html(seite("bestand", bestandsSeite(c,
                        "Bitte einen Konnektor auswählen, an dem das Terminal betrieben wird.", true),
                        angemeldeter(c)));
                return;
            }
            var e = bestand.terminalAnlegen(Anmeldeseiten.feld(c, "terminalId"), konnektorId,
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
            c.html(seite("bestand", Bestandsseiten.terminalMaske(t.get(), null), angemeldeter(c)));
        });

        app.post("/bestand/terminal/{id}", c -> {
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
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
                        ? Bestandsseiten.terminalMaske(t.get(), e.fehler())
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
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
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

        // ---------------------------------------------------------- Verwaltung
        app.get("/verwaltung", c -> c.html(seite("verwaltung", verwaltungsSeite(null, null),
                angemeldeter(c))));

        app.post("/verwaltung/demodaten/anlegen", c -> {
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
            try {
                var konten = demodaten.anlegen();
                var b = demodaten.bestand();
                protokoll.anfuegen("Praxis", null, Ereignisart.DEMODATEN_ANGELEGT, true,
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
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
            int anzahl = zahl(Anmeldeseiten.feld(c, "anzahl"), Lasterzeuger.TERMINALS_ZIEL);
            try {
                var e = last.erzeugen(anzahl);
                protokoll.anfuegen("Praxis", null, Ereignisart.DEMODATEN_ANGELEGT, true,
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
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
            try {
                int weg = last.entfernen();
                protokoll.anfuegen("Praxis", null, Ereignisart.DEMODATEN_GELOESCHT, true,
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
            if (!istAdministrator(c)) { c.status(403).result("Nur Administratoren."); return; }
            var weg = demodaten.loeschen();
            protokoll.anfuegen("Praxis", null, Ereignisart.DEMODATEN_GELOESCHT, true,
                    angemeldeter(c).anzeigename(), null,
                    "Demodatensatz entfernt: %d Organisationseinheiten, %d Konnektoren, %d Arbeitsplätze, %d Kartenterminals, %d Konten"
                            .formatted(weg.organisationen(), weg.konnektoren(),
                                       weg.arbeitsplaetze(), weg.terminals(), weg.benutzer()), null);
            c.html(seite("verwaltung", verwaltungsSeite(null, null), angemeldeter(c)));
        });

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
            var eintraege = protokoll.filtern(fGer, fArt, fErg, Tabellenmappe.MAX_ZEILEN - 10);

            var aus = new java.io.ByteArrayOutputStream();
            try (Tabellenmappe m = new Tabellenmappe(aus)) {
                m.blattBeginnen("Deckblatt");
                m.zeile("PIN-Roboter - Protokollauszug");
                m.zeile();
                m.zeile("Erstellt am", java.time.Instant.now());
                m.zeile("Erstellt von", b.anzeigename());
                m.zeile("Einrichtung", "Praxis");
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
            protokoll.anfuegen("Praxis", null, Ereignisart.PROTOKOLL_EXPORTIERT, true,
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

        app.post("/notaus", c -> {
            notAus = !notAus;
            protokoll.anfuegen("Praxis", null,
                    notAus ? Ereignisart.NOT_AUS_AUSGELOEST : Ereignisart.NOT_AUS_AUFGEHOBEN,
                    true, "Benutzer", null,
                    notAus ? "Not-Aus ausgelöst — die Automatik ruht."
                           : "Not-Aus aufgehoben — die Automatik arbeitet wieder.", null);
            c.header("HX-Refresh", "true").result("");
        });

        // Meldeschnittstelle des Agenten. Im Produkt eine gegenseitig
        // authentifizierte WebSocket-Verbindung, ausgehend vom Agenten (ADR-0002).
        app.post("/api/agent/melden", this::agentMeldung);
        app.get("/api/agent/notaus", c -> c.json(Map.of("notAus", notAus)));

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

            boolean modellDa = Boolean.TRUE.equals(m.get("informationsmodellVerfuegbar"));
            ermittlung.modellAblegen(agent.id(), modellDa,
                    modellDa ? String.valueOf(m.get("informationsmodell")) : null);

            int neu = ermittlung.neueAnzahl();
            protokoll.anfuegen(agent.einrichtung(), null, Ereignisart.BESTAND_ERMITTELT, true,
                    "Standort-Agent", null,
                    "Bestand beim Konnektor ermittelt: %d Kartenterminal(s)%s".formatted(n,
                            modellDa ? ", Informationsmodell gelesen"
                                     : ", Informationsmodell nicht verfügbar"),
                    neu == 0 ? null : neu + " Gerät(e) sind noch nicht erfasst. "
                            + "Unter „Bestand → Ermittelte Geräte“ prüfen und übernehmen.");
            c.json(Map.of("aufgenommen", n, "neu", neu));
        });

        /**
         * FA-AGT-010: Ein Agent löst hier sein einmalig gültiges
         * Registrierungsgeheimnis gegen ein dauerhaftes Token ein.
         */
        app.post("/api/registrieren", c -> {
            String geheimnis = kopfWert(c, "X-Registrierung");
            var ergebnis = agenten.registrieren(geheimnis);
            if (ergebnis instanceof Agentenverwaltung.Registrierung.Erfolg e) {
                protokoll.anfuegen("Praxis", null, Ereignisart.AGENT_REGISTRIERT, true,
                        "Registrierung", null,
                        "Standort-Agent registriert (Nummer " + e.agentId() + ")", null);
                c.json(Map.of("token", e.token(), "agentId", e.agentId()));
            } else {
                var a = (Agentenverwaltung.Registrierung.Abgelehnt) ergebnis;
                // Fehlversuche werden protokolliert - sie sind ein Angriffshinweis.
                protokoll.anfuegen("Praxis", null, Ereignisart.AGENT_REGISTRIERUNG_ABGELEHNT,
                        false, "Registrierung", null, a.grund(),
                        "Falls ein Agent eingerichtet werden soll, bitte ein neues "
                        + "Registrierungsgeheimnis erzeugen.");
                c.status(403).json(Map.of("fehler", a.grund()));
            }
        });

        // Erzeugt ein Registrierungsgeheimnis. Nur für angemeldete Administratoren.
        app.post("/agenten/geheimnis", c -> {
            var angemeldet = angemeldeter(c);
            if (angemeldet == null || angemeldet.rolle() != Benutzerverwaltung.Rolle.ADMINISTRATOR) {
                c.status(403).result("Nur Administratoren dürfen Agenten einrichten.");
                return;
            }
            String bez = Anmeldeseiten.feld(c, "bezeichnung");
            String geheimnis = agenten.geheimnisAnlegen(
                    bez.isBlank() ? "Standort-Agent" : bez, "Praxis", angemeldet.anzeigename());
            protokoll.anfuegen("Praxis", null, Ereignisart.AGENT_GEHEIMNIS_ERZEUGT, true,
                    angemeldet.anzeigename(), null,
                    "Registrierungsgeheimnis für \"" + bez + "\" erzeugt", null);
            // Der Klartext erscheint genau einmal - danach ist nur der Hash gespeichert.
            c.html(geheimnisAnzeige(geheimnis));
        });

        app.start("0.0.0.0", port);
        System.out.println("""
                ────────────────────────────────────────────────────────────
                  PIN-Roboter · Zentrale
                  Oberfläche:  http://localhost:%d
                  Protokoll:   %d Einträge, %s von %s belegt
                ────────────────────────────────────────────────────────────
                """.formatted(port, speicher.zustand().eintraege(),
                        speicher.zustand().belegtLesbar(), speicher.zustand().budgetLesbar()));

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
            gruppen.geraetSicherstellen(k, "Praxis", String.valueOf(m.get("name")));
            protokoll.anfuegen("Praxis", k, Ereignisart.GERAET_ERKANNT, true, "Automatik", null,
                    "Kartenterminal erstmals erkannt", null);
            return neu;
        });

        PinStatus vorher = g.pinStatus;
        boolean vorherErreichbar = g.erreichbar;

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
            protokoll.anfuegen("Praxis", id, Ereignisart.PIN_STATUS_GEAENDERT, true, "Automatik", null,
                    "Status: %s → %s".formatted(vorher.anzeige(), g.pinStatus.anzeige()), null);
        }
        if (vorherErreichbar != g.erreichbar) {
            protokoll.anfuegen("Praxis", id,
                    g.erreichbar ? Ereignisart.GERAET_ERREICHBAR : Ereignisart.GERAET_NICHT_ERREICHBAR,
                    g.erreichbar, "Automatik", null,
                    g.erreichbar ? "Gerät wieder erreichbar" : "Gerät nicht erreichbar", null);
        }

        // Vom Agenten gemeldete Ereignisse übernehmen
        Object roh = m.get("ereignisse");
        if (roh instanceof List<?> liste) {
            for (Object o : liste) {
                if (o instanceof Map<?, ?> e) {
                    Ereignisart art;
                    try { art = Ereignisart.valueOf(String.valueOf(e.get("art"))); }
                    catch (Exception ex) { continue; }
                    protokoll.anfuegen("Praxis", id, art,
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

        pruefe(g.automatikAngehalten, o, Alarmverwaltung.Art.AUTOMATIK_ANGEHALTEN,
                "Die Automatik wurde für dieses Gerät angehalten.",
                g.handlung != null ? g.handlung
                        : "Bitte die hinterlegte PIN prüfen und die Automatik wieder freigeben.");

        pruefe(!g.erreichbar, o, Alarmverwaltung.Art.GERAET_NICHT_ERREICHBAR,
                "Das Kartenterminal antwortet nicht.",
                "Bitte prüfen, ob das Gerät eingeschaltet und mit dem Netzwerk verbunden ist.");

        pruefe(g.pinStatus == de.pinroboter.gemeinsam.PinStatus.BLOCKED, o,
                Alarmverwaltung.Art.KARTE_GESPERRT,
                "Die SMC-B ist gesperrt. Der TI-Zugang über dieses Gerät ist unterbrochen.",
                "Die Karte lässt sich nur mit der PUK entsperren. Ein weiterer PIN-Versuch hilft nicht.");

        pruefe(g.pinStatus.handlungsbedarf()
                        && g.pinStatus != de.pinroboter.gemeinsam.PinStatus.BLOCKED,
                o, Alarmverwaltung.Art.HANDLUNGSBEDARF_KARTE,
                g.pinStatus.erklaerung(),
                "Dieser Zustand lässt sich nur von Hand am Kartenterminal auflösen.");

        pruefe(g.erreichbar && g.pevimUnterstuetzt && !g.pevimAktiv, o,
                Alarmverwaltung.Art.PEVIM_NICHT_AKTIV,
                "Die automatische PIN-Eingabe ist an diesem Gerät nicht aktiviert.",
                "Ein Administrator muss die Funktion direkt am Kartenterminal freischalten. "
                + "Bis dahin wird das Gerät überwacht, aber nicht automatisch freigeschaltet.");
    }

    private void pruefe(boolean liegtVor, String objekt, Alarmverwaltung.Art art,
                        String meldung, String handlung) {
        if (liegtVor) {
            if (alarme.melden("Praxis", objekt, art, meldung, handlung)) {
                protokoll.anfuegen("Praxis", objekt, Ereignisart.ALARM_AUSGELOEST, false,
                        "Automatik", null, meldung, handlung);
            }
        } else if (alarme.beheben("Praxis", objekt, art)) {
            protokoll.anfuegen("Praxis", objekt, Ereignisart.ALARM_BEHOBEN, true,
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
            alarme.melden("Praxis", null, Alarmverwaltung.Art.SPEICHER_KNAPP,
                    "Das Protokoll belegt %s von %s (%d %%)."
                            .formatted(z.belegtLesbar(), z.budgetLesbar(), z.prozent()),
                    "Ältere Einträge werden automatisch gelöscht. Wenn Sie sie behalten "
                    + "möchten, exportieren Sie das Protokoll und erhöhen Sie das "
                    + "Speicherbudget in der Konfiguration.");
        } else if (z.anteil() < Speicherwaechter.SCHWELLE_ROTATION) {
            alarme.beheben("Praxis", null, Alarmverwaltung.Art.SPEICHER_KNAPP);
        }

        if (z.rotationNoetig()) {
            var e = speicher.rotieren();
            if (e.rotiert() && e.geloescht() > 0) {
                protokoll.anfuegen("Praxis", null, Ereignisart.PROTOKOLL_ROTIERT, true,
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
                        bestand.zugangsdatenMoeglich(), meldung, istFehler);
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
        var filter = new Bestandsverwaltung.Filter(c.queryParam("suche"), konnektor, aktiv,
                erreichbar, c.queryParam("modell"), c.queryParam("firmware"), null);
        var sortierung = Bestandsverwaltung.Sortierung.aus(c.queryParam("sortierung"));
        boolean absteigend = "ab".equals(c.queryParam("richtung"));

        return new Bestandsseiten.Ansicht(filter, sortierung, absteigend,
                bestand.vorhandeneWerte("modell", bereich(c)), bestand.vorhandeneWerte("firmware", bereich(c)),
                gruppen.alle("Praxis"), ermittlung.aufrufkontextAuswahl());
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

    /** Ein nicht angekreuztes Kästchen wird vom Browser gar nicht gesendet. */
    private static boolean angekreuzt(Context c, String name) {
        return c.formParam(name) != null;
    }

    private String verwaltungsSeite(String fehler, java.util.List<Demodaten.Konto> neueKonten) {
        var b = demodaten.bestand();
        StringBuilder s = new StringBuilder("""
                <h2>Verwaltung</h2>
                <p class="unterzeile">Werkzeuge für Administratoren.</p>
                """);
        if (fehler != null) s.append(Anmeldeseiten.meldung(fehler, "warnend"));

        s.append("<div class=\"tafel\"><h3>Demodatensatz</h3><div class=\"anmeldeinhalt\">");
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
                      <button class="knopf warnend" type="submit">Demodatensatz entfernen</button>
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
                              style="width:auto">Demodatensatz anlegen</button>
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
                <div class="anmeldeinhalt">
                  <p class="unterzeile">Legt einen Bestand in der Größe des Mengengerüsts an
                     (%d Kartenterminals), damit sich die Zielwerte aus NFA-LEI-010 auf
                     dieser Hardware nachmessen lassen — statt sie zu schätzen.</p>
                  <div class="hinweis">Alle erzeugten Datensätze sind als Demo gekennzeichnet
                     und lassen sich rückstandsfrei entfernen. Echte Bestandsdaten bleiben
                     unberührt.</div>
                  <div class="reihe" style="display:flex;gap:.6rem;flex-wrap:wrap">
                    <form method="post" action="/verwaltung/lasttest/anlegen">
                      <input type="hidden" name="anzahl" value="%d">
                      <button class="knopf" type="submit">%d Testgeräte anlegen</button>
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
                          <button class="knopf warnend" type="submit">Testbestand entfernen</button>
                        </form>"""));

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

    private String gruppenSeite(String fehler) {
        return """
                <h2>Gerätegruppen</h2>
                <p class="unterzeile">
                  Gruppen tragen das Prüfintervall — und später die Zeitfenster für
                  Firmware-Updates. Neu erkannte Geräte landen zunächst in der Vorgabegruppe.
                </p>
                %s
                <div class="tafel">
                  <h3>Neue Gruppe</h3>
                  <div class="filter">
                    <form method="post" action="/gruppen" style="display:flex;gap:.6rem;flex-wrap:wrap;align-items:flex-end">
                      <div><label for="bez">Bezeichnung</label>
                        <input id="bez" name="bezeichnung" type="text" required
                               placeholder="z. B. Notaufnahme"></div>
                      <div><label for="iv">Prüfintervall (Sekunden)</label>
                        <input id="iv" name="intervall" type="text" inputmode="numeric"
                               value="60" size="6"></div>
                      <button class="knopf" type="submit">Anlegen</button>
                    </form>
                  </div>
                </div>
                <div class="tafel"><h3>Gruppen</h3>
                  <div id="gruppen" hx-get="/teil/gruppen" hx-trigger="every 10s" hx-swap="innerHTML">%s</div>
                </div>"""
                .formatted(fehler == null ? "" : Anmeldeseiten.meldung(fehler, "warnend"),
                           gruppenTabelle());
    }

    private String gruppenTabelle() {
        var liste = gruppen.alle("Praxis");
        StringBuilder s = new StringBuilder("<div class=\"rollbar\"><table><thead><tr>"
                + "<th>Gruppe</th><th>Geräte</th><th>Prüfintervall</th><th></th>"
                + "</tr></thead><tbody>");
        for (var g : liste) {
            s.append("""
                    <tr><td class="geraet">%s%s</td><td>%d</td>
                    <td><form method="post" action="/gruppen/%d/intervall"
                              style="display:flex;gap:.4rem;align-items:center">
                          <input name="intervall" type="text" inputmode="numeric" size="5" value="%d">
                          <span class="feldhinweis">s</span>
                          <button class="knopf" type="submit">Speichern</button></form></td>
                    <td>%s</td></tr>"""
                    .formatted(esc(g.bezeichnung()),
                            g.istVorgabe() ? " <span class=\"marke unbekannt\">Vorgabe</span>" : "",
                            g.geraetezahl(), g.id(), g.pruefintervallSekunden(),
                            g.istVorgabe() ? "" :
                              ("<form method=\"post\" action=\"/gruppen/" + g.id() + "/loeschen\">"
                               + "<button class=\"knopf warnend\" type=\"submit\">Löschen</button></form>")));
        }
        return s.append("</tbody></table></div>").toString();
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

    private String uebersicht() {
        var alle = geraete.values();
        long ok = alle.stream().filter(g -> g.ampel() == Ampel.OK).count();
        long warn = alle.stream().filter(g -> g.ampel() == Ampel.WARNUNG).count();
        long fehler = alle.stream().filter(g -> g.ampel() == Ampel.FEHLER).count();
        long ohnePevim = alle.stream().filter(g -> !g.pevimAktiv).count();

        StringBuilder s = new StringBuilder();
        s.append(alarmblock());
        if (notAus) s.append("""
                <div class="hinweis" style="border-left-color:var(--fehler);background:var(--fehler-hg)">
                  <strong>Not-Aus ist aktiv</strong>
                  Die Automatik ruht. Es werden keine Karten freigeschaltet, bis der Not-Aus aufgehoben wird.
                </div>""");

        s.append("""
                <div class="lage">
                  <a class="kachel ok" href="/geraete"><div class="zahl">%d</div><div class="bez">Karten freigeschaltet</div></a>
                  <a class="kachel warnung" href="/geraete"><div class="zahl">%d</div><div class="bez">brauchen Aufmerksamkeit</div></a>
                  <a class="kachel fehler" href="/geraete"><div class="zahl">%d</div><div class="bez">Handlungsbedarf</div></a>
                  <a class="kachel unbekannt" href="/geraete"><div class="zahl">%d</div><div class="bez">ohne Automatik (PEviM)</div></a>
                </div>""".formatted(ok, warn, fehler, ohnePevim));

        var sp = speicher.zustand();
        if (sp.anteil() >= 0.5) {
            s.append("""
                    <div class="hinweis"><strong>Protokollspeicher: %d %% belegt</strong>
                    %s von %s. Ab 80 %% werden die ältesten Einträge automatisch gelöscht —
                    technische Diagnose zuerst, sicherheitsrelevante Ereignisse zuletzt.
                    </div>""".formatted(sp.prozent(), sp.belegtLesbar(), sp.budgetLesbar()));
        }

        var auffaellig = alle.stream()
                .filter(g -> g.ampel() != Ampel.OK)
                .sorted(Comparator.comparing(g -> g.terminalId)).toList();

        s.append("<div class=\"tafel\"><h3>Was zu tun ist <span class=\"zaehler\">")
         .append(auffaellig.isEmpty() ? "nichts offen" : auffaellig.size() + " Punkte").append("</span></h3>");
        if (auffaellig.isEmpty()) {
            s.append("<p class=\"leer\">Alle überwachten Karten sind freigeschaltet. Es ist nichts zu tun.</p>");
        } else {
            s.append("<div class=\"rollbar\"><table><thead><tr><th>Gerät</th><th>Zustand</th><th>Was zu tun ist</th></tr></thead><tbody>");
            for (Geraetezustand g : auffaellig) {
                s.append("""
                        <tr><td class="geraet">%s<br><span style="font-weight:400;color:var(--gedaempft);font-size:.85em">%s</span></td>
                        <td>%s</td><td>%s%s</td></tr>"""
                        .formatted(esc(anzeigename(g)), esc(g.terminalId), marke(g),
                                esc(g.hinweis != null ? g.hinweis : g.pinStatus.erklaerung()),
                                g.handlung == null ? "" : "<div class=\"handlung\">" + esc(g.handlung) + "</div>"));
            }
            s.append("</tbody></table></div>");
        }
        s.append("</div>");
        return s.toString();
    }

    /** Offene Alarme ganz oben — sie sind das Erste, was jemand sehen muss. */
    private String alarmblock() {
        var offen = alarme.offene("Praxis");
        if (offen.isEmpty()) return "";
        StringBuilder s = new StringBuilder(
                "<div class=\"tafel\"><h3>Alarme <span class=\"zaehler\">"
                + offen.size() + " offen</span></h3><div class=\"rollbar\"><table><tbody>");
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
                            a.quittiert()
                                ? "<span class=\"feldhinweis\">quittiert von "
                                  + esc(a.quittiertVon()) + "</span>"
                                : "<button class=\"knopf\" hx-post=\"/alarme/" + a.id()
                                  + "/quittieren\" hx-target=\"body\">Quittieren</button>"));
        }
        return s.append("</tbody></table></div></div>").toString();
    }

    private String geraeteSeite() {
        return """
                <h2>Kartenterminals</h2>
                <p class="unterzeile">Zustand aller überwachten Geräte. Aktualisiert sich selbsttätig.</p>
                <div class="tafel"><h3>Geräte <span class="zaehler"><span class="puls"></span> live</span></h3>
                <div id="geraete" hx-get="/teil/geraete" hx-trigger="load, every 3s" hx-swap="innerHTML">%s</div></div>
                """.formatted(geraeteTabelle());
    }

    private String geraeteTabelle() {
        if (geraete.isEmpty()) return "<p class=\"leer\">Noch keine Geräte gemeldet. "
                + "Sobald der Standort-Agent verbunden ist, erscheinen sie hier.</p>";
        StringBuilder s = new StringBuilder("<div class=\"rollbar\"><table><thead><tr>"
                + "<th>Gerät</th><th>Modell</th><th>Firmware</th><th>Zustand</th>"
                + "<th>Automatik</th><th>Versuche</th><th>geprüft</th></tr></thead><tbody>");
        geraete.values().stream().sorted(Comparator.comparing(g -> g.terminalId)).forEach(g -> s.append("""
                <tr><td class="geraet">%s<br><span style="font-weight:400;color:var(--gedaempft);font-size:.85em">%s</span></td>
                <td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td>
                <td style="font-variant-numeric:tabular-nums">%s</td><td class="zeit">%s</td></tr>"""
                .formatted(esc(anzeigename(g)), esc(g.terminalId), esc(nz(g.modell)), esc(nz(g.firmware)),
                        marke(g), automatikMarke(g),
                        g.verbleibendeVersuche < 0 ? "—" : String.valueOf(g.verbleibendeVersuche),
                        g.zuletztGeprueftText())));
        return s.append("</tbody></table></div>").toString();
    }

    private String protokollSeite(Context c) {
        StringBuilder arten = new StringBuilder("<option value=\"\">alle Ereignisarten</option>");
        for (Ereignisart a : Ereignisart.values())
            arten.append("<option value=\"%s\">%s</option>".formatted(a.name(), esc(a.anzeige())));
        return """
                <h2>Protokoll</h2>
                <p class="unterzeile">Jede Handlung des Roboters ist hier nachvollziehbar.
                   Protokolliert werden Zustandsänderungen und Handlungen — keine Abfragen.</p>
                <div class="tafel">
                  <div class="filter">
                    <label class="nurLeser" for="f-art">Ereignisart</label>
                    <select id="f-art" name="art" hx-get="/teil/protokoll" hx-target="#protokoll" hx-include=".filter">%s</select>
                    <label class="nurLeser" for="f-erg">Ergebnis</label>
                    <select id="f-erg" name="ergebnis" hx-get="/teil/protokoll" hx-target="#protokoll" hx-include=".filter">
                      <option value="">alle Ergebnisse</option><option value="erfolg">erfolgreich</option>
                      <option value="fehler">fehlgeschlagen</option></select>
                    <button class="knopf" hx-get="/teil/protokoll" hx-target="#protokoll" hx-include=".filter">Aktualisieren</button>
                    <a class="knopf" href="/protokoll/export" download>Als Excel herunterladen</a>
                    <!-- Traegt die Seitennummer durch jede Abfrage. Ohne sie
                         wuerde die Auffrischung alle 5 s auf Seite 1
                         zurueckspringen, sobald jemand zurueckblaettert. -->
                    <input type="hidden" id="f-seite" name="seite" value="1">
                  </div>
                  <div id="protokoll" hx-get="/teil/protokoll" hx-trigger="every 5s" hx-include=".filter" hx-swap="innerHTML">%s</div>
                </div>""".formatted(arten, protokollTabelle(c));
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
                c.queryParam("ergebnis"), nr, PROTOKOLL_SEITENGROESSE);

        // Hinter das Ende geblaettert - etwa weil ein Filter enger wurde.
        // Statt einer leeren Seite die letzte vorhandene zeigen.
        if (seite.eintraege().isEmpty() && seite.gesamt() > 0 && nr > seite.seitenZahl()) {
            seite = protokoll.seite(c.queryParam("geraet"), c.queryParam("art"),
                    c.queryParam("ergebnis"), seite.seitenZahl(), PROTOKOLL_SEITENGROESSE);
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
                <button class="knopf %s" %s
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
                                ? knopf.formatted("", "", seite.seitenNummer() - 1, "Neuere")
                                : knopf.formatted("gesperrt", "disabled", 1, "Neuere"),
                        seite.von(), seite.bis(), seite.gesamt(),
                        seite.seitenNummer(), seite.seitenZahl(),
                        seite.seitenNummer() > 1
                                ? " · neue Einträge erscheinen auf Seite 1" : "",
                        seite.hatNaechste()
                                ? knopf.formatted("", "", seite.seitenNummer() + 1, "Ältere")
                                : knopf.formatted("gesperrt", "disabled",
                                                  seite.seitenNummer(), "Ältere"));
    }

    // ---------------------------------------------------------------- Rahmen

    private String seite(String aktiv, String inhalt) {
        return seite(aktiv, inhalt, null);
    }

    private String seite(String aktiv, String inhalt, Benutzerverwaltung.Benutzer angemeldet) {
        String agentText = agentGesehen == null ? "kein Agent verbunden" : "Agent verbunden";
        String benutzerBlock = angemeldet == null ? "" : """
                <span class="angemeldet" title="Rolle: %s">%s</span>
                <button class="knopf" hx-post="/abmelden">Abmelden</button>"""
                .formatted(esc(angemeldet.rolle().anzeige()), esc(angemeldet.anzeigename()));
        return """
                <!doctype html><html lang="de"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>PIN-Roboter</title><link rel="stylesheet" href="/statisch/stil.css">
                <script src="/statisch/htmx.min.js" defer></script></head><body>
                <a href="#inhalt" class="nurLeser">Zum Inhalt springen</a>
                <header>
                  <h1>PIN-Roboter <span>· %s</span></h1>
                  <nav aria-label="Hauptnavigation">
                    <a href="/" %s>Übersicht</a>
                    <a href="/geraete" %s>Kartenterminals</a>
                    <a href="/bestand" %s>Bestand</a>
                    <a href="/gruppen" %s>Gruppen</a>
                    <a href="/verwaltung" %s>Verwaltung</a>
                    <a href="/protokoll" %s>Protokoll</a>
                    <button class="knopf %s" hx-post="/notaus"
                      title="Hält die Automatik sofort an">%s</button>
                    %s
                  </nav>
                </header>
                <main id="inhalt">
                  <div hx-get="/teil/uebersicht" hx-trigger="every 3s" hx-swap="innerHTML" %s>%s</div>
                </main></body></html>"""
                .formatted(esc(agentText),
                        aktiv.equals("uebersicht") ? "aria-current=\"page\"" : "",
                        aktiv.equals("geraete") ? "aria-current=\"page\"" : "",
                        aktiv.equals("bestand") ? "aria-current=\"page\"" : "",
                        aktiv.equals("gruppen") ? "aria-current=\"page\"" : "",
                        aktiv.equals("verwaltung") ? "aria-current=\"page\"" : "",
                        aktiv.equals("protokoll") ? "aria-current=\"page\"" : "",
                        notAus ? "" : "warnend", notAus ? "Not-Aus aufheben" : "Not-Aus",
                        benutzerBlock,
                        aktiv.equals("uebersicht") ? "" : "hx-disable", inhalt);
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
}
