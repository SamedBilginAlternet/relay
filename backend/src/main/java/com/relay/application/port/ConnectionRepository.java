package com.relay.application.port;

import com.relay.domain.Connection;
import java.util.List;
import java.util.Optional;

public interface ConnectionRepository {

    List<Connection> findAll();

    Optional<Connection> findByProvider(String provider);

    Connection save(Connection connection);
}
