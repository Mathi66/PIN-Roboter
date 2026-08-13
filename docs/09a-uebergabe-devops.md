# Übergabe an DevOps — PIN-Roboter auf Labs

**Antwort auf:** Anforderungen an die Anwendung „PIN-Roboter" (DevOps, 12.08.2026)
· **Stand:** 13.08.2026 · **Version:** 0.3.1

Alle Angaben sind **gemessen**, nicht geschätzt: an einem Container, der mit
euren Vorgaben gestartet wurde (`read_only`, `cap_drop: ALL`,
`no-new-privileges`, `pids_limit`, PostgreSQL 16-alpine). Was wir nicht
erfüllen, steht in Abschnitt 6 — mit Begründung, nicht weggelassen.

---

## 1. Container, Ports, Benutzer, Schreibpfade

| Angabe | Wert |
|---|---|
| Container | `pin-roboter` (Anwendung), `pin-roboter-db` (PostgreSQL) |
| Image | `ghcr.io/mathi66/pin-roboter:X.Y.Z`, **nur `linux/amd64`** |
| Zweites Image | `ghcr.io/mathi66/pin-roboter-agent:X.Y.Z` — der Standort-Agent. Läuft **im Praxisnetz, nicht auf Labs**; unter derselben Version veröffentlicht, damit die Bausteine zusammenpassen. |
| Port im Container | **8090** (HTTP, kein TLS), im Image als `EXPOSE 8090` |
| Benutzer im Image | **UID 10001, GID 10001** (`roboter`), gesetzt per `groupadd`/`useradd` |
| Prozesse | ein Dienst je Container, kein Supervisor, kein Cron |
| Docker-Socket | wird **nicht** gebraucht |

**Schreibpfade — vollständig:**

| Pfad | Art | Zweck |
|---|---|---|
| `/tmp` | **tmpfs**, 64 MB genügen | Die JVM schreibt dorthin. `-Djava.io.tmpdir=/tmp` ist im Compose ausdrücklich gesetzt, damit es nicht am Basisimage hängt. |
| `/sicherungen` | **benanntes Volume** | Sicherungsdateien der Anwendung (`pg_dump`-Auszüge). Nur nötig, wenn `SICHERUNG_PLAN=an`. |
| `/var/lib/postgresql/data` | **benanntes Volume** (DB-Container) | die Datenbank |

Sonst schreibt die Anwendung **nirgends**. Das Wurzeldateisystem darf
schreibgeschützt sein — nachgewiesen: Der Container läuft mit `read_only: true`
und meldet `healthy`.

> Der Standort-Agent (eigenes Image, `/daten`) läuft **beim Kunden**, nicht auf
> Labs. Für dieses Deployment ist er ohne Belang.

---

## 2. Umgebungsvariablen — vollständig

| Variable | Pflicht | Vorgabe | Bedeutung |
|---|---|---|---|
| `DB_PASSWORD` | **ja** | — | Kennwort der Datenbank. **Fehlt sie, bricht der Start mit Meldung und Code 2 ab.** |
| `GEHEIMNIS_SCHLUESSEL` | **ja** (Labs) | — | 32 Byte Base64. Verschlüsselt die hinterlegten Geräte-Zugangsdaten (ADR-0008). Technisch optional — ohne ihn läuft alles außer dem Speichern von Kennwörtern; auf Labs setzen wir ihn per `:?` in der Compose als Pflicht. **Muss in die Sicherung, aber nicht in dieselbe Datei wie der Datenbankauszug.** |
| `DB_URL` | nein | `jdbc:postgresql://datenbank:5432/pinroboter` | |
| `DB_USER` | nein | `pinroboter` | |
| `PORT` | nein | `8090` | |
| `PROTOKOLL_BUDGET` | nein | `2G` | Speicherbudget des Fachprotokolls. Ab 80 % rotiert es, gestaffelt nach Wichtigkeit. |
| `SICHERUNG_ABLAGE` | nein | — | Verzeichnis der Sicherungen. Auf Labs `/sicherungen`. Ohne Angabe keine Dateisicherung. |
| `SICHERUNG_PLAN` | nein | `an` | `aus` schaltet geplante Sicherungen ab. |
| `START_ADMIN_BENUTZER` | nein | — | siehe Abschnitt 3 |
| `START_ADMIN_NAME` | nein | — | Anzeigename des ersten Administrators |
| `START_ADMIN_PASSWORT` | nein | — | Klartext. **Besser die Datei-Variante.** |
| `START_ADMIN_PASSWORT_DATEI` | nein | — | Pfad zu einem Docker-Secret. Erscheint dann nicht in `docker inspect`. |
| `START_ADMIN_TOTP` | nein | — | Base32-Geheimnis für den zweiten Faktor |
| `JAVA_TOOL_OPTIONS` | nein | im Image gesetzt | Speichergrenze und Brainpool-Kurven. **Wird sie überschrieben, muss `-Djava.security.properties=/app/brainpool.security` erhalten bleiben** — sonst scheitert die TLS-Verbindung zu Konnektor und Kartenterminal. |
| `TZ` | nein | Host | Wir speichern zeitzonenbehaftet (`timestamptz`); UTC auf dem Host ist richtig. |

Vorlage: [`deploy/labs/env.beispiel`](../deploy/labs/env.beispiel).

---

## 3. Wie der erste Administrator entsteht

**Zwei Wege, beide ohne Standardzugang** — es gibt kein `admin/admin`:

1. **Empfohlen für Labs:** `START_ADMIN_BENUTZER`, `START_ADMIN_NAME`,
   `START_ADMIN_PASSWORT_DATEI` und `START_ADMIN_TOTP` setzen. Die Anwendung
   legt das Konto beim Start an, **falls es fehlt**; ein vorhandenes Konto wird
   nie verändert.
2. **Ohne diese Variablen:** Beim ersten Aufruf erscheint eine
   Ersteinrichtungsseite, die genau einmal ein TOTP-Geheimnis anzeigt.

> **Wichtig für eine öffentlich erreichbare Instanz:** Weg 2 ist ein Wettlauf —
> wer die Seite zuerst aufruft, wird Administrator. Bitte Weg 1 nehmen, das
> Konto prüfen und **erst dann** den DNS-Namen veröffentlichen.

Anmeldung: Benutzername + Passwort + **TOTP, verpflichtend und nicht
abschaltbar**. Passwörter mit **Argon2id** (BouncyCastle), nie umkehrbar.

---

## 4. Was gesichert werden muss

| Gegenstand | Sichern? | Begründung |
|---|---|---|
| Volume `pinroboter-db` | **ja** | Bestand, Konten, Fachprotokoll, Organisationsstruktur |
| `GEHEIMNIS_SCHLUESSEL` (aus der `.env`) | **ja, getrennt** | Ohne ihn sind die verschlüsselten Geräte-Zugangsdaten in der Datenbank unbrauchbar. Nicht in dieselbe Datei wie den Auszug — sonst schützt die Verschlüsselung vor nichts. |
| Volume `pinroboter-sicherungen` | ja, wenn genutzt | Auszüge der anwendungseigenen Sicherung |
| Container-Dateisystem, `/tmp` | **nein** | zustandslos, jederzeit neu erzeugbar |
| Images | **nein** | liegen in GHCR |

Die Anwendung bringt **eine eigene Sicherung** mit (verschlüsselte Auszüge nach
`/sicherungen`, Zeitplan über `SICHERUNG_PLAN`). Sie ersetzt eure
Volume-Sicherung nicht, sondern ergänzt sie um einen Weg, den der Betreiber
selbst bedienen kann.

---

## 5. Ressourcen, Netz, Client

**Gemessen** an der laufenden Instanz mit Demobestand (36 Kartenterminals):

| | Arbeitsspeicher | Prozessorlast im Leerlauf |
|---|---|---|
| Anwendung | **196 MiB** | ~1 % eines Kerns |
| PostgreSQL | **66 MiB** | ~0,2 % |

Für Labs sind **512 MiB** für die Anwendung reichlich bemessen; die JVM ist auf
`MaxRAMPercentage=70` eingestellt und richtet sich nach dem Container-Limit.
Bei der Zielgröße des Produkts (bis 5.000 Kartenterminals) rechnen wir mit
1–2 GB — das ist eine Rechnung, keine Messung, und für Labs ohne Belang.

**Ausgehende Verbindungen:**

| Ziel | Wann | Zweck |
|---|---|---|
| `ghcr.io` (443) | nur wenn ein Betreiber in der Oberfläche Zugangsdaten hinterlegt | Prüfung auf neue Produktversionen. **Ohne Zugangsdaten findet keine Verbindung statt.** |
| SMTP-Server nach Konfiguration | nur wenn E-Mail-Benachrichtigung eingerichtet ist | Alarmmeldungen. Host und Port trägt der Betreiber selbst ein. |
| Konnektor / Kartenterminals im Kundennetz | **nicht von Labs aus** | Das macht der Standort-Agent beim Kunden. Die Zentrale nimmt nur eingehende Verbindungen des Agenten an. |

Ohne diese beiden Einstellungen baut die Anwendung **keine ausgehende
Verbindung** auf.

**Keine großen Uploads**, keine Antwortzeiten über 60 s. Der Excel-Export des
Protokolls ist der größte Download; bei rotierendem Budget bleibt er im
einstelligen MB-Bereich.

**Keine WebSockets, keine Server-Sent Events** in der Zentrale. Die Oberfläche
aktualisiert über HTMX mit gewöhnlichen HTTP-Aufrufen im Sekundentakt.
(WebSockets nutzt der Agent zum Kartenterminal — im Kundennetz, nicht hier.)

**Client:** Browser-Anwendung, nichts zu installieren. Unterstützt sind aktuelle
Chrome, Edge, Firefox und Safari (je die letzten zwei Hauptversionen). Die
Oberfläche ist responsiv; **Desktop ist der Primärfall, Tablet muss
funktionieren** — Telefon ist nicht das Ziel. Barrierefreiheit nach WCAG 2.2 AA
ist Vorgabe und wird maschinell mitgeprüft.

**Maschinenschnittstelle — bitte im Proxy beachten:**

| Pfad | Authentifizierung | Anmerkung |
|---|---|---|
| `/api/registrieren` | einmaliges Registrierungsgeheimnis | Der Standort-Agent löst es beim ersten Start gegen ein Token ein. |
| `/api/agent/*` | Kopfzeile `X-Agent-Token` | dauerhaftes Token je Agent, **keine Sitzung, kein Cookie** |
| `/health` | **keine** | siehe unten |

Auf Labs läuft **kein** Standort-Agent — die `/api/`-Pfade werden dort nicht
benutzt. Sie sind trotzdem erreichbar; wenn ihr sie im Proxy sperren wollt,
stört das den Betrieb auf Labs nicht.

---

## 6. Punkt für Punkt gegen eure Liste

### Erfüllt

| Anforderung | Nachweis |
|---|---|
| `linux/amd64`, ein Dienst je Container | Workflow baut nur amd64 |
| kein Bauen auf dem Server | `deploy/labs/docker-compose.yml` hat kein `build:` |
| kein Docker-Socket | wird nirgends eingebunden |
| `read_only: true` | Container gestartet und `healthy`, alle Schreibpfade oben genannt |
| `cap_drop: ALL`, `no-new-privileges` | ebenso gestartet, keine Rechteänderung zur Laufzeit |
| fester Nicht-Root-Benutzer | `uid=10001(roboter) gid=10001(roboter)` — gemessen im Container |
| `pids_limit` | 512 für die Anwendung, 256 für die Datenbank |
| fester Port, nur `expose` | 8090, `deploy/labs/docker-compose.yml` öffnet keinen Host-Port |
| kein TLS im Container | die Zentrale spricht HTTP |
| `/health` ohne Anmeldung, mit Version | `{"status":"ok","db":true,"version":"0.3.1"}`, 503 wenn die Datenbank fehlt |
| `HEALTHCHECK` im Image, ohne curl/wget | `docker/gesundheit.sh`, bash `/dev/tcp` |
| Protokoll nach stdout | keine Logdateien im Container |
| SIGTERM ≤ 10 s | **gemessen: 1 s** |
| Konfiguration nur über Env | keine Konfigurationsdatei im Image |
| Pflichtvariablen brechen ab | `DB_PASSWORD` fehlt → Meldung + Exit 2 |
| keine Geheimnisse im Image | kein Standardpasswort, kein eingebackener Schlüssel |
| Migrationen beim Start, idempotent | 27 Migrationen, jede über `schema_stand` einmalig |
| zeitzonenbehaftete Zeitstempel | durchgängig `timestamptz` |
| `X-Forwarded-Proto` auswerten | steuert das `Secure`-Kennzeichen am Cookie |
| Client-Adresse aus `X-Forwarded-For` | erster Eintrag, ein vertrauenswürdiger Zwischenschritt |
| `X-Authentik-*` niemals als Anmeldung | die Anwendung wertet **keine** Authentifizierungs-Kopfzeilen aus; die Anmeldung hängt allein an der Sitzung |
| Argon2id | BouncyCastle, mit Parametern aus dem Code |
| kein Standard-Zugang | siehe Abschnitt 3 |
| Anmeldeversuche begrenzen | zunehmende Verzögerung **je Absender** (ab 6. Fehlversuch, bis 30 s) **plus** Sperre je Konto |
| Sitzungs-Cookies | `HttpOnly`, `SameSite=Strict` (strenger als das geforderte `Lax`), `Secure` bei HTTPS, 8 h Lebensdauer, neue Kennung nach der Anmeldung |
| TOTP | **Pflicht, nicht abschaltbar** |
| Anmeldungen protokollieren | Erfolg und Fehlschlag mit Zeit und Adresse im Fachprotokoll |
| GitHub Actions, GHCR, Tag ohne `v`, kein `:latest` | `.github/workflows/veroeffentlichen.yml` |
| Smoke-Test im Workflow | startet das Image gehärtet, prüft `/health`, Version, UID, SIGTERM |
| unveränderliche Tags | der Workflow bricht ab, wenn der Tag in GHCR existiert |
| `RELEASENOTES.md` | im Repositoriumswurzelverzeichnis |

### Abweichungen — bitte prüfen

**1. PostgreSQL 16 statt 17.** Ihr gebt `postgres:16-alpine` vor, unser
Entwicklungsstack nutzt 17. Für Labs haben wir **16-alpine** eingetragen und
geprüft: Schema, Migrationen, Sicherung und Wiederherstellung laufen. Der
`pg_dump`-Client im Image ist Version **18** — neuer als der Server, das ist die
zulässige Richtung. Falls ihr später auf 17 oder 18 geht, funktioniert es
weiterhin; **umgekehrt nicht**: Ein älterer Client verweigert die Arbeit an einer
neueren Datenbank.

**2. CSRF-Schutz ohne Token.** Wir haben keinen Token je Formular, sondern zwei
andere Linien: `SameSite=Strict` am Sitzungscookie (ein fremder Ursprung bekommt
es gar nicht erst mit) und eine **Prüfung des `Origin`-Kopfs** bei jedem
verändernden Aufruf — abgelehnt mit 403, nachgewiesen. Das ist der von OWASP
als gleichwertig genannte Weg. Wenn ihr Tokens ausdrücklich verlangt, sagt
Bescheid; das ist Arbeit, aber machbar.

**3. Das Image ist 632 MB.** Groß für einen Dienst, der 196 MiB Arbeitsspeicher
braucht. Ursache: `eclipse-temurin:21-jre` auf Ubuntu plus der
PostgreSQL-Client für die Sicherung. Ein Alpine-Basisimage scheidet aus — wir
brauchen Brainpool-Kurven aus BouncyCastle und haben die TLS-Kette nur auf
dieser Kombination gemessen. Wenn die Größe stört, wäre ein `jlink`-Laufzeitbild
der nächste Schritt; das würde ich vor einem Produktionsdeployment ohnehin
angehen.

**4. Kein `HEALTHCHECK` für den Standort-Agenten.** Er hat keinen Port — er baut
Verbindungen ausschließlich nach außen auf, damit im Kundennetz keine Freigabe
nötig ist (ADR-0002). Für Labs ohne Belang, hier steht es nur der Vollständigkeit
halber.

---

## 7. Übergabe für den ersten Rollout

```
PIN-Roboter – Deploy v0.3.1 auf Labs
Image:               ghcr.io/mathi66/pin-roboter:0.3.1
Schema-Änderung:     keine (0.3.0 → 0.3.1)
Neue Env:            DB_PASSWORD (Pflicht), GEHEIMNIS_SCHLUESSEL (Pflicht),
                     PROTOKOLL_BUDGET, SICHERUNG_ABLAGE, SICHERUNG_PLAN,
                     START_ADMIN_* (nur zur Ersteinrichtung)
Prüfung danach:      curl -s https://pin-roboter.lab.vps.nieting.de/health
                     → {"status":"ok","db":true,"version":"0.3.1"}
                     Anmeldeseite erreichbar, Protokoll zeigt „Migration 27 angewendet"
Rückfall:            0.3.0 — genügt, da keine Schemaänderung
```

**Reihenfolge, die wir empfehlen:**

1. `.env` aus [`deploy/labs/env.beispiel`](../deploy/labs/env.beispiel) füllen,
   `chmod 0600`.
2. Stack starten, `/health` abwarten.
3. Am Administratorkonto anmelden, TOTP prüfen.
4. **Erst dann** den DNS-Namen veröffentlichen.

Der Caddy-Block aus eurer Vorlage passt unverändert; `reverse_proxy pin-roboter:8090`.
