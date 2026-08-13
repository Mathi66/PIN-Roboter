package de.pinroboter.zentrale;

/**
 * Die Zeichnungen des Handbuchs.
 *
 * <h2>Warum eingebettete Vektorgrafik und kein Bild</h2>
 *
 * <ul>
 *   <li><b>Nichts wird nachgeladen.</b> Zentrale und Agent stehen in Netzen
 *       ohne Internetzugang. Ein fehlendes Bild ist schlimmer als keines.</li>
 *   <li><b>Sie nehmen die Farben der Oberfläche an.</b> Über
 *       {@code var(--akzent)} und Verwandte passen sie sich jedem späteren
 *       Farbwechsel an, ohne dass jemand Bilder neu zeichnet.</li>
 *   <li><b>Sie skalieren.</b> Auf einem Tablet und auf einem 27-Zoll-Schirm.</li>
 *   <li><b>Sie sind lesbar für Screenreader.</b> Jede Zeichnung trägt
 *       {@code role="img"} und eine Beschreibung, die dasselbe sagt wie das
 *       Bild — nicht „Diagramm“, sondern der Inhalt.</li>
 * </ul>
 *
 * <p>Der Text in den Zeichnungen ist echter Text, keine Pfade: Er lässt sich
 * markieren, vergrößern und vorlesen.
 */
final class Handbuchgrafik {

    private Handbuchgrafik() { }

    /** Der Kreislauf aus Prüfen, Erkennen, Freischalten. */
    static final String KREISLAUF = """
            <figure class="hb-figur">
            <svg viewBox="0 0 720 260" role="img" class="hb-svg"
                 aria-label="Kreislauf: Der Agent prüft im Takt den Zustand jeder Karte.
                 Ist sie freigeschaltet, geschieht nichts. Ist sie es nicht, prüft der
                 Roboter die verbleibenden Versuche und übergibt die PIN über die
                 Managementschnittstelle. Danach beginnt der Takt von vorn.">
              <defs>
                <marker id="hbPfeil" viewBox="0 0 10 10" refX="9" refY="5"
                        markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                  <path d="M0 0 10 5 0 10z" fill="var(--akzent)"/>
                </marker>
              </defs>

              <rect x="14" y="96" width="150" height="66" rx="12"
                    fill="var(--akzent-hg)" stroke="var(--akzent)" stroke-width="1.5"/>
              <text x="89" y="124" class="hb-t hb-t-fett">Prüfen</text>
              <text x="89" y="143" class="hb-t hb-t-klein">alle 60 Sekunden</text>

              <path d="M170 129h56" stroke="var(--akzent)" stroke-width="2"
                    marker-end="url(#hbPfeil)" fill="none"/>

              <rect x="232" y="96" width="150" height="66" rx="12"
                    fill="var(--karte)" stroke="var(--rand-bedien)" stroke-width="1.5"/>
              <text x="307" y="124" class="hb-t hb-t-fett">Zustand?</text>
              <text x="307" y="143" class="hb-t hb-t-klein">Antwort des Konnektors</text>

              <path d="M388 112h50" stroke="var(--ok)" stroke-width="2"
                    marker-end="url(#hbPfeil)" fill="none"/>
              <text x="413" y="103" class="hb-t hb-t-mini" fill="var(--ok)">frei</text>
              <rect x="444" y="30" width="160" height="58" rx="12"
                    fill="var(--ok-hg)" stroke="var(--ok)" stroke-width="1.5"/>
              <text x="524" y="55" class="hb-t hb-t-fett" fill="var(--ok)">Nichts tun</text>
              <text x="524" y="73" class="hb-t hb-t-klein">so soll es sein</text>
              <path d="M438 112 444 88" stroke="var(--ok)" stroke-width="2" fill="none"/>

              <path d="M388 146h50" stroke="var(--warnung)" stroke-width="2"
                    marker-end="url(#hbPfeil)" fill="none"/>
              <text x="413" y="167" class="hb-t hb-t-mini" fill="var(--warnung)">gesperrt</text>
              <rect x="444" y="170" width="160" height="58" rx="12"
                    fill="var(--warnung-hg)" stroke="var(--warnung)" stroke-width="1.5"/>
              <text x="524" y="195" class="hb-t hb-t-fett" fill="var(--warnung)">PIN übergeben</text>
              <text x="524" y="213" class="hb-t hb-t-klein">nur wenn ≥ 2 Versuche frei</text>
              <path d="M438 146 444 170" stroke="var(--warnung)" stroke-width="2" fill="none"/>

              <path d="M604 199c60 0 66-70 24-70" stroke="var(--akzent)" stroke-width="2"
                    fill="none" stroke-dasharray="4 4"/>
              <path d="M660 129H89v-33" stroke="var(--akzent)" stroke-width="2"
                    fill="none" stroke-dasharray="4 4" marker-end="url(#hbPfeil)"
                    transform="translate(0,-30)"/>
              <text x="360" y="86" class="hb-t hb-t-mini">und wieder von vorn</text>
            </svg>
            <figcaption>Der Takt läuft ununterbrochen. Zwischen dem Zurückfallen einer
            Karte und ihrer Freischaltung liegt im Regelfall weniger als eine
            Minute.</figcaption>
            </figure>
            """;

    /** Die fünf Kartenzustände als Zustandsbild. */
    static final String ZUSTAENDE = """
            <figure class="hb-figur">
            <svg viewBox="0 0 720 210" role="img" class="hb-svg"
                 aria-label="Die Zustände einer SMC-B: Aus VERIFIABLE wird durch die
                 PIN-Übergabe VERIFIED. Ein Neustart oder Stromausfall wirft die Karte
                 auf VERIFIABLE zurück. Drei Fehlversuche führen zu BLOCKED, das nur
                 mit der PUK verlassen wird. TRANSPORT_PIN und EMPTY_PIN sind
                 Ausgangszustände, die ein Mensch am Gerät auflösen muss.">
              <defs>
                <marker id="hbP2" viewBox="0 0 10 10" refX="9" refY="5"
                        markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                  <path d="M0 0 10 5 0 10z" fill="var(--gedaempft)"/>
                </marker>
              </defs>

              <rect x="30" y="76" width="170" height="60" rx="12"
                    fill="var(--warnung-hg)" stroke="var(--warnung)" stroke-width="1.5"/>
              <text x="115" y="101" class="hb-t hb-t-fett">VERIFIABLE</text>
              <text x="115" y="120" class="hb-t hb-t-klein">bereit, nicht frei</text>

              <path d="M206 96h108" stroke="var(--ok)" stroke-width="2"
                    marker-end="url(#hbP2)" fill="none"/>
              <text x="260" y="88" class="hb-t hb-t-mini" fill="var(--ok)">PIN richtig</text>

              <rect x="320" y="76" width="170" height="60" rx="12"
                    fill="var(--ok-hg)" stroke="var(--ok)" stroke-width="1.5"/>
              <text x="405" y="101" class="hb-t hb-t-fett" fill="var(--ok)">VERIFIED</text>
              <text x="405" y="120" class="hb-t hb-t-klein">freigeschaltet</text>

              <path d="M314 122H206" stroke="var(--gedaempft)" stroke-width="2"
                    marker-end="url(#hbP2)" fill="none"/>
              <text x="260" y="140" class="hb-t hb-t-mini">Neustart, Stromausfall</text>

              <rect x="530" y="76" width="160" height="60" rx="12"
                    fill="var(--fehler-hg)" stroke="var(--fehler)" stroke-width="1.5"/>
              <text x="610" y="101" class="hb-t hb-t-fett" fill="var(--fehler)">BLOCKED</text>
              <text x="610" y="120" class="hb-t hb-t-klein">nur mit PUK zurück</text>

              <path d="M115 72V34h495v38" stroke="var(--fehler)" stroke-width="1.8"
                    fill="none" marker-end="url(#hbP2)"/>
              <text x="360" y="26" class="hb-t hb-t-mini" fill="var(--fehler)">3 Fehlversuche —
                der Roboter hält vorher an</text>

              <rect x="30" y="158" width="170" height="40" rx="10"
                    fill="var(--unbekannt-hg)" stroke="var(--rand-bedien)" stroke-width="1.2"/>
              <text x="115" y="183" class="hb-t hb-t-klein">TRANSPORT_PIN · EMPTY_PIN</text>
              <path d="M115 158v-22" stroke="var(--gedaempft)" stroke-width="1.6"
                    fill="none" stroke-dasharray="4 3" marker-end="url(#hbP2)"/>
              <text x="290" y="183" class="hb-t hb-t-mini" text-anchor="start">nur von Hand
                am Gerät aufzulösen</text>
            </svg>
            <figcaption>Der Roboter bewegt sich ausschließlich auf dem grünen Pfeil.
            Alles andere ist Beobachtung — oder Anlass, aufzuhören.</figcaption>
            </figure>
            """;

    /** Zentrale, Agent, Praxisnetz — und die Richtung des Verbindungsaufbaus. */
    static final String TOPOLOGIE = """
            <figure class="hb-figur">
            <svg viewBox="0 0 720 300" role="img" class="hb-svg"
                 aria-label="Aufbau: Links die Zentrale mit Oberfläche und Datenbank,
                 rechts das Praxisnetz mit Standort-Agent, Konnektor und
                 Kartenterminals. Der Agent baut die Verbindung zur Zentrale von sich
                 aus auf; durch die Firewall geht nichts in die Gegenrichtung.">
              <defs>
                <marker id="hbP3" viewBox="0 0 10 10" refX="9" refY="5"
                        markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                  <path d="M0 0 10 5 0 10z" fill="var(--akzent)"/>
                </marker>
              </defs>

              <rect x="16" y="40" width="210" height="220" rx="16"
                    fill="var(--akzent-hg)" stroke="var(--akzent)" stroke-width="1.5"/>
              <text x="121" y="68" class="hb-t hb-t-fett" fill="var(--akzent)">Zentrale</text>
              <rect x="40" y="86" width="162" height="44" rx="10"
                    fill="var(--karte)" stroke="var(--rand-bedien)" stroke-width="1.2"/>
              <text x="121" y="113" class="hb-t hb-t-klein">Oberfläche · Konten · Rollen</text>
              <rect x="40" y="140" width="162" height="44" rx="10"
                    fill="var(--karte)" stroke="var(--rand-bedien)" stroke-width="1.2"/>
              <text x="121" y="167" class="hb-t hb-t-klein">Datenbank · Protokoll</text>
              <rect x="40" y="194" width="162" height="44" rx="10"
                    fill="var(--karte)" stroke="var(--rand-bedien)" stroke-width="1.2"/>
              <text x="121" y="221" class="hb-t hb-t-klein">PIN nur als Chiffrat</text>

              <path d="M300 150h-58" stroke="var(--akzent)" stroke-width="2.4"
                    marker-end="url(#hbP3)" fill="none"/>
              <text x="290" y="136" class="hb-t hb-t-mini" fill="var(--akzent)"
                    text-anchor="end">meldet, holt Aufträge</text>
              <text x="290" y="172" class="hb-t hb-t-mini" text-anchor="end">verschlüsselt</text>

              <path d="M262 30v240" stroke="var(--gedaempft)" stroke-width="1.4"
                    stroke-dasharray="6 5" fill="none"/>
              <text x="262" y="22" class="hb-t hb-t-mini">Firewall</text>
              <text x="262" y="286" class="hb-t hb-t-mini">keine eingehende Freigabe</text>

              <rect x="300" y="40" width="404" height="220" rx="16"
                    fill="var(--karte)" stroke="var(--rand)" stroke-width="1.5"/>
              <text x="502" y="68" class="hb-t hb-t-fett">Praxisnetz</text>

              <rect x="320" y="86" width="150" height="52" rx="10"
                    fill="var(--akzent-hg)" stroke="var(--akzent)" stroke-width="1.4"/>
              <text x="395" y="108" class="hb-t hb-t-fett" fill="var(--akzent)">Standort-Agent</text>
              <text x="395" y="126" class="hb-t hb-t-klein">hält die PIN</text>

              <path d="M476 112h44" stroke="var(--gedaempft)" stroke-width="2"
                    marker-end="url(#hbP3)" fill="none"/>

              <rect x="526" y="86" width="158" height="52" rx="10"
                    fill="var(--grund-tief)" stroke="var(--rand-bedien)" stroke-width="1.2"/>
              <text x="605" y="108" class="hb-t hb-t-fett">Konnektor</text>
              <text x="605" y="126" class="hb-t hb-t-klein">oder HSK</text>

              <path d="M605 144v26H395v20" stroke="var(--gedaempft)" stroke-width="1.6"
                    fill="none" marker-end="url(#hbP3)"/>
              <path d="M605 170v20" stroke="var(--gedaempft)" stroke-width="1.6"
                    fill="none" marker-end="url(#hbP3)"/>
              <path d="M500 170v20" stroke="var(--gedaempft)" stroke-width="1.6"
                    fill="none" marker-end="url(#hbP3)"/>

              <rect x="330" y="194" width="130" height="46" rx="9"
                    fill="var(--ok-hg)" stroke="var(--ok)" stroke-width="1.2"/>
              <text x="395" y="214" class="hb-t hb-t-klein">Kartenterminal</text>
              <text x="395" y="231" class="hb-t hb-t-mini" fill="var(--ok)">SMC-B frei</text>

              <rect x="440" y="194" width="120" height="46" rx="9"
                    fill="var(--ok-hg)" stroke="var(--ok)" stroke-width="1.2"/>
              <text x="500" y="214" class="hb-t hb-t-klein">Kartenterminal</text>
              <text x="500" y="231" class="hb-t hb-t-mini" fill="var(--ok)">SMC-B frei</text>

              <rect x="540" y="194" width="130" height="46" rx="9"
                    fill="var(--warnung-hg)" stroke="var(--warnung)" stroke-width="1.2"/>
              <text x="605" y="214" class="hb-t hb-t-klein">Kartenterminal</text>
              <text x="605" y="231" class="hb-t hb-t-mini" fill="var(--warnung)">wird
                freigeschaltet</text>
            </svg>
            <figcaption>Der Pfeil zeigt nur in eine Richtung. Das ist der Grund, warum
            keine Firewall geöffnet werden muss — und warum der Agent
            weiterarbeitet, wenn die Zentrale ausfällt.</figcaption>
            </figure>
            """;

    /** Instanz, Serviceorganisation, Einrichtung, Gerät. */
    static final String HIERARCHIE = """
            <figure class="hb-figur">
            <svg viewBox="0 0 720 250" role="img" class="hb-svg"
                 aria-label="Hierarchie von oben nach unten: Instanz, darunter
                 Serviceorganisationen, darunter Einrichtungen, darunter Konnektoren
                 mit Kartenterminals. Berechtigungen gelten immer nur innerhalb eines
                 Zweiges.">
              <rect x="280" y="14" width="160" height="42" rx="10"
                    fill="var(--akzent)" stroke="none"/>
              <text x="360" y="40" class="hb-t hb-t-fett" fill="var(--auf-akzent)">Instanz</text>

              <path d="M360 56v20M180 76h360M180 76v18M540 76v18" stroke="var(--rand-bedien)"
                    stroke-width="1.6" fill="none"/>

              <rect x="80" y="94" width="200" height="44" rx="10"
                    fill="var(--akzent-hg)" stroke="var(--akzent)" stroke-width="1.4"/>
              <text x="180" y="113" class="hb-t hb-t-fett" fill="var(--akzent)">Serviceorganisation</text>
              <text x="180" y="130" class="hb-t hb-t-mini">IT-Dienstleister Nord</text>

              <rect x="440" y="94" width="200" height="44" rx="10"
                    fill="var(--akzent-hg)" stroke="var(--akzent)" stroke-width="1.4"/>
              <text x="540" y="113" class="hb-t hb-t-fett" fill="var(--akzent)">Serviceorganisation</text>
              <text x="540" y="130" class="hb-t hb-t-mini">IT-Dienstleister Süd</text>

              <path d="M180 138v14M100 152h160M100 152v16M260 152v16" stroke="var(--rand-bedien)"
                    stroke-width="1.4" fill="none"/>
              <path d="M540 138v14M540 152v16" stroke="var(--rand-bedien)"
                    stroke-width="1.4" fill="none"/>

              <rect x="24" y="168" width="152" height="40" rx="9"
                    fill="var(--karte)" stroke="var(--rand-bedien)" stroke-width="1.2"/>
              <text x="100" y="185" class="hb-t hb-t-klein">Einrichtung</text>
              <text x="100" y="201" class="hb-t hb-t-mini">Praxis Dr. Meyer</text>

              <rect x="188" y="168" width="152" height="40" rx="9"
                    fill="var(--karte)" stroke="var(--rand-bedien)" stroke-width="1.2"/>
              <text x="264" y="185" class="hb-t hb-t-klein">Einrichtung</text>
              <text x="264" y="201" class="hb-t hb-t-mini">MVZ Hafen</text>

              <rect x="464" y="168" width="152" height="40" rx="9"
                    fill="var(--karte)" stroke="var(--rand-bedien)" stroke-width="1.2"/>
              <text x="540" y="185" class="hb-t hb-t-klein">Einrichtung</text>
              <text x="540" y="201" class="hb-t hb-t-mini">Klinikum West</text>

              <path d="M100 208v10M264 208v10M540 208v10" stroke="var(--rand-bedien)"
                    stroke-width="1.2" fill="none"/>
              <text x="100" y="232" class="hb-t hb-t-mini">Konnektor → 4 Terminals</text>
              <text x="264" y="232" class="hb-t hb-t-mini">Konnektor → 12 Terminals</text>
              <text x="540" y="232" class="hb-t hb-t-mini">HSK → 380 Terminals</text>
            </svg>
            <figcaption>Wer beim IT-Dienstleister Nord angemeldet ist, sieht Süd nicht
            — auch nicht versehentlich. Die Grenze wird bei jeder Abfrage erzwungen,
            nicht in der Anzeige gefiltert.</figcaption>
            </figure>
            """;

    /** Der Weg der PIN vom Browser bis zur Karte. */
    static final String PINWEG = """
            <figure class="hb-figur">
            <svg viewBox="0 0 720 220" role="img" class="hb-svg"
                 aria-label="Der Weg der PIN: Im Browser wird sie mit dem öffentlichen
                 Schlüssel des Agenten verschlüsselt. Die Zentrale speichert und
                 überträgt nur das Chiffrat und kann es nicht lesen. Erst der Agent
                 entschlüsselt sie mit seinem privaten Schlüssel und übergibt sie dem
                 Kartenterminal.">
              <defs>
                <marker id="hbP4" viewBox="0 0 10 10" refX="9" refY="5"
                        markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                  <path d="M0 0 10 5 0 10z" fill="var(--akzent)"/>
                </marker>
              </defs>

              <rect x="12" y="60" width="150" height="72" rx="12"
                    fill="var(--karte)" stroke="var(--akzent)" stroke-width="1.5"/>
              <text x="87" y="86" class="hb-t hb-t-fett">Browser</text>
              <text x="87" y="105" class="hb-t hb-t-klein">PIN eingeben</text>
              <text x="87" y="122" class="hb-t hb-t-mini" fill="var(--akzent)">hier verschlüsselt</text>

              <path d="M168 96h56" stroke="var(--akzent)" stroke-width="2"
                    marker-end="url(#hbP4)" fill="none"/>
              <text x="196" y="88" class="hb-t hb-t-mini">Chiffrat</text>

              <rect x="230" y="60" width="180" height="72" rx="12"
                    fill="var(--unbekannt-hg)" stroke="var(--rand-bedien)" stroke-width="1.5"/>
              <text x="320" y="86" class="hb-t hb-t-fett">Zentrale</text>
              <text x="320" y="105" class="hb-t hb-t-klein">speichert, reicht weiter</text>
              <text x="320" y="122" class="hb-t hb-t-mini" fill="var(--fehler)">kann nicht lesen</text>

              <path d="M416 96h56" stroke="var(--akzent)" stroke-width="2"
                    marker-end="url(#hbP4)" fill="none"/>
              <text x="444" y="88" class="hb-t hb-t-mini">Chiffrat</text>

              <rect x="478" y="60" width="150" height="72" rx="12"
                    fill="var(--akzent-hg)" stroke="var(--akzent)" stroke-width="1.5"/>
              <text x="553" y="86" class="hb-t hb-t-fett">Agent</text>
              <text x="553" y="105" class="hb-t hb-t-klein">privater Schlüssel</text>
              <text x="553" y="122" class="hb-t hb-t-mini" fill="var(--ok)">entschlüsselt hier</text>

              <path d="M634 96h44" stroke="var(--ok)" stroke-width="2"
                    marker-end="url(#hbP4)" fill="none"/>
              <text x="704" y="100" class="hb-t hb-t-mini" text-anchor="end">Karte</text>

              <rect x="230" y="156" width="180" height="46" rx="10"
                    fill="var(--fehler-hg)" stroke="var(--fehler)" stroke-width="1.3"/>
              <text x="320" y="175" class="hb-t hb-t-klein" fill="var(--fehler)">Datenbankabzug</text>
              <text x="320" y="192" class="hb-t hb-t-mini">enthält keine lesbare PIN</text>
              <path d="M320 132v22" stroke="var(--fehler)" stroke-width="1.4"
                    stroke-dasharray="4 3" fill="none"/>
            </svg>
            <figcaption>Der Schlüssel zum Lesen liegt ausschließlich beim Agenten. Das
            ist der Grund, warum ein Einbruch in die Zentrale keine PIN
            preisgibt.</figcaption>
            </figure>
            """;
}
