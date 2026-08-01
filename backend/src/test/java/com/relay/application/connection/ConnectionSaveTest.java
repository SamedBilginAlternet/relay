package com.relay.application.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.application.port.ToolRegistry;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An API that reports something saved has to have saved it somewhere the rest of the API
 * can see.
 *
 * <p>{@code PUT /api/connections {"provider":"evilcorp"}} answered
 * {@code {"configured":true}} and stored a token under a name no tool will ever be called
 * with. It did not appear in {@code GET /api/connections}, which lists the providers Relay
 * knows, so it could not be reviewed or removed either — a credential accepted into a place
 * with no door.
 */
class ConnectionSaveTest {

    private ConnectionService service(TestDoubles.InMemoryConnectionRepository store) {
        ToolRegistry tools = new ToolRegistryImpl(List.of());
        return new ConnectionService(store, tools, new TestDoubles.FixedClock());
    }

    @Test
    void a_provider_relay_has_no_tools_for_is_refused_rather_than_stored() {
        TestDoubles.InMemoryConnectionRepository store = new TestDoubles.InMemoryConnectionRepository();

        assertThatThrownBy(() -> service(store).save("evilcorp", Map.of("token", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bilinmeyen sağlayıcı");

        assertThat(store.findByProvider("evilcorp")).isEmpty();
        // The listing is the only place a stored credential can be seen; it did not change.
        assertThat(service(store).describeAll())
                .allSatisfy(row -> assertThat(row.get("configured")).isEqualTo(false));
    }

    @Test
    void the_four_providers_relay_does_have_tools_for_still_save() {
        TestDoubles.InMemoryConnectionRepository store = new TestDoubles.InMemoryConnectionRepository();
        ConnectionService connections = service(store);

        for (String provider : connections.providers()) {
            connections.save(provider, Map.of("token", "gizli-" + provider));
            assertThat(store.findByProvider(provider)).isPresent();
        }
    }
}
