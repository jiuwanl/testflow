package com.testflow.core;

import static org.assertj.core.api.Assertions.*;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {
  private String key() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  @Test
  void roundTripUsesFreshNonce() {
    var cipher = new SecretCipher(key());
    String value = java.util.UUID.randomUUID().toString();
    String first = cipher.encrypt(value);
    String second = cipher.encrypt(value);
    assertThat(first.equals(second)).isFalse();
    assertThat(cipher.decrypt(first).equals(value)).isTrue();
    assertThat(first.contains(value)).isFalse();
  }

  @Test
  void refusesTamperingAndWrongKeyWithoutLeakingInput() {
    var cipher = new SecretCipher(key());
    String encrypted = cipher.encrypt(java.util.UUID.randomUUID().toString());
    byte[] bytes = Base64.getDecoder().decode(encrypted.substring(3));
    bytes[15] ^= 1;
    assertThatThrownBy(() -> cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(bytes)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new SecretCipher(key()).decrypt(encrypted))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsInvalidKeysAndEnvelope() {
    assertThatThrownBy(() -> new SecretCipher("invalid"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SecretCipher(key()).decrypt("v2:invalid"))
        .isInstanceOf(IllegalStateException.class);
  }
}
