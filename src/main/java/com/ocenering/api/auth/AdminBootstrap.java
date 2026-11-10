package com.ocenering.api.auth;

import com.ocenering.api.db.entity.AppUser;
import com.ocenering.api.db.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first admin from ADMIN_EMAIL / ADMIN_PASSWORD when the user table is empty. */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthProperties props;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder, AuthProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = props.adminEmail();
        String password = props.adminPassword();

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }

        if (users.count() > 0) {
            return;
        }

        int minLength = props.minPasswordLength();

        if (password.length() < minLength) {
            throw new IllegalStateException("ADMIN_PASSWORD must be at least " + minLength + " characters");
        }

        AppUser admin = new AppUser(email, "ShipHappens Administrator", encoder.encode(password));
        users.save(admin);
        log.info("Created initial admin user {}", email);
    }
}

