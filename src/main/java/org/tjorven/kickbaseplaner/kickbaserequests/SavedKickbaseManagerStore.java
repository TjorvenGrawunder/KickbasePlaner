package org.tjorven.kickbaseplaner.kickbaserequests;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class SavedKickbaseManagerStore {

    private static final byte[] FILE_MAGIC = {'K', 'B', 'M', '1'};
    private static final byte[] LEGACY_FILE_MAGIC = {'K', 'B', 'P', '1'};
    private static final int NONCE_LENGTH = 12;
    private static final int MAX_MANAGERS = 10_000;

    private final String encryptionKey;
    private final Path file;
    private final Path legacyFile;
    private final SecureRandom secureRandom = new SecureRandom();

    public SavedKickbaseManagerStore(
            @Value("${kickbase.credentials.encryption-key:}") String encryptionKey,
            @Value("${kickbase.credentials.file:}") String configuredFile
    ) {
        this.encryptionKey = encryptionKey;
        Path defaultFile = Path.of(System.getProperty("user.home"), ".kickbase-planer", "managers.enc");
        Path legacyFile = Path.of(System.getProperty("user.home"), ".kickbase-planer", "players.enc");
        this.file = configuredFile.isBlank()
                ? defaultFile
                : Path.of(configuredFile);
        this.legacyFile = configuredFile.isBlank() ? legacyFile : null;
    }

    public synchronized List<SavedManager> findAll() {
        return readManagers().stream()
                .map(manager -> new SavedManager(manager.id(), manager.email()))
                .toList();
    }

    public synchronized Credentials findCredentials(String id) {
        return readManagers().stream()
                .filter(manager -> manager.id().equals(id))
                .findFirst()
                .map(manager -> new Credentials(manager.email(), manager.password()))
                .orElseThrow(ManagerNotFoundException::new);
    }

    public synchronized void save(String id, String email, String password) {
        List<StoredManager> managers = new ArrayList<>(readManagers());
        managers.removeIf(manager -> manager.id().equals(id));
        managers.add(new StoredManager(id, email, password));
        writeManagers(managers);
    }

    private List<StoredManager> readManagers() {
        if (!Files.exists(file)) {
            if (legacyFile == null || !Files.exists(legacyFile)) {
                return List.of();
            }
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.copy(legacyFile, file);
            } catch (IOException exception) {
                throw new StoreException("Could not migrate saved Kickbase managers.", exception);
            }
        }

        byte[] fileContents;
        try {
            fileContents = Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new StoreException("Could not read saved Kickbase managers.", exception);
        }
        byte[] key = decodeEncryptionKey();

        try {
            if (fileContents.length < FILE_MAGIC.length + NONCE_LENGTH + 16
                    || !matchesMagic(fileContents, FILE_MAGIC) && !matchesMagic(fileContents, LEGACY_FILE_MAGIC)) {
                throw new StoreException("The saved Kickbase manager file is invalid.");
            }
            ByteBuffer buffer = ByteBuffer.wrap(fileContents);
            buffer.position(FILE_MAGIC.length);
            byte[] nonce = new byte[NONCE_LENGTH];
            buffer.get(nonce);
            byte[] encryptedData = new byte[buffer.remaining()];
            buffer.get(encryptedData);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return decodeManagers(cipher.doFinal(encryptedData));
        } catch (AEADBadTagException exception) {
            throw new StoreException(
                    "Saved Kickbase managers could not be decrypted. Check KICKBASE_CREDENTIALS_ENCRYPTION_KEY.",
                    exception
            );
        } catch (GeneralSecurityException | IOException exception) {
            throw new StoreException("Could not decrypt saved Kickbase managers.", exception);
        }
    }

    private void writeManagers(List<StoredManager> managers) {
        byte[] key = decodeEncryptionKey();
        byte[] nonce = new byte[NONCE_LENGTH];
        secureRandom.nextBytes(nonce);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encryptedData = cipher.doFinal(encodeManagers(managers));
            ByteBuffer buffer = ByteBuffer.allocate(FILE_MAGIC.length + nonce.length + encryptedData.length);
            buffer.put(FILE_MAGIC).put(nonce).put(encryptedData);

            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temporaryFile = Files.createTempFile(parent, "managers-", ".tmp");
            try {
                Files.write(temporaryFile, buffer.array());
                try {
                    Files.move(temporaryFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicMoveException) {
                    Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporaryFile);
            }
        } catch (GeneralSecurityException | IOException exception) {
            throw new StoreException("Could not save encrypted Kickbase managers.", exception);
        }
    }

    private byte[] decodeEncryptionKey() {
        if (encryptionKey.isBlank()) {
            throw new StoreException(
                    "Set KICKBASE_CREDENTIALS_ENCRYPTION_KEY to a base64-encoded 256-bit key."
            );
        }

        byte[] key;
        try {
            key = Base64.getDecoder().decode(encryptionKey);
        } catch (IllegalArgumentException exception) {
            throw new StoreException("KICKBASE_CREDENTIALS_ENCRYPTION_KEY must be valid base64.", exception);
        }
        if (key.length != 32) {
            throw new StoreException("KICKBASE_CREDENTIALS_ENCRYPTION_KEY must decode to exactly 32 bytes.");
        }
        return key;
    }

    private boolean matchesMagic(byte[] contents, byte[] expectedMagic) {
        for (int index = 0; index < FILE_MAGIC.length; index++) {
            if (contents[index] != expectedMagic[index]) {
                return false;
            }
        }
        return true;
    }

    private byte[] encodeManagers(List<StoredManager> managers) throws IOException {
        if (managers.size() > MAX_MANAGERS) {
            throw new StoreException("Too many saved Kickbase managers.");
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(managers.size());
            for (StoredManager manager : managers) {
                output.writeUTF(manager.id());
                output.writeUTF(manager.email());
                output.writeUTF(manager.password());
            }
        }
        return bytes.toByteArray();
    }

    private List<StoredManager> decodeManagers(byte[] contents) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(contents))) {
            int count = input.readInt();
            if (count < 0 || count > MAX_MANAGERS) {
                throw new StoreException("The saved Kickbase manager file has an invalid manager count.");
            }
            List<StoredManager> managers = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                managers.add(new StoredManager(input.readUTF(), input.readUTF(), input.readUTF()));
            }
            if (input.available() != 0) {
                throw new StoreException("The saved Kickbase manager file contains unexpected data.");
            }
            return List.copyOf(managers);
        }
    }

    public record SavedManager(String id, String email) {
    }

    public record Credentials(String email, String password) {
        @Override
        public String toString() {
            return "Credentials[email=" + email + ", password=REDACTED]";
        }
    }

    public static class StoreException extends RuntimeException {
        public StoreException(String message) {
            super(message);
        }

        public StoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class ManagerNotFoundException extends StoreException {
        public ManagerNotFoundException() {
            super("The saved manager does not exist.");
        }
    }

    private record StoredManager(String id, String email, String password) {
    }
}
