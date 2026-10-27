package com.digithink.zsretail.erp.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.digithink.zsretail.erp.api.dto.ErpCustomerDTO;
import com.digithink.zsretail.erp.api.dto.ErpDeletionLogEntryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpLocationDTO;
import com.digithink.zsretail.erp.api.dto.ErpOperationResult;
import com.digithink.zsretail.erp.api.dto.ErpPaymentHeaderDTO;
import com.digithink.zsretail.erp.api.dto.ErpPaymentLineDTO;
import com.digithink.zsretail.erp.api.dto.ErpReturnDTO;
import com.digithink.zsretail.erp.api.dto.ErpReturnLineDTO;
import com.digithink.zsretail.erp.api.dto.ErpSalesDiscountDTO;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderDTO;
import com.digithink.zsretail.erp.api.dto.ErpSalesPriceDTO;
import com.digithink.zsretail.erp.api.dto.ErpSessionDTO;
import com.digithink.zsretail.erp.api.dto.ErpSupplyInvoiceDTO;
import com.digithink.zsretail.erp.api.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.api.dto.ErpTicketDTO;
import com.digithink.zsretail.erp.api.dto.ErpTicketLineDTO;
import com.digithink.zsretail.erp.api.dto.ErpVariantStockDTO;
import com.digithink.zsretail.erp.api.dto.PullOperationResult;

/**
 * Contract to be implemented by ERP-specific connectors.
 */
public interface ErpConnector {

	List<ErpItemFamilyDTO> fetchItemFamilies(ErpSyncFilter filter);

	List<ErpItemSubFamilyDTO> fetchItemSubFamilies(ErpSyncFilter filter);

	List<ErpItemDTO> fetchItems(ErpSyncFilter filter);

	List<ErpItemBarcodeDTO> fetchItemBarcodes(ErpSyncFilter filter);

	List<ErpLocationDTO> fetchLocations(ErpSyncFilter filter);

	List<ErpCustomerDTO> fetchCustomers(ErpSyncFilter filter);

	List<ErpSalesPriceDTO> fetchSalesPrices(ErpSyncFilter filter);

	List<ErpSalesDiscountDTO> fetchSalesDiscounts(ErpSyncFilter filter);

	/**
	 * Fetch deletion log entries from ERP (e.g. Business Central Log API). Used to
	 * remove from POS the records that were deleted in ERP.
	 */
	List<ErpDeletionLogEntryDTO> fetchDeletionLog(ErpSyncFilter filter);

	/**
	 * Code cleanup, step 2: the erp_external_id of the sales price a deletion log entry removes, as this connector built
	 * it at the import; null when the entry is not a sales price (or the connector has no deletion log).
	 */
	default String deletedSalesPriceExternalId(ErpDeletionLogEntryDTO entry) {
		return null;
	}

	/**
	 * Code cleanup, step 2: the erp_external_id of the sales discount a deletion log entry removes, as this connector
	 * built it at the import; null when the entry is not a sales discount (or the connector has no deletion log).
	 */
	default String deletedSalesDiscountExternalId(ErpDeletionLogEntryDTO entry) {
		return null;
	}

	/**
	 * Get the result metadata from the last pull operation (for logging purposes).
	 * This should be called immediately after a fetch operation within the same
	 * thread. Returns null if no pull operation was performed or if metadata is not
	 * available.
	 */
	/**
	 * Invoices from the ERP: the posted invoices to franchise stores after the highest number the head office has of each
	 * year, by year prefix (FVV26 -> FVV26000000123). Only the navpospages connector reads them; every other connector
	 * answers none.
	 */
	default List<ErpSupplyInvoiceDTO> fetchSupplyInvoices(Map<String, String> highestByYear) {
		return Collections.emptyList();
	}

	/**
	 * Code cleanup, step 2: the seller the head office names on the stores' purchase invoices copied from the ERP's
	 * invoices, as this connector's settings give it; null when the connector reads no invoices (the head office then
	 * uses its default).
	 */
	default String supplyInvoiceSellerName() {
		return null;
	}

	/** 2.2.1: the same invoices, given by number (read again). Only the navpospages connector reads them. */
	default List<ErpSupplyInvoiceDTO> fetchSupplyInvoicesByNumbers(List<String> numbers) {
		return Collections.emptyList();
	}

	/** 3.1.1 (store orders): the text of the two defaults below. */
	String STORE_ORDERS_NOT_SUPPORTED = "The ERP connector in effect does not take the stores' orders";

	/**
	 * 3.1.1 (store orders): the ERP's stock of every variant of an ERP item number at a location, read now. Only a
	 * connector with ErpAbility.STORE_ORDERS reads it; every other one throws {@link ErpNotSupportedException}.
	 */
	default List<ErpVariantStockDTO> fetchVariantStock(String itemNo, String locationCode) {
		throw new ErpNotSupportedException(STORE_ORDERS_NOT_SUPPORTED + " (no variant stock).");
	}

	/**
	 * 3.1.1 (store orders): posts a store's order as a sales order of the ERP; success with the ERP's order number as the
	 * external reference. The connector first looks up an order of the same externalDocumentNo and answers its number
	 * when there is one (never a second order). Only a connector with ErpAbility.STORE_ORDERS posts; every other one
	 * answers a failure.
	 */
	default ErpOperationResult postSalesOrder(ErpSalesOrderDTO order) {
		return ErpOperationResult.failure(STORE_ORDERS_NOT_SUPPORTED + ".");
	}

	default PullOperationResult<?> getLastPullOperationResult() {
		return null;
	}

	/**
	 * Clear the stored pull operation result metadata (should be called after
	 * logging). Default implementation does nothing - connectors that store
	 * metadata should override this.
	 */
	default void clearLastPullOperationResult() {
		// Default: no-op
	}

	ErpOperationResult pushCustomer(ErpCustomerDTO customer);

	ErpOperationResult pushTicket(ErpTicketDTO ticket);

	/**
	 * Push ticket header to ERP and return external reference (document number)
	 */
	ErpOperationResult pushTicketHeader(ErpTicketDTO ticket);

	/**
	 * Push a single ticket line to ERP
	 */
	ErpOperationResult pushTicketLine(ErpTicketDTO ticket, String externalReference, ErpTicketLineDTO line);

	/**
	 * Update ticket status in ERP (e.g., POS_Order flag). Optional POS_Invoice, Fiscal_Registration and Bill_to_Name_2 when preparing invoice.
	 */
	ErpOperationResult updateTicketStatus(String externalReference, boolean posOrder, Boolean posInvoice, String fiscalRegistration, String billToName2);

	/** Delegates to full signature with null invoice fields */
	default ErpOperationResult updateTicketStatus(String externalReference, boolean posOrder) {
		return updateTicketStatus(externalReference, posOrder, null, null, null);
	}

	/** Delegates to full signature with null billToName2 */
	default ErpOperationResult updateTicketStatus(String externalReference, boolean posOrder, Boolean posInvoice, String fiscalRegistration) {
		return updateTicketStatus(externalReference, posOrder, posInvoice, fiscalRegistration, null);
	}

	/**
	 * Push payment header to ERP and return external reference (document number)
	 */
	ErpOperationResult pushPaymentHeader(ErpPaymentHeaderDTO headerDTO);

	/**
	 * Push a single payment line to ERP
	 */
	ErpOperationResult pushPaymentLine(String paymentHeaderDocNo, ErpPaymentLineDTO lineDTO);

	/**
	 * Push return header to ERP and return external reference (document number)
	 * TODO: Implement with Dynamics NAV later
	 */
	default ErpOperationResult pushReturnHeader(ErpReturnDTO returnDTO) {
		// Stub implementation - to be implemented with NAV later
		return ErpOperationResult.failure("Return export not yet implemented");
	}

	/**
	 * Push a single return line to ERP TODO: Implement with Dynamics NAV later
	 */
	default ErpOperationResult pushReturnLine(ErpReturnDTO returnDTO, String externalReference,
			ErpReturnLineDTO lineDTO) {
		// Stub implementation - to be implemented with NAV later
		return ErpOperationResult.failure("Return line export not yet implemented");
	}

	/**
	 * Update return header status in ERP (e.g. POS_Order = true after all lines are synched).
	 */
	ErpOperationResult updateReturnStatus(String externalReference, boolean posOrder);

	/**
	 * Push cashier session to ERP and return external reference (document number)
	 */
	default ErpOperationResult pushSession(ErpSessionDTO sessionDTO) {
		// Stub implementation - to be implemented with NAV later
		return ErpOperationResult.failure("Session export not yet implemented");
	}
}
