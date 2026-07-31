package com.relay.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.domain.User;
import com.relay.infrastructure.auth.BCryptPasswordHasher;
import com.relay.support.AuthDoubles;
import com.relay.support.TestDoubles;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Sign-in is the one place where a mistake is silent: a password stored in the clear or a
 * session that outlives logout looks exactly like a working feature from the outside.
 */
class AuthServiceTest {

    private record Rig(AuthService auth, AuthDoubles.InMemoryUsers users, AuthDoubles.InMemorySessions sessions,
                       TestDoubles.FixedClock clock) {
    }

    private static Rig rig() {
        AuthDoubles.InMemoryUsers users = new AuthDoubles.InMemoryUsers();
        AuthDoubles.InMemorySessions sessions = new AuthDoubles.InMemorySessions();
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        // Cost 4: still real BCrypt, but a test suite should not burn a second per hash.
        return new Rig(new AuthService(users, sessions, new BCryptPasswordHasher(4), clock), users, sessions, clock);
    }

    @Test
    void registerStoresAHashNotThePassword() {
        Rig rig = rig();
        User user = rig.auth().register("Ada@Example.com", "kalabalik-parola", "Ada");

        assertThat(user.email()).isEqualTo("ada@example.com");
        assertThat(user.displayName()).isEqualTo("Ada");
        assertThat(user.provider()).isEqualTo(User.PROVIDER_PASSWORD);
        assertThat(user.onboarded()).isFalse();
        assertThat(user.passwordHash())
                .isNotNull()
                .doesNotContain("kalabalik-parola")
                .startsWith("$2");
        assertThat(user.toString()).doesNotContain(user.passwordHash());
    }

    @Test
    void registeredUserCanSignInAndTheHashActuallyVerifies() {
        Rig rig = rig();
        rig.auth().register("ada@example.com", "kalabalik-parola", null);

        User user = rig.auth().login("ADA@example.com", "kalabalik-parola");
        assertThat(user.email()).isEqualTo("ada@example.com");
        // No display name given → the local part stands in.
        assertThat(user.displayName()).isEqualTo("ada");

        assertThatThrownBy(() -> rig.auth().login("ada@example.com", "kalabalik-parolb"))
                .isInstanceOf(AuthException.class)
                .hasMessage("E-posta veya parola hatalı.");
    }

    @Test
    void unknownAccountAnswersExactlyLikeAWrongPassword() {
        Rig rig = rig();
        rig.auth().register("ada@example.com", "kalabalik-parola", null);

        AuthException wrongPassword = catchAuth(() -> rig.auth().login("ada@example.com", "yanlis-parola"));
        AuthException noSuchUser = catchAuth(() -> rig.auth().login("kimse@example.com", "yanlis-parola"));

        // A login form must not tell a stranger which addresses have accounts.
        assertThat(noSuchUser.getMessage()).isEqualTo(wrongPassword.getMessage());
        assertThat(noSuchUser.status()).isEqualTo(wrongPassword.status()).isEqualTo(401);
    }

    @Test
    void passwordMustBeAtLeastEightCharactersAndTheErrorNamesTheField() {
        Rig rig = rig();
        AuthException e = catchAuth(() -> rig.auth().register("ada@example.com", "kisa123", null));

        assertThat(e.field()).isEqualTo("password");
        assertThat(e.status()).isEqualTo(400);
        assertThat(e.getMessage()).isEqualTo("Parola en az 8 karakter olmalı.");
        assertThat(rig.users().rows).isEmpty();
    }

    @Test
    void malformedEmailIsRefusedBeforeAnythingIsStored() {
        Rig rig = rig();
        AuthException e = catchAuth(() -> rig.auth().register("ada-at-example", "kalabalik-parola", null));

        assertThat(e.field()).isEqualTo("email");
        assertThat(e.getMessage()).isEqualTo("Geçerli bir e-posta adresi gir.");
        assertThat(rig.users().rows).isEmpty();
    }

    @Test
    void theSameAddressCannotRegisterTwiceEvenWithDifferentCase() {
        Rig rig = rig();
        rig.auth().register("ada@example.com", "kalabalik-parola", null);

        AuthException e = catchAuth(() -> rig.auth().register("ADA@Example.COM", "baska-parola", null));
        assertThat(e.status()).isEqualTo(409);
        assertThat(e.field()).isEqualTo("email");
        assertThat(rig.users().rows).hasSize(1);
    }

    /**
     * Under a Turkish default locale {@code "I".toLowerCase()} is "ı" — Irmak@ and irmak@
     * would become two accounts, and the second registration would look like a new user.
     */
    @Test
    void emailNormalisationIsLocaleIndependent() {
        assertThat(AuthService.normalizeEmail("  IRMAK@Example.COM ")).isEqualTo("irmak@example.com");
    }

    @Test
    void sessionCookieValueIsNeverStoredOnlyItsHash() {
        Rig rig = rig();
        User user = rig.auth().register("ada@example.com", "kalabalik-parola", null);

        String token = rig.auth().startSession(user);
        assertThat(token).isNotBlank().hasSizeGreaterThan(20);
        assertThat(rig.sessions().rows).hasSize(1);
        assertThat(rig.sessions().rows.get(0).tokenHash())
                .isNotEqualTo(token)
                .hasSize(64);

        assertThat(rig.auth().authenticate(token)).map(User::email).contains("ada@example.com");
        assertThat(rig.auth().authenticate("uydurma-token")).isEmpty();
    }

    @Test
    void logoutEndsTheSessionForGood() {
        Rig rig = rig();
        User user = rig.auth().register("ada@example.com", "kalabalik-parola", null);
        String token = rig.auth().startSession(user);

        rig.auth().logout(token);

        assertThat(rig.auth().authenticate(token)).isEmpty();
        assertThat(rig.sessions().rows).isEmpty();
    }

    @Test
    void anExpiredSessionStopsWorkingAndIsCleanedUp() {
        Rig rig = rig();
        User user = rig.auth().register("ada@example.com", "kalabalik-parola", null);
        String token = rig.auth().startSession(user);

        rig.clock().advance(AuthService.SESSION_TTL.plus(Duration.ofSeconds(1)));

        assertThat(rig.auth().authenticate(token)).isEmpty();
        assertThat(rig.sessions().rows).isEmpty();
    }

    @Test
    void twoSessionsForTheSameUserAreIndependent() {
        Rig rig = rig();
        User user = rig.auth().register("ada@example.com", "kalabalik-parola", null);
        String laptop = rig.auth().startSession(user);
        String phone = rig.auth().startSession(user);

        rig.auth().logout(laptop);

        assertThat(rig.auth().authenticate(laptop)).isEmpty();
        assertThat(rig.auth().authenticate(phone)).isPresent();
    }

    @Test
    void googleSignInCreatesTheAccountOnceAndReusesItAfterwards() {
        Rig rig = rig();
        User first = rig.auth().loginWithGoogle("Ada@example.com", "Ada Lovelace", "https://pic/ada.png");
        User second = rig.auth().loginWithGoogle("ada@example.com", "Ada Lovelace", "https://pic/ada2.png");

        assertThat(first.id()).isEqualTo(second.id());
        assertThat(first.provider()).isEqualTo(User.PROVIDER_GOOGLE);
        assertThat(first.passwordHash()).isNull();
        assertThat(rig.users().rows).hasSize(1);
        assertThat(second.avatarUrl()).isEqualTo("https://pic/ada2.png");
    }

    @Test
    void aGoogleOnlyAccountIsToldToUseGoogleInsteadOfFailingSilently() {
        Rig rig = rig();
        rig.auth().loginWithGoogle("ada@example.com", "Ada", null);

        AuthException e = catchAuth(() -> rig.auth().login("ada@example.com", "herhangi-parola"));
        assertThat(e.getMessage()).contains("Google");
        assertThat(e.status()).isEqualTo(401);
    }

    @Test
    void signingInWithGoogleOnAnExistingPasswordAccountIsTheSamePersonNotANewOne() {
        Rig rig = rig();
        User registered = rig.auth().register("ada@example.com", "kalabalik-parola", "Ada");

        User viaGoogle = rig.auth().loginWithGoogle("ada@example.com", "Ada Lovelace", "https://pic/ada.png");

        assertThat(viaGoogle.id()).isEqualTo(registered.id());
        assertThat(rig.users().rows).hasSize(1);
        // The password still works — the account gained a second door, it did not lose one.
        assertThat(rig.auth().login("ada@example.com", "kalabalik-parola").id()).isEqualTo(registered.id());
    }

    @Test
    void onboardingIsRememberedAcrossSessions() {
        Rig rig = rig();
        User user = rig.auth().register("ada@example.com", "kalabalik-parola", null);
        assertThat(user.onboarded()).isFalse();

        User done = rig.auth().completeOnboarding(user);
        assertThat(done.onboarded()).isTrue();
        assertThat(done.onboardedAt()).isEqualTo(rig.clock().now());

        String token = rig.auth().startSession(done);
        assertThat(rig.auth().authenticate(token)).map(User::onboarded).contains(true);

        // Calling it again must not move the timestamp.
        rig.clock().advance(Duration.ofHours(2));
        assertThat(rig.auth().completeOnboarding(done).onboardedAt()).isEqualTo(done.onboardedAt());
    }

    @Test
    void expiredSessionsCanBePurgedInBulk() {
        Rig rig = rig();
        User user = rig.auth().register("ada@example.com", "kalabalik-parola", null);
        String old = rig.auth().startSession(user);
        rig.clock().advance(AuthService.SESSION_TTL.plus(Duration.ofSeconds(1)));
        String fresh = rig.auth().startSession(user);

        rig.auth().purgeExpiredSessions();

        assertThat(rig.sessions().rows).hasSize(1);
        assertThat(rig.auth().authenticate(old)).isEmpty();
        assertThat(rig.auth().authenticate(fresh)).isPresent();
    }

    private static AuthException catchAuth(Runnable action) {
        try {
            action.run();
        } catch (AuthException e) {
            return e;
        }
        throw new AssertionError("expected an AuthException");
    }

}
