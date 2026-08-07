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
RUN useradd --system --uid 10001 --create-home roboter
WORKDIR /app
COPY --from=bau /bau/src/${BAUSTEIN}/target/${BAUSTEIN}.jar /app/anwendung.jar
COPY docker/brainpool.security /app/brainpool.security
USER 10001
# Speicherobergrenze: NFA-LEI-030 verlangt ≤ 3 GB für den ganzen Stack,
# der Agent allein ≤ 512 MB. Die Laufzeitvorgaben genügen dafür nicht.
# Die Sicherheitsrichtlinie muss beim JVM-Start greifen. Zur Laufzeit gesetzte
# Security-Properties kommen zu spaet: Der Krypto-Provider hat die Kurvenliste
# dann bereits gelesen, und die Freischaltung bleibt wirkungslos.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k \
    -Djava.security.properties=/app/brainpool.security"
ENTRYPOINT ["java", "-jar", "/app/anwendung.jar"]
