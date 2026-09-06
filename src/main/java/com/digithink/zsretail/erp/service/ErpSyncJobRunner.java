package com.digithink.zsretail.erp.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.erp.dto.ErpCustomerDTO;
import com.digithink.zsretail.erp.dto.ErpDeletionLogEntryDTO;
import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpLocationDTO;
import com.digithink.zsretail.erp.dto.ErpSalesDiscountDTO;
import com.digithink.zsretail.erp.dto.ErpSalesPriceDTO;
import com.digithink.zsretail.erp.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.erp.model.ErpSyncJob;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ErpSyncJobRunner {

	private static final Logger LOGGER = LoggerFactory.getLogger(ErpSyncJobRunner.class);

	private final ErpSynchronizationManager synchronizationManager;
	private final ErpSyncCheckpointService checkpointService;
	private final ErpItemBootstrapService erpItemBootstrapService;
	private final TicketExportService ticketExportService;
	private final ReturnExportService returnExportService;
	private final SessionExportService sessionExportService;
	private final ErpDeletionSyncService erpDeletionSyncService;

	public void run(ErpSyncJob job) {
		ErpSyncJobType jobType = job.getJobType();
		if (jobType == null) {
			LOGGER.warn("ERP sync job {} has no type defined", job.getJobType());
			return;
		}

		ErpSyncFilter filter = checkpointService.createFilterForJob(jobType);
		try {
			switch (jobType) {
			case IMPORT_ITEM_FAMILIES:
				List<ErpItemFamilyDTO> families = synchronizationManager.pullItemFamilies(filter);
				erpItemBootstrapService.importItemFamilies(families);
				checkpointService.updateLastSync(jobType, families);
				break;
			case IMPORT_ITEM_SUBFAMILIES:
				List<ErpItemSubFamilyDTO> subFamilies = synchronizationManager.pullItemSubFamilies(filter);
				erpItemBootstrapService.importItemSubFamilies(subFamilies);
				checkpointService.updateLastSync(jobType, subFamilies);
				break;
			case IMPORT_ITEMS:
				List<ErpItemDTO> items = synchronizationManager.pullItems(filter);
				erpItemBootstrapService.importItems(items);
				checkpointService.updateLastSync(jobType, items);
				break;
			case IMPORT_ITEM_BARCODES:
				List<ErpItemBarcodeDTO> barcodes = synchronizationManager.pullItemBarcodes(filter);
				erpItemBootstrapService.importItemBarcodes(barcodes);
				checkpointService.updateLastSync(jobType, barcodes);
				break;
			case IMPORT_LOCATIONS:
				List<ErpLocationDTO> locations = synchronizationManager.pullLocations(filter);
				erpItemBootstrapService.importLocations(locations);
				checkpointService.updateLastSync(jobType, locations);
				break;
			case IMPORT_CUSTOMERS:
				List<ErpCustomerDTO> customers = synchronizationManager.pullCustomers(filter);
				erpItemBootstrapService.importCustomers(customers);
				checkpointService.updateLastSync(jobType, customers);
				break;
			case IMPORT_SALES_PRICES_AND_DISCOUNTS:
				// Create separate filters for each operation
				ErpSyncFilter priceFilter = new ErpSyncFilter();
				checkpointService.resolveLastSyncForSalesPrices().ifPresent(priceFilter::setUpdatedAfter);
				ErpSyncFilter discountFilter = new ErpSyncFilter();
				checkpointService.resolveLastSyncForSalesDiscounts().ifPresent(discountFilter::setUpdatedAfter);

				// Pull and import sales prices
				List<ErpSalesPriceDTO> salesPrices = synchronizationManager.pullSalesPrices(priceFilter);
				erpItemBootstrapService.importSalesPrices(salesPrices);
				checkpointService.updateLastSyncForSalesPrices(salesPrices);

				// Pull and import sales discounts
				List<ErpSalesDiscountDTO> salesDiscounts = synchronizationManager.pullSalesDiscounts(discountFilter);
				erpItemBootstrapService.importSalesDiscounts(salesDiscounts);
				checkpointService.updateLastSyncForSalesDiscounts(salesDiscounts);
				break;
			case EXPORT_CUSTOMERS:
				LOGGER.info("ERP sync job {} is configured for EXPORT_CUSTOMERS. "
						+ "Data extraction from POS is not yet implemented.", job.getJobType());
				break;
			case EXPORT_TICKETS:
				ticketExportService.exportTickets();
				break;
			case EXPORT_RETURNS:
				returnExportService.exportReturns();
				break;
			case EXPORT_SESSIONS:
				sessionExportService.exportSessions();
				break;
			case SYNC_ERP_DELETIONS:
				List<ErpDeletionLogEntryDTO> deletionEntries = erpDeletionSyncService.applyDeletionsFromLog(filter);
				checkpointService.updateLastSync(jobType, deletionEntries);
				break;
			default:
				LOGGER.warn("Unhandled ERP sync job type {} for job {}", jobType, job.getJobType());
			}
		} catch (ErpSyncWarningException warning) {
			LOGGER.warn("ERP sync job {} skipped: {}", jobType, warning.getMessage());
			throw warning;
		}
	}
}
