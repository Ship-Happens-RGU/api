package com.ocenering.api.db.repository;

import com.ocenering.api.db.entity.AppUser;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<AppUser, UUID> {

    /** Matches the unique index on lower(email). */
    Optional<AppUser> findByEmailIgnoreCase(String email);

    /** Adds a failed attempt; after max attempts, locks the account and resets the counter. */
    @Modifying
    @Transactional
    @Query("""
            UPDATE AppUser u SET
                u.failedAttempts = CASE WHEN u.failedAttempts + 1 >= :max
                                        THEN 0 ELSE u.failedAttempts + 1 END,
                u.lockedUntil    = CASE WHEN u.failedAttempts + 1 >= :max
                                        THEN :lockUntil ELSE u.lockedUntil END
            WHERE u.id = :id
            """)
    void recordFailedAttempt(@Param("id") UUID id,
                             @Param("max") int max,
                             @Param("lockUntil") OffsetDateTime lockUntil);

    @Modifying
    @Transactional
    @Query("UPDATE AppUser u SET u.failedAttempts = 0, u.lockedUntil = NULL WHERE u.id = :id")
    void resetFailedAttempts(@Param("id") UUID id);
}
