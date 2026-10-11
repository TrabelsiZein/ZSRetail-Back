package com.digithink.zsretail.headoffice.repository;

import java.util.List;
import java.util.Optional;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.headoffice.model.HoStockPoint;

public interface HoStockPointRepository extends _BaseRepository<HoStockPoint, Long> {

	Optional<HoStockPoint> findByCodeIgnoreCase(String code);

	/** Every point in list order. */
	List<HoStockPoint> findAllByOrderBySortOrderAscCodeAsc();
}
