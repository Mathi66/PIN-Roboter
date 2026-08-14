# Mehrstufiger Bau. Ergebnis ist ein Laufzeitbild aus drei Teilen:
# Alpine-Basis, ein per jlink zugeschnittenes Java und unser Jar.
#
# ── Warum jlink und Alpine (13.08.2026) ───────────────────────────────────
# Vorher: eclipse-temurin:21-jre auf Ubuntu — 524 MB allein für die Basis, bei
# 18 MB eigenem Code. Gemessen wurde: Basis 524, PostgreSQL-Client 65, Jar 18.
# Nicht die Anwendung war groß, sondern ihr Unterbau.
#
# jlink nimmt nur die JVM-Module, die `jdeps` im Jar tatsächlich findet. Alpine
# bringt eine Basis von 13 MB statt 524. Beides zusammen ersetzt ein
# vollständiges JRE-Image durch das, was dieser Dienst wirklich ausführt.
#
# Der TLS-Unterbau bleibt derselbe: BouncyCastle ist reines Java, kein JNI. Die
# Brainpool-Kette wird nach dem Umbau trotzdem neu gemessen — eine Annahme über
# Kryptographie ist keine Grundlage.

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

# ── Zugeschnittene Java-Laufzeit ──────────────────────────────────────────
# Auf ALPINE gebaut, damit die Laufzeit gegen musl gelinkt ist. Ein auf glibc
# erzeugtes jlink-Bild startet auf Alpine nicht.
FROM eclipse-temurin:21-jdk-alpine AS laufzeitbild
ARG BAUSTEIN
COPY --from=bau /bau/src/${BAUSTEIN}/target/${BAUSTEIN}.jar /tmp/anwendung.jar

# Die Modulliste wird ERMITTELT, nicht gepflegt. Eine Liste von Hand läuft dem
# Code hinterher; ein fehlendes Modul fällt dann erst zur Laufzeit auf — im
# ungünstigsten Fall in einem selten benutzten Zweig.
#
# jdk.crypto.ec: findet jdeps nicht, weil der Provider zur Laufzeit geladen
# wird. Ohne ihn fehlen die EC-Kurven. jdk.localedata und java.logging ebenso:
# deutsche Formatierung und die Protokollausgabe von Jetty.
RUN MODULE=$(jdeps --print-module-deps --ignore-missing-deps \
              --multi-release 21 /tmp/anwendung.jar) && \
    echo "Ermittelte Module: $MODULE" && \
    jlink --add-modules "${MODULE},jdk.crypto.ec,jdk.localedata,java.logging" \
          --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
          --include-locales=de,en \
          --output /java

# ── Laufzeit ──────────────────────────────────────────────────────────────
FROM alpine:3.21 AS laufzeit
ARG BAUSTEIN

# NFA-SIC-030: Container laufen als Nicht-Root-Benutzer.
#
# GID AUSDRUECKLICH setzen. DevOps bereitet die Volumes anhand von UID:GID vor
# (Vorgabe 12.08.2026); ohne CAP_DAC_OVERRIDE kann der Container nichts
# nachtraeglich richtigstellen.
RUN addgroup -S -g 10001 roboter && \
    adduser -S -u 10001 -G roboter -h /home/roboter roboter

# Der PostgreSQL-Client für die Datensicherung (FA-BAK-010, ADR-0010).
#
# Nur die Zentrale braucht ihn — der Agent hat keine Datenbank.
#
# Der Client muss mindestens so neu sein wie der Server: pg_dump verweigert die
# Arbeit an einer neueren Datenbank. Alpine 3.21 liefert 17; die Datenbank läuft
# auf 16 (Labs) beziehungsweise 17 (Entwicklung). Wer den Server auf 18 anhebt,
# muss den Client mit anheben — dann Alpine-Version wechseln oder das
# PGDG-Paket verwenden.
RUN if [ "$BAUSTEIN" = "zentrale" ]; then \
      apk add --no-cache postgresql17-client; \
    fi

ENV JAVA_HOME=/java
ENV PATH="/java/bin:${PATH}"
COPY --from=laufzeitbild /java /java

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
# und der Container laeuft als Nicht-Root (NFA-SIC-030).
# /auftrag ist der Briefkasten zum Aktualisierungsdienst (FA-AGT-100). Er muss
# hier angelegt werden, nicht erst beim Mounten: Docker übernimmt die Rechte
# des Verzeichnisses aus dem Abbild, wenn ein leeres Volume daraufgelegt wird.
# Ohne diesen Schritt gehört das Volume root, und der Agent — der als 10001
# läuft — kann nichts hineinlegen. Am 14.08.2026 am laufenden Stack gemessen:
# „Der Auftrag ließ sich nicht ablegen … ist /auftrag beschreibbar?"
RUN mkdir -p /daten /sicherungen /auftrag \
 && chown 10001:10001 /daten /sicherungen /auftrag
USER 10001

# Dokumentiert den Port im Image. Oeffnet nichts — das Veroeffentlichen
# entscheidet der Betreiber im Compose (auf Labs: nur „expose").
EXPOSE 8090

# Speicherobergrenze: NFA-LEI-030 verlangt ≤ 3 GB für den ganzen Stack, der
# Agent allein ≤ 512 MB. Die Sicherheitsrichtlinie muss beim JVM-Start greifen:
# Zur Laufzeit gesetzte Security-Properties kommen zu spaet, der Krypto-Provider
# hat die Kurvenliste dann bereits gelesen.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k \
    -Djava.security.properties=/app/brainpool.security"

HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=6 \
  CMD ["/app/gesundheit.sh"]

ENTRYPOINT ["java", "-jar", "/app/anwendung.jar"]
