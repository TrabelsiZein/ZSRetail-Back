package com.digithink.zsretail.core.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.core.model.StockMovement;

public interface StockMovementRepository extends _BaseRepository<StockMovement, Long> {

	/** 3.1.2, the item card: an item's last movements, newest first (the index ix_stock_movement_item). */
	List<StockMovement> findByItem_IdOrderByIdDesc(Long itemId, Pageable pageable);
}
