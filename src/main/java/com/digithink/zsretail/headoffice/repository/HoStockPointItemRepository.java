package com.digithink.zsretail.headoffice.repository;

import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoStockPointItemRepository extends _BaseRepository<HoStockPointItem, Long> {

	long countByStockPointId(Long stockPointId);
}
