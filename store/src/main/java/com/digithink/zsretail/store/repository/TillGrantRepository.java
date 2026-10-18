package com.digithink.zsretail.store.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.model.TillGrant;

/** Step D (3.1.0): the grants of the badge scans (docs/modules/badge-permissions.md). */
@Repository
public interface TillGrantRepository extends _BaseRepository<TillGrant, Long> {

	Optional<TillGrant> findByTokenHash(String tokenHash);

	/**
	 * Uses a grant, once: 1 when this call used it, 0 when it was used already (another request was first). The caller
	 * checked the action, the session and the expiry; the request's transaction gives it back if the request fails.
	 */
	@Modifying(flushAutomatically = true) // no clear: it runs inside the sale, whose entities stay attached
	@Query("UPDATE TillGrant g SET g.consumedAt = :now, g.consumedByRef = :ref, g.updatedAt = :now"
			+ " WHERE g.id = :id AND g.consumedAt IS NULL")
	int consume(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("ref") String ref);
}
