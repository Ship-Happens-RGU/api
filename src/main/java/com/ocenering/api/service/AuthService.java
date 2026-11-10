package com.ocenering.api.service;

import com.ocenering.api.auth.AuditService;
import com.ocenering.api.auth.AuthProperties;
import com.ocenering.api.auth.AuthenticatedUser;
import com.ocenering.api.auth.SessionAuthFilter;
import com.ocenering.api.auth.SessionService;
import com.ocenering.api.db.entity.AppUser;
import com.ocenering.api.db.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    public record LoginResult(ResponseCookie cookie, AuthenticatedUser user) {
    }

    private final UserRepository users;
    private final SessionService sessions;
    private final AuditService audit;
    private final PasswordEncoder encoder;
    private final AuthProperties props;
    private final String dummyHash;

    public AuthService(UserRepository users, SessionService sessions, AuditService audit,
                       PasswordEncoder encoder, AuthProperties props) {
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
        this.encoder = encoder;
        this.props = props;
        this.dummyHash = encoder.encode("timing-" + UUID.randomUUID());
    }

    public Optional<LoginResult> login(String email, String password, HttpServletRequest http) {
        Optional<AppUser> user = users.findByEmailIgnoreCase(email);

        String hash = user.map(AppUser::getPasswordHash).orElse(dummyHash);
        boolean passwordOk = encoder.matches(password, hash);

        boolean allowed = user.isPresent()
                && user.get().getPasswordHash() != null
                && passwordOk
                && !user.get().isLocked()
                && !user.get().isDisabled();

        if (!allowed) {
            user.filter(u -> !u.isLocked()).ifPresent(u ->
                    users.recordFailedAttempt(u.getId(), props.maxFailedAttempts(),
                            OffsetDateTime.now().plus(props.lockDuration())));
            audit.log("login_failed", user.map(AppUser::getId).orElse(null), email, http);

            return Optional.empty();
        }

        AppUser u = user.get();
        String oldToken = SessionAuthFilter.readToken(http);

        if (oldToken != null) {
            sessions.delete(oldToken);
        }

        users.resetFailedAttempts(u.getId());
        String token = sessions.create(u.getId(), props.sessionTtl(), http);
        audit.log("login_success", u.getId(), u.getEmail(), http);

        ResponseCookie cookie = sessionCookie(token, props.sessionTtl());
        AuthenticatedUser authenticatedUser = new AuthenticatedUser(
                u.getId(), u.getEmail(), u.getDisplayName(), u.isMustChangePassword());

        return Optional.of(new LoginResult(cookie, authenticatedUser));
    }

    public ResponseCookie logout(AuthenticatedUser me, HttpServletRequest http) {
        String token = SessionAuthFilter.readToken(http);

        if (token != null) {
            sessions.delete(token);
        }

        if (me != null) {
            audit.log("logout", me.id(), me.email(), http);
        }

        return sessionCookie("", Duration.ZERO);
    }

    public ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(SessionAuthFilter.COOKIE_NAME, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}

