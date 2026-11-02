package com.digithink.zsretail.core.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.model.ItemCostHistory;
import com.digithink.zsretail.core.model.enumeration.CostChangeSource;
import com.digithink.zsretail.core.repository.ItemCostHistoryRepository;

import lombok.extern.log4j.Log4j2;

/**
 * 3.1.2, the cost history: one item_cost_history row per change of an item's CMP, called by each writer of the cost in
 * its transaction (StockService.applyEntryCost for the entries, ItemService.adjustCost, ItemService.create,
 * DataImportService). No row when the cost does not change, nothing where the installation keeps no stock of its own
 * (the CMP is not kept there). A sale never calls it.
 */
@Service
@Log4j2
public class CostHistoryService {

	@Autowired
	private ApplicationModeService applicationModeService;

	@Autowired
	private ItemCostHistoryRepository repository;

	/** What changed the cost: its source, its document (type and number, or none) and who. */
	public static final class Origin {
		final CostChangeSource source;
		final String documentType;
		final String documentNumber;
		final String changedBy;

		private Origin(CostChangeSource source, String documentType, String documentNumber, String changedBy) {
			this.source = source;
			this.documentType = documentType;
			this.documentNumber = documentNumber;
			this.changedBy = changedBy;
		}

		public static Origin of(CostChangeSource source, String documentType, String documentNumber, String changedBy) {
			return new Origin(source, documentType, documentNumber, changedBy);
		}

		/** A change without a document: by hand, an import, a creation. */
		public static Origin of(CostChangeSource source, String changedBy) {
			return new Origin(source, null, null, changedBy);
		}
	}

	/**
	 * One row: the item's cost went from oldCost to newCost (now), with the entry's quantity and unit cost. Nothing when
	 * they are equal (both null included) or newCost is null, or where this installation keeps no stock of its own.
	 */
	@Transactional(rollbackFor = Exception.class)
	public void record(Long itemId, Double oldCost, Double newCost, BigDecimal qtyIn, Double unitCostIn, Origin origin) {
		if (!applicationModeService.keepsOwnStock() || itemId == null || newCost == null || newCost.equals(oldCost)) {
			return;
		}
		ItemCostHistory row = new ItemCostHistory();
		row.setItemId(itemId);
		row.setChangedAt(LocalDateTime.now());
		row.setOldCost(oldCost);
		row.setNewCost(newCost);
		row.setSource(origin.source);
		row.setDocumentType(origin.documentType);
		row.setDocumentNumber(origin.documentNumber);
		row.setQtyIn(qtyIn);
		row.setUnitCostIn(unitCostIn);
		row.setChangedBy(origin.changedBy);
		repository.save(row);
		log.debug("Cost history: itemId={} {} -> {} ({})", itemId, oldCost, newCost, origin.source);
	}
}
