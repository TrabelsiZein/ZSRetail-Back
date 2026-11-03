package com.digithink.zsretail.store.repository;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.model.QuickQuantity;

/** 3.1.2, bulk sale: the store's quick quantities. */
@Repository
public interface QuickQuantityRepository extends _BaseRepository<QuickQuantity, Long> {

	/** How many quick quantities add this item as their container (ItemDeletionCheck). */
	long countByLinkedItemId(Long itemId);
}
