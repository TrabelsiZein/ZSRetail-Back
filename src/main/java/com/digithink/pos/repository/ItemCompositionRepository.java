package com.digithink.pos.repository;

import java.util.List;
import java.util.Optional;

import com.digithink.pos.model.ItemComposition;

public interface ItemCompositionRepository extends _BaseRepository<ItemComposition, Long> {

	List<ItemComposition> findByParentItemIdAndActiveTrue(Long parentItemId);

	Optional<ItemComposition> findByParentItemIdAndComponentItemId(Long parentItemId, Long componentItemId);

	List<ItemComposition> findByParentItemId(Long parentItemId);

	List<ItemComposition> findByComponentItemId(Long componentItemId);
}
