package com.digithink.zsretail.headoffice.repository;

import java.util.Collection;
import java.util.List;

import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoStockPointItemRepository extends _BaseRepository<HoStockPointItem, Long> {

	long countByStockPointId(Long stockPointId);

	/** Stock points, step 3a: the point's rows of these items (the copy sent to a store of the point). */
	List<HoStockPointItem> findByStockPointIdAndItemIdIn(Long stockPointId, Collection<Long> itemIds);
}
