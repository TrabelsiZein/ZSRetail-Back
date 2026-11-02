package com.digithink.zsretail.core.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.digithink.zsretail.core.model.ItemCostHistory;

/** 3.1.2, the cost history (item_cost_history); the stock value at a date reads it in ReportService. */
public interface ItemCostHistoryRepository extends JpaRepository<ItemCostHistory, Long> {

	/** The card's "Historique du coût": an item's changes, newest first (the ids follow the time of the changes). */
	List<ItemCostHistory> findByItemIdOrderByIdDesc(Long itemId, Pageable page);
}
