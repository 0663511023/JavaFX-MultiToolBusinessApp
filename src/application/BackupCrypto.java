package application;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password protection for backups. The key comes from the password with PBKDF2-HMAC-SHA256 and a random salt, and
 * the data is sealed with AES-256-GCM, so a wrong password or a changed byte is detected rather than producing
 * garbage.
 *
 * <p>New backups (magic MIQBK2) hold their iteration count in the file: magic (6 bytes), iteration count (4 bytes,
 * big-endian), salt (16), IV (12), then the sealed data with its tag. The magic and the count are authenticated.
 *
 * <p>Backups made by earlier versions (magic MIQBK1) hold no count; they were made with 210,000 iterations and
 * still open. Pure logic, no JavaFX.
 */
public final class BackupCrypto {

    /** The magic of the current format, which records its iteration count in the file. */
    static final byte[] MAGIC = "MIQBK2".getBytes(StandardCharsets.US_ASCII);
    /** The magic of the format made by earlier versions. It is still accepted on restore. */
    static final byte[] MAGIC_V1 = "MIQBK1".getBytes(StandardCharsets.US_ASCII);
    /** The iteration count for new backups: OWASP's current guidance for PBKDF2-HMAC-SHA256. */
    static final int ITERATIONS = 600_000;
    /** The count used by backups made by earlier versions, which hold no count of their own. */
    static final int ITERATIONS_V1 = 210_000;
    /** A file asking for fewer or more iterations is refused, so a crafted file cannot weaken or stall the check. */
    static final int MIN_ITERATIONS = 100_000;
    static final int MAX_ITERATIONS = 10_000_000;

    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    public static final int MIN_PASSWORD_LENGTH = PasswordRules.MIN_LENGTH;

    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCrypto() {
    }

    /** A new backup password must pass every rule in PasswordRules. */
    public static void checkPassword(String password) throws InputParser.InvalidInputException {
        PasswordRules.check(password == null ? "" : password);
    }

    /** Seals the bytes with the password. Every backup gets a new salt and IV. */
    public static byte[] encrypt(byte[] plain, char[] password) throws GeneralSecurityException {
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(iv);
        byte[] header = ByteBuffer.allocate(MAGIC.length + 4).put(MAGIC).putInt(ITERATIONS).array();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(header);
        byte[] sealed = cipher.doFinal(plain);
        return ByteBuffer.allocate(header.length + SALT_BYTES + IV_BYTES + sealed.length)
                .put(header).put(salt).put(iv).put(sealed).array();
    }

    /**
     * Opens a backup made by this version or an earlier one. A wrong password, or a file that has been changed,
     * throws AEADBadTagException. A file that is not a MarginIQ backup at all throws IOException.
     */
    public static byte[] decrypt(byte[] data, char[] password) throws GeneralSecurityException, IOException {
        if (startsWith(data, MAGIC)) {
            int saltAt = MAGIC.length + 4;
            if (data.length < saltAt + SALT_BYTES + IV_BYTES + TAG_BITS / 8) {
                throw new IOException("This backup file is damaged.");
            }
            int iterations = ByteBuffer.wrap(data, MAGIC.length, 4).getInt();
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
                throw new IOException("This backup file uses settings that this version of MarginIQ Suite does not accept.");
            }
            return open(data, password, saltAt, iterations);
        }
        if (startsWith(data, MAGIC_V1)) {
            if (data.length < MAGIC_V1.length + SALT_BYTES + IV_BYTES + TAG_BITS / 8) {
                throw new IOException("This backup file is damaged.");
            }
            return open(data, password, MAGIC_V1.length, ITERATIONS_V1);
        }
        throw new IOException("This file is not a MarginIQ Suite backup.");
    }

    /** Opens the sealed data, whose salt starts at saltAt. Everything before the salt is the authenticated header. */
    private static byte[] open(byte[] data, char[] password, int saltAt, int iterations)
            throws GeneralSecurityException {
        byte[] aad = Arrays.copyOf(data, saltAt);
        byte[] salt = Arrays.copyOfRange(data, saltAt, saltAt + SALT_BYTES);
        byte[] iv = Arrays.copyOfRange(data, saltAt + SALT_BYTES, saltAt + SALT_BYTES + IV_BYTES);
        int sealedAt = saltAt + SALT_BYTES + IV_BYTES;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad);
        return cipher.doFinal(data, sealedAt, data.length - sealedAt);
    }

    private static boolean startsWith(byte[] data, byte[] magic) {
        return data.length >= magic.length && Arrays.equals(Arrays.copyOf(data, magic.length), magic);
    }

    /** The AES key for a password, salt and iteration count. The password's copy inside the spec is wiped afterwards. */
    static SecretKey key(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            byte[] secret = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(secret, "AES");
        } finally {
            spec.clearPassword();
        }
    }
}
