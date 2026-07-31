package com.relay.infrastructure.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BCryptPasswordHasherTest {

    private final BCryptPasswordHasher hasher = new BCryptPasswordHasher(4);

    @Test
    void theSamePasswordHashesDifferentlyEveryTimeAndStillVerifies() {
        String first = hasher.hash("kalabalik-parola");
        String second = hasher.hash("kalabalik-parola");

        // Distinct salts: two accounts with the same password must not share a hash,
        // otherwise the table itself leaks who reused a password.
        assertThat(first).isNotEqualTo(second);
        assertThat(hasher.matches("kalabalik-parola", first)).isTrue();
        assertThat(hasher.matches("kalabalik-parola", second)).isTrue();
        assertThat(hasher.matches("kalabalik-parolb", first)).isFalse();
    }

    @Test
    void aMissingOrGarbledHashIsAMismatchNotACrash() {
        assertThat(hasher.matches("kalabalik-parola", null)).isFalse();
        assertThat(hasher.matches("kalabalik-parola", "")).isFalse();
        assertThat(hasher.matches("kalabalik-parola", "elle-yazilmis-deger")).isFalse();
        assertThat(hasher.matches(null, hasher.hash("kalabalik-parola"))).isFalse();
    }
}
