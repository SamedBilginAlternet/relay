package com.relay.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.relay.application.port.UserScope;
import com.relay.domain.Connection;
import com.relay.infrastructure.crypto.AesGcmCipher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JpaConnectionRepositoryOwnershipTest {

    @Test
    void readsAndWritesAreAlwaysScopedToTheSignedInUser() {
        ConnectionEntityRepository entities = mock(ConnectionEntityRepository.class);
        UserScope users = new UserScope();
        JpaConnectionRepository repository = new JpaConnectionRepository(
                entities, new AesGcmCipher("test-key"), users);
        UUID ada = UUID.randomUUID();
        Connection github = Connection.of("github", Map.of("token", "ada-token"), Instant.now());

        when(entities.findAllByUserId(ada)).thenReturn(List.of());
        when(entities.findByUserIdAndProvider(ada, "github")).thenReturn(Optional.empty());

        try (UserScope.Scope ignored = users.enter(ada)) {
            assertThat(repository.findAll()).isEmpty();
            repository.save(github);
        }

        verify(entities).findAllByUserId(ada);
        verify(entities).findByUserIdAndProvider(ada, "github");
        verify(entities).save(org.mockito.ArgumentMatchers.argThat(entity ->
                ada.equals(entity.getUserId()) && "github".equals(entity.getProvider())));
        assertThat(repository.findByProvider("github")).isEmpty();
    }

    @Test
    void theSameProviderLookupUsesDifferentOwnerKeys() {
        ConnectionEntityRepository entities = mock(ConnectionEntityRepository.class);
        UserScope users = new UserScope();
        JpaConnectionRepository repository = new JpaConnectionRepository(
                entities, new AesGcmCipher("test-key"), users);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        when(entities.findByUserIdAndProvider(first, "slack")).thenReturn(Optional.empty());
        when(entities.findByUserIdAndProvider(second, "slack")).thenReturn(Optional.empty());

        try (UserScope.Scope ignored = users.enter(first)) {
            repository.findByProvider("slack");
        }
        try (UserScope.Scope ignored = users.enter(second)) {
            repository.findByProvider("slack");
        }

        verify(entities).findByUserIdAndProvider(first, "slack");
        verify(entities).findByUserIdAndProvider(second, "slack");
    }
}
