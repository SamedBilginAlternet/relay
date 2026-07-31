package com.relay.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.relay.application.json.Json;
import com.relay.application.port.ConnectionRepository;
import com.relay.domain.Connection;
import com.relay.infrastructure.crypto.AesGcmCipher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persists connections with the config blob encrypted at rest. */
@Repository
public class JpaConnectionRepository implements ConnectionRepository {

    private final ConnectionEntityRepository connections;
    private final AesGcmCipher cipher;

    public JpaConnectionRepository(ConnectionEntityRepository connections, AesGcmCipher cipher) {
        this.connections = connections;
        this.cipher = cipher;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Connection> findAll() {
        List<Connection> out = new ArrayList<>();
        connections.findAll().forEach(entity -> out.add(toDomain(entity)));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Connection> findByProvider(String provider) {
        return connections.findByProvider(provider).map(this::toDomain);
    }

    @Override
    @Transactional
    public Connection save(Connection connection) {
        ConnectionEntity entity = connections.findByProvider(connection.provider())
                .orElseGet(ConnectionEntity::new);
        if (entity.getId() == null) {
            entity.setId(connection.id());
            entity.setProvider(connection.provider());
            entity.setCreatedAt(connection.createdAt());
        }
        entity.setConfig(cipher.encrypt(Json.write(connection.config())));
        connections.save(entity);
        return connection;
    }

    private Connection toDomain(ConnectionEntity entity) {
        Map<String, String> config = new LinkedHashMap<>();
        try {
            String plain = cipher.decrypt(entity.getConfig());
            config.putAll(Json.mapper().readValue(plain, new TypeReference<LinkedHashMap<String, String>>() {
            }));
        } catch (Exception e) {
            // A key rotation should not take the whole app down — the connection simply reads empty.
            config.clear();
        }
        return new Connection(entity.getId(), entity.getProvider(), config, entity.getCreatedAt());
    }
}
