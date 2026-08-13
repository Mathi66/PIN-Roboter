package de.pinroboter.agent;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;

import javax.net.ssl.*;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * TLS-Client für Konnektor und Kartenterminal — mit Brainpool.
 *
 * <p>Belegt durch Messung (siehe {@code docs/00-referenzen/messung-brainpool-tls.md}):
 * <ul>
 *   <li>Ohne BouncyCastle als JSSE-Provider meldet das JDK bei Brainpool
 *       „no supported named groups".</li>
 *   <li>Ohne {@code jdk.tls.namedGroups} werden die Gruppen nicht angeboten,
 *       selbst wenn die Bibliothek sie beherrscht.</li>
 *   <li>Unter TLS 1.3 scheitert der Handshake; TLS 1.2 genügt, weil
 *       [gemSpec_Krypt] {@code GS-A_4385} nur diese Version verpflichtend macht.</li>
 * </ul>
 *
 * <p>Die Zertifikatsprüfung wird <b>nicht</b> abgeschaltet. Wo Geräte
 * selbstsignierte Zertifikate verwenden ({@code A_24102} lässt das zu), ist der
 * richtige Weg ein festgehaltener Fingerabdruck — nicht blindes Vertrauen.
 */
public final class TlsClient {

    static {
        // Java sperrt Brainpool-Kurven in seiner Standard-Sicherheitsrichtlinie:
        // jdk.tls.disabledAlgorithms enthaelt "include jdk.disabled.namedCurves",
        // und dort stehen die Brainpool-Kurven. Ohne diese Freischaltung meldet
        // BouncyCastle "contained no usable NamedGroup values" und der Handshake
        // scheitert - obwohl die Bibliothek die Kurven beherrscht.
        //
        // Gefunden erst beim Ausfuehren, nicht beim Lesen der Dokumentation.
        // BCJSSE muss AUSDRUECKLICH auf BouncyCastle als Krypto-Unterbau
        // verwiesen werden. Ohne Argument nutzt es den JDK-Standardprovider -
        // und der kennt Brainpool nicht. Die Folge ist die irrefuehrende Meldung
        // "'jdk.tls.namedGroups' contains disabled NamedGroup: brainpoolP256r1",
        // obwohl die Sicherheitsrichtlinie die Kurven laengst freigegeben hat.
        BouncyCastleProvider bc = new BouncyCastleProvider();
        Security.addProvider(bc);
        Security.addProvider(new BouncyCastleJsseProvider(bc));
        BrainpoolFreischaltung.kurvenlisteSetzen();   // nach der Provider-Registrierung
    }

    private TlsClient() {}

    /**
     * Der Client für die <b>Zentrale</b> — gewöhnliches Web-TLS.
     *
     * <h2>Warum ein zweiter Client nötig ist</h2>
     *
     * Bis zum 13.08.2026 sprach der Agent mit <em>allem</em> über denselben
     * Client: Konnektor, Kartenterminal und Zentrale. Das fiel nicht auf, weil
     * die Zentrale im Entwicklungsstack über schlichtes {@code http} erreichbar
     * war. Sobald sie hinter einem Reverse Proxy mit einem Zertifikat von
     * Let's Encrypt steht, scheitert die Verbindung: Der Vertrauensspeicher
     * des TI-Clients enthält <b>ausschließlich</b> die CA aus {@code CA_URL} —
     * kein öffentliches Wurzelzertifikat.
     *
     * <p>Gemessen am 13.08.2026 gegen {@code pin-roboter.lab.vps.nieting.de}:
     * Die Cipher-Suites hätten sogar gepasst (Caddy kann TLS 1.2 mit
     * {@code ECDHE-ECDSA-AES128-GCM-SHA256}). Es scheiterte allein am
     * Vertrauensanker.
     *
     * <h2>Warum das nicht dieselben Regeln sind</h2>
     *
     * Die Einschränkung auf TLS 1.2 und zwei Cipher-Suites stammt aus
     * {@code [gemSpec_Krypt]} und gilt für die <b>TI-Strecke</b> — Konnektor
     * und Kartenterminal mit ihren Brainpool-Chipkartenidentitäten. Für die
     * Verbindung zur eigenen Zentrale gilt sie nicht; dort ist TLS 1.3 mit dem
     * System-Vertrauensspeicher das Richtige und Sicherere.
     *
     * <p>Beides in einem Client zu führen war der Fehler: zwei völlig
     * verschiedene Gegenstellen mit einer Konfiguration.
     */
    public static HttpClient fuerZentrale() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)   // ein Umzug wäre ein Fehler
                .build();
    }

    /** Baut einen HTTP-Client, der der übergebenen CA vertraut. */
    public static HttpClient mitVertrauensanker(String caPem) throws Exception {
        SSLContext ctx = kontext(caPem);
        return HttpClient.newBuilder()
                .sslContext(ctx)
                .sslParameters(parameter(ctx))
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static SSLContext kontext(String caPem) throws Exception {
        X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509", "BC")
                .generateCertificate(new ByteArrayInputStream(caPem.getBytes(StandardCharsets.UTF_8)));

        KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
        ks.load(null, null);
        ks.setCertificateEntry("ca", ca);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX", "BCJSSE");
        tmf.init(ks);

        SSLContext ctx = SSLContext.getInstance("TLSv1.2", "BCJSSE");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static SSLParameters parameter(SSLContext ctx) {
        SSLParameters p = ctx.getDefaultSSLParameters();
        p.setProtocols(new String[]{"TLSv1.2"});
        // Nach [gemSpec_Krypt] zulässig sind nur (RSA, AES-CBC) und (ECC, AES-GCM).
        // A_22457 verlangt zusätzlich, nur Suites anzubieten, für deren
        // Authentisierungsverfahren wir selbst Schlüsselmaterial besitzen.
        p.setCipherSuites(new String[]{
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"
        });
        return p;
    }

}
