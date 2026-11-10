package com.ocenering.api.auth;

import com.ocenering.api.db.entity.Session;
import com.ocenering.api.db.repository.SessionRepository;
import com.ocenering.api.db.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SessionService {

    private final SessionRepository sessions;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    public SessionService(SessionRepository sessions, UserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    public String create(UUID userId, Duration ttl, HttpServletRequest req) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        Session session = new Session();
        session.setTokenHash(sha256(token));
        session.setUser(users.getReferenceById(userId));
        session.setExpiresAt(OffsetDateTime.now().plus(ttl));
        session.setIp(req.getRemoteAddr());
        session.setUserAgent(req.getHeader("User-Agent"));
        sessions.save(session);

        return token;
    }

    public Optional<AuthenticatedUser> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        return sessions.findActiveByTokenHash(sha256(token), OffsetDateTime.now());
    }

    public void delete(String token) {
        sessions.deleteByTokenHash(sha256(token));
    }

    public void deleteOthersForUser(UUID userId, String keepToken) {
        sessions.deleteOtherSessions(userId, sha256(keepToken));
    }

    public void deleteAllForUser(UUID userId) {
        sessions.deleteByUserId(userId);
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    public void purgeExpired() {
        sessions.deleteByExpiresAtBefore(OffsetDateTime.now());
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
