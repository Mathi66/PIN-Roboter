package de.pinroboter.zentrale;

import java.util.List;

/**
 * Die Kapitel des Handbuchs.
 *
 * <h2>Warum der Inhalt im Quelltext steht</h2>
 *
 * Er wird mit derselben Auslieferung transportiert wie das Verhalten, das er
 * beschreibt. Ein Handbuch in einer separaten Datei — oder gar in einem
 * Wiki — beschreibt nach der ersten Aktualisierung eine andere Software als
 * die, die läuft.
 *
 * <h2>Zum Ton</h2>
 *
 * Geschrieben für jemanden, der in einer Praxis arbeitet und keine Zeit hat.
 * Kurze Sätze, das Wichtige zuerst, jeder Fachbegriff bei der ersten
 * Verwendung erklärt. Wo eine Entscheidung erklärungsbedürftig ist, steht das
 * <b>Warum</b> dabei — nicht aus Freude an der Begründung, sondern weil man
 * eine Regel nur dann richtig anwendet, wenn man ihren Zweck kennt.
 *
 * <p>Die Grafiken sind eingebettete Vektorzeichnungen in den Farben der
 * Oberfläche. Kein Bild wird nachgeladen: Der Agent und die Zentrale stehen in
 * Netzen ohne Internetzugang, und ein fehlendes Bild ist schlimmer als keines.
 */
final class HandbuchInhalt {

    private HandbuchInhalt() { }

    static void fuellen(List<Handbuch.Kapitel> ziel) {
        var T = Handbuch.Teil.VERSTEHEN;
        var B = Handbuch.Teil.BEDIENEN;
        var O = Handbuch.Teil.BETREIBEN;

        ziel.add(new Handbuch.Kapitel("ueberblick", "Der PIN-Roboter",
                "Was das Produkt löst — in fünf Minuten", T, "🎯", UEBERBLICK));
        ziel.add(new Handbuch.Kapitel("verfahren", "Wie die Freischaltung funktioniert",
                "PEviM, der Kreislauf, die Grenzen", T, "🔄", VERFAHREN));
        ziel.add(new Handbuch.Kapitel("aufbau", "Aufbau des Systems",
                "Zentrale, Standort-Agent, Betriebsarten", T, "🏗", AUFBAU));
        ziel.add(new Handbuch.Kapitel("organisation", "Organisation und Mandanten",
                "Serviceorganisation, Einrichtung, Geräte", T, "🏢", ORGANISATION));
        ziel.add(new Handbuch.Kapitel("sicherheit", "Sicherheit",
                "Wo die PIN liegt und wer sie sehen kann", T, "🔐", SICHERHEIT));

        ziel.add(new Handbuch.Kapitel("erste-schritte", "Die ersten fünf Minuten",
                "Anmelden, Lage lesen, handeln", B, "🚀", ERSTE_SCHRITTE));
        ziel.add(new Handbuch.Kapitel("lage", "Die Lage-Übersicht",
                "Was die Zahlen bedeuten", B, "📊", LAGE));
        ziel.add(new Handbuch.Kapitel("alarme", "Alarme",
                "Erkennen, einordnen, schließen", B, "🔔", ALARME));
        ziel.add(new Handbuch.Kapitel("pins", "PINs hinterlegen",
                "Der Weg der PIN, Schritt für Schritt", B, "🔑", PINS));
        ziel.add(new Handbuch.Kapitel("gruppen", "Gerätegruppen und Prüfintervalle",
                "Last steuern, Standorte trennen", B, "🗂", GRUPPEN));
        ziel.add(new Handbuch.Kapitel("protokoll", "Protokoll und Nachweise",
                "Nachvollziehen, filtern, exportieren", B, "📜", PROTOKOLL));

        ziel.add(new Handbuch.Kapitel("inbetriebnahme", "Inbetriebnahme",
                "Von der leeren Instanz zum laufenden Betrieb", O, "⚙", INBETRIEBNAHME));
        ziel.add(new Handbuch.Kapitel("bestand", "Bestand pflegen",
                "Konnektoren, Kartenterminals, Firmware", O, "🗄", BESTAND));
        ziel.add(new Handbuch.Kapitel("agenten", "Standort-Agenten betreiben",
                "Einrichten, überwachen, aktualisieren", O, "📡", AGENTEN));
        ziel.add(new Handbuch.Kapitel("konten", "Konten und Rollen",
                "Wer darf was — und wie jemand dazukommt", O, "👥", KONTEN));
        ziel.add(new Handbuch.Kapitel("sicherung", "Datensicherung",
                "Sichern, prüfen, wiederherstellen", O, "💾", SICHERUNG));
        ziel.add(new Handbuch.Kapitel("stoerungen", "Störungen beheben",
                "Die häufigsten Fälle und ihre Ursachen", O, "🔧", STOERUNGEN));
        ziel.add(new Handbuch.Kapitel("begriffe", "Begriffe",
                "Nachschlagen, was ein Wort bedeutet", O, "📖", BEGRIFFE));
    }

    // ═══════════════════════════════════════════════════════ Teil 1: Verstehen

    private static final String UEBERBLICK = """
            <p class="anriss">Eine Praxis ohne freigeschaltete SMC-B ist von der
            Telematikinfrastruktur abgeschnitten. Kein Versichertenstammdatenabgleich,
            kein elektronisches Rezept, keine Kommunikation im Medizinwesen. Der
            PIN-Roboter sorgt dafür, dass dieser Zustand gar nicht erst eintritt.</p>

            <h2>Das Problem, das jeder kennt, der es einmal hatte</h2>

            <p>Die SMC-B ist der Praxisausweis in Kartenform. Sie steckt im
            Kartenterminal und muss <strong>freigeschaltet</strong> sein — durch die
            Eingabe einer PIN. Solange sie das ist, funktioniert alles.</p>

            <p>Nur bleibt sie es nicht. Ein Stromausfall, ein Neustart des Terminals,
            eine Firmware-Aktualisierung, ein versehentlich gezogenes Netzkabel — und
            die Karte ist wieder gesperrt. Niemand merkt es. Bemerkt wird es erst,
            wenn der erste Patient am Tresen steht und die Karte nicht gelesen wird.</p>

            <p>Dann beginnt die Suche: <em>Welches Terminal?</em> Jemand geht durch die
            Räume, tippt die PIN an sechs Geräten ein, findet das siebte in einem
            Behandlungszimmer, in dem gerade behandelt wird. In einer Klinik mit
            fünfhundert Terminals ist dieser Weg keine Option mehr.</p>

            <div class="hb-zahlen">
              <div><b>3</b><span>Kartenterminals in der kleinsten Praxis</span></div>
              <div><b>5.000</b><span>im größten unterstützten Klinikverbund</span></div>
              <div><b>0</b><span>Wege durchs Haus, wenn es funktioniert</span></div>
            </div>

            <h2>Was der PIN-Roboter tut</h2>

            <p>Er überwacht jedes Kartenterminal und stellt den freigeschalteten
            Zustand <strong>selbsttätig wieder her</strong>. Fällt eine Karte zurück,
            übergibt er die hinterlegte PIN über die Verwaltungsschnittstelle des
            Terminals — in dem Moment, in dem es passiert, und nicht, wenn es jemand
            bemerkt.</p>

            %s

            <h2>Was er ausdrücklich nicht tut</h2>

            <table class="hb-tabelle">
              <tbody>
              <tr><td>Patientendaten verarbeiten</td>
                  <td>Er sieht keine. Der PIN-Roboter kennt Geräte, Karten und
                      Zustände — keine Person, keine Diagnose, keinen Befund.</td></tr>
              <tr><td>eGK oder HBA freischalten</td>
                  <td>Das Verfahren ist ausschließlich für die SMC-B zugelassen. Die
                      Karte eines Menschen automatisch freizuschalten wäre das
                      Gegenteil von dem, wofür eine PIN da ist.</td></tr>
              <tr><td>Eine gesperrte Karte entsperren</td>
                  <td>Ist die Karte nach zu vielen Fehlversuchen gesperrt, hilft nur
                      die PUK — von Hand, durch einen Menschen. Der Roboter erkennt
                      den Zustand und hört auf, es zu versuchen.</td></tr>
              <tr><td>Ein Medizinprodukt sein</td>
                  <td>Reine Organisationssoftware. Kein MDR-Verfahren, keine
                      Zweckbestimmung im medizinischen Sinn.</td></tr>
              </tbody>
            </table>

            <h2>Für wen</h2>

            <div class="hb-karten">
              <div class="hb-karte">
                <h3>Einzelpraxis</h3>
                <p>Ein Rechner, ein Konnektor, eine Handvoll Terminals. Installation
                   über <code>docker compose up</code>, danach läuft es. Vier
                   Administratoren, etwa 25 Benutzer — das ist die typische
                   Größe.</p>
              </div>
              <div class="hb-karte">
                <h3>MVZ und Verbund</h3>
                <p>Mehrere Standorte unter einem Dach, jeder mit eigenen Geräten. Eine
                   Zentrale, je Standort ein Agent. Die Sicht bleibt getrennt, die
                   Verwaltung zusammen.</p>
              </div>
              <div class="hb-karte">
                <h3>Klinik und Rechenzentrum</h3>
                <p>Bis zu 1.000 Konnektoren, 5.000 Terminals, 150 Administratoren.
                   Serviceorganisationen betreuen darin einzelne Einrichtungen, ohne
                   die Daten der anderen zu sehen.</p>
              </div>
            </div>

            <h2>Die Grundhaltung</h2>

            <p>Ein Roboter, der selbsttätig mit einer PIN hantiert, muss sich
            <strong>rechtfertigen können</strong>. Jede Handlung steht im Protokoll:
            wann, an welchem Gerät, mit welchem Ergebnis. Nichts geschieht unsichtbar,
            und nichts lässt sich nachträglich ändern — das Protokoll ist technisch
            nur anfügbar.</p>

            <p>Und er kennt seine Grenze: <strong>Bleibt nur noch ein Versuch übrig,
            hört er auf.</strong> Lieber eine Karte, die jemand von Hand freischaltet,
            als eine gesperrte Karte und eine Praxis ohne Anbindung.</p>
            """.formatted(Handbuchgrafik.KREISLAUF);

    private static final String VERFAHREN = """
            <p class="anriss">Das Verfahren heißt <b>PEviM</b> — „PIN-Eingabe via
            Managementschnittstelle“. Es ist von der gematik spezifiziert, nicht von
            uns erfunden, und ausdrücklich nur für die SMC-B zugelassen.</p>

            <h2>Der Kreislauf</h2>

            <p>Der Agent fragt in einem festen Takt — im Auslieferungszustand alle 60
            Sekunden — den Konnektor nach dem Zustand jeder Karte. Fünf Antworten sind
            möglich:</p>

            <table class="hb-tabelle">
              <thead><tr><th>Zustand</th><th>Bedeutung</th><th>Was geschieht</th></tr></thead>
              <tbody>
              <tr><td><span class="marke ok">VERIFIED</span></td>
                  <td>Die Karte ist freigeschaltet.</td>
                  <td>Nichts. Genau so soll es sein.</td></tr>
              <tr><td><span class="marke warnung">VERIFIABLE</span></td>
                  <td>Sie ist bereit, aber nicht freigeschaltet.</td>
                  <td>Der Roboter übergibt die PIN.</td></tr>
              <tr><td><span class="marke fehler">BLOCKED</span></td>
                  <td>Gesperrt nach zu vielen Fehlversuchen.</td>
                  <td>Nichts — und ein kritischer Alarm. Nur die PUK hilft.</td></tr>
              <tr><td><span class="marke warnung">TRANSPORT_PIN</span></td>
                  <td>Die Karte trägt noch ihre Transport-PIN.</td>
                  <td>Nichts. Das muss ein Mensch am Gerät erledigen.</td></tr>
              <tr><td><span class="marke unbekannt">EMPTY_PIN</span></td>
                  <td>Es ist keine PIN gesetzt.</td>
                  <td>Nichts. Auch das ist ein Fall für einen Menschen.</td></tr>
              </tbody>
            </table>

            %s

            <h2>Die wichtigste Regel: Der Fehlversuchszähler</h2>

            <div class="hb-merke">
              <b>Vor jedem Versuch wird geprüft, wie viele Versuche noch übrig sind.
              Bleibt nur noch einer, bricht der Roboter ab.</b>
              <p>Eine SMC-B sperrt sich nach drei Fehlversuchen. Ein Roboter, der es
              alle 60 Sekunden mit einer falschen PIN versucht, hätte die Karte nach
              drei Minuten unbrauchbar gemacht — und die Praxis für den Rest des Tages
              von der TI getrennt. Der letzte Versuch gehört einem Menschen, der die
              PIN nachschlagen kann.</p>
            </div>

            <h2>Wenn der Konnektor gerade aktualisiert</h2>

            <p>Konnektoren spielen von sich aus neue Firmware auf ihre Kartenterminals.
            Während dieser Zeit meldet der Kartenterminaldienst den Zustand
            <strong>„aktualisierend“</strong>. Der PIN-Roboter erkennt ihn und
            <strong>versucht in dieser Zeit nichts</strong>. Ein PIN-Versuch gegen ein
            Terminal mitten im Update könnte im ungünstigen Fall als Fehlversuch
            zählen.</p>

            <h2>Nur die SMC-B</h2>
            <section data-hilfe="nur-smcb">
            <p>Die PIN-Automatik ist <b>ausschließlich für SMC-B</b> zulässig (gematik
            <code>A_24080</code>). Beim Erfassen eines Kartenterminals wird deshalb
            angegeben, ob dort eine SMC-B steckt.</p>

            <p>Für Terminals ohne SMC-B bleibt die Automatik abgewählt. Diese Geräte
            werden <b>erfasst und überwacht</b> — Erreichbarkeit, Firmware, Zustand —,
            aber nie verifiziert.</p>

            <div class="hb-merke">
              <b>Das ist keine technische Grenze, sondern eine gewollte.</b>
              <p>Für eGK und HBA wäre eine automatische Freischaltung das Gegenteil
              dessen, wofür eine PIN da ist: Sie gehören einem Menschen, der
              anwesend sein soll, wenn seine Karte benutzt wird.</p>
            </div>
            </section>

            <h2>Was das Verfahren voraussetzt</h2>

            <ul class="hb-liste">
              <li><b>Das Terminal muss PEviM unterstützen</b> — eine Frage der
                  Firmware. Ältere Modelle können es nicht.</li>
              <li><b>Es muss dort freigeschaltet sein.</b> Die Funktion ist im
                  Auslieferungszustand aus und wird am Gerät selbst aktiviert. Der
                  PIN-Roboter zeigt an, wo das noch fehlt — machen kann er es
                  nicht.</li>
              <li><b>Die Karte muss dem Terminal zugeordnet sein</b> und der Konnektor
                  sie kennen.</li>
            </ul>

            <p>Fehlt eine dieser Voraussetzungen, wird das Gerät trotzdem
            <em>überwacht</em>. Sie sehen dann jederzeit, dass dort etwas fehlt — und
            was. Ein Gerät stillschweigend auszulassen wäre die schlechtere
            Antwort.</p>
            """.formatted(Handbuchgrafik.ZUSTAENDE);

    private static final String AUFBAU = """
            <p class="anriss">Zwei Bausteine: die <b>Zentrale</b> mit der Oberfläche
            und den Daten, und der <b>Standort-Agent</b>, der dort steht, wo die Geräte
            stehen. Dieselbe Software läuft in der Einzelpraxis wie im
            Klinikverbund.</p>

            %s

            <h2>Warum überhaupt zwei Teile</h2>

            <p>Der Konnektor und die Kartenterminals stehen im Praxisnetz. Dort kommt
            man von außen nicht hin — und das soll auch so bleiben. Gleichzeitig soll
            ein Dienstleister dreißig Praxen aus einer Oberfläche betreuen können.</p>

            <p>Die Lösung ist eine Richtungsentscheidung: <strong>Der Agent baut die
            Verbindung auf, nie die Zentrale.</strong> Damit ist in der
            Praxis-Firewall <strong>keine eingehende Freigabe nötig</strong> — der
            häufigste Grund, warum eine Installation an der IT-Abteilung scheitert,
            entfällt.</p>

            <h2>Was passiert, wenn die Verbindung abreißt</h2>

            <div class="hb-merke">
              <b>Der Agent arbeitet weiter.</b>
              <p>Er hat die PINs, die er braucht, verschlüsselt bei sich liegen. Fällt
              die Zentrale aus oder ist die Leitung tot, überwacht und verifiziert er
              unverändert weiter. Was fehlt, ist die Sicht darauf — nicht die
              Funktion. Für eine Praxis ist das der entscheidende Unterschied.</p>
            </div>

            <h2>Zwei Betriebsarten, eine Software</h2>

            <div class="hb-karten">
              <div class="hb-karte">
                <h3>Verteilt</h3>
                <p>Eine Instanz je Einrichtung, im eigenen Netz. Alles bleibt im Haus.
                   Typisch für die Einzelpraxis und für Häuser, die keine Daten
                   außerhalb wollen.</p>
              </div>
              <div class="hb-karte">
                <h3>Zentral</h3>
                <p>Eine Instanz je Serviceorganisation, viele Standorte daran. Ein
                   Dienstleister sieht alle betreuten Einrichtungen in einer
                   Oberfläche — getrennt nach Mandant.</p>
              </div>
            </div>

            <h2>Wie ausgeliefert wird</h2>

            <p>Als Docker-Container, unter Linux, Windows und macOS, für
            <code>amd64</code> und <code>arm64</code>. Die Installation ist ein
            Befehl. Es gibt keine Nachinstallationsschritte beim Kunden, keine
            Datenbank, die jemand von Hand anlegen müsste, und keine Migration, die
            jemand von Hand starten muss.</p>

            <table class="hb-tabelle">
              <tbody>
              <tr><td>Zentrale</td><td>Java 21 mit eingebettetem Webserver,
                  PostgreSQL, serverseitig gerendertes HTML</td></tr>
              <tr><td>Standort-Agent</td><td>Java 21, eingebettete Ablage, eigene
                  Oberfläche nur auf dem lokalen Rechner</td></tr>
              <tr><td>Verschlüsselung</td><td>TLS 1.2 mit Brainpool-Kurven über
                  BouncyCastle — dieselbe Bibliothek, die auch Konnektor und
                  Hochleistungskonnektor verwenden</td></tr>
              </tbody>
            </table>

            <p class="hb-fussnote">Die Wahl fiel auf Java, weil die Geräteidentitäten
            von Konnektor und Kartenterminal von Chipkarten stammen und auf die Kurve
            <code>brainpoolP256r1</code> festgelegt sind. Andere Umgebungen scheitern
            gemessen bereits am Einlesen solcher Zertifikate oder an den Kurvengruppen
            im Handschlag. Java mit BouncyCastle funktioniert.</p>
            """.formatted(Handbuchgrafik.TOPOLOGIE);

    private static final String ORGANISATION = """
            <p class="anriss">Bei tausend Benutzern und hundertfünfzig Administratoren
            ist eine flache Liste nicht mehr bedienbar. Der PIN-Roboter ordnet deshalb
            alles in eine Hierarchie — und trennt danach, wer was sehen darf.</p>

            %s

            <h2>Die vier Ebenen</h2>

            <table class="hb-tabelle">
              <tbody>
              <tr><td><b>Instanz</b></td><td>Die Installation als Ganzes. Sie gehört
                  dem Betreiber.</td></tr>
              <tr><td><b>Serviceorganisation</b></td><td>Ein IT-Dienstleister, der
                  mehrere Einrichtungen betreut. In der Einzelpraxis gibt es genau
                  eine, und niemand denkt darüber nach.</td></tr>
              <tr><td><b>Einrichtung</b></td><td>Eine Praxis, ein MVZ, ein Standort,
                  eine Klinik. Hier hängen die Geräte, hier arbeiten die Menschen.</td></tr>
              <tr><td><b>Gerät</b></td><td>Konnektor beziehungsweise Hochleistungs­konnektor,
                  darunter die Kartenterminals mit ihren Karten.</td></tr>
              </tbody>
            </table>

            <h2>Warum das von Anfang an drin ist</h2>

            <p>Auch dann, wenn die erste Installation nur eine Praxis hat. Die
            Trennung von Mandanten nachzurüsten ist unverhältnismäßig teuer — und
            gefährlich: Eine Abfrage, die vergessen wurde einzuschränken, zeigt die
            Daten eines fremden Hauses. Das ist die häufigste schwere Sicherheitslücke
            in mandantenfähiger Software.</p>

            <div class="hb-merke">
              <b>Die Trennung wird zentral erzwungen, nicht in jeder Abfrage von
              Hand.</b>
              <p>Jede Datenabfrage bekommt den Geltungsbereich des Aufrufers als
              Pflichtangabe. Wer sie vergisst, bekommt keinen leeren Filter, sondern
              gar keine Ergebnisse — der Fehler fällt sofort auf, statt still zu viel
              zu zeigen.</p>
            </div>

            <h2>Nicht zu verwechseln: der Mandant des Konnektors</h2>

            <p>Der Konnektor kennt selbst Mandanten (<code>MandantId</code>,
            <code>ClientSystemId</code>, <code>WorkplaceId</code>). Diese sind
            <strong>nicht dasselbe</strong> wie die Einrichtungen des PIN-Roboters. Die
            Zuordnung wird ausdrücklich hinterlegt, nicht stillschweigend
            gleichgesetzt. In der Praxis fallen beide oft zusammen — verlassen sollte
            man sich nicht darauf.</p>

            <h2>Welche Ebene wofür</h2>
            <section data-hilfe="organisationsebenen">
            <p>Die Struktur ist frei aufbaubar — aber nur <b>eine</b> Ebene trägt
            fachliche Bedeutung.</p>

            <table class="hb-tabelle">
              <thead><tr><th>Ebene</th><th>Wofür</th></tr></thead>
              <tbody>
              <tr><td><b>Betriebsstätte</b></td>
                  <td>Die Leistungserbringerinstitution: Sie trägt BSNR, SMC-B und
                      Telematik-ID. <b>Nur hier</b> hängen Geräte und Karten.</td></tr>
              <tr><td>Trägergesellschaft</td>
                  <td>Wem das Ganze gehört. Freiwillig.</td></tr>
              <tr><td>Standort</td>
                  <td>Fasst mehrere Betriebsstätten räumlich zusammen. Freiwillig.</td></tr>
              <tr><td>Leistungserbringer</td>
                  <td>Das zugelassene MVZ als Ganzes. Freiwillig.</td></tr>
              <tr><td>Fachbereich</td>
                  <td>Gliedert eine große Betriebsstätte. Steht <b>nur unterhalb von
                      ihr</b>; darunter lässt sich nichts mehr anlegen.</td></tr>
              </tbody>
            </table>

            <div class="hb-merke">
              <b>Eine Einzelpraxis braucht zwei Ebenen.</b>
              <p>Trägergesellschaft und Betriebsstätte — mehr nicht. Die übrigen Ebenen
              sind für Häuser gedacht, die sie wirklich haben. Wer eine Struktur baut,
              die es in der Wirklichkeit nicht gibt, pflegt sie hinterher.</p>
            </div>
            </section>

            <h2>Bezeichnungen müssen eindeutig sein</h2>
            <section data-hilfe="bezeichnung-eindeutig">
            <p>Der Name einer Einrichtung muss instanzweit eindeutig sein — auch
            gegenüber Einrichtungen, die jemand anders betreut.</p>

            <div class="hb-merke">
              <b>Der Grund steht im Protokoll.</b>
              <p>Dort erscheint die Bezeichnung ohne die Spalte daneben, aus der
              hervorginge, um welches Haus es geht. Wer im Nachhinein einen Vorgang
              zuordnen will, hat dann zwei Zeilen „Notaufnahme“ und keine Möglichkeit,
              sie zu unterscheiden. Dasselbe gilt für Auswahlfelder.</p>
            </div>

            <p>Ergänzen Sie deshalb eine Kennzeichnung des Hauses:
            <b>„Notaufnahme Nordstadt“</b> statt „Notaufnahme“. Ein doppelter Name
            wird abgelehnt; die Meldung sagt, wie er zu ergänzen ist — <em>ohne</em> zu
            verraten, wo er schon vorkommt. Eine Mandantengrenze, die man mit einem
            Formular ausprobieren kann, ist keine.</p>

            <p>Für <b>Gerätegruppen</b> gilt dieselbe Regel aus demselben Grund.</p>
            </section>

            <h2>Die Betriebsstätte</h2>
            <section data-hilfe="betriebsstaette">
            <p>Eine Einrichtung kann als <b>Betriebsstätte</b> gekennzeichnet sein. Das
            heißt: Sie ist die Leistungserbringerinstitution — sie führt eine
            <b>eigene SMC-B</b> und tritt gegenüber der Telematikinfrastruktur unter
            eigenem Namen auf.</p>
            <table class="hb-tabelle">
              <tbody>
              <tr><td><b>BSNR</b></td><td>Die Betriebsstättennummer aus der
                  vertragsärztlichen Versorgung.</td></tr>
              <tr><td><b>Telematik-ID</b></td><td>Die Kennung der SMC-B in der
                  Telematikinfrastruktur. Über sie ordnet der PIN-Roboter eine
                  gefundene Karte der richtigen Einrichtung zu.</td></tr>
              </tbody>
            </table>
            <p>Alle anderen Ebenen — Serviceorganisation, Verbund, Standort ohne eigene
            Karte — sind reine <b>Gliederung</b>. Sie tragen keine SMC-B und keine
            Telematik-ID.</p>

            <p><b>Beide Angaben sind freiwillig.</b> Liegen sie noch nicht vor, lassen
            sie sich später nachtragen; die Einrichtung funktioniert auch ohne sie. Die
            BSNR ist mit der zuständigen Kassenärztlichen Vereinigung abzustimmen.</p>
            <div class="hb-merke">
              <b>Warum die Unterscheidung zählt.</b>
              <p>Ohne sie ließe sich eine gefundene SMC-B keiner Einrichtung sicher
              zuordnen — und in einem Verbund mit mehreren Karten wüsste niemand,
              welche PIN zu welcher gehört.</p>
            </div>
            </section>

            <h2>Eine Einheit ändern oder umhängen</h2>
            <section data-hilfe="einheit-aendern">
            <p>Bezeichnung, Art und die übergeordnete Einheit lassen sich ändern.
            Zwei Regeln begrenzen das (ADR-0012):</p>

            <ul class="hb-liste">
              <li>Eine Einheit lässt sich <b>nicht unter sich selbst</b> hängen — auch
                  nicht über Umwege. Sonst entstünde ein Kreis, in dem keine Abfrage
                  mehr endet.</li>
              <li>Ein <b>Fachbereich</b> steht nur unterhalb einer Betriebsstätte.</li>
            </ul>

            <div class="hb-merke">
              <b>Was am Namen hängt, hängt am Text.</b>
              <p>Standort-Agenten merken sich ihre Einrichtung als Namen, nicht als
              Verweis. Wird eine Einrichtung umbenannt, meldet der Agent ins Leere —
              die Agentenliste kennzeichnet das als „Einrichtung unbekannt“, und die
              Zuordnung lässt sich dort in einem Zug korrigieren.</p>
            </div>
            </section>

            <h2>Was zu einer Einheit gehört</h2>
            <section data-hilfe="einheit-inhalt">
            <p>Die Detailseite einer Einheit zeigt, was an ihr hängt: untergeordnete
            Einheiten, Konnektoren, Kartenterminals, Benutzerkonten.</p>

            <p>Das ist die Antwort auf die Frage <b>„was passiert, wenn ich das
            lösche?“</b> — und der Grund, warum eine Einheit mit Inhalt nicht
            gelöscht werden kann, ohne dass klar ist, was mit dem Inhalt geschieht.</p>

            <p>Bei einer Betriebsstätte stehen hier zusätzlich BSNR und Telematik-ID.</p>
            </section>

            <h2>Rollen</h2>

            <table class="hb-tabelle">
              <thead><tr><th>Rolle</th><th>Darf</th></tr></thead>
              <tbody>
              <tr><td>Betrachter</td><td>Zustand sehen, Protokoll lesen</td></tr>
              <tr><td>Anwender</td><td>zusätzlich Alarme quittieren und schließen</td></tr>
              <tr><td>Bestandspflege</td><td>zusätzlich Geräte, Gruppen und PINs
                  pflegen</td></tr>
              <tr><td>Administrator</td><td>zusätzlich Konten, Organisation und die
                  Instanz verwalten</td></tr>
              </tbody>
            </table>

            <p>Jede Rolle gilt <strong>innerhalb</strong> eines Geltungsbereichs. Ein
            Administrator einer Praxis ist kein Administrator der Nachbarpraxis.</p>
            """.formatted(Handbuchgrafik.HIERARCHIE);

    private static final String SICHERHEIT = """
            <p class="anriss">Der PIN-Roboter muss die PIN der SMC-B vorhalten. Anders
            geht es nicht — und genau deshalb ist dieses Kapitel das wichtigste des
            Handbuchs.</p>

            <h2>Was die Vorschrift verlangt</h2>

            <p>Die gematik erlaubt das Vorhalten der PIN nur für Clients, deren
            Sicherheit die betreibende Organisation durchsetzen kann. Daraus folgen
            Regeln, die nicht verhandelbar sind:</p>

            <ul class="hb-liste">
              <li><b>Keine PIN im Klartext auf einem Datenträger</b> — nirgends, zu
                  keinem Zeitpunkt.</li>
              <li><b>Keine PIN in Protokollen, Sicherungen oder Messwerten.</b> Das
                  wird zentral erzwungen, nicht durch Sorgfalt an jeder Einzelstelle.</li>
              <li><b>Verschlüsselte Übertragung</b> mit geprüften Zertifikaten.</li>
              <li><b>Getrennte Rollen</b> und ein unveränderliches Protokoll.</li>
              <li><b>Ein wirksamer Not-Aus.</b></li>
            </ul>

            <h2>Der Weg der PIN</h2>

            %s

            <p>Die PIN wird <strong>im Browser verschlüsselt</strong>, bevor sie das
            Gerät verlässt — mit dem öffentlichen Schlüssel genau des Agenten, für den
            sie bestimmt ist. Die Zentrale sieht ein Chiffrat und reicht es weiter. Sie
            könnte es nicht entschlüsseln, selbst wenn sie wollte: Der passende
            private Schlüssel liegt ausschließlich beim Agenten.</p>

            <div class="hb-merke">
              <b>Wer die Datenbank der Zentrale stiehlt, hat keine einzige PIN.</b>
              <p>Das ist der Zweck dieser Konstruktion. Eine Sicherung, ein
              Datenbankabzug, ein kompromittierter Server der Zentrale — in keinem
              dieser Fälle liegt eine verwertbare PIN vor.</p>
            </div>

            <h2>Beim Agenten</h2>

            <p>Dort wird sie entschlüsselt und in einer verschlüsselten Ablage
            gespeichert, damit sie einen Neustart übersteht. Der Schlüssel dafür steht
            in der Konfiguration des Standorts, nicht im Programm. Fehlt er, bleibt die
            PIN nur im Arbeitsspeicher — dann muss sie nach jedem Neustart neu
            hinterlegt werden. Der Agent sagt das in seiner Prüfliste deutlich.</p>

            <h2>Der Not-Aus</h2>

            <p>Ein Knopf, immer erreichbar, unten in der Seitenleiste. Er hält die
            Automatik <strong>sofort und überall</strong> an. Kein Agent verifiziert
            mehr, bis jemand ihn wieder freigibt. Gedacht für den Fall, in dem etwas
            nicht stimmt und man erst verstehen will, was.</p>

            <h2>Anmeldung</h2>

            <p>Benutzername, Passwort und ein <strong>zeitbasiertes Einmalkennwort
            (TOTP)</strong> aus einer Authenticator-App. Der zweite Faktor ist
            verpflichtend und nicht abschaltbar. Ein bereits benutzter Code wird
            abgelehnt; nach fünf Fehlversuchen sperrt das Konto.</p>

            <h2>Was wir nicht behaupten</h2>

            <p class="hb-fussnote">Die PIN liegt am Ende auf einem Rechner in der
            Praxis — anders könnte der Agent sie nicht übergeben. Was diese
            Konstruktion leistet, ist die <em>Begrenzung des Schadens</em>: Ein
            Einbruch in die Zentrale gibt keine PIN preis, und ein widerrufener Agent
            bekommt keine neue mehr. Was sie nicht leistet, ist Schutz vor jemandem,
            der bereits Administrator des Praxisrechners ist.</p>
            """.formatted(Handbuchgrafik.PINWEG);

    // ══════════════════════════════════════════════════════ Teil 2: Bedienen

    private static final String ERSTE_SCHRITTE = """
            <p class="anriss">Wenn Sie zum ersten Mal vor dieser Oberfläche sitzen:
            Diese Seite reicht, um zu arbeiten. Alles andere können Sie
            nachschlagen.</p>

            <h2>1 · Anmelden</h2>
            <p>Benutzername, Passwort, dann der sechsstellige Code aus Ihrer
            Authenticator-App. Der Code wechselt alle 30 Sekunden. <strong>Geben Sie
            denselben Code nie zweimal ein</strong> — er wird abgelehnt, und der
            Versuch zählt als Fehlversuch. Warten Sie lieber auf den nächsten.</p>

            <h2>2 · Die Lage lesen</h2>
            <p>Die Startseite beantwortet eine Frage: <em>Muss ich jetzt etwas tun?</em></p>
            <ul class="hb-liste">
              <li><span class="marke ok">Grün</span> — alles freigeschaltet, nichts zu tun.</li>
              <li><span class="marke warnung">Amber</span> — etwas braucht Aufmerksamkeit,
                  aber der Betrieb läuft.</li>
              <li><span class="marke fehler">Rot</span> — hier ist jemand von der TI
                  getrennt oder wird es gleich sein.</li>
            </ul>
            <p>Die Farbe steht nie allein. Jede Marke trägt zusätzlich ein Zeichen und
            einen Text — wer Farben schlecht unterscheidet, liest dasselbe.</p>

            <h2>3 · Einen Alarm bearbeiten</h2>
            <p>Jeder Alarm sagt, <strong>was zu tun ist</strong>, nicht nur was nicht
            stimmt. Zwei Schritte stehen zur Wahl:</p>
            <ul class="hb-liste">
              <li><b>Quittieren</b> heißt: „Ich habe es gesehen und kümmere mich.“
                  Der Alarm bleibt offen, aber sichtbar in Arbeit.</li>
              <li><b>Schließen</b> heißt: „Erledigt.“ Alarme, deren Ursache
                  verschwunden ist, schließen sich ohnehin von selbst.</li>
            </ul>

            <h2>4 · Ein einzelnes Gerät ansehen</h2>
            <p>Unter <b>Kartenterminals</b> steht jedes Gerät mit seinem Zustand. Ein
            Klick auf den Namen öffnet die Detailseite: Verlauf, letzte Vorgänge,
            hinterlegte PIN, Zuordnung. Bei vielen Geräten helfen Suche und Filter —
            die Suche reagiert beim Tippen.</p>

            <div class="hb-merke">
              <b>Sie können nichts kaputt machen.</b>
              <p>Jede Aktion mit Folgen fragt vorher nach und sagt, was passieren wird.
              Nichts wird ohne Rückmeldung ausgeführt, und alles Wichtige steht
              danach im Protokoll.</p>
            </div>

            <h2>Tastatur</h2>
            <p>Die gesamte Oberfläche ist ohne Maus bedienbar. <kbd>Tab</kbd> springt
            weiter, <kbd>Enter</kbd> löst aus, <kbd>Esc</kbd> schließt. Die
            Sprungmarke am Seitenanfang führt direkt zum Inhalt, ohne die Navigation
            durchzugehen.</p>
            """;

    private static final String LAGE = """
            <p class="anriss">Die Lage-Übersicht ist die Startseite. Sie ist so
            gebaut, dass ein Blick aus zwei Metern Entfernung genügt.</p>

            <h2>Die Kacheln</h2>
            <table class="hb-tabelle">
              <tbody>
              <tr><td><b>Freigeschaltet</b></td><td>Karten im Zustand
                  <code>VERIFIED</code>. Diese Zahl soll der Gesamtzahl
                  entsprechen.</td></tr>
              <tr><td><b>Nicht freigeschaltet</b></td><td>Der Roboter arbeitet gerade
                  daran. Eine Zahl über null ist normal — sie soll nur nicht stehen
                  bleiben.</td></tr>
              <tr><td><b>Handlungsbedarf</b></td><td>Hier kommt der Roboter nicht
                  weiter. Ein Mensch muss ans Gerät.</td></tr>
              <tr><td><b>Nicht erreichbar</b></td><td>Das Terminal antwortet nicht.
                  Meist ausgeschaltet oder vom Netz getrennt.</td></tr>
              </tbody>
            </table>

            <h2>Wenn eine Zahl stehen bleibt</h2>
            <p>„Nicht freigeschaltet“ bei 3, und nach zehn Minuten immer noch bei 3 —
            dann kommt der Roboter nicht durch. Die häufigsten Gründe, in dieser
            Reihenfolge:</p>
            <ol class="hb-liste">
              <li>Für diese Karte ist <b>keine PIN hinterlegt</b>.</li>
              <li><b>PEviM ist am Gerät nicht aktiviert</b> — muss dort freigeschaltet
                  werden.</li>
              <li>Die Karte hat nur noch <b>einen Versuch</b> übrig; der Roboter hält
                  bewusst an.</li>
              <li>Der <b>Not-Aus</b> ist aktiv.</li>
            </ol>
            <p>Alle vier Fälle stehen als Alarm mit Handlungsanweisung darunter. Sie
            müssen nicht raten.</p>

            <h2>Was zu tun ist</h2>
            <section data-hilfe="was-zu-tun-ist">
            <p>Diese Tafel führt zusammen, was <b>Aufmerksamkeit braucht</b> — Geräte
            mit Handlungsbedarf, gesperrte Karten, angehaltene Automatik. Steht dort
            nichts, ist auch nichts zu tun.</p>

            <div class="hb-merke">
              <b>Sie ist nach Dringlichkeit sortiert, nicht nach Gerätename.</b>
              <p>Oben steht, was den TI-Zugang bedroht; darunter, was auffällig ist,
              aber laufen kann. Wer die Liste von oben abarbeitet, arbeitet richtig.</p>
            </div>

            <p>Jeder Eintrag nennt das Gerät, den Zustand und <b>was zu tun ist</b>.
            Ein Klick führt zur Detailseite des Geräts.</p>
            </section>

            <h2>Geräte live</h2>
            <section data-hilfe="geraete-live">
            <p>Die Tafel zeigt den <b>aktuellen</b> Zustand aller Kartenterminals und
            aktualisiert sich von selbst. Sie ist für den Blick zwischendurch gedacht —
            etwa während man an einem Gerät arbeitet und sehen will, ob die Änderung
            angekommen ist.</p>

            <p>Was hier steht, kommt aus den Meldungen der Standort-Agenten. Ein Gerät,
            dessen Agent verstummt ist, behält seinen letzten bekannten Zustand — die
            Lage-Übersicht weist dann oben darauf hin, dass ein Standort nicht mehr
            meldet.</p>
            </section>

            <h2>Die Seitenleiste</h2>
            <p>Oben links steht, wie viele Agenten sich gerade melden — etwa „3 von 3
            verbunden“. Steht dort eine kleinere Zahl, ist ein Standort abgehängt und
            wird nicht überwacht. Daneben die Version dieser Zentrale.</p>
            """;

    private static final String ALARME = """
            <p class="anriss">Ein Alarm entsteht aus einem Zustand, nicht aus einem
            Ereignis. Verschwindet der Zustand, verschwindet der Alarm — von
            selbst.</p>

            <h2>Warum das wichtig ist</h2>
            <p>Alarme, die man von Hand wegräumen muss, werden irgendwann pauschal
            weggeräumt. Dann bedeutet ein offener Alarm nichts mehr. Hier gilt:
            <strong>Ein offener Alarm heißt, dass der Zustand jetzt gerade
            vorliegt.</strong></p>

            <h2>Die Alarmtafel</h2>
            <section data-hilfe="alarme-bearbeiten">
            <p>Sie listet alle <b>offenen</b> Alarme des Geltungsbereichs, den
            schwersten zuerst. Jeder Eintrag nennt Gerät, Zustand, Dauer — und was zu
            tun ist.</p>

            <table class="hb-tabelle">
              <tbody>
              <tr><td><b>Quittieren</b></td>
                  <td>„Ich habe es gesehen und kümmere mich.“ Der Alarm bleibt offen,
                      ist aber als in Arbeit erkennbar — mit Name und Zeitpunkt.</td></tr>
              <tr><td><b>Schließen</b></td>
                  <td>„Erledigt.“ Besteht die Ursache weiter, entsteht der Alarm im
                      nächsten Takt neu.</td></tr>
              <tr><td><b>Alle schließen</b></td>
                  <td>Sammelaktion mit Rückfrage und Anzahl. Für die Lage nach einer
                      behobenen Störung.</td></tr>
              </tbody>
            </table>

            <div class="hb-merke">
              <b>Ein Alarm, dessen Ursache verschwindet, schließt sich von selbst.</b>
              <p>Deshalb bedeutet ein offener Alarm immer: Der Zustand liegt
              <em>jetzt</em> vor. Alarme, die man von Hand wegräumen muss, werden
              irgendwann pauschal weggeräumt — dann sagt ein offener Alarm nichts
              mehr.</p>
            </div>
            </section>

            <h2>Die Arten</h2>
            <table class="hb-tabelle">
              <thead><tr><th>Alarm</th><th>Schwere</th><th>Was zu tun ist</th></tr></thead>
              <tbody>
              <tr><td>Karte gesperrt</td><td><span class="marke fehler">kritisch</span></td>
                  <td>Nur mit der PUK zu lösen. Weitere PIN-Versuche helfen nicht.</td></tr>
              <tr><td>Konnektor nicht erreichbar</td><td><span class="marke fehler">kritisch</span></td>
                  <td>Netzwerk und Gerät prüfen. Alle Terminals dahinter sind
                      betroffen.</td></tr>
              <tr><td>Agent stumm</td><td><span class="marke fehler">kritisch</span></td>
                  <td>Der Standort wird nicht mehr überwacht. Läuft der Rechner?</td></tr>
              <tr><td>Automatik angehalten</td><td><span class="marke fehler">kritisch</span></td>
                  <td>Für dieses Gerät wurde angehalten. PIN prüfen, dann
                      freigeben.</td></tr>
              <tr><td>Gerät nicht erreichbar</td><td><span class="marke warnung">Warnung</span></td>
                  <td>Terminal eingeschaltet? Netzwerkkabel?</td></tr>
              <tr><td>Karte unverifiziert</td><td><span class="marke warnung">Warnung</span></td>
                  <td>Meist vorübergehend. Bleibt es stehen, fehlt die PIN.</td></tr>
              <tr><td>Handlungsbedarf Karte</td><td><span class="marke warnung">Warnung</span></td>
                  <td>Transport-PIN oder keine PIN gesetzt — am Gerät zu lösen.</td></tr>
              <tr><td>Agent veraltet</td><td><span class="marke warnung">Warnung</span></td>
                  <td>Die selbsttätige Aktualisierung greift dort nicht.</td></tr>
              <tr><td>Speicher knapp</td><td><span class="marke warnung">Warnung</span></td>
                  <td>Das Protokoll rotiert bereits. Budget prüfen.</td></tr>
              <tr><td>Sicherung fehlgeschlagen</td><td><span class="marke warnung">Warnung</span></td>
                  <td>Eine ausgebliebene Sicherung merkt man erst, wenn man sie
                      braucht.</td></tr>
              <tr><td>PEviM nicht aktiv</td><td><span class="marke unbekannt">Hinweis</span></td>
                  <td>Am Kartenterminal freizuschalten. Bis dahin wird überwacht,
                      aber nicht freigeschaltet.</td></tr>
              </tbody>
            </table>

            <h2>Benachrichtigung per E-Mail</h2>
            <section data-hilfe="sammelfenster">
            <p>Je Einrichtung lässt sich einstellen, wer ab welcher Schwere eine Mail
            bekommt.</p>
            <div class="hb-merke">
              <b>Das Sammelfenster ist die wichtigste Einstellung dieser Seite.</b>
              <p>Fällt ein Konnektor mit fünfhundert Terminals aus, entstehen binnen
              Sekunden fünfhundert Alarme — aber nur <em>eine</em> Mail, die sie
              zusammenfasst. Ohne diese Bremse wäre das Postfach unbrauchbar, und zwar
              genau dann, wenn es gebraucht wird.</p>
            </div>
            <p>Ein kurzes Fenster meldet schneller, ein langes fasst besser zusammen.
            Bei wenigen Geräten sind 30 Sekunden vertretbar; bei hunderten sollten es
            eher fünf Minuten sein. Gesammelt wird nur, was <b>neu</b> ist — ein
            wiederkehrender Alarm desselben Zustands erzeugt keine zweite Mail.</p>
            <p>In keiner Mail steht eine PIN, ein Zugangsdatum oder ein
            Personenbezug.</p>
            </section>

            <h2>Den Mailserver einrichten</h2>
            <section data-hilfe="mailserver">
            <p>Damit Benachrichtigungen überhaupt hinausgehen, braucht der PIN-Roboter
            einen Mailserver: Adresse, Port, Absender und — falls verlangt —
            Zugangsdaten.</p>

            <p>Das <b>Kennwort wird verschlüsselt gespeichert</b> (ADR-0008) und nie
            wieder angezeigt. Ein leeres Feld beim Speichern lässt das hinterlegte
            unverändert.</p>

            <div class="hb-merke">
              <b>Prüfen Sie mit einem Testversand.</b>
              <p>Eine falsche Einstellung fällt sonst erst auf, wenn die erste
              Störungsmeldung nicht ankommt — also genau dann, wenn man sie braucht.
              Der Testversand sagt im Klartext, woran es lag.</p>
            </div>
            </section>

            <h2>Wer wird benachrichtigt</h2>
            <section data-hilfe="benachrichtigungsregeln">
            <p>Je Einrichtung eine Regel: <b>welche Empfänger</b> ab <b>welchem
            Schweregrad</b>, mit <b>welchem Sammelfenster</b>.</p>

            <table class="hb-tabelle">
              <tbody>
              <tr><td>nur Kritisches</td><td>gesperrte Karte, Konnektor weg, Agent
                  stumm — Dinge, die den TI-Zugang unterbrechen</td></tr>
              <tr><td>Warnungen und Kritisches</td><td>zusätzlich das, was Aufmerksamkeit
                  braucht, aber läuft. Der übliche Fall.</td></tr>
              <tr><td>alles</td><td>zusätzlich Hinweise. Sinnvoll in der Einführung,
                  auf Dauer meist zu viel.</td></tr>
              </tbody>
            </table>

            <p>Eine Regel lässt sich <b>abschalten, ohne sie zu löschen</b> — etwa für
            die Dauer einer geplanten Wartung.</p>
            </section>

            <h2>Verschlüsselter Mailversand</h2>
            <section data-hilfe="mail-verschluesselung">
            <p>Der PIN-Roboter kann verlangen, dass der Mailserver eine verschlüsselte
            Verbindung anbietet, und den Versand sonst abbrechen.</p>

            <div class="hb-merke">
              <b>Ohne diese Einstellung wird auch an Server gesendet, die keine
              Verschlüsselung anbieten.</b>
              <p>Die Nachricht wäre auf dem Weg mitlesbar. Vertretbar ist das nur,
              wenn der Server im eigenen Netz steht und dieses Netz vertrauenswürdig
              ist.</p>
            </div>

            <p>In einer Mail steht ohnehin nie eine PIN, ein Zugangsdatum oder ein
            Personenbezug — wohl aber, an welchem Standort welches Gerät klemmt. Das
            ist eine Information über Ihre Einrichtung, die nicht jeden angeht.</p>
            </section>

            <h2>Alle Alarme schließen</h2>
            <p>Auf der Lage-Übersicht gibt es eine Sammelaktion. Sie fragt vorher nach
            und nennt die Anzahl. Alarme, deren Ursache weiter besteht, entstehen im
            nächsten Takt neu — das ist kein Fehler, sondern der Beweis, dass die
            Anzeige der Wirklichkeit folgt.</p>
            """;

    private static final String PINS = """
            <p class="anriss">Eine PIN zu hinterlegen dauert zwanzig Sekunden. Was
            dabei technisch passiert, steht im Kapitel
            <a href="/handbuch/sicherheit">Sicherheit</a> — hier steht, wie es
            geht.</p>

            <h2>Schritt für Schritt</h2>
            <section data-hilfe="pin-hinterlegen">
            <ol class="hb-liste">
              <li>Unter <b>Kartenterminals</b> das Gerät öffnen.</li>
              <li>Bei der SMC-B auf <b>PIN hinterlegen</b>.</li>
              <li>Die PIN eingeben und bestätigen.</li>
            </ol>

            <p>Beim Absenden wird sie <strong>im Browser verschlüsselt</strong> — für
            genau den Standort-Agenten dieses Hauses und für sonst niemanden. Sie
            verlässt Ihren Rechner nie im Klartext, wird nirgends im Klartext
            gespeichert und erscheint in keinem Protokoll.</p>

            <p>Beim Agenten liegt sie verschlüsselt. <b>Anzeigen lässt sie sich
            nirgends</b> — auch nicht gekürzt, auch nicht als Administrator. Die
            Oberfläche zeigt ausschließlich, <em>ob</em> eine hinterlegt ist. Zum
            Ändern gibt man eine neue ein; die alte wird nicht gebraucht, um sie zu
            ersetzen.</p>

            <div class="hb-merke">
              <b>Prüfen Sie die PIN, bevor Sie sie hinterlegen.</b>
              <p>Eine falsch hinterlegte PIN erzeugt Fehlversuche. Der Roboter bricht
              zwar ab, bevor die Karte sperrt — aber dann steht das Gerät auf
              „Automatik angehalten“ und jemand muss ran. Im Zweifel einmal am
              Terminal von Hand eingeben.</p>
            </div>
            </section>

            <h2>Die PIN auf der Karte ändern</h2>
            <section data-hilfe="pin-aendern">
            <p>Das ist etwas anderes als „eine andere PIN hinterlegen“: Hier wird die
            PIN <b>auf der Karte selbst</b> geändert (gematik <code>A_24082</code>) und
            danach im PIN-Roboter neu hinterlegt. Gebraucht werden dafür die bisherige
            und die neue.</p>

            <div class="hb-merke">
              <b>Auch das Ändern kann die Karte sperren.</b>
              <p>Stimmt die bisherige PIN nicht, zählt ein Fehlversuch — genau wie bei
              der Verifikation. Bleibt nur noch ein Versuch übrig, bricht der Roboter
              ab, statt die Sperre zu riskieren.</p>
            </div>

            <p>Schlägt die Änderung fehl, bleibt die hinterlegte PIN <b>unverändert</b>.
            Sonst hätte der Agent eine PIN im Speicher, die auf der Karte nicht gilt —
            und würde damit reihenweise Fehlversuche erzeugen.</p>
            </section>

            <h2>Nach einer Wiederherstellung</h2>
            <section data-hilfe="pins-neu-hinterlegen">
            <p>PINs sind für den Schlüssel eines <b>bestimmten</b> Agenten
            verschlüsselt. Wird ein Agent neu eingerichtet — nach einem Rechnertausch,
            einer Wiederherstellung oder einem verlorenen Datenverzeichnis —, sind die
            alten Chiffrate wertlos. Niemand kann sie mehr lesen, auch wir nicht.</p>

            <p>Die Zentrale führt dann unter <b>Instanz</b> eine Liste der betroffenen
            Karten. Sie <b>erscheint nur, wenn wirklich etwas fehlt</b>; eine dauerhaft
            leere Tafel „alles in Ordnung“ erzieht dazu, sie zu übersehen.</p>

            <div class="hb-merke">
              <b>Bis die PIN neu hinterlegt ist, wird dort nicht freigeschaltet.</b>
              <p>Das ist der Preis dafür, dass die Zentrale keine lesbare PIN hält. Die
              betroffenen Karten bleiben in ihrem Zustand — bereits freigeschaltete
              bleiben frei, bis das Terminal neu startet.</p>
            </div>
            </section>

            """;

    private static final String GRUPPEN = """
            <p class="anriss">Gerätegruppen sind das Werkzeug, mit dem Last und
            Zuständigkeit steuerbar bleiben. Bei fünf Geräten braucht man sie nicht.
            Bei fünftausend entscheiden sie über die Bedienbarkeit.</p>

            <h2>Gruppen anlegen und verwalten</h2>
            <section data-hilfe="geraetegruppen">
            <p>Eine Gruppe braucht drei Angaben: <b>Bezeichnung</b>,
            <b>Einrichtung</b> und <b>Prüfintervall</b>. Die Einrichtung lässt sich
            danach nicht mehr ändern.</p>

            <p>Die Übersicht zeigt je Gruppe die Zahl ihrer Geräte, das Intervall und
            ob sie die Vorgabegruppe ihrer Einrichtung ist. Die Suche reagiert beim
            Tippen.</p>

            <p><b>Löschen</b> geht nur, wenn die Gruppe leer ist und nicht die
            Vorgabegruppe. Beides ist keine Schikane: Eine gelöschte Gruppe mit Geräten
            würde diese aus jeder Überwachung nehmen, und ohne Vorgabegruppe hätte ein
            neu erkanntes Gerät keinen Platz.</p>

            <p>Auf der Detailseite steht auch, <b>welche Geräte</b> in der Gruppe
            liegen — zugeordnet werden sie im Bestand, nicht hier.</p>
            </section>

            <h2>Das Prüfintervall</h2>
            <p>Jede Gruppe hat ein eigenes Intervall. Warum das zählt:</p>
            <div class="hb-zahlen">
              <div><b>5.000</b><span>Kartenterminals</span></div>
              <div><b>60 s</b><span>Prüfintervall</span></div>
              <div><b>≈83</b><span>Abfragen pro Sekunde</span></div>
            </div>
            <p>Der Engpass sind dabei nicht wir, sondern die Antwortzeiten von
            Konnektor und Terminal. Deshalb: Die Notaufnahme bekommt 30 Sekunden, das
            Archiv kommt mit fünf Minuten aus. Das senkt die Last erheblich, ohne dort
            langsamer zu werden, wo es zählt.</p>

            <h2>Eindeutige Namen</h2>
            <div class="hb-merke">
              <b>Gruppennamen müssen instanzweit eindeutig sein.</b>
              <p>Neun Gruppen namens „Alle Geräte“ sind neun Gruppen, bei denen niemand
              weiß, welche gemeint ist. Der PIN-Roboter lehnt einen doppelten Namen
              deshalb ab und bittet darum, eine Praxiskennzeichnung zu ergänzen — etwa
              „Alle Geräte (Praxis Nord)“.</p>
            </div>

            <h2>Die Vorgabegruppe</h2>
            <section data-hilfe="vorgabegruppe">
            <p>Ein erstmals erkanntes Kartenterminal landet automatisch in der
            <b>Vorgabegruppe</b> seiner Einrichtung. Es wird also <strong>sofort
            überwacht</strong>, bevor jemand es zuordnet. Ein Gerät, das erst nach
            manueller Einordnung überwacht wird, ist ein Gerät, das man vergisst.</p>

            <p><b>Jede Einrichtung hat genau eine.</b> Um sie zu wechseln, öffnen Sie
            die Gruppe, die es künftig sein soll, und machen sie dort zur Vorgabe —
            nicht umgekehrt. Die bisherige verliert die Rolle automatisch.</p>

            <div class="hb-merke">
              <b>Ein Wechsel ändert nichts an vorhandenen Zuordnungen.</b>
              <p>Er ändert nur, wo <em>künftige</em> Geräte auftauchen. Wer erwartet,
              dass sich damit auch der Bestand umsortiert, sucht die Geräte hinterher
              an der falschen Stelle.</p>
            </div>
            </section>

            <h2>Die Einrichtung einer Gruppe steht fest</h2>
            <section data-hilfe="gruppe-einrichtung">
            <p>Eine Gerätegruppe liegt in <b>genau einer</b> Einrichtung, und das lässt
            sich nachträglich nicht ändern.</p>

            <div class="hb-merke">
              <b>Ein Wechsel würde die Geräte über die Mandantengrenze tragen.</b>
              <p>Alles, was in der Gruppe hängt, wäre plötzlich in der Sicht eines
              anderen Hauses — samt Alarmen und Protokolleinträgen. Das ist kein
              Sonderfall, den man abfangen könnte, sondern der Grund, warum es die
              Grenze gibt.</p>
            </div>

            <p>Wird für einen anderen Standort eine Gruppe gebraucht, legen Sie sie
            <b>dort</b> an. Geräte lassen sich einzeln umhängen; die Gruppe selbst
            bleibt, wo sie ist.</p>
            </section>

            <h2>Zeitfenster</h2>
            <p>Je Gruppe lassen sich Zeitfenster hinterlegen — für die spätere
            Firmware-Verteilung. Sie begrenzen den <em>Start</em> weiterer Geräte, nicht
            das Ende eines laufenden Vorgangs: Ein begonnenes Firmware-Update wird
            immer zu Ende geführt. Ein abgebrochenes Update ist der einzige Weg, ein
            Terminal wirklich zu beschädigen.</p>
            """;

    private static final String PROTOKOLL = """
            <p class="anriss">Der Roboter handelt selbsttätig. Jede seiner Handlungen
            muss im Nachhinein erklärbar sein — sonst wäre er nicht einsetzbar.</p>

            <h2>Zwei getrennte Protokolle</h2>
            <table class="hb-tabelle">
              <tbody>
              <tr><td><b>Fachprotokoll</b></td><td>Was fachlich geschah. In der
                  Oberfläche sichtbar, filterbar, nach Excel exportierbar —
                  und <b>technisch unveränderlich</b>.</td></tr>
              <tr><td><b>Technisches Protokoll</b></td><td>Diagnose für den Betrieb.
                  Rotiert, nicht in der Oberfläche.</td></tr>
              </tbody>
            </table>

            <h2>Was protokolliert wird — und was nicht</h2>
            <div class="hb-merke">
              <b>Zustandsänderungen und Handlungen. Keine Abfragen.</b>
              <p>Bei 5.000 Terminals im Minutentakt entstünden sonst rund 7,2 Millionen
              Einträge pro Tag. Ein Protokoll dieser Größe ist kein Nachweis mehr,
              sondern ein Versteck.</p>
            </div>
            <p><strong>Niemals im Protokoll:</strong> PINs, Terminal-Kennwörter,
            Zugangsdaten, TOTP-Geheimnisse. Das wird zentral erzwungen, nicht an jeder
            einzelnen Schreibstelle geprüft.</p>

            <h2>Die vier Sichten</h2>
            <ul class="hb-liste">
              <li><b>Lage</b> — der Zustand jetzt.</li>
              <li><b>Geräte-Zeitstrahl</b> — was an diesem Gerät geschah, der Reihe nach.</li>
              <li><b>Vorgangsansicht</b> — ein Vorgang von Anfang bis Ende, über alle
                  Beteiligten.</li>
              <li><b>Protokoll</b> — die filterbare Gesamtliste mit Export.</li>
            </ul>

            <h2>Filtern und ausgeben</h2>
            <section data-hilfe="protokoll-filtern">
            <p>Das Fachprotokoll lässt sich nach <b>Ereignisart</b>, <b>Ergebnis</b>,
            <b>Zeitraum</b> und <b>Gerät</b> einschränken. Die Filter wirken
            zusammen und lassen sich frei kombinieren.</p>

            <p>Was übrig bleibt, geht als <b>Excel-Datei</b> heraus — mit denselben
            Filtern. Das ist der Weg für eine Rückfrage vom Hersteller, für eine
            Prüfung oder für die eigene Nachschau.</p>

            <div class="hb-merke">
              <b>Das Protokoll wächst weiter, während Sie es ansehen.</b>
              <p>Neue Einträge erscheinen oben; wer zurückblättert, sieht Einträge
              langsam nach hinten wandern. Für einen belastbaren Auszug also erst den
              Zeitraum eingrenzen, dann exportieren.</p>
            </div>

            <p>Im Export steht dasselbe wie auf dem Schirm — <b>keine PINs, keine
            Zugangsdaten</b>. Das ist zentral erzwungen und nicht abschaltbar.</p>
            </section>

            <h2>Zeitstrahl und Vorgang</h2>
            <section data-hilfe="zeitstrahl">
            <p>Zwei Sichten auf dieselben Einträge, für zwei verschiedene Fragen.</p>

            <table class="hb-tabelle">
              <tbody>
              <tr><td><b>Geräte-Zeitstrahl</b></td>
                  <td>„Was ist an <em>diesem</em> Gerät passiert?“ Alle Einträge eines
                      Kartenterminals in zeitlicher Folge — für die Frage, seit wann
                      etwas klemmt.</td></tr>
              <tr><td><b>Vorgangsansicht</b></td>
                  <td>„Wie lief <em>dieser eine</em> Vorgang ab?“ Eine PIN-Übergabe
                      berührt Zentrale, Agent, Konnektor und Terminal; die Ansicht
                      führt die Einträge aller Beteiligten zusammen.</td></tr>
              </tbody>
            </table>

            <div class="hb-merke">
              <b>Der Zeitstrahl wächst weiter, während Sie ihn ansehen.</b>
              <p>Neue Einträge erscheinen oben. Wer zurückblättert, sieht Einträge
              deshalb langsam nach hinten wandern — unvermeidbar bei einer Live-Ansicht,
              aber gut zu wissen, bevor man sich wundert.</p>
            </div>
            </section>

            <h2>Fehlermeldungen</h2>
            <p>Technische Codes wie <code>4078</code> werden übersetzt. Der Klartext
            steht vorn, der Code bleibt aufklappbar erreichbar — für den Fall, dass
            jemand ihn an den Hersteller weitergeben muss.</p>

            <h2>Speicherplatz</h2>
            <p>Das Protokoll liegt in einem eigenen Speicherbereich, getrennt von den
            Betriebsdaten. Ab 80 Prozent Füllstand wird rotiert, gestaffelt nach
            Wichtigkeit: technische Diagnose zuerst, sicherheitsrelevante Ereignisse
            zuletzt.</p>
            <div class="hb-merke">
              <b>Ein volles Protokoll hält die Kernfunktion nie an.</b>
              <p>Eine Praxis darf ihren TI-Zugang nicht verlieren, weil eine
              Protokolldatei voll ist. Es wird deutlich alarmiert — aber weiter
              freigeschaltet.</p>
            </div>

            <h2>Personenbezug</h2>
            <p>Wer etwas getan hat, steht im Protokoll. Nach einer einstellbaren Frist
            wird dieser Bezug pseudonymisiert (Art. 17 DSGVO). Der Vorgang bleibt
            nachvollziehbar, die Person nicht mehr benennbar.</p>
            """;

    // ═════════════════════════════════════════════════════ Teil 3: Betreiben

    private static final String INBETRIEBNAHME = """
            <p class="anriss">Von der leeren Instanz zum überwachten Standort. Rechnen
            Sie mit einer halben Stunde, wenn die Adressen von Konnektor und Terminals
            bereitliegen.</p>

            <h2>1 · Zentrale starten</h2>
            <p>Der Stack wird mit <code>docker compose up -d</code> gestartet. Alle
            Einstellungen kommen aus Umgebungsvariablen oder eingehängten Dateien —
            kein Geheimnis liegt im Abbild, keines im Quellbaum. Die Datenbank legt
            sich selbst an, Migrationen laufen automatisch und wiederholbar.</p>

            <h2>2 · Ersteinrichtung</h2>
            <p>Beim ersten Aufruf führt <code>/ersteinrichtung</code> durch das Anlegen
            des ersten Administrators, einschließlich TOTP mit Prüfschritt. Der Schritt
            ist nur erreichbar, solange <em>kein einziger</em> Benutzer existiert.</p>
            <div class="hb-merke">
              <b>Sichern Sie das TOTP-Geheimnis, bevor Sie fortfahren.</b>
              <p>Es wird genau einmal angezeigt. Zusätzlich lässt sich in der
              Konfiguration ein Administratorkonto hinterlegen, das einen vollständigen
              Datenverlust übersteht — der einzige Weg zurück, wenn der zweite Faktor
              verloren geht.</p>
            </div>

            <h2>3 · Organisation anlegen</h2>
            <p>Zuerst die Serviceorganisation, darunter die Einrichtungen. Erst wenn
            eine Einrichtung existiert, lässt sich ein Agent anlegen — er arbeitet
            immer für genau eine.</p>

            <h2>4 · Standort-Agent einrichten</h2>
            <section data-hilfe="einrichtungspaket">
            <p>Unter <b>Bestand</b> das <b>Einrichtungspaket herunterladen</b>. Es
            enthält alles, was am Standort gebraucht wird:</p>
            <ul class="hb-liste">
              <li>eine fertige <code>docker-compose.yml</code>,</li>
              <li>eine vorausgefüllte Konfiguration mit der Adresse dieser Zentrale und
                  einem Zugangscode,</li>
              <li>eine Anleitung in verständlicher Sprache.</li>
            </ul>
            <p>In der Praxis sind nur noch die Adressen von Konnektor und
            Kartenterminals einzutragen, dann <code>docker compose up -d</code>.</p>
            <p><b>Die Wahl der Einrichtung ist wesentlich:</b> Alles, was dieser Agent
            meldet — Geräte, Alarme, Protokolleinträge —, gehört zu ihr. Sie lässt sich
            später korrigieren, ohne den Agenten neu einzurichten.</p>
            <p>Der Zugangscode gilt <strong>einmalig und 24 Stunden</strong>. Danach
            hilft ein neues Paket; der alte Code ist dann wertlos.</p>
            </section>

            <h2>5 · Prüfen</h2>
            <p>Nach dem Start meldet sich der Agent binnen Sekunden. In der
            Seitenleiste steigt die Zahl der verbundenen Agenten, unter
            <b>Kartenterminals</b> erscheinen die gefundenen Geräte. Am Standort selbst
            zeigt <code>http://127.0.0.1:8091</code> eine Prüfliste, in der jeder
            offene Punkt sagt, was zu tun ist.</p>

            <h2>6 · PINs hinterlegen</h2>
            <p>Der letzte Schritt. Danach arbeitet der Roboter.</p>

            <h2>Der Aufrufkontext des Konnektors</h2>
            <section data-hilfe="aufrufkontext">
            <p>Jeder Aufruf an den Konnektor trägt drei Angaben mit sich, an denen er
            erkennt, <em>in wessen Namen</em> gefragt wird:</p>
            <table class="hb-tabelle">
              <tbody>
              <tr><td><b>Mandant</b></td><td>Für welche Einrichtung. Nicht zu
                  verwechseln mit den Einrichtungen des PIN-Roboters — der Konnektor
                  führt seine eigene Liste.</td></tr>
              <tr><td><b>Clientsystem</b></td><td>Welches Programm fragt. Für uns ist
                  das der PIN-Roboter.</td></tr>
              <tr><td><b>Arbeitsplatz</b></td><td>Von welchem Platz aus. Bestimmt mit,
                  welche Kartenterminals überhaupt sichtbar sind.</td></tr>
              </tbody>
            </table>
            <div class="hb-merke">
              <b>Stimmt einer der drei Werte nicht, weist der Konnektor jede Anfrage
              ab.</b>
              <p>Ein Tippfehler zeigt sich deshalb nicht als halbe Funktion, sondern
              als vollständiger Ausfall dieses Standorts. Die Werte stehen in der
              Verwaltungsoberfläche des Konnektors — abtippen ist die häufigste
              Fehlerquelle bei der Inbetriebnahme.</p>
            </div>
            <p>Lässt sich das Informationsmodell des Konnektors auslesen, bietet der
            PIN-Roboter die vorhandenen Werte zur <b>Auswahl</b> an. Das ist der
            sicherere Weg.</p>
            </section>

            <h2>Die Webadmin-Schnittstelle</h2>
            <section data-hilfe="webadmin">
            <p>Neben der von der gematik spezifizierten Fachschnittstelle bieten manche
            Konnektoren eine <b>Verwaltungsschnittstelle</b> an. Über sie lässt sich das
            Informationsmodell auslesen: welche Kartenterminals es gibt, wie sie heißen,
            welche Mandanten und Arbeitsplätze eingerichtet sind.</p>
            <div class="hb-merke">
              <b>Diese Schnittstelle ist herstellerspezifisch und nirgends
              spezifiziert.</b>
              <p>Deshalb ist alles, was auf ihr beruht, im PIN-Roboter
              <b>optional</b>. Ohne sie funktioniert die Kernfunktion vollständig — es
              entfällt nur der Komfort, Geräte und Kontextwerte automatisch zu
              erkennen. Wäre sie Voraussetzung, wäre das Produkt an einen
              Konnektorhersteller gekettet.</p>
            </div>
            <p>Ist sie nicht erreichbar, weist die Oberfläche darauf hin, statt das
            Feld einfach leer zu lassen.</p>
            </section>

            <h2>Die Programmablage</h2>
            <section data-hilfe="programmablage">
            <p>Neue Fassungen des PIN-Roboters liegen in einer <b>Programmablage</b> im
            Internet — technisch eine Container-Registry. Die Zentrale sieht dort
            regelmäßig nach, ob es etwas Neueres gibt, und zeigt an, was sich ändern
            würde.</p>

            <table class="hb-tabelle">
              <tbody>
              <tr><td><b>Registry</b></td><td>Die Adresse der Ablage, bei uns
                  <code>ghcr.io</code>.</td></tr>
              <tr><td><b>Abbild</b></td><td>Der Name des Programms dort, etwa
                  <code>mathi66/pin-roboter</code>. Der Agent liegt unter demselben
                  Namen mit dem Zusatz <code>-agent</code>.</td></tr>
              <tr><td><b>Benutzername</b></td><td>Nur nötig, wenn die Ablage nicht
                  öffentlich ist.</td></tr>
              <tr><td><b>Zugriffstoken</b></td><td>Das Kennwort für die Ablage. Es wird
                  verschlüsselt gespeichert und <b>nie wieder angezeigt</b>. Ein leeres
                  Feld lässt das gespeicherte Token unverändert.</td></tr>
              </tbody>
            </table>

            <div class="hb-merke">
              <b>Der Roboter liest nur.</b>
              <p>Er lädt keine Fassung selbst ein und braucht keinen Zugriff auf den
              Docker-Dienst. Was er hier tut, ist nachsehen und anzeigen — die
              Entscheidung bleibt bei einem Menschen.</p>
            </div>

            <p>Das Token sollte auf <code>read:packages</code> beschränkt sein. Mehr
            braucht weder die Zentrale noch ein Agent.</p>
            </section>

            <h2>Zum Ausprobieren: Demo- und Lastdaten</h2>
            <section data-hilfe="demodaten">
            <p>Unter <b>Instanz</b> lassen sich zwei Datensätze anlegen, die niemand im
            Echtbetrieb braucht — beide sind vollständig wieder entfernbar.</p>

            <table class="hb-tabelle">
              <tbody>
              <tr><td><b>Demodatensatz</b></td>
                  <td>Ein vollständiger MVZ-Verbund: 3 Standorte mit je 2
                      Facharztpraxen und 1 Allgemeinpraxis, je Praxis ein Konnektor
                      mit eigener SMC-B, ein Empfangsplatz mit SMC-B-Terminal und
                      drei Behandlerplätze mit Terminals ohne SMC-B. Dazu
                      Benutzerkonten auf allen drei Berechtigungsebenen.</td></tr>
              <tr><td><b>Lastdatensatz</b></td>
                  <td>Ein Bestand in der Größe des Mengengerüsts. Damit lassen sich
                      die Zielwerte für Antwortzeiten <em>auf dieser Hardware
                      nachmessen</em>, statt sie zu schätzen.</td></tr>
              </tbody>
            </table>

            <div class="hb-merke">
              <b>Wozu der Demodatensatz gut ist.</b>
              <p>Die Oberfläche verhält sich mit einem Gerät anders als mit
              vierzig — Suche, Filter und Gruppierung zeigen ihren Nutzen erst dann.
              Wer das Produkt bewerten oder Personal einarbeiten will, sieht mit
              einer leeren Instanz nicht, worum es geht.</p>
            </div>

            <p>Beide Datensätze sind als solche gekennzeichnet und lassen sich in einem
            Zug wieder löschen. Echte Daten bleiben dabei unberührt.</p>
            </section>

            <h2>Netzwerk</h2>
            <table class="hb-tabelle">
              <tbody>
              <tr><td>Agent → Zentrale</td><td>ausgehend, verschlüsselt. <b>Keine
                  eingehende Freigabe nötig.</b></td></tr>
              <tr><td>Agent → Konnektor/Terminals</td><td>im Praxisnetz, TLS 1.2 mit
                  Brainpool</td></tr>
              <tr><td>Zentrale → Programmablage</td><td>nur für Versionsprüfung und
                  Aktualisierung</td></tr>
              </tbody>
            </table>
            """;

    private static final String BESTAND = """
            <p class="anriss">Der Standort-Agent findet Kartenterminals von selbst und
            trägt sie ein. Von Hand erfasst wird, was er nicht sehen kann — und
            gepflegt wird, was er nicht wissen kann.</p>

            <h2>Einen Konnektor erfassen</h2>
            <section data-hilfe="konnektor-adresse">
            <p>Gebraucht wird die <b>Adresse</b>: Rechnername oder IP-Adresse,
            wahlweise mit Port. Beim Speichern wird geprüft, ob dort etwas
            antwortet.</p>

            <p>Ein <b>noch nicht angeschlossener</b> Konnektor lässt sich trotzdem
            erfassen. Das ist Absicht: Man richtet oft ein, bevor die Hardware steht,
            und ein Formular, das das verhindert, zwingt dazu, den Vorgang später
            noch einmal von vorn zu beginnen.</p>

            <p>Dazu kommen die drei Werte des <a href="/handbuch/inbetriebnahme">Aufrufkontexts</a> —
            Mandant, Clientsystem, Arbeitsplatz. Stimmt einer nicht, weist der
            Konnektor jede Anfrage ab.</p>
            </section>

            <h2>Einen fremden Konnektor mitnutzen</h2>
            <section data-hilfe="konnektor-mitnutzung">
            <p>In einem Verbund betreibt oft <b>eine</b> Einrichtung den Konnektor,
            und andere hängen ihre Kartenterminals daran. Der PIN-Roboter unterscheidet
            deshalb zwischen <b>Betreiber</b> und <b>Mitnutzer</b>.</p>

            <table class="hb-tabelle">
              <thead><tr><th></th><th>Betreiber</th><th>Mitnutzer</th></tr></thead>
              <tbody>
              <tr><td>Konnektor sehen</td><td>ja</td><td>ja</td></tr>
              <tr><td>eigene Terminals zuordnen</td><td>ja</td><td>ja</td></tr>
              <tr><td>Zugangsdaten ändern</td><td>ja</td><td><b>nein</b></td></tr>
              <tr><td>Konnektor löschen</td><td>ja</td><td><b>nein</b></td></tr>
              </tbody>
            </table>

            <div class="hb-merke">
              <b>So kann keine Einrichtung ein Gerät umkonfigurieren, an dem andere
              hängen.</b>
              <p>Ein geänderter Aufrufkontext oder ein gelöschter Konnektor legt
              nicht nur den eigenen Standort lahm, sondern alle, die mitnutzen. Diese
              Grenze verhindert das — nicht durch Sorgfalt, sondern durch fehlende
              Rechte.</p>
            </div>
            </section>

            <h2>Ein Terminal ohne Konnektor</h2>
            <section data-hilfe="geraet-ohne-konnektor">
            <p>Ein Kartenterminal lässt sich erfassen, bevor es einem Konnektor
            zugeordnet ist. Es wird dann <b>verwaltet, aber nicht freigeschaltet</b>.</p>

            <p>Der Grund liegt im Verfahren: <b>Die Verifikation löst der Konnektor
            aus</b>, nicht der PIN-Roboter. Ohne ihn gibt es niemanden, der die PIN
            entgegennimmt — das Gerät steht in der Liste, wird überwacht, nimmt aber
            an der Automatik nicht teil.</p>

            <p>Sobald das Terminal am Konnektor angemeldet ist, lässt sich dieser hier
            nachtragen. Ab dann läuft es mit.</p>
            </section>

            <h2>Die Listen</h2>
            <section data-hilfe="bestandslisten">
            <p>Zwei Listen, beide serverseitig gefiltert und geblättert — bei 5.000
            Geräten geht es nicht anders.</p>

            <table class="hb-tabelle">
              <tbody>
              <tr><td><b>Konnektoren</b></td>
                  <td>Konnektoren und Hochleistungskonnektoren, mit Betreiber und
                      Erreichbarkeit. Ein Klick öffnet die Detailseite mit
                      Aufrufkontext und mitnutzenden Einrichtungen.</td></tr>
              <tr><td><b>Kartenterminals</b></td>
                  <td>Alle Geräte mit Zustand, Gruppe und Einrichtung. Die Suche
                      reagiert beim Tippen; sortiert wird nach Praxis, damit
                      gleichnamige Geräte auseinanderzuhalten sind.</td></tr>
              </tbody>
            </table>

            <p>Was hier steht, hat der Standort-Agent gemeldet oder jemand von Hand
            erfasst. <b>Geändert wird hier nichts an den Geräten selbst</b> — der
            PIN-Roboter führt Buch, er konfiguriert keine Hardware.</p>
            </section>

            <h2>Der Abgleich mit dem Bestand</h2>
            <section data-hilfe="bestandsabgleich">
            <p>Der Standort-Agent meldet, was er am Konnektor vorfindet. Weicht das
            vom erfassten Bestand ab, erscheint der Unterschied hier: neu gefundene
            Geräte, verschwundene, geänderte Angaben.</p>

            <div class="hb-merke">
              <b>Hier wird nichts selbsttätig geändert.</b>
              <p>Der Roboter zeigt den Unterschied und wartet. Ein Gerät, das über
              Nacht aus der Liste verschwindet, weil es einmal nicht geantwortet hat,
              wäre schlimmer als eine Liste, die jemand bestätigen muss.</p>
            </div>

            <p>Jeder Fund lässt sich einzeln übernehmen. Was übernommen wird, landet in
            der Vorgabegruppe seiner Einrichtung und wird ab dann überwacht.</p>
            </section>

            <h2>Zugangsdaten eines Geräts</h2>
            <section data-hilfe="geraetezugang">
            <p>Manche Kartenterminals verlangen für ihre Verwaltungsschnittstelle ein
            Kennwort. Es wird verschlüsselt gespeichert (ADR-0008) und
            <b>nie wieder angezeigt</b> — auch nicht gekürzt.</p>

            <p>Zum Entfernen gibt es einen eigenen Knopf. Das ist bewusst getrennt vom
            Ändern: Ein leeres Feld beim Speichern lässt das hinterlegte Kennwort
            unverändert, sonst würde jedes versehentliche Speichern es löschen.</p>

            <p>Wie die PIN erscheint es in keinem Protokoll, keiner Sicherung und
            keiner Fehlermeldung.</p>
            </section>

            <h2>Karten im Terminal</h2>
            <section data-hilfe="karten-im-terminal">
            <p>Je Kartenterminal listet der PIN-Roboter, welche Karten der Konnektor
            dort sieht — <b>alle</b>, nicht nur die SMC-B.</p>

            <table class="hb-tabelle">
              <tbody>
              <tr><td><b>SMC-B</b></td><td>Der Praxisausweis. Nur diese Karte wird
                  automatisch freigeschaltet.</td></tr>
              <tr><td>eGK, HBA</td><td>Werden angezeigt, damit der Bestand vollständig
                  ist — angefasst werden sie nie.</td></tr>
              </tbody>
            </table>

            <p>Zu jeder Karte stehen Kartentyp, Kennung (ICCSN) und die Gültigkeit des
            Zertifikats. Ein ablaufendes Zertifikat ist der zweithäufigste Grund, warum
            ein Standort plötzlich keinen TI-Zugang mehr hat — der häufigste ist die
            nicht freigeschaltete Karte.</p>
            </section>

            <h2>Firmware-Stände im Blick</h2>
            <section data-hilfe="firmwarestand">
            <p>Der PIN-Roboter zeigt je Gerät den gemeldeten Firmware-Stand und
            markiert, welche Geräte hinter dem aktuellsten zurückliegen.</p>

            <div class="hb-merke">
              <b>Als „aktuellster Stand“ gilt die höchste vorkommende Version je
              Modell.</b>
              <p>Einen <em>Sollstand</em> kennt der Roboter nicht — die Firmware kommt
              vom Hersteller des Geräts, nicht von uns. Was hier steht, ist also ein
              Vergleich innerhalb Ihres Bestands, keine Aussage darüber, ob es beim
              Hersteller etwas Neueres gibt.</p>
            </div>

            <p>Nützlich ist das trotzdem: Weicht ein einzelnes Gerät von zwanzig
            gleichen ab, ist es dort liegengeblieben. Und ein Terminal, das PEviM
            nicht unterstützt, hat fast immer eine zu alte Firmware.</p>
            </section>
            """;

    private static final String AGENTEN = """
            <p class="anriss">Ein Standort-Agent ist ein Container, der läuft und sich
            meldet. Die meiste Zeit gibt es nichts zu tun — dieses Kapitel behandelt
            die übrige.</p>

            <h2>Die Oberfläche des Agenten</h2>
            <p>Unter <code>http://127.0.0.1:8091</code>, <strong>nur auf dem Rechner
            selbst</strong> erreichbar — nicht im Praxisnetz, nicht aus dem Internet.
            Sie zeigt eine Prüfliste der Inbetriebnahme, den Zustand aller Geräte und
            die letzten Meldungen. Jeder offene Punkt sagt, was zu tun ist. PINs,
            Token und Schlüssel zeigt sie nie, auch nicht gekürzt.</p>

            <h2>Serverumzug</h2>
            <p>Zieht die Zentrale auf eine andere Adresse, zeigen alle ausgelieferten
            Agenten ins Leere. Deshalb ist die Adresse in der Agentenoberfläche
            <strong>änderbar</strong> und überlebt einen Neustart. Eine neue Adresse
            wird vor der Übernahme geprüft: Antwortet dort keine betriebsbereite
            Zentrale, bleibt die alte bestehen. Ein Tippfehler kann den Agenten nicht
            abschneiden.</p>

            <h2>Wie der Austausch abläuft</h2>
            <section data-hilfe="agent-weitergabe">
            <p>Neben dem Agenten läuft ein zweiter Dienst, der ihn austauschen kann.
            <b>Von selbst tut er nichts</b> — er wartet auf einen Auftrag.</p>

            <div class="hb-merke">
              <b>Es gibt keine nächtliche Aktualisierung.</b>
              <p>Aktualisiert wird nur, was ein Mensch anstößt — und er wählt damit
              auch den Zeitpunkt. So trifft eine fehlerhafte Fassung nie mehrere
              Standorte gleichzeitig.</p>
            </div>

            <p>Was dann passiert, ist genau das, was Sie auch von Hand eingeben
            würden:</p>
            <ol class="hb-liste">
              <li><code>docker login</code> — nur wenn die Programmablage privat ist</li>
              <li><code>docker compose pull agent</code></li>
              <li><code>docker compose up -d agent</code></li>
              <li><code>docker logout</code></li>
            </ol>
            <p>Das Skript dazu liegt <b>offen im Einrichtungspaket</b>
            (<code>aktualisieren.sh</code>). Wer es liest, weiß danach genau, was auf
            seinem Rechner geschieht — bei einem fremden Programm ist das nicht so.</p>

            <p>Drei Vorkehrungen:</p>
            <ul class="hb-liste">
              <li><b>Nur der Agent</b> wird angefasst. Was sonst auf dem Rechner
                  läuft, bleibt unberührt.</li>
              <li><b>Kein direkter Zugriff auf den Docker-Dienst</b>, sondern über
                  einen Vermittler mit eingeschränkten Rechten. Wer den Docker-Dienst
                  hat, ist faktisch Administrator des Rechners.</li>
              <li><b>Kein Netzwerkdienst.</b> Der Agent legt seinen Auftrag in ein
                  Verzeichnis, das nur diese beiden Dienste sehen. Es gibt keine
                  Schnittstelle, die jemand im Netz erreichen könnte.</li>
            </ul>
            </section>

            <h3>Wo die Zugangsdaten herkommen</h3>
            <section data-hilfe="agent-zugangsdaten">
            <p>Die Programmablage ist privat; ohne Anmeldung kommt kein Agent an sein
            Programm. Sie steht <b>nicht im Einrichtungspaket</b> — sonst stünde
            derselbe Token in jeder Praxis, und ein Wechsel hieße: an jeden Rechner.</p>

            <div class="hb-merke">
              <b>Die Zugangsdaten liegen nicht dauerhaft am Standort.</b>
              <p>Der Agent holt sie erst, wenn ein Auftrag vorliegt, und legt sie
              zusammen mit dem Auftrag ab. Der Aktualisierer löscht die Datei als
              Erstes, meldet sich damit an und meldet sich hinterher wieder ab. Auf
              einem Rechner, an dem gerade niemand aktualisiert, ist kein Token.</p>
            </div>

            <p>Die Zentrale gibt sie außerdem <b>nur bei einem offenen Auftrag</b>
            heraus. Ein Agent, der ohne Anlass fragt, bekommt nichts.</p>

            <p>Eingeschaltet wird die Weitergabe unter <b>Instanz → Produktversion →
            Zugangsdaten für die Agenten</b>. Ab Werk ist sie aus: Wer
            einen Zugang hinterlegt, damit die Zentrale nach Updates sieht, hat damit
            nicht der Weitergabe zugestimmt. Abschalten ist der Not-Aus — ohne
            Anmeldung wird kein Programm geholt.</p>

            <p>Der Token sollte auf <code>read:packages</code> beschränkt sein; mehr
            braucht ein Agent nie. Wird ein Agent widerrufen, bekommt er keine
            Zugangsdaten mehr — ein kompromittierter Praxisrechner lässt sich so
            ausschließen, ohne den Token überall zu tauschen.</p>

            <p class="hb-fussnote">Ehrlich zur Grenze: Wer in dem Moment, in dem ein
            Update läuft, Administrator des Praxisrechners ist, kommt an den Token.
            Gewonnen ist die Zeit — Sekunden statt dauerhaft.</p>
            </section>

            <h2>Gezielt aktualisieren</h2>
            <section data-hilfe="agent-aktualisierung">
            <p>In der Agentenliste unter <b>Instanz</b> trägt jeder Agent die Spalte
            <b>Version &amp; Aktualisierung</b>. Dort stehen die laufende Version, was
            in der Programmablage bereitsteht, und ein Knopf. Der Agent holt den
            Auftrag im nächsten Prüftakt ab — meist binnen Sekunden — und tauscht sich
            aus.</p>

            <p>Die Auswahl steht auf der <b>neuesten</b> Fassung, lässt aber auch
            ältere zu: Manchmal will man an einem Standort bewusst zurück, etwa wenn
            sich eine neue Fassung dort als unpassend erweist.</p>

            <div class="hb-merke">
              <b>Erfolgreich ist die Aktualisierung erst, wenn der Agent sich mit der
              neuen Version wiedermeldet.</b>
              <p>Nicht seine Quittung entscheidet — er quittiert, <em>bevor</em> sein
              Container ersetzt wird, denn danach kann er nichts mehr sagen. Gemessen
              wird also das Ergebnis, nicht die Absicht.</p>
            </div>

            <p>Während der Aktualisierung ist der Standort etwa eine Minute lang nicht
            überwacht. <strong>Bereits freigeschaltete Karten bleiben
            freigeschaltet</strong> — es fehlt nur die Aufsicht.</p>
            </section>

            <h3>Warum nur Versionsnummern zur Auswahl stehen</h3>
            <section data-hilfe="agent-zielversion">
            <p>In der Programmablage gibt es neben den Nummern auch <b>Zeiger</b> wie
            <code>stabil</code> — sie verweisen jeweils auf die freigegebene Fassung.
            Zur gezielten Aktualisierung stehen sie <b>nicht</b> zur Wahl.</p>
            <p>Der Grund: Ein Agent meldet immer die <b>Nummer</b> aus seinem
            Programm, nie den Zeiger, über den er geholt wurde. Ein Auftrag
            „aktualisiere auf <code>stabil</code>“ wäre deshalb nie erfüllbar — der
            Agent käme mit <code>1.2.0</code> wieder, das Ziel hieße
            <code>stabil</code>, und nach zehn Minuten stünde ein Fehlschlag da,
            obwohl alles geklappt hat.</p>
            <p>Der nächtliche Lauf benutzt den Zeiger weiterhin. Dort wird nichts
            verifiziert, sondern nur nachgezogen.</p>
            </section>

            <h3>Wenn es nicht klappt</h3>
            <section data-hilfe="agent-fehlschlag">
            <table class="hb-tabelle">
              <thead><tr><th>Anzeige</th><th>Bedeutung</th></tr></thead>
              <tbody>
              <tr><td><span class="marke">angefordert</span></td>
                  <td>Der Auftrag liegt bereit. Der Agent holt ihn im nächsten
                      Prüftakt ab.</td></tr>
              <tr><td><span class="marke">läuft</span></td>
                  <td>Der Agent hat angestoßen und tauscht sich gerade aus. Die
                      Anzeige aktualisiert sich alle fünf Sekunden von selbst.</td></tr>
              <tr><td><span class="marke ok">erfolgreich</span></td>
                  <td>Der Agent meldet sich mit der Zielversion. Verifiziert.</td></tr>
              <tr><td><span class="marke fehler">fehlgeschlagen</span></td>
                  <td>Entweder scheiterte schon der Anstoß, oder der Agent kam
                      binnen zehn Minuten nicht mit der neuen Version zurück.</td></tr>
              </tbody>
            </table>

            <p><b>Der Agent läuft dabei unverändert weiter.</b> Ein fehlgeschlagener
            Abruf lässt den alten Container unangetastet — der Standort ist nicht
            ausgefallen, er hängt nur zurück.</p>

            <p>Die drei häufigsten Ursachen:</p>
            <ol class="hb-liste">
              <li><b>Der Aktualisierungsdienst läuft dort nicht.</b> Manche Häuser
                  entfernen ihn bewusst aus dem Einrichtungspaket. Dann muss vor Ort
                  von Hand aktualisiert werden:
                  <code>docker compose pull agent &amp;&amp; docker compose up -d agent</code></li>
              <li><b>Die Zugangsdaten wurden abgelehnt.</b> Neues Token unter
                  <b>Instanz → Produktversion</b> hinterlegen — die Agenten übernehmen
                  es im nächsten Takt.</li>
              <li><b>Die Version gibt es dort nicht.</b> Prüfen, ob sie in der
                  Programmablage wirklich veröffentlicht wurde.</li>
            </ol>
            <p>Am Standort selbst hilft <code>docker compose logs aktualisierung</code>.</p>
            </section>

            <h3>Abbrechen und zur Kenntnis nehmen</h3>
            <section data-hilfe="agent-abbrechen">
            <p>Ein <b>laufender</b> Auftrag lässt sich abbrechen. Das nimmt ihn aus der
            Anzeige; hat der Agent bereits angestoßen, läuft die Aktualisierung vor Ort
            trotzdem zu Ende — abgebrochen wird die Erwartung, nicht der Vorgang.</p>
            <p>Ein <b>fehlgeschlagener</b> Auftrag bleibt in der Zeile stehen, bis
            jemand ihn mit <b>Zur Kenntnis</b> ausblendet. Er verschwindet damit aus
            der Übersicht, bleibt aber im Verlauf und im Protokoll. Wer die Ursache
            behoben hat, wird die Meldung so wieder los — und eine Meldung, die man
            nicht loswird, wird ignoriert.</p>
            <p>Ein neuer Fehlschlag zeigt sich wieder, auch wenn der vorige zur
            Kenntnis genommen wurde.</p>
            </section>

            <h2>Wenn Agent und Zentrale verschieden alt sind</h2>
            <section data-hilfe="protokollversion">
            <p>Beide tragen <b>eigene</b> Versionsnummern und werden getrennt
            veröffentlicht. Am Agenten wird häufiger nachgearbeitet, weil die
            Erfahrung mit Kartenterminals und Konnektoren erst wächst.</p>

            <p>Zusammengehalten werden sie durch die <b>Protokollversion</b> — die
            Sprache, in der sie miteinander reden. Der Agent nennt sie bei jeder
            Meldung; die Zentrale kennt eine unterstützte Spanne und zeigt an, wenn
            ein Agent außerhalb liegt.</p>

            <div class="hb-merke">
              <b>Ein Agent mit fremder Protokollversion wird nicht abgewiesen.</b>
              <p>Das hieße: keine PIN-Verifikation an diesem Standort — und damit
              wäre der Schaden größer als der Nutzen. Er arbeitet weiter; nur
              neuere Funktionen wirken dort nicht. Die Agentenliste unter
              „Instanz“ zeigt, wo das der Fall ist.</p>
            </div>
            </section>

            <h2>Wenn ein Agent verstummt</h2>
            <p>Meldet er länger als 15 Minuten nichts, entsteht ein kritischer Alarm:
            An diesem Standort wird nicht mehr überwacht. Prüfen Sie, ob der Rechner
            läuft und der Container gestartet ist.</p>

            <h2>Einen Agenten umhängen</h2>
            <p>Wird eine Einrichtung umbenannt, meldet der Agent ins Leere — die
            Zentrale kann seine Geräte keiner Gruppe mehr zuordnen. Die Agentenliste
            kennzeichnet das als „Einrichtung unbekannt“; die Zuordnung lässt sich dort
            korrigieren, ohne den Agenten neu einzurichten.</p>
            """;

    private static final String KONTEN = """
            <p class="anriss">Anmeldung nur mit Benutzername, Passwort und einem
            Einmalkennwort aus der Authenticator-App. Das ist nicht abschaltbar — hier
            wird eine PIN verwaltet, die einen ganzen Standort öffnet.</p>

            <h2>Die Kontenliste</h2>
            <section data-hilfe="konten">
            <p>Sie zeigt alle Konten des Geltungsbereichs mit Rolle, Einrichtung und
            letzter Anmeldung. Bei tausend Benutzern hilft die Suche; sortiert und
            geblättert wird serverseitig.</p>

            <table class="hb-tabelle">
              <thead><tr><th>Rolle</th><th>darf zusätzlich</th></tr></thead>
              <tbody>
              <tr><td>Betrachter</td><td>Zustand sehen, Protokoll lesen</td></tr>
              <tr><td>Anwender</td><td>Alarme quittieren und schließen</td></tr>
              <tr><td>Bestandspflege</td><td>Geräte, Gruppen und PINs pflegen</td></tr>
              <tr><td>Administrator</td><td>Konten, Organisation, Instanz verwalten</td></tr>
              </tbody>
            </table>

            <div class="hb-merke">
              <b>Jede Rolle gilt innerhalb eines Geltungsbereichs.</b>
              <p>Ein Administrator einer Praxis ist kein Administrator der
              Nachbarpraxis. Wer eine Einrichtung sieht, sieht ihre Geräte, ihre Alarme
              und ihr Protokoll — sonst nichts.</p>
            </div>

            <p>Ein Konto lässt sich <b>sperren statt löschen</b>. Das ist der Regelfall
            beim Ausscheiden: Die Protokolleinträge behalten ihren Bezug, aber niemand
            kann sich mehr anmelden.</p>
            </section>

            <h2>Jemanden einladen</h2>
            <section data-hilfe="einladungen">
            <p>Ein neues Konto entsteht über eine <b>Einladung</b>. Sie enthält einen
            einmaligen Zugangslink; der Eingeladene setzt sein Passwort selbst und
            richtet dabei den zweiten Faktor ein.</p>

            <div class="hb-merke">
              <b>Niemand vergibt ein Passwort für jemand anderen.</b>
              <p>Ein Passwort, das zwei Menschen kennen, ist keines. Über die Einladung
              kennt es nur der, dem das Konto gehört — und im Protokoll steht trotzdem,
              wer eingeladen hat.</p>
            </div>

            <p>Die Einladung gilt <b>begrenzt</b> und lässt sich zurückziehen, solange
            sie offen ist. Unter „Offene Einladungen“ steht, wer noch nicht
            angenommen hat.</p>
            </section>

            <h2>Was nur einmal zu sehen ist</h2>
            <section data-hilfe="einmalige-anzeige">
            <p>Manche Angaben zeigt der PIN-Roboter <b>genau einmal</b>: den Zugangslink
            einer Einladung, das TOTP-Geheimnis bei der Ersteinrichtung, die Kennwörter
            frisch angelegter Demo-Konten.</p>

            <p>Wer die Seite verlässt, bekommt sie nicht wieder — sie sind nirgends
            gespeichert, sondern nur berechnet worden. Schreiben Sie sie auf oder geben
            Sie sie weiter, <b>bevor</b> Sie weiterklicken.</p>

            <p>Das ist kein Versehen, sondern derselbe Grundsatz wie bei der PIN: Was
            man nicht speichert, kann auch nicht gestohlen werden. Verloren heißt hier
            nicht gesperrt — eine neue Einladung ist in zwei Klicks erzeugt.</p>
            </section>

            <h2>Wenn der zweite Faktor verloren geht</h2>
            <section data-hilfe="totp-verlust">
            <p>Ein neues Handy, ein gelöschter Authenticator — das kommt vor. Ein
            anderer Administrator setzt den zweiten Faktor zurück; beim nächsten
            Anmelden wird er neu eingerichtet.</p>

            <p>Gibt es <b>keinen</b> anderen Administrator mehr, hilft das in der
            Konfiguration hinterlegte Startkonto. Es übersteht auch einen vollständigen
            Datenverlust und ist der einzige Weg zurück, wenn sich alle ausgesperrt
            haben.</p>

            <p class="hb-fussnote">Häufigste Ursache fürs Aussperren ist nicht der
            verlorene Faktor, sondern derselbe Code zweimal: Ein bereits benutztes
            Einmalkennwort wird abgelehnt und zählt als Fehlversuch. Fünf davon sperren
            das Konto. Warten Sie auf den nächsten Code.</p>
            </section>
            """;

    private static final String SICHERUNG = """
            <p class="anriss">Eine ausgebliebene Sicherung merkt man erst, wenn man sie
            braucht. Deshalb ist ein Fehlschlag hier ein Alarm und keine Logzeile.</p>

            <h2>Automatisch</h2>
            <section data-hilfe="sicherung-erstellen">
            <p>Drei Pläne laufen parallel: täglich (7 Stände), wöchentlich (5),
            monatlich (12). Die Fälligkeit wird stündlich geprüft, die Rotation
            entfernt den jeweils ältesten Stand. Die Sicherungen liegen in einem
            eigenen Speicherbereich, getrennt von den Betriebsdaten.</p>

            </section>

            <h2>Herunterladen</h2>
            <section data-hilfe="sicherung-wiederherstellen">
            <p>Eine Sicherung zum Mitnehmen wird mit einer Passphrase verschlüsselt,
            die Sie beim Herunterladen vergeben. <strong>Diese Passphrase wird nirgends
            gespeichert.</strong> Ohne sie ist die Datei unbrauchbar — auch für uns.</p>
            <div class="hb-merke">
              <b>Bewahren Sie die Passphrase getrennt von der Sicherung auf.</b>
              <p>Beides am selben Ort ist wie ein Schlüssel, der im Schloss steckt.</p>
            </div>
            <p>Ein verändertes Byte in der Datei wird beim Einspielen erkannt und
            abgelehnt — eine beschädigte Sicherung fällt auf, bevor sie Schaden
            anrichtet.</p>

            <h2>Wiederherstellen</h2>
            <p>Vor dem Einspielen legt die Zentrale automatisch eine Sicherheitskopie
            des aktuellen Standes an. Das Einspielen läuft in einer Transaktion:
            Entweder es gelingt vollständig, oder nichts ändert sich. Danach startet
            die Zentrale neu.</p>
            <p>Anschließend erscheint die Liste <b>„PINs neu zu hinterlegen“</b>, falls
            welche fehlen — PINs sind für den Schlüssel eines bestimmten Agenten
            verschlüsselt und lassen sich nicht aus einer Sicherung
            wiederbeleben, wenn dieser Agent neu eingerichtet wurde.</p>

            </section>

            <h2>Was Sie einmal getan haben sollten</h2>
            <p>Eine Wiederherstellung ausprobieren — auf einer Testinstanz, bevor Sie
            sie brauchen. Eine Sicherung, die nie eingespielt wurde, ist eine
            Vermutung.</p>
            """;

    private static final String STOERUNGEN = """
            <p class="anriss">Die Fälle in der Reihenfolge, in der sie tatsächlich
            vorkommen.</p>

            <h2>„Der Agent meldet sich nicht“</h2>
            <ol class="hb-liste">
              <li>Läuft der Container? <code>docker compose ps</code> am Standort.</li>
              <li>Was sagt seine Oberfläche unter <code>http://127.0.0.1:8091</code>?
                  Die Prüfliste nennt den offenen Punkt.</li>
              <li>Stimmt die Adresse der Zentrale? Sie steht dort und ist änderbar.</li>
              <li>Ist der Zugangscode abgelaufen? Er gilt 24 Stunden. Dann ein neues
                  Einrichtungspaket herunterladen.</li>
            </ol>

            <h2>„Eine Karte wird nicht freigeschaltet“</h2>
            <p>Die Detailseite des Geräts nennt den Grund. Fünf mögliche Antworten:</p>
            <table class="hb-tabelle">
              <tbody>
              <tr><td>keine PIN hinterlegt</td><td>hinterlegen</td></tr>
              <tr><td>PEviM nicht aktiv</td><td>am Kartenterminal freischalten</td></tr>
              <tr><td>nur noch ein Versuch</td><td>von Hand am Gerät — der Roboter hält
                  bewusst an</td></tr>
              <tr><td>Karte gesperrt</td><td>PUK, von Hand</td></tr>
              <tr><td>Not-Aus aktiv</td><td>in der Seitenleiste aufheben</td></tr>
              </tbody>
            </table>

            <h2>„Die Verbindung zum Konnektor scheitert“</h2>
            <p>Die Fehlermeldung unterscheidet ausdrücklich zwischen einem
            <b>Zertifikatsfehler</b> und einem <b>Kurvenfehler</b>. Das ist keine
            Spitzfindigkeit:</p>
            <ul class="hb-liste">
              <li><b>Zertifikat</b> — der Vertrauensanker fehlt oder passt nicht.
                  Prüfen Sie <code>CA_URL</code> in der Konfiguration des Agenten.</li>
              <li><b>Kurve</b> — die Gegenstelle bietet keine Brainpool-Kurve an. Meist
                  ist die falsche Adresse oder ein falscher Port eingetragen.</li>
            </ul>

            <h2>„Der Demodatensatz lässt sich nicht löschen“</h2>
            <p>Behoben. Sollte es dennoch auftreten: Es lag daran, dass Verweise auf
            Demodaten bestehen blieben. Der Vorgang räumt heute über die Beziehungen
            auf, nicht über eine Kennzeichnung.</p>

            <h2>„Ein Agent bleibt auf einer alten Version“</h2>
            <ol class="hb-liste">
              <li>Ist die Weitergabe unter <b>Instanz → Produktversion</b>
                  eingeschaltet?</li>
              <li>Am Standort: <code>docker compose logs aktualisierung</code>.</li>
              <li>Wurde die Version in der Programmablage überhaupt veröffentlicht?</li>
            </ol>

            <h2>„Ich habe mich ausgesperrt“</h2>
            <p>Fünf Fehlversuche sperren ein Konto. Ein anderer Administrator kann es
            freigeben. Gibt es keinen, hilft das in der Konfiguration hinterlegte
            Startkonto — es übersteht auch einen vollständigen Datenverlust.</p>
            <p class="hb-fussnote">Häufigste Ursache: derselbe TOTP-Code zweimal
            gesendet. Ein bereits benutzter Code wird abgelehnt und zählt als
            Fehlversuch. Warten Sie auf den nächsten.</p>

            <h2>Der Systemtest</h2>
            <section data-hilfe="systemtest">
            <p>Unter <b>Einstellungen</b> prüft der Systemtest auf Knopfdruck, ob die
            Teile zusammenspielen: Datenbank erreichbar, Speicherplatz vorhanden,
            Agenten melden sich, Mailversand funktioniert.</p>

            <p>Er ist für zwei Momente gedacht — <b>nach der Einrichtung</b>, um zu
            sehen, dass alles steht, und <b>bei einer Störung</b>, um schnell
            einzugrenzen, wo sie liegt.</p>

            <div class="hb-merke">
              <b>Er ändert nichts.</b>
              <p>Der Test liest und misst, er repariert nicht. Was er findet, steht in
              Klartext da — samt dem, was zu tun ist.</p>
            </div>
            </section>

            <h2>Wenn nichts hilft</h2>
            <p>Exportieren Sie das Protokoll für den betroffenen Zeitraum
            (<b>Protokoll → Export</b>) und legen Sie die Ausgabe von
            <code>docker compose logs</code> dazu. Beides enthält keine PINs und keine
            Zugangsdaten — das ist zentral erzwungen.</p>
            """;

    private static final String BEGRIFFE = """
            <p class="anriss">Was ein Wort bedeutet, wenn es in dieser Oberfläche
            steht.</p>

            <dl class="hb-glossar">
              <dt>SMC-B</dt>
              <dd>Der Praxisausweis als Chipkarte. Weist die Einrichtung gegenüber der
                  Telematikinfrastruktur aus. Muss mit einer PIN freigeschaltet sein —
                  das ist der ganze Grund für dieses Produkt.</dd>

              <dt>PEviM</dt>
              <dd>„PIN-Eingabe via Managementschnittstelle“. Das von der gematik
                  spezifizierte Verfahren, mit dem eine PIN über die Verwaltungs­schnitt­stelle
                  des Kartenterminals übergeben wird. Ausschließlich für die SMC-B
                  zugelassen.</dd>

              <dt>Konnektor</dt>
              <dd>Das Gerät, das die Praxis mit der Telematikinfrastruktur verbindet.
                  Er verwaltet die Kartenterminals und kennt den Zustand jeder
                  Karte.</dd>

              <dt>HSK</dt>
              <dd>Hochleistungskonnektor. Dieselbe Aufgabe für viele Einrichtungen,
                  meist im Rechenzentrum. Für den PIN-Roboter dieselbe Anbindung.</dd>

              <dt>Kartenterminal</dt>
              <dd>Das Lesegerät, in dem die Karte steckt. Muss PEviM unterstützen und
                  es freigeschaltet haben.</dd>

              <dt>Standort-Agent</dt>
              <dd>Der Teil des PIN-Roboters, der im Praxisnetz läuft und mit Konnektor
                  und Terminals spricht. Baut Verbindungen ausschließlich nach außen
                  auf.</dd>

              <dt>Zentrale</dt>
              <dd>Der Teil mit der Oberfläche und der Datenbank. Kann für viele
                  Standorte zuständig sein.</dd>

              <dt>Einrichtung</dt>
              <dd>Eine Praxis, ein MVZ, ein Standort. Die Ebene, an der Geräte und
                  Berechtigungen hängen.</dd>

              <dt>Serviceorganisation</dt>
              <dd>Ein IT-Dienstleister, der mehrere Einrichtungen betreut. In der
                  Einzelpraxis gibt es genau eine.</dd>

              <dt>Geltungsbereich</dt>
              <dd>Was ein angemeldeter Mensch sehen darf. Wird bei jeder Abfrage
                  erzwungen, nicht nur in der Anzeige gefiltert.</dd>

              <dt>Fehlversuchszähler</dt>
              <dd>Wie viele PIN-Versuche eine Karte noch hat. Bei einem verbleibenden
                  Versuch hält der Roboter an.</dd>

              <dt>Not-Aus</dt>
              <dd>Hält die Automatik sofort und überall an. Immer erreichbar, unten in
                  der Seitenleiste.</dd>

              <dt>TOTP</dt>
              <dd>Der sechsstellige Code aus der Authenticator-App. Zweiter Faktor bei
                  der Anmeldung, verpflichtend.</dd>

              <dt>Version</dt>
              <dd>Zentrale und Standort-Agent tragen <b>eigene</b> Versionsnummern
                  und werden getrennt veröffentlicht. Am Agenten wird häufiger
                  nachgearbeitet, weil die Erfahrung mit Kartenterminals und
                  Konnektoren erst wächst — eine gemeinsame Nummer zwänge dazu, die
                  Zentrale mitzuziehen, obwohl sich dort nichts geändert hat.
                  <br>Zusammengehalten werden beide durch die
                  <b>Protokollversion</b>: Der Agent nennt sie bei jeder Meldung,
                  und die Zentrale prüft, ob sie damit umgehen kann.</dd>

              <dt>Programmablage</dt>
              <dd>Der Ort im Internet, aus dem neue Fassungen des Programms geholt
                  werden (eine Container-Registry). Privat, mit Zugangsdaten.</dd>

              <dt>Fachprotokoll</dt>
              <dd>Die unveränderliche Aufzeichnung dessen, was fachlich geschah.
                  Sichtbar, filterbar, exportierbar.</dd>
            </dl>
            """;
}
