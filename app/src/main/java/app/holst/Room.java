package app.holst;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Общий лист двух друзей. Всё выводится из 12-значного кода: и ключ подписи, и ключ шифрования.
 * Сам код в сеть не уходит, а ретрансляторы видят только зашифрованные данные.
 */
final class Room {
    static final int ITERATIONS = 150_000;
    private static final byte[] SALT = "holst-room-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    final String code;
    final byte[] secret;
    final byte[] aesKey;
    final String pubHex;
    private final Crypto.Signer signer;

    private Room(String code, byte[] secret, byte[] aesKey, String pubHex) {
        this.code = code;
        this.secret = secret;
        this.aesKey = aesKey;
        this.pubHex = pubHex;
        this.signer = new Crypto.Signer(secret);
    }

    static Room derive(String code) {
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] master = f.generateSecret(
                    new PBEKeySpec(code.toCharArray(), SALT, ITERATIONS, 256)).getEncoded();
            byte[] secret = Crypto.sha256("holst-sign".getBytes(StandardCharsets.UTF_8), master);
            while (new BigInteger(1, secret).signum() == 0
                    || new BigInteger(1, secret).compareTo(Crypto.N) >= 0) {
                secret = Crypto.sha256(secret);
            }
            byte[] aes = Crypto.sha256("holst-aes".getBytes(StandardCharsets.UTF_8), master);
            return new Room(code, secret, aes, Crypto.hex(Crypto.publicKey(secret)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String newCode() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }

    static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("[^0-9]", "");
    }

    static String pretty(String code) {
        if (code.length() != 12) return code;
        return code.substring(0, 4) + " " + code.substring(4, 8) + " " + code.substring(8);
    }

    String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** null, если данные не наши или повреждены. */
    String decrypt(String b64) {
        try {
            byte[] all = Base64.getDecoder().decode(b64);
            if (all.length < 28) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Готовое подписанное событие Nostr. */
    static final class Event {
        final String id;
        final String json;

        Event(String id, String json) {
            this.id = id;
            this.json = json;
        }
    }

    /** content — Base64, поэтому экранирование JSON здесь не требуется. */
    Event event(int kind, String dTag, String content, long createdAt) {
        String tags = dTag == null ? "[]" : "[[\"d\",\"" + dTag + "\"]]";
        String serial = "[0,\"" + pubHex + "\"," + createdAt + "," + kind + "," + tags + ",\"" + content + "\"]";
        byte[] idBytes = Crypto.sha256(serial.getBytes(StandardCharsets.UTF_8));
        byte[] aux = new byte[32];
        RANDOM.nextBytes(aux);
        String id = Crypto.hex(idBytes);
        String sig = Crypto.hex(signer.sign(idBytes, aux));
        String json = "{\"id\":\"" + id + "\",\"pubkey\":\"" + pubHex + "\",\"created_at\":" + createdAt
                + ",\"kind\":" + kind + ",\"tags\":" + tags + ",\"content\":\"" + content
                + "\",\"sig\":\"" + sig + "\"}";
        return new Event(id, json);
    }
}
