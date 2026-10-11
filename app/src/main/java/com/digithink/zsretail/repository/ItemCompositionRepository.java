package com.digithink.zsretail.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.digithink.zsretail.model.ItemComposition;

public interface ItemCompositionRepository extends _BaseRepository<ItemComposition, Long> {

	List<ItemComposition> findByParentItemIdAndActiveTrue(Long parentItemId);

	Optional<ItemComposition> findByParentItemIdAndComponentItemId(Long parentItemId, Long componentItemId);

	List<ItemComposition> findByParentItemId(Long parentItemId);

	List<ItemComposition> findByComponentItemId(Long componentItemId);

	/** Head office plan, step 6: the compositions of these packs. */
	List<ItemComposition> findByParentItemIdIn(Collection<Long> parentItemIds);
}
