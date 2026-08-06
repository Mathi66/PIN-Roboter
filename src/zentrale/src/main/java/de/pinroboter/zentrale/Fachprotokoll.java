package de.pinroboter.zentrale;

import de.pinroboter.gemeinsam.Ereignisart;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Fachprotokoll — nur anfügbar, filterbar, mit Speicherbudget.
 *
 * <p>Im Prototyp im Arbeitsspeicher; im Produkt in einem eigenen Volume,
 * getrennt von den Betriebsdaten (FA-PRO-070). Die Grundregel gilt schon hier:
 * <b>protokolliert werden Zustandsänderungen und Handlungen, keine Abfragen</b>.
 * Bei 5.000 Terminals im Minutentakt entstünden sonst 7,2 Millionen Einträge am
 * Tag, und das Protokoll wäre unbrauchbar.
 */
public class Fachprotokoll {

    private final AtomicLong naechsteId = new AtomicLong(1);
    private final Deque<Ereignis> eintraege = new ArrayDeque<>();
    private final int hoechstzahl;

    public Fachprotokoll(int hoechstzahl) { this.hoechstzahl = hoechstzahl; }

    public synchronized Ereignis anfuegen(String einrichtung, String geraet, Ereignisart art,
                                          boolean erfolg, String ausloeser, String vorgang,
                                          String text, String handlung) {
        Ereignis e = new Ereignis(naechsteId.getAndIncrement(), Instant.now(),
                einrichtung, geraet, art, erfolg, ausloeser, vorgang, text, handlung);
        eintraege.addFirst(e);
        // Gestaffelte Rotation: technische Einträge zuerst, sicherheitsrelevante
        // zuletzt. Ein volles Protokoll darf die Kernfunktion nie anhalten.
        while (eintraege.size() > hoechstzahl) {
            Ereignis aeltester = eintraege.peekLast();
            if (aeltester != null && aeltester.art().stufe() == Ereignisart.Stufe.SICHERHEIT
                    && eintraege.size() < hoechstzahl * 2) break;
            eintraege.pollLast();
        }
        return e;
    }

    public synchronized List<Ereignis> filtern(String geraet, String art, String ergebnis, int grenze) {
        return eintraege.stream()
                .filter(e -> geraet == null || geraet.isBlank() || geraet.equals(e.geraet()))
                .filter(e -> art == null || art.isBlank() || e.art().name().equals(art))
                .filter(e -> ergebnis == null || ergebnis.isBlank()
                        || ("erfolg".equals(ergebnis) == e.erfolg()))
                .limit(grenze)
                .collect(Collectors.toList());
    }

    public synchronized int anzahl() { return eintraege.size(); }
}
