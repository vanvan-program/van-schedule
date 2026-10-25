import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Signs build input with the existing keystore. Private key material never leaves this JVM. */
class KeystoreSign {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Properties p = new Properties();
        try (var input = Files.newInputStream(root.resolve("signing.properties"))) { p.load(input); }
        KeyStore keys = KeyStore.getInstance("JKS");
        try (var input = Files.newInputStream(root.resolve(p.getProperty("storeFile")))) {
            keys.load(input, p.getProperty("storePassword").toCharArray());
        }
        var certificate = keys.getCertificate(p.getProperty("keyAlias"));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        if (!digest.equals("2787e9a025cbd6606ac2ec05415432513ada6732360ce4dc2abb22e049e938bf"))
            throw new IllegalStateException("Signing certificate mismatch");
        if (args[1].equals("certificate")) {
            String body = Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(certificate.getEncoded());
            System.out.print("-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n");
            return;
        }
        PrivateKey key = (PrivateKey) keys.getKey(p.getProperty("keyAlias"), p.getProperty("keyPassword").toCharArray());
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(System.in.readAllBytes());
        System.out.write(signer.sign());
    }
}
