package com.relay.application.port;

import com.relay.domain.User;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    /** {@code email} is expected already normalised (trimmed, lower case). */
    Optional<User> findByEmail(String email);

    Optional<User> findById(UUID id);

    User save(User user);

    long count();
}
