package de.pinroboter.simulator;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveGenParameterSpec;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Erzeugt zur Laufzeit eine kleine PKI auf {@code brainpoolP256r1}.
 *
 * <p>Grund für Brainpool: Die Geräteidentitäten der TI stammen von Chipkarten
 * (gSMC-K beim Konnektor, gSMC-KT beim Kartenterminal) und sind dort auf
 * {@code brainpoolP256r1} festgelegt — fixiert in den Objektsystemen der Karten
 * ([gemSpec_Krypt]). Der Simulator bildet das nach, damit die TLS-Kette des
 * Agenten unter realistischen Bedingungen geprüft wird und nicht gegen eine
 * bequemere NIST-Kurve (ADR-0007).
 *
 * <p>Die Schlüssel entstehen bei jedem Start neu und liegen nur im Speicher.
 * Es gibt bewusst keine Dateiablage — Testschlüssel, die auf Platte liegen,
 * werden irgendwann versehentlich woanders verwendet.
 */
public final class BrainpoolZertifikate {

    public static final String KURVE = "brainpoolP256r1";
    private static final String SIGNATUR = "SHA256withECDSA";

    static { Security.addProvider(new BouncyCastleProvider()); }

    public record Kette(KeyPair caSchluessel, X509Certificate caZertifikat,
                        KeyPair schluessel, X509Certificate zertifikat) {}

    private BrainpoolZertifikate() {}

    /** Erzeugt CA und ein davon signiertes Serverzertifikat für {@code cn}. */
    public static Kette erzeugen(String cn) throws Exception {
        KeyPair ca = schluesselpaar();
        X509Certificate caZert = zertifikat(
                "CN=PIN-Roboter Simulator CA (" + KURVE + "),O=Simulation,C=DE",
                "CN=PIN-Roboter Simulator CA (" + KURVE + "),O=Simulation,C=DE",
                ca.getPublic(), ca.getPrivate(), true, null);

        KeyPair server = schluesselpaar();
        X509Certificate serverZert = zertifikat(
                "CN=PIN-Roboter Simulator CA (" + KURVE + "),O=Simulation,C=DE",
                "CN=" + cn + ",O=Simulation,C=DE",
                server.getPublic(), ca.getPrivate(), false, cn);

        return new Kette(ca, caZert, server, serverZert);
    }

    private static KeyPair schluesselpaar() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC", "BC");
        g.initialize(new ECNamedCurveGenParameterSpec(KURVE), new SecureRandom());
        return g.generateKeyPair();
    }

    private static X509Certificate zertifikat(String aussteller, String inhaber,
                                              PublicKey oeffentlich, PrivateKey signierer,
                                              boolean istCa, String dnsName) throws Exception {
        Instant jetzt = Instant.now();
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                new X500Name(aussteller),
                BigInteger.valueOf(jetzt.toEpochMilli()),
                Date.from(jetzt.minus(1, ChronoUnit.HOURS)),
                Date.from(jetzt.plus(365, ChronoUnit.DAYS)),
                new X500Name(inhaber), oeffentlich);

        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(istCa));

        if (istCa) {
            // keyUsage ist hier nicht Zierrat: Fehlt sie, weisen strenge Clients
            // die Kette mit einer Meldung ab, die nach einem Kurvenproblem aussieht.
            // Genau dieser Irrweg ist bei der Vorabmessung am 06.08.2026 aufgetreten.
            b.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        } else {
            b.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            b.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            if (dnsName != null) {
                b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(
                        new GeneralName[]{
                                new GeneralName(GeneralName.dNSName, dnsName),
                                new GeneralName(GeneralName.dNSName, "localhost"),
                                new GeneralName(GeneralName.iPAddress, "127.0.0.1")}));
            }
        }

        ContentSigner signer = new JcaContentSignerBuilder(SIGNATUR).setProvider("BC").build(signierer);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(b.build(signer));
    }

    /** CA-Zertifikat im PEM-Format, damit der Agent es als Vertrauensanker laden kann. */
    public static String alsPem(X509Certificate zert) throws Exception {
        String b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(zert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
    }
}
