package com.digithink.zsretail.erp.spi;

import java.util.List;

import com.digithink.zsretail.erp.dto.ErpCustomerDTO;
import com.digithink.zsretail.erp.dto.ErpDeletionLogEntryDTO;
import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpLocationDTO;
import com.digithink.zsretail.erp.dto.ErpOperationResult;
import com.digithink.zsretail.erp.dto.ErpPaymentHeaderDTO;
import com.digithink.zsretail.erp.dto.ErpPaymentLineDTO;
import com.digithink.zsretail.erp.dto.ErpReturnDTO;
import com.digithink.zsretail.erp.dto.ErpReturnLineDTO;
import com.digithink.zsretail.erp.dto.ErpSalesDiscountDTO;
import com.digithink.zsretail.erp.dto.ErpSalesPriceDTO;
import com.digithink.zsretail.erp.dto.ErpSessionDTO;
import com.digithink.zsretail.erp.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.dto.ErpTicketDTO;
import com.digithink.zsretail.erp.dto.ErpTicketLineDTO;
import com.digithink.zsretail.erp.dto.PullOperationResult;

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
	 * Get the result metadata from the last pull operation (for logging purposes).
	 * This should be called immediately after a fetch operation within the same
	 * thread. Returns null if no pull operation was performed or if metadata is not
	 * available.
	 */
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
