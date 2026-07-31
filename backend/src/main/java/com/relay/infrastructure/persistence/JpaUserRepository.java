package com.relay.infrastructure.persistence;

import com.relay.application.port.UserRepository;
import com.relay.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaUserRepository implements UserRepository {

    private final UserEntityRepository users;

    public JpaUserRepository(UserEntityRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return users.findByEmail(email).map(JpaUserRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findById(UUID id) {
        return users.findById(id).map(JpaUserRepository::toDomain);
    }

    @Override
    @Transactional
    public User save(User user) {
        UserEntity entity = users.findById(user.id()).orElseGet(UserEntity::new);
        entity.setId(user.id());
        entity.setEmail(user.email());
        entity.setPasswordHash(user.passwordHash());
        entity.setDisplayName(user.displayName());
        entity.setAvatarUrl(user.avatarUrl());
        entity.setProvider(user.provider());
        entity.setOnboardedAt(user.onboardedAt());
        entity.setCreatedAt(user.createdAt());
        users.save(entity);
        return user;
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return users.count();
    }

    private static User toDomain(UserEntity entity) {
        return new User(entity.getId(), entity.getEmail(), entity.getPasswordHash(), entity.getDisplayName(),
                entity.getAvatarUrl(), entity.getProvider(), entity.getOnboardedAt(), entity.getCreatedAt());
    }
}
