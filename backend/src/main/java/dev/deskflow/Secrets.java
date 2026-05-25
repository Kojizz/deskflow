package dev.deskflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component
class Secrets {
    private final SecretKeySpec key;
    Secrets(@Value("${app.encryption-key}") String encoded) {
        byte[] bytes;
        try { bytes=Base64.getDecoder().decode(encoded); } catch(Exception e) { throw new IllegalStateException("APP_ENCRYPTION_KEY must be base64"); }
        if(bytes.length!=32) throw new IllegalStateException("APP_ENCRYPTION_KEY must encode 32 bytes; run scripts/setup.ps1");
        key=new SecretKeySpec(bytes,"AES");
    }
    String encrypt(String value) {
        try {
            byte[] iv=new byte[12]; new SecureRandom().nextBytes(iv);
            var c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));
            return Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e) { throw new IllegalStateException("Encryption failed"); }
    }
    String decrypt(String value) {
        try {
            var p=value.split("\\."); var c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.getDecoder().decode(p[0])));
            return new String(c.doFinal(Base64.getDecoder().decode(p[1])),StandardCharsets.UTF_8);
        } catch(Exception e) { throw new IllegalStateException("Credential cannot be decrypted"); }
    }
    static String token() { byte[] b=new byte[32]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
