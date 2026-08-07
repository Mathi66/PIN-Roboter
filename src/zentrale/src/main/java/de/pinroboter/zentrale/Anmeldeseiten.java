package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Totp;
import io.javalin.http.Context;

/**
 * Anmeldung, Ersteinrichtung und TOTP-Einrichtung als Oberfläche.
 *
 * <p>Diese Seiten sind die einzigen, die ohne Sitzung erreichbar sind. Der
 * Zugriffsschutz für alles Übrige sitzt in {@link Zentrale} an einer Stelle —
 * eine neue Seite ist damit von sich aus geschützt, nicht erst wenn jemand daran
 * denkt (dasselbe Prinzip wie bei der Mandantentrennung).
 */
public final class Anmeldeseiten {

    private Anmeldeseiten() {}

    /** Rahmen für alle Seiten vor der Anmeldung — bewusst schlicht und ohne Navigation. */
    static String rahmen(String titel, String inhalt) {
        return """
                <!doctype html><html lang="de"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s · PIN-Roboter</title><link rel="stylesheet" href="/statisch/stil.css">
                </head><body class="anmeldeseite">
                <main class="anmeldung">
                  <div class="tafel">
                    <h3>PIN-Roboter <span class="zaehler">%s</span></h3>
                    <div class="anmeldeinhalt">%s</div>
                  </div>
                </main></body></html>"""
                .formatted(Zentrale.esc(titel), Zentrale.esc(titel), inhalt);
    }

    static String meldung(String text, String art) {
        if (text == null || text.isBlank()) return "";
        return "<div class=\"hinweis %s\" role=\"alert\">%s</div>".formatted(art, Zentrale.esc(text));
    }

    // ---------------------------------------------------------------- Anmelden

    static String anmeldeformular(String fehler) {
        return rahmen("Anmelden", """
                %s
                <form method="post" action="/anmelden" autocomplete="on">
                  <label for="benutzer">Benutzername</label>
                  <input id="benutzer" name="benutzer" type="text" required autofocus
                         autocomplete="username" autocapitalize="none" spellcheck="false">

                  <label for="passwort">Passwort</label>
                  <input id="passwort" name="passwort" type="password" required
                         autocomplete="current-password">

                  <label for="code">Einmalkennwort aus Ihrer App</label>
                  <input id="code" name="code" type="text" required inputmode="numeric"
                         autocomplete="one-time-code" pattern="[0-9 ]{6,7}"
                         placeholder="6 Ziffern">

                  <button class="knopf haupt" type="submit">Anmelden</button>
                </form>
                """.formatted(meldung(fehler, "warnend")));
    }

    // ---------------------------------------------------------------- Ersteinrichtung

    /** FA-BEN-030: Bei leerem System führt ein Assistent durch die Ersteinrichtung. */
    static String ersteinrichtung(String fehler) {
        return rahmen("Ersteinrichtung", """
                <p class="unterzeile">
                  Dieses System wird zum ersten Mal verwendet. Bitte legen Sie das erste
                  Administratorkonto an. Danach ist keine Anmeldung ohne Konto mehr möglich.
                </p>
                %s
                <form method="post" action="/ersteinrichtung">
                  <label for="benutzer">Benutzername</label>
                  <input id="benutzer" name="benutzer" type="text" required autofocus
                         autocomplete="username" autocapitalize="none" spellcheck="false">

                  <label for="anzeigename">Name (wird im Protokoll angezeigt)</label>
                  <input id="anzeigename" name="anzeigename" type="text" required
                         autocomplete="name">

                  <label for="passwort">Passwort</label>
                  <input id="passwort" name="passwort" type="password" required
                         autocomplete="new-password" minlength="12">
                  <p class="feldhinweis">
                    Mindestens 12 Zeichen. Eine Folge mehrerer Wörter ist leichter zu merken
                    und sicherer als ein kurzes Passwort mit Sonderzeichen.
                  </p>

                  <button class="knopf haupt" type="submit">Konto anlegen</button>
                </form>
                """.formatted(meldung(fehler, "warnend")));
    }

    // ---------------------------------------------------------------- TOTP einrichten

    /**
     * Zweiter Faktor einrichten. Das Geheimnis wird in Vierergruppen dargestellt —
     * es wird abgetippt, und 32 Zeichen am Stück tippt niemand fehlerfrei ab.
     */
    static String totpEinrichten(long benutzerId, String uri, String geheimnis, String fehler) {
        StringBuilder gruppiert = new StringBuilder();
        for (int i = 0; i < geheimnis.length(); i += 4) {
            gruppiert.append(geheimnis, i, Math.min(i + 4, geheimnis.length())).append(' ');
        }
        return rahmen("Zweiten Faktor einrichten", """
                <p class="unterzeile">
                  Die Anmeldung verlangt zusätzlich zum Passwort ein Einmalkennwort.
                  Richten Sie dafür eine Authenticator-App auf Ihrem Telefon ein.
                </p>
                %s
                <ol class="schritte">
                  <li>Öffnen Sie Ihre Authenticator-App und legen Sie einen neuen Eintrag an.</li>
                  <li>Geben Sie diesen Schlüssel ein:
                    <div class="geheimnis"><code>%s</code></div>
                    <p class="feldhinweis">
                      Alternativ dieser Link, falls Ihre App ihn öffnen kann:
                      <a href="%s">Eintrag übernehmen</a>
                    </p>
                  </li>
                  <li>Tragen Sie den angezeigten sechsstelligen Code hier ein — damit wird
                      geprüft, ob die Einrichtung funktioniert hat.</li>
                </ol>
                <form method="post" action="/totp-bestaetigen">
                  <input type="hidden" name="benutzerId" value="%d">
                  <label for="code">Code aus der App</label>
                  <input id="code" name="code" type="text" required autofocus inputmode="numeric"
                         autocomplete="one-time-code" pattern="[0-9 ]{6,7}" placeholder="6 Ziffern">
                  <button class="knopf haupt" type="submit">Einrichtung abschließen</button>
                </form>
                <p class="feldhinweis">
                  Bewahren Sie den Schlüssel sicher auf. Geht das Telefon verloren, kann ein
                  anderer Administrator den zweiten Faktor zurücksetzen.
                </p>
                """.formatted(meldung(fehler, "warnend"),
                        Zentrale.esc(gruppiert.toString().trim()),
                        Zentrale.esc(uri), benutzerId));
    }

    /** Liest ein Formularfeld und gibt nie null zurück. */
    static String feld(Context c, String name) {
        String v = c.formParam(name);
        return v == null ? "" : v.trim();
    }

    static char[] passwortFeld(Context c, String name) {
        String v = c.formParam(name);
        return v == null ? new char[0] : v.toCharArray();
    }

    static String geheimnisAusUri(String uri) {
        int i = uri.indexOf("secret=");
        if (i < 0) return "";
        int e = uri.indexOf('&', i);
        return e < 0 ? uri.substring(i + 7) : uri.substring(i + 7, e);
    }

    static boolean codeGueltig(byte[] geheimnis, String code) {
        return Totp.pruefe(geheimnis, code, java.time.Instant.now());
    }
}
