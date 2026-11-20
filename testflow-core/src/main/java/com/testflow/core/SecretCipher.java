package com.testflow.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Versioned, authenticated encryption for environment secrets; never use for passwords. */
public final class SecretCipher {
  private final byte[] aad;
  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public SecretCipher(String base64Key) {
    this(base64Key, "testflow:environment:v1");
  }

  public SecretCipher(String base64Key, String purpose) {
    this.aad = purpose.getBytes(StandardCharsets.UTF_8);
    try {
      byte[] bytes = Base64.getDecoder().decode(base64Key);
      if (bytes.length != 32) {
        throw new IllegalArgumentException("TF_SECRET_KEY must encode exactly 32 bytes");
      }
      this.key = new SecretKeySpec(bytes, "AES");
      Arrays.fill(bytes, (byte) 0);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("TF_SECRET_KEY must encode exactly 32 bytes");
    }
  }

  public String encrypt(String plaintext) {
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad);
      byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] envelope = new byte[nonce.length + encrypted.length];
      System.arraycopy(nonce, 0, envelope, 0, nonce.length);
      System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
      return "v1:" + Base64.getEncoder().encodeToString(envelope);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Secret encryption failed");
    }
  }

  public String decrypt(String envelope) {
    try {
      if (envelope == null || !envelope.startsWith("v1:")) {
        throw new IllegalArgumentException();
      }
      byte[] bytes = Base64.getDecoder().decode(envelope.substring(3));
      if (bytes.length < 28) {
        throw new IllegalArgumentException();
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, bytes, 0, 12));
      cipher.updateAAD(aad);
      return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      throw new IllegalStateException("Secret cannot be decrypted with the configured key");
    }
  }
}
