package com.digithink.zsretail.inventory.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import javax.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.inventory.enumeration.InventoryCountStatus;
import com.digithink.zsretail.inventory.model.InventoryCount;
import com.digithink.zsretail.repository._BaseRepository;

public interface InventoryCountRepository extends _BaseRepository<InventoryCount, Long> {

	/** The counts, newest first. */
	Page<InventoryCount> findAllByOrderByIdDesc(Pageable page);

	/** One count, its row locked until the end of the transaction (a new import, a deletion). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from InventoryCount c where c.id = :id")
	Optional<InventoryCount> findByIdForUpdate(@Param("id") Long id);

	/** The last count created: its number gives the next one. */
	Optional<InventoryCount> findTopByOrderByIdDesc();

	/**
	 * DRAFT to VALIDATED in one update, only while still DRAFT: 1 when this call took it, 0 when it was not a draft (a
	 * second validation waits for the first one's row lock, then finds it VALIDATED).
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update InventoryCount c set c.status = :validated, c.validatedAt = :at, c.validatedBy = :by,"
			+ " c.updatedAt = :at, c.updatedBy = :by where c.id = :id and c.status = :draft")
	int markValidated(@Param("id") Long id, @Param("at") LocalDateTime at, @Param("by") String by,
			@Param("draft") InventoryCountStatus draft, @Param("validated") InventoryCountStatus validated);
}
