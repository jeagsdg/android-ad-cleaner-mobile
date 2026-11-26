import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;

/** JDK 17 source entry point. Reads the same local keystore as the APK build. */
class SignRulePack {
    public static void main(String[] args) throws IOException {
        byte[] payload = System.in.readNBytes(900 * 1024 + 1);
        if (payload.length == 0 || payload.length > 900 * 1024) {
            System.err.println("Invalid rule payload size");
            System.exit(1);
        }
        try {
            if ("--verify".equals(args[0])) {
                String[] fields = new String(payload, StandardCharsets.US_ASCII).split("\\n", -1);
                if (fields.length != 3) throw new IllegalArgumentException("invalid envelope");
                byte[] der = Base64.getDecoder().decode(fields[0]);
                if (!args[1].equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der))))
                    throw new IllegalArgumentException("wrong certificate");
                Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
                Signature proof = Signature.getInstance("SHA256withRSA");
                proof.initVerify(cert);
                proof.update(Base64.getDecoder().decode(fields[1]));
                if (!proof.verify(Base64.getDecoder().decode(fields[2]))) throw new IllegalArgumentException("invalid signature");
                return;
            }
            Path project = Path.of(args[0]);
            String certificatePin = args[1];
            Properties settings = new Properties();
            try (var file = Files.newInputStream(project.resolve(".local/signing.properties"))) {
                settings.load(file);
            }
            Path keystore = project.resolve("app").resolve(settings.getProperty("storeFile"));
            char[] storePass = settings.getProperty("storePassword").toCharArray();
            char[] keyPass = settings.getProperty("keyPassword").toCharArray();
            try {
                KeyStore store = KeyStore.getInstance(keystore.toFile(), storePass);
                String alias = settings.getProperty("keyAlias");
                Certificate cert = store.getCertificate(alias);
                byte[] certificate = cert.getEncoded();
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate);
                if (!certificatePin.equals(HexFormat.of().formatHex(digest))) {
                    throw new IllegalArgumentException("certificate does not match the official release");
                }
                Signature signer = Signature.getInstance("SHA256withRSA");
                signer.initSign((PrivateKey) store.getKey(alias, keyPass));
                signer.update(payload);
                byte[] proof = signer.sign();
                signer.initVerify(cert.getPublicKey());
                signer.update(payload);
                if (!signer.verify(proof)) throw new IllegalStateException("signature verification failed");
                Base64.Encoder base64 = Base64.getEncoder();
                String envelope = "{\"certificate\":\"" + base64.encodeToString(certificate)
                        + "\",\"payload\":\"" + base64.encodeToString(payload)
                        + "\",\"signature\":\"" + base64.encodeToString(proof) + "\"}\n";
                System.out.write(envelope.getBytes(StandardCharsets.US_ASCII));
            } finally {
                Arrays.fill(storePass, '\0');
                Arrays.fill(keyPass, '\0');
            }
        } catch (Exception e) {
            // Avoid leaking paths or private signing configuration in build output.
            System.err.println("Rule signature operation failed. Check the envelope or local signing configuration.");
            System.exit(1);
        }
    }
}
