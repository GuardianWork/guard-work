package com.guardwork.backend.auth;

import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;

@Service
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,50}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    public AuthResponse register(RegisterUserRequest request) {
        validateRegistration(request);

        if (userRepository.findByUsername(request.username()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username already taken");
        }
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }

        User user = new User();
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setUsername(request.username());
        user.setEmail(request.email().toLowerCase().trim());
        user.setPassword(passwordEncoder.encode(request.password()));

        return AuthResponse.from(userRepository.save(user));
    }

    public AuthResponse login(LoginRequest request) {
        Optional<User> byUsername = userRepository.findByUsername(request.usernameOrEmail());
        Optional<User> user = byUsername.isPresent() ? byUsername
                : userRepository.findByEmail(request.usernameOrEmail().toLowerCase());

        if (user.isEmpty() || !passwordEncoder.matches(request.password(), user.get().getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        return AuthResponse.from(user.get());
    }

    private void validateRegistration(RegisterUserRequest request) {
        if (blank(request.firstName())) {
            throw badRequest("first name is required");
        }
        if (blank(request.lastName())) {
            throw badRequest("last name is required");
        }
        if (blank(request.username()) || !USERNAME_PATTERN.matcher(request.username()).matches()) {
            throw badRequest("username must be 3-50 characters (letters, digits, underscore)");
        }
        if (blank(request.email()) || !EMAIL_PATTERN.matcher(request.email()).matches()) {
            throw badRequest("valid email is required");
        }
        if (blank(request.password()) || request.password().length() < 8) {
            throw badRequest("password must be at least 8 characters");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}