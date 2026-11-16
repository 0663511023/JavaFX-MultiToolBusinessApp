package application;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Base64;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keeps the backup password so it can be shown again after it is forgotten. The password is sealed with
 * AES-256-GCM and the sealed form is stored in the database, so it is never plain text on disk. The key is kept
 * outside the database and outside every backup, in the preferences of the current Windows user. A database or
 * backup file on its own therefore cannot reveal the password; anyone signed in to that Windows account on that
 * PC still can. Pure logic, no JavaFX.
 */
final class SavedPassword {

    private static final String NODE = "MarginIQSuite";
    private static final String KEY_NAME = "backupPasswordKey";
    private static final byte[] AAD = "MIQPW1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SavedPassword() {
    }

    /** Seals the password and saves it in the database, replacing any password saved before. */
    static void save(char[] password) throws GeneralSecurityException, SQLException {
        byte[] plain = toBytes(password);
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            AppDatabase.get().saveBackupPassword(iv, cipher.doFinal(plain));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /**
     * The saved password, or null when none has been saved. Throws GeneralSecurityException if it cannot be
     * opened on this PC, for example after the database was restored from another computer.
     */
    static char[] load() throws GeneralSecurityException, SQLException {
        MarginDatabase.SavedSecret saved = AppDatabase.get().backupPassword();
        if (saved == null) {
            return null;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(TAG_BITS, saved.iv));
        cipher.updateAAD(AAD);
        byte[] plain = cipher.doFinal(saved.ciphertext);
        try {
            return toChars(plain);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** The key from the user's preferences. A new key is made only when asked for, on the first save. */
    private static SecretKey key(boolean create) throws GeneralSecurityException {
        Preferences prefs = Preferences.userRoot().node(NODE);
        String stored = prefs.get(KEY_NAME, null);
        byte[] raw;
        if (stored != null) {
            raw = Base64.getDecoder().decode(stored);
        } else if (create) {
            raw = new byte[KEY_BYTES];
            RANDOM.nextBytes(raw);
            prefs.put(KEY_NAME, Base64.getEncoder().encodeToString(raw));
            try {
                prefs.flush();
            } catch (BackingStoreException ex) {
                throw new GeneralSecurityException("The password key could not be saved on this PC.", ex);
            }
        } else {
            throw new GeneralSecurityException("The saved password cannot be opened on this PC.");
        }
        if (raw.length != KEY_BYTES) {
            throw new GeneralSecurityException("The saved password key is damaged.");
        }
        return new SecretKeySpec(raw, "AES");
    }

    /** The password as UTF-8 bytes. The caller clears the array when done. */
    private static byte[] toBytes(char[] chars) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return out;
    }

    private static char[] toChars(byte[] bytes) {
        CharBuffer buffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
        char[] out = new char[buffer.remaining()];
        buffer.get(out);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), '\0');
        }
        return out;
    }
}
