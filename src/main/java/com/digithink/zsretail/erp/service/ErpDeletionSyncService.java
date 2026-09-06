package com.digithink.zsretail.erp.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.erp.dto.ErpDeletionLogEntryDTO;
import com.digithink.zsretail.erp.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.dynamicsnav.mapper.DynamicsNavMapper;
import com.digithink.zsretail.repository.SalesDiscountRepository;
import com.digithink.zsretail.repository.SalesPriceRepository;

import lombok.RequiredArgsConstructor;

/**
 * Applies deletion sync from ERP Log: fetches deletion records from Business
 * Central Log API and removes the corresponding Sales Price and Sales Discount
 * records from POS by erp_external_id.
 */
@Service
@RequiredArgsConstructor
public class ErpDeletionSyncService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ErpDeletionSyncService.class);

	private final ErpSynchronizationManager synchronizationManager;
	private final DynamicsNavMapper dynamicsNavMapper;
	private final SalesPriceRepository salesPriceRepository;
	private final SalesDiscountRepository salesDiscountRepository;

	/**
	 * Fetches deletion log from ERP and removes from POS any Sales Price or Sales
	 * Discount records that match by erp_external_id. Returns the list of
	 * processed log entries (for checkpoint update).
	 */
	@Transactional
	public List<ErpDeletionLogEntryDTO> applyDeletionsFromLog(ErpSyncFilter filter) {
		List<ErpDeletionLogEntryDTO> entries = synchronizationManager.pullDeletionLog(filter);
		if (entries == null || entries.isEmpty()) {
			return List.of();
		}

		int[] deletedPrices = { 0 };
		int[] deletedDiscounts = { 0 };
		for (ErpDeletionLogEntryDTO entry : entries) {
			String priceExternalId = dynamicsNavMapper.buildSalesPriceExternalIdFromLogEntry(entry);
			if (priceExternalId != null) {
				salesPriceRepository.findByErpExternalId(priceExternalId).ifPresent(entity -> {
					salesPriceRepository.delete(entity);
					deletedPrices[0]++;
					LOGGER.debug("Deleted SalesPrice by erp_external_id: {}", priceExternalId);
				});
				continue;
			}

			String discountExternalId = dynamicsNavMapper.buildSalesDiscountExternalIdFromLogEntry(entry);
			if (discountExternalId != null) {
				salesDiscountRepository.findByErpExternalId(discountExternalId).ifPresent(entity -> {
					salesDiscountRepository.delete(entity);
					deletedDiscounts[0]++;
					LOGGER.debug("Deleted SalesDiscount by erp_external_id: {}", discountExternalId);
				});
				continue;
			}

			// Unknown Source_Table, skip
			if (entry.getSourceTable() != null && !entry.getSourceTable().isBlank()) {
				LOGGER.trace("Skipping deletion log entry with unknown Source_Table: {}", entry.getSourceTable());
			}
		}

		if (deletedPrices[0] > 0 || deletedDiscounts[0] > 0) {
			LOGGER.info("ERP deletion sync: processed {} entries, deleted {} SalesPrice, {} SalesDiscount",
					entries.size(), deletedPrices[0], deletedDiscounts[0]);
		}

		return entries;
	}
}
