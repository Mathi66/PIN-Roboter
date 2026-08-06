package de.pinroboter.simulator;

import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;

import javax.net.ssl.*;
import java.security.KeyStore;
import java.security.Security;

/**
 * Baut einen TLS-Kontext, der ausschließlich Brainpool anbietet.
 *
 * <p>Zwei Dinge sind hier gemessen und nicht geraten (Messbericht vom
 * 06.08.2026, {@code docs/00-referenzen/messung-brainpool-tls.md}):
 *
 * <ul>
 *   <li><b>TLS 1.2, nicht 1.3.</b> Unter TLS 1.3 scheitern die geprüften
 *       Bibliotheken mit Brainpool. Zulässig ist das, weil [gemSpec_Krypt]
 *       {@code GS-A_4385} nur TLS 1.2 verpflichtend macht und {@code A_18467}
 *       TLS 1.3 ausdrücklich als Kann-Bestimmung führt.</li>
 *   <li><b>BouncyCastle ist nötig.</b> Das Standard-JDK meldet bei
 *       Brainpool-Gruppen „no supported named groups". Konnektor und HSK
 *       verwenden dieselbe Bibliothek — damit läuft auf beiden Seiten dieselbe
 *       Umsetzung von RFC 5639.</li>
 * </ul>
 */
public final class TlsAufbau {

    /**
     * Zulässige Cipher-Suites nach [gemSpec_Krypt]: nur die Kombinationen
     * (RSA, AES-CBC) und (ECC, AES-GCM). Bei der Vorabmessung einigten sich
     * Client und Server ohne diese Einschränkung auf ChaCha20-Poly1305 — eine
     * Suite, die die Spezifikation nicht zulässt. Die Vorgabe der Bibliothek
     * genügt also nicht; die Liste muss ausdrücklich gesetzt werden.
     */
    public static final String[] ZULAESSIGE_SUITES = {
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"
    };

    public static final String[] PROTOKOLLE = {"TLSv1.2"};

    private TlsAufbau() {}

    static {
        // Java sperrt Brainpool-Kurven in seiner Standard-Sicherheitsrichtlinie:
        // jdk.tls.disabledAlgorithms enthaelt "include jdk.disabled.namedCurves",
        // und dort stehen die Brainpool-Kurven. Ohne diese Freischaltung meldet
        // BouncyCastle "contained no usable NamedGroup values" und der Handshake
        // scheitert - obwohl die Bibliothek die Kurven beherrscht.
        //
        // Gefunden erst beim Ausfuehren, nicht beim Lesen der Dokumentation.
        Security.addProvider(new BouncyCastleJsseProvider());
        // Ohne diese Eigenschaft bietet der Handshake die Brainpool-Gruppen nicht an,
        // obwohl die Bibliothek sie beherrscht. Gemessen am 06.08.2026.
        BrainpoolFreischaltung.kurvenlisteSetzen();   // nach der Provider-Registrierung
    }

    public static SSLContext serverKontext(BrainpoolZertifikate.Kette kette) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
        ks.load(null, null);
        ks.setKeyEntry("server", kette.schluessel().getPrivate(), new char[0],
                new java.security.cert.Certificate[]{kette.zertifikat(), kette.caZertifikat()});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX", "BCJSSE");
        kmf.init(ks, new char[0]);

        SSLContext ctx = SSLContext.getInstance("TLSv1.2", "BCJSSE");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** Legt Protokoll und Suite-Liste auf einem Server-Socket fest. */
    public static void parameterSetzen(SSLServerSocket socket) {
        socket.setEnabledProtocols(PROTOKOLLE);
        socket.setEnabledCipherSuites(schnittmenge(socket.getSupportedCipherSuites()));
    }

    public static void parameterSetzen(SSLParameters p) {
        p.setProtocols(PROTOKOLLE);
        p.setCipherSuites(ZULAESSIGE_SUITES);
    }

    private static String[] schnittmenge(String[] unterstuetzt) {
        java.util.List<String> ergebnis = new java.util.ArrayList<>();
        for (String s : ZULAESSIGE_SUITES) {
            for (String u : unterstuetzt) if (u.equals(s)) { ergebnis.add(s); break; }
        }
        if (ergebnis.isEmpty()) {
            throw new IllegalStateException(
                    "Keine der nach [gemSpec_Krypt] zulässigen Cipher-Suites wird unterstützt. "
                    + "Prüfen Sie, ob BouncyCastle als JSSE-Provider geladen ist.");
        }
        return ergebnis.toArray(new String[0]);
    }

}
