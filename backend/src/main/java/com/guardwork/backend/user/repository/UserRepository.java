package com.guardwork.backend.user.repository;

import java.util.Optional;

import com.guardwork.backend.user.model.User;

public interface UserRepository {

    User save(User user);

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);
}