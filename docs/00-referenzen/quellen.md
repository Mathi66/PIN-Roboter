# Quellenverzeichnis

Alle technischen Aussagen im Projekt werden auf diese Quellen zurückgeführt.
Abrufdatum durchgehend: **06.08.2026**.

Regel: Sekundärquellen (Hersteller-Blogs, Foren, Support-Artikel) dienen nur als
Hinweis. Verbindlich ist ausschließlich die gematik-Primärquelle.

---

## Primärquellen — gematik-Spezifikationen

| Kürzel | Dokument | Version | Bezug |
|---|---|---|---|
| `[gemSpec_KT]` | Spezifikation eHealth-Kartenterminal | **3.17.1** (freigegeben) | https://gemspec.gematik.de/downloads/gemSpec/gemSpec_KT/gemSpec_KT_V3.17.1.html |
| `[gemSpec_Kon]` | Spezifikation Konnektor | **5.25.0** | https://gemspec.gematik.de/downloads/gemSpec/gemSpec_Kon/gemSpec_Kon_V5.25.0.html |

Lokale Kopien liegen unter `docs/00-referenzen/spezifikationen/` und sind
**bewusst nicht versioniert** (Größe, Fremdurheberschaft). Sie lassen sich
jederzeit über die obigen URLs erneut beziehen.

Übersichtsseiten für Versionsstände:
- https://gemspec.gematik.de/docs/gemSpec/gemSpec_KT/latest/
- https://gemspec.gematik.de/docs/gemSpec/gemSpec_Kon/latest/

### Referenzierte, noch nicht ausgewertete Primärquellen

| Kürzel | Dokument | Status |
|---|---|---|
| `[SICCT]` | Secure Interoperable ChipCard Terminal (TeleTrusT) | offen — Basis der gemSpec_KT, Kommandos PERFORM VERIFICATION (#5.19), MODIFY VERIFICATION DATA (#5.20), CMD DO (#5.5.10.23) |
| `[gemILF_PS]` | Implementierungsleitfaden Primärsysteme, V2.14.0 | offen — beschreibt die Clientsystem-Sicht auf den Konnektor |
| `[gemProdT_KT]` | Produkttypsteckbrief/Prüfvorschrift eHealth-Kartenterminal | offen — enthält die Option „PEviM" als prüfbares Merkmal |
| `[TR-03114]` | BSI TR-03114 (entfernte PIN-Eingabe) | offen — betrifft Stapel-/Komfortsignatur, für SMC-B nicht einschlägig |

---

## Sekundärquellen (Hinweischarakter, nicht verbindlich)

| Thema | Quelle |
|---|---|
| Highspeed-Konnektor (HSK), Zulassung | https://fachportal.gematik.de/telematikinfrastruktur/komponenten-dienste/highspeed-konnektor · https://www.gematik.de/newsroom/news-detail/aktuelles-erste-produktzulassung-fuer-einen-highspeed-konnektor |
| HSK-Produkte | https://rise-ti.de/produkte/hsk/ · https://www.secunet.com/loesungen/telematikinfrastruktur/secunet-highspeedkonnektor |
| CHERRY ST-1506, Weboberfläche/REST-API | https://www.cherry.de/en-us/product/ehealth-terminal-st-1506 · https://curenect.de/blog/cherry-remote-management-schnittstelle/ |
| SMC-B-Verifikation über CHERRY-Weboberfläche (Ablaufbeschreibung) | https://ti-support.visionmaxx.net/support/solutions/articles/101000473706-smc-b-über-cherry-weboberfläche-verifizieren |
| Ingenico ORGA 6141 online, Kurzanleitung | https://cdn.ingenico.com/binaries/content/assets/healthcare/manuals-ger/kurzanleitung_orga_6141_online_v8.9.1.pdf |
| Vergleichbare Lösung: automatische PIN-Verifikation im TI-Gateway | https://ehealthblog.akquinet.de/ehealth-blog/blogbeitrag-details/stabiler-praxisbetrieb-ohne-pin-panik-fuer-smc-b |
| Referenzimplementierung Monitoring (MIT-Lizenz, Ruby/Rails) — nutzt `GetResourceInformation`, `GetCardTerminals`, `GetCards`, `GetPinStatus`, `VerifyPin`, `ReadCardCertificate` | https://github.com/swobspace/cocard |
| gematik zum sicheren Umgang mit der SMC-B | https://www.gematik.de/newsroom/news-detail/aktuelles-video-erklaert-sicheren-umgang-mit-der-smc-b-karte |

---

## Korrekturhinweis zur Recherche

Eine automatisierte Zusammenfassung der gemSpec_KT lieferte am 06.08.2026 die
Anforderungs-IDs `A_21515`, `A_21516`, `A_21617` sowie die Auflösung
„PEviM = Personalisierungs- und Verwaltungssystem". **Beides ist falsch.** Die
Prüfung am Volltext ergab die tatsächlichen IDs `A_24074`–`A_24157` und die
Auflösung „PIN-Eingabe via Managementschnittstelle".

Konsequenz für das Projekt: Zusammenfassungen Dritter werden nie als Beleg
verwendet. Anforderungs-IDs und Wortlaute werden ausschließlich am Volltext der
Spezifikation verifiziert.
