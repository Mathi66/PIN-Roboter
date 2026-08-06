# Prototyp — Arbeitsstand

**Auftrag (PO, 07.08.2026):** Über Nacht autonom einen lauffähigen Prototypen
bauen. Zuschnitt von der Entwicklung festgelegt, siehe unten.

## Ziel dieses Prototypen

Der **technische Durchstich als lauffähige Software**: Simulator mit
Brainpool-TLS, Agent mit Überwachung und Verifikation, Zentrale mit Oberfläche
und Fachprotokoll — startbar mit einem `docker compose up`.

**Ausdrücklich nicht enthalten:** TOTP-Onboarding, Mandantenverwaltung,
Firmware-Rollout, Excel-Export, Update-Manager. Das ist Wochen, nicht eine Nacht.

## Selbst getroffene Entscheidungen (morgen zum Widerruf vorzulegen)

| Entscheidung | Begründung |
|---|---|
| **Java 21** statt Kotlin | ADR-0006, Expertise des Teams |
| **Javalin** als Webserver | leichtgewichtig; der Agent muss ≤ 512 MB (NFA-LEI-030). Spring Boot wäre dafür grenzwertig. |
| **SOAP von Hand** statt JAX-WS | sechs feste Operationen; ADR-0006 nennt Nachvollziehbarkeit des PIN-Aufrufs als Maßstab |
| **SQLite im Agenten, PostgreSQL in der Zentrale** | ADR-0006 |
| **HTMX eingebettet**, nicht aus dem Netz geladen | keine externen Abhängigkeiten zur Laufzeit |

## Stand

- [x] Projektstruktur, Eltern-POM
- [ ] Simulator: Brainpool-TLS, Konnektor-SOAP, Terminal-PEviM
- [ ] Agent: Überwachung, Sicherungslogik, Verifikationsablauf
- [ ] Zentrale: Oberfläche, Fachprotokoll
- [ ] docker-compose
- [ ] Tests, allen voran FA-PIN-030
