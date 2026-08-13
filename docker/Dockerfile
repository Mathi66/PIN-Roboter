# Mehrstufiger Bau: Ergebnis ist ein schlankes Laufzeit-Image ohne Bauwerkzeuge.
FROM maven:3.9-eclipse-temurin-21 AS bau
WORKDIR /bau
COPY pom.xml .
COPY src/gemeinsam/pom.xml  src/gemeinsam/
COPY src/simulator/pom.xml  src/simulator/
COPY src/agent/pom.xml      src/agent/
COPY src/zentrale/pom.xml   src/zentrale/
RUN mvn -B -q dependency:go-offline || true
COPY src src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre AS laufzeit
ARG BAUSTEIN
# NFA-SIC-030: Container laufen als Nicht-Root-Benutzer.
#
# GID AUSDRUECKLICH setzen. „useradd --system --uid 10001" legt die Gruppe zwar
# an, aber mit der naechsten freien System-GID — hier 999. DevOps bereitet die
# Volumes anhand von UID:GID vor (Vorgabe 12.08.2026); eine GID, die sich mit
# dem Basisimage aendern kann, macht daraus ein Ratespiel. Ohne
# CAP_DAC_OVERRIDE kann der Container nichts nachtraeglich richtigstellen.
RUN groupadd --system --gid 10001 roboter && \
    useradd --system --uid 10001 --gid 10001 --create-home roboter

# Der PostgreSQL-Client für die Datensicherung (FA-BAK-010, ADR-0010).
#
# Nur die Zentrale braucht ihn — der Agent hat keine Datenbank. Rund 30 MB, die
# im Agentenbild nichts zu suchen hätten.
#
# Der Client muss mindestens so neu sein wie der Server: pg_dump verweigert die
# Arbeit an einer neueren Datenbank. Ubuntu 26.04 liefert 18, die Datenbank
# läuft auf 17. Am 10.08.2026 gemessen: Auszug und Wiederherstellung
# funktionieren. Wer die Datenbank anhebt, muss den Client mit anheben.
ARG BAUSTEIN
RUN if [ "$BAUSTEIN" = "zentrale" ]; then \
      apt-get update -qq && \
      DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        postgresql-client-18 && \
      rm -rf /var/lib/apt/lists/*; \
    fi

WORKDIR /app
COPY --from=bau /bau/src/${BAUSTEIN}/target/${BAUSTEIN}.jar /app/anwendung.jar
COPY docker/brainpool.security /app/brainpool.security
COPY docker/gesundheit.sh /app/gesundheit.sh
# Nur Bausteine mit HTTP-Schnittstelle bekommen einen Port zum Pruefen. Der
# Agent hat keinen: Er baut Verbindungen ausschliesslich nach aussen auf
# (ADR-0002), damit im Kundennetz keine Freigabe noetig ist.
RUN chmod 0555 /app/gesundheit.sh && \
    if [ "$BAUSTEIN" = "zentrale" ]; then echo 8090 > /app/gesundheitsport; fi

# Datenverzeichnis des Agenten: Token, Schluesselpaar und PIN-Ablage.
#
# Es MUSS hier angelegt und uebereignet werden, nicht erst zur Laufzeit: Ein
# frisch erzeugtes benanntes Volume uebernimmt Eigentuemer und Rechte des
# Mountpunkts aus dem Image. Fehlt das Verzeichnis, gehoert das Volume root -
# und der Container laeuft als Nicht-Root (NFA-SIC-030). Er kann dann weder
# sein Token noch seinen privaten Schluessel ablegen.
RUN mkdir -p /daten /sicherungen && chown 10001:10001 /daten /sicherungen
USER 10001
# Speicherobergrenze: NFA-LEI-030 verlangt ≤ 3 GB für den ganzen Stack,
# der Agent allein ≤ 512 MB. Die Laufzeitvorgaben genügen dafür nicht.
# Die Sicherheitsrichtlinie muss beim JVM-Start greifen. Zur Laufzeit gesetzte
# Security-Properties kommen zu spaet: Der Krypto-Provider hat die Kurvenliste
# dann bereits gelesen, und die Freischaltung bleibt wirkungslos.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k \
    -Djava.security.properties=/app/brainpool.security"
# Der Healthcheck gehoert INS IMAGE (DevOps-Vorgabe 12.08.2026): Wer das Image
# startet, bekommt die Pruefung mit, ohne sie in seinem Compose nachbauen zu
# muessen. Nur die Zentrale hat einen HTTP-Endpunkt; Agent und Simulator
# bekommen keinen, sonst meldete Docker sie dauerhaft als ungesund.
#
# start-period: Die Zentrale wendet beim ersten Start Migrationen an. 40 s sind
# grosszuegig bemessen - ein zu knapper Wert liesse den Container in einer
# Neustartschleife enden, und genau danach sucht das Deploy-Skript.
# Dokumentiert den Port im Image. Oeffnet nichts — das Veroeffentlichen
# entscheidet der Betreiber im Compose (auf Labs: nur „expose").
EXPOSE 8090

ARG BAUSTEIN
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=6 \
  CMD ["/app/gesundheit.sh"]

ENTRYPOINT ["java", "-jar", "/app/anwendung.jar"]
