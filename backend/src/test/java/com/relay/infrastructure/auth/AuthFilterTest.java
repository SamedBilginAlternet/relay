package com.relay.infrastructure.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.auth.AuthService;
import com.relay.domain.User;
import com.relay.support.AuthDoubles;
import com.relay.support.TestDoubles;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The guard has two failure modes that both look fine in a browser: letting an
 * unauthenticated request through, and answering with something the SPA cannot read.
 */
class AuthFilterTest {

    private record Rig(AuthFilter filter, AuthService auth, User user, String token) {
    }

    private static Rig rig() {
        AuthDoubles.InMemoryUsers users = new AuthDoubles.InMemoryUsers();
        AuthDoubles.InMemorySessions sessions = new AuthDoubles.InMemorySessions();
        AuthService auth = new AuthService(users, sessions, new BCryptPasswordHasher(4),
                new TestDoubles.FixedClock());
        User user = auth.register("ada@example.com", "kalabalik-parola", "Ada");
        return new Rig(new AuthFilter(auth, true), auth, user, auth.startSession(user));
    }

    private static MockHttpServletResponse run(AuthFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    void aProtectedEndpointWithoutASessionAnswers401AsJson() throws Exception {
        Rig rig = rig();
        MockHttpServletResponse response = run(rig.filter(), get("/api/runs"));

        assertThat(response.getStatus()).isEqualTo(401);
        // Never a redirect to a login page: this lands inside fetch(), not in the address bar.
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).contains("\"error\":\"unauthorized\"");
    }

    @Test
    void aValidSessionCookieLetsTheRequestThroughAndNamesTheUser() throws Exception {
        Rig rig = rig();
        MockHttpServletRequest request = get("/api/runs");
        request.setCookies(new Cookie(SessionCookies.NAME, rig.token()));

        MockHttpServletResponse response = run(rig.filter(), request);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(AuthFilter.current(request)).map(User::email).contains("ada@example.com");
    }

    /**
     * The whole reason the session is a cookie: {@code EventSource} cannot send an
     * Authorization header, so the live run stream would be unreachable behind a bearer token.
     */
    @Test
    void theSseStreamIsReachableWithNothingButTheCookie() throws Exception {
        Rig rig = rig();
        MockHttpServletRequest request = get("/api/runs/2f1c/stream");
        request.addHeader("Accept", "text/event-stream");
        request.setCookies(new Cookie(SessionCookies.NAME, rig.token()));

        assertThat(run(rig.filter(), request).getStatus()).isEqualTo(200);
        assertThat(run(rig.filter(), get("/api/runs/2f1c/stream")).getStatus()).isEqualTo(401);
    }

    @Test
    void anExpiredOrForgedCookieIsNotASession() throws Exception {
        Rig rig = rig();
        MockHttpServletRequest request = get("/api/connections");
        request.setCookies(new Cookie(SessionCookies.NAME, "uydurma-token"));

        assertThat(run(rig.filter(), request).getStatus()).isEqualTo(401);
    }

    @Test
    void loggingOutClosesTheDoorBehindYou() throws Exception {
        Rig rig = rig();
        rig.auth().logout(rig.token());

        MockHttpServletRequest request = get("/api/runs");
        request.setCookies(new Cookie(SessionCookies.NAME, rig.token()));

        assertThat(run(rig.filter(), request).getStatus()).isEqualTo(401);
    }

    @Test
    void theEndpointsThatMustStayOpenStayOpen() throws Exception {
        Rig rig = rig();
        List<String> open = List.of(
                "/api/health",
                "/api/auth/me",
                "/api/auth/login",
                "/api/auth/register",
                "/api/auth/google/callback");

        for (String path : open) {
            assertThat(run(rig.filter(), get(path)).getStatus())
                    .as("open endpoint %s", path)
                    .isEqualTo(200);
        }
    }

    @Test
    void theDataAccessConsentStartStaysProtected() throws Exception {
        Rig rig = rig();
        // Handing out Gmail/Calendar consent is not something a stranger gets to start.
        assertThat(run(rig.filter(), get("/api/oauth/google/start")).getStatus()).isEqualTo(401);
        assertThat(run(rig.filter(), get("/api/oauth/google/callback")).getStatus()).isEqualTo(401);
    }

    @Test
    void anExemptPathStillLearnsWhoIsSignedIn() throws Exception {
        Rig rig = rig();
        MockHttpServletRequest request = get("/api/auth/me");
        request.setCookies(new Cookie(SessionCookies.NAME, rig.token()));

        run(rig.filter(), request);

        assertThat(AuthFilter.current(request)).map(User::email).contains("ada@example.com");
    }

    @Test
    void corsPreflightIsNotAnAuthenticationQuestion() throws Exception {
        Rig rig = rig();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/runs");
        request.setRequestURI("/api/runs");

        assertThat(run(rig.filter(), request).getStatus()).isEqualTo(200);
    }

    @Test
    void nonApiTrafficIsNoneOfTheFiltersBusiness() throws Exception {
        Rig rig = rig();
        assertThat(run(rig.filter(), get("/index.html")).getStatus()).isEqualTo(200);
    }

    @Test
    void theGuardCanBeTurnedOffForAnOfflineDemo() throws Exception {
        AuthDoubles.InMemoryUsers users = new AuthDoubles.InMemoryUsers();
        AuthDoubles.InMemorySessions sessions = new AuthDoubles.InMemorySessions();
        AuthService auth = new AuthService(users, sessions, new BCryptPasswordHasher(4),
                new TestDoubles.FixedClock());

        AuthFilter disabled = new AuthFilter(auth, false);

        assertThat(run(disabled, get("/api/runs")).getStatus()).isEqualTo(200);
    }
}
