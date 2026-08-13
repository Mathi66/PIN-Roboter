package de.pinroboter.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Die Adresse der Zentrale — Vorrang, Prüfung, Dauerhaftigkeit.
 *
 * <p>Der Anlass ist ein <b>Serverumzug</b> (PO, 13.08.2026): Zieht die Zentrale
 * um, zeigen alle ausgelieferten Agenten ins Leere, und niemand kommt an 150
 * Praxisrechner, um dort eine {@code .env} zu bearbeiten.
 *
 * <p>Nicht geprüft wird hier die Erreichbarkeitsprobe — sie braucht eine
 * antwortende Zentrale und gehört in die Systemnachweise. Geprüft wird alles,
 * was <em>davor</em> entscheidet.
 */
class ZentralenadresseTest {

    @Test
    @DisplayName("Ohne gespeicherte Datei gilt die Umgebungsvariable")
    void umgebungAlsVorgabe(@TempDir Path tmp) {
        var a = new Zentralenadresse(tmp.resolve("zentrale-url"), "https://alt.example/");
        assertEquals("https://alt.example/", a.aktuell().toString());
        assertFalse(a.abweichend());
    }

    @Test
    @DisplayName("Die gespeicherte Adresse sticht die Umgebungsvariable")
    void dateiGewinnt(@TempDir Path tmp) throws Exception {
        // Sie ist die jüngere, bewusste Entscheidung an diesem Standort; die
        // Umgebungsvariable stammt aus dem Einrichtungspaket von damals.
        Path d = tmp.resolve("zentrale-url");
        Files.writeString(d, "https://neu.example/", StandardCharsets.UTF_8);

        var a = new Zentralenadresse(d, "https://alt.example/");
        assertEquals("https://neu.example/", a.aktuell().toString());
        assertEquals("https://alt.example/", a.ausUmgebung().toString(),
                "Die Herkunft muss sichtbar bleiben — sonst rätselt jemand, "
                + "warum seine Änderung an der .env nichts bewirkt");
        assertTrue(a.abweichend());
    }

    @Test
    @DisplayName("Eine unlesbare Datei verhindert den Start nicht")
    void unlesbareDateiIstKeinAbbruch(@TempDir Path tmp) throws Exception {
        // Lieber irgendwohin laufen als gar nicht starten: Ein Agent, der wegen
        // einer kaputten Datei nicht hochkommt, ist in einer Praxis nicht mehr
        // zu retten.
        Path d = tmp.resolve("zentrale-url");
        Files.writeString(d, "   ", StandardCharsets.UTF_8);
        var a = new Zentralenadresse(d, "https://alt.example/");
        assertEquals("https://alt.example/", a.aktuell().toString());
    }

    @Test
    @DisplayName("Eine Eingabe ohne Schema wird abgelehnt")
    void ohneSchema(@TempDir Path tmp) {
        var a = new Zentralenadresse(tmp.resolve("z"), "https://alt.example/");
        String f = a.setzen("pin-roboter.example.de");
        assertNotNull(f);
        assertTrue(f.contains("http://") || f.contains("https://"), f);
        assertEquals("https://alt.example/", a.aktuell().toString(), "unverändert");
    }

    @Test
    @DisplayName("Eine leere Eingabe ändert nichts")
    void leereEingabe(@TempDir Path tmp) {
        var a = new Zentralenadresse(tmp.resolve("z"), "https://alt.example/");
        assertNotNull(a.setzen(""));
        assertNotNull(a.setzen(null));
        assertEquals("https://alt.example/", a.aktuell().toString());
    }

    @Test
    @DisplayName("Ein anderes Schema als http(s) wird abgelehnt")
    void fremdesSchema(@TempDir Path tmp) {
        var a = new Zentralenadresse(tmp.resolve("z"), "https://alt.example/");
        assertNotNull(a.setzen("file:///etc/passwd"));
        assertNotNull(a.setzen("ftp://beispiel.de/"));
        assertEquals("https://alt.example/", a.aktuell().toString());
    }

    @Test
    @DisplayName("Eine nicht erreichbare Adresse wird nicht übernommen")
    void nichtErreichbarBleibtAussen(@TempDir Path tmp) {
        // Der Kern der Absicherung: Ein Tippfehler würde den Agenten sonst
        // dauerhaft von seiner Zentrale abschneiden — und die einzige Stelle
        // zum Korrigieren wäre wieder der Praxisrechner.
        var a = new Zentralenadresse(tmp.resolve("z"), "https://alt.example/");
        // Port 9 ist der „discard“-Dienst und praktisch nie belegt.
        String f = a.setzen("http://127.0.0.1:9/");
        assertNotNull(f, "Eine tote Adresse darf nicht übernommen werden");
        assertFalse(f.contains("null"), "Die Meldung muss lesbar sein statt technisch: " + f);
        assertEquals("https://alt.example/", a.aktuell().toString());
        assertFalse(Files.exists(tmp.resolve("z")), "Es darf nichts gespeichert worden sein");
    }
}
