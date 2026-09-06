package com.finagent.repository;

import com.finagent.model.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 2: refresh-token persistence.
 *
 * <p>Rotation reads the presented token with a pessimistic write lock so two
 * concurrent uses of the same token serialize in the database (the loser then
 * observes the winner's {@code used_at} mark and triggers reuse handling)
 * instead of racing on application-level checks. The {@code UNIQUE} constraint
 * on {@code token_hash} is the backstop.</p>
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByFamilyId(UUID familyId);

    List<RefreshToken> findByUserId(UUID userId);

    boolean existsByTokenHash(String tokenHash);

    /**
     * Task 4: housekeeping bulk delete — one statement, no entity loading.
     *
     * <p>Deletes only rows that can no longer influence rotation or reuse
     * detection: (a) expired beyond the retention grace
     * ({@code expiresAt <= expiryCutoff}) — {@code rotate()} rejects such
     * rows as expired <i>before</i> consulting {@code usedAt}/{@code revokedAt},
     * so their tripwire value is already nil; (b) revoked beyond the
     * retention grace — presenting a revoked row changes no state, so its
     * absence is observationally identical (generic 401 either way). Used but
     * unexpired rows are NEVER matched: they are the live replay tripwires.</p>
     *
     * @return number of rows removed
     */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt <= :expiryCutoff "
            + "or (t.revokedAt is not null and t.revokedAt <= :revocationCutoff)")
    int deleteCleanupCandidates(@Param("expiryCutoff") Instant expiryCutoff,
                                @Param("revocationCutoff") Instant revocationCutoff);

    /** Task 4: session counters for the admin statistics endpoint. Active and
     * expired overlap revoked by design — these are independent counters for
     * operations, not a partition of the table (documented on the endpoint). */
    @Query("select count(t) from RefreshToken t "
            + "where t.usedAt is null and t.revokedAt is null and t.expiresAt > :now")
    long countActive(@Param("now") Instant now);

    @Query("select count(t) from RefreshToken t where t.revokedAt is not null")
    long countRevoked();

    @Query("select count(t) from RefreshToken t where t.expiresAt <= :now")
    long countExpired(@Param("now") Instant now);

    @Query("select count(distinct t.familyId) from RefreshToken t")
    long countFamilies();
}
