package com.ocenering.api.db.repository;

import com.ocenering.api.auth.AuthenticatedUser;
import com.ocenering.api.db.entity.Session;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SessionRepository extends JpaRepository<Session, byte[]> {

    /** Returns the API-safe user for a valid, unexpired session whose account isn't disabled. */
    @Query("""
            SELECT new com.ocenering.api.auth.AuthenticatedUser(
                    u.id, u.email, u.displayName, u.mustChangePassword)
            FROM Session s JOIN s.user u
            WHERE s.tokenHash = :tokenHash
              AND s.expiresAt > :now
              AND u.disabled = false
            """)
    Optional<AuthenticatedUser> findActiveByTokenHash(@Param("tokenHash") byte[] tokenHash,
                                                      @Param("now") OffsetDateTime now);

    @Transactional
    void deleteByTokenHash(byte[] tokenHash);

    @Transactional
    void deleteByUserId(UUID userId);

    /** Signs a user out everywhere except the given session (e.g. after a password change). */
    @Modifying
    @Transactional
    @Query("DELETE FROM Session s WHERE s.user.id = :userId AND s.tokenHash <> :keepTokenHash")
    void deleteOtherSessions(@Param("userId") UUID userId, @Param("keepTokenHash") byte[] keepTokenHash);

    @Transactional
    void deleteByExpiresAtBefore(OffsetDateTime now);
}
