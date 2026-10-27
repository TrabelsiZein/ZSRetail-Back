package com.digithink.zsretail.core.erp.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.core.erp.model.ErpSyncJob;
import com.digithink.zsretail.erp.api.ErpCatalogueSync;
import com.digithink.zsretail.erp.api.ErpStoreOrderPosting;
import com.digithink.zsretail.erp.api.ErpSupplyInvoiceImport;
import com.digithink.zsretail.erp.api.ErpSyncWarningException;
import com.digithink.zsretail.erp.api.dto.ErpCustomerDTO;
import com.digithink.zsretail.erp.api.dto.ErpDeletionLogEntryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpLocationDTO;
import com.digithink.zsretail.erp.api.dto.ErpSalesDiscountDTO;
import com.digithink.zsretail.erp.api.dto.ErpSalesPriceDTO;
import com.digithink.zsretail.erp.api.dto.ErpSyncFilter;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ErpSyncJobRunner {

	private static final Logger LOGGER = LoggerFactory.getLogger(ErpSyncJobRunner.class);

	private final ErpSynchronizationManager synchronizationManager;
	private final ErpSyncCheckpointService checkpointService;
	private final ErpItemBootstrapService erpItemBootstrapService;
	private final ErpDeletionSyncService erpDeletionSyncService;

	/**
	 * Invoices from the ERP: the head office import (HoErpInvoiceService), present only with headoffice.supply.source=ERP.
	 * A field, so the constructor stays as it is.
	 */
	@Autowired(required = false)
	private ObjectProvider<ErpSupplyInvoiceImport> supplyInvoiceImport;

	/** Release 2.2: the job SYNC_CATALOGUE (navpospages connector only). A field, so the constructor stays as it is. */
	@Autowired(required = false)
	private ObjectProvider<ErpCatalogueSync> catalogueSync;

	/** 3.1.1: the job POST_STORE_ORDERS (HoStoreOrderPosting, headoffice.storeorders.post=ERP). A field, as the two above. */
	@Autowired(required = false)
	private ObjectProvider<ErpStoreOrderPosting> storeOrderPosting;

	/**
	 * Code cleanup step 5 (knot d): the exports of the store's documents (tickets, returns, sessions), by job type. A field,
	 * like the two above; an application without them (the head office after step 6) exports nothing.
	 */
	@Autowired(required = false)
	private List<ErpExportJob> exportJobs;

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
			case EXPORT_RETURNS:
			case EXPORT_SESSIONS:
				export(jobType);
				break;
			case SYNC_ERP_DELETIONS:
				List<ErpDeletionLogEntryDTO> deletionEntries = erpDeletionSyncService.applyDeletionsFromLog(filter);
				checkpointService.updateLastSync(jobType, deletionEntries);
				break;
			case IMPORT_SUPPLY_INVOICES:
				// Invoices from the ERP: no checkpoint (the head office's highest numbers are the memory)
				ErpSupplyInvoiceImport invoices = supplyInvoiceImport == null ? null
						: supplyInvoiceImport.getIfAvailable();
				if (invoices == null) {
					throw new ErpSyncWarningException("The supply invoices are read from the ERP only on a head office"
							+ " with headoffice.supply.source=ERP");
				}
				invoices.importSupplyInvoices();
				break;
			case SYNC_CATALOGUE:
				// Release 2.2: no checkpoint (the head office tables and the barcode cursor are the memory)
				ErpCatalogueSync catalogue = catalogueSync == null ? null : catalogueSync.getIfAvailable();
				if (catalogue == null) {
					throw new ErpSyncWarningException("The catalogue is synchronised in one job only on a head office whose"
							+ " catalogue comes from the ERP, through a connector that keeps points de stock");
				}
				catalogue.syncCatalogue();
				break;
			case POST_STORE_ORDERS:
				// 3.1.1: no checkpoint (the orders' status is the memory: SENT and ERROR wait)
				ErpStoreOrderPosting posting = storeOrderPosting == null ? null : storeOrderPosting.getIfAvailable();
				if (posting == null) {
					throw new ErpSyncWarningException("The stores' orders are posted into the ERP only on a head office"
							+ " with headoffice.storeorders.post=ERP");
				}
				posting.postStoreOrders();
				break;
			default:
				LOGGER.warn("Unhandled ERP sync job type {} for job {}", jobType, job.getJobType());
			}
		} catch (ErpSyncWarningException warning) {
			LOGGER.warn("ERP sync job {} skipped: {}", jobType, warning.getMessage());
			throw warning;
		}
	}

	/** The export of this job type; none in this application: nothing to export. */
	private void export(ErpSyncJobType jobType) {
		if (exportJobs != null) {
			for (ErpExportJob export : exportJobs) {
				if (export.jobType() == jobType) {
					export.export();
					return;
				}
			}
		}
		LOGGER.info("ERP sync job {}: no export of this type in this application, nothing sent", jobType);
	}
}
