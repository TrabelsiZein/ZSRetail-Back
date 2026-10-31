package com.digithink.zsretail.connector.parafendri.connector;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.parafendri.ParafendriConnectorDescriptor;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpConnector;
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
import com.digithink.zsretail.erp.api.dto.ErpSalesPriceDTO;
import com.digithink.zsretail.erp.api.dto.ErpSessionDTO;
import com.digithink.zsretail.erp.api.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.api.dto.ErpTicketDTO;
import com.digithink.zsretail.erp.api.dto.ErpTicketLineDTO;

/**
 * ParaFendri, step 0: the ERP connector of a store on ParaFendri's Business Central, the shape of EMTOP's (a store
 * connector, not a head office's catalogue source). Exists only with erp.connector=parafendri: the only ErpConnector
 * bean then. Nothing is implemented yet: every fetch answers an empty list, every push or update a failure, so the
 * store's file keeps erp.sync.enabled=false until the jobs are written.
 */
@Component
@ConditionalOnErpConnector(ParafendriConnectorDescriptor.NAME)
public class ParafendriConnector implements ErpConnector {

	static final String NOT_YET = "The parafendri ERP connector is at step 0 (settings and connection test): it sends"
			+ " nothing to the ERP yet.";

	@Override
	public List<ErpItemFamilyDTO> fetchItemFamilies(ErpSyncFilter filter) {
		return Collections.emptyList();
	}

	@Override
	public List<ErpItemSubFamilyDTO> fetchItemSubFamilies(ErpSyncFilter filter) {
		return Collections.emptyList();
	}

	@Override
	public List<ErpItemDTO> fetchItems(ErpSyncFilter filter) {
		return Collections.emptyList();
	}

	@Override
	public List<ErpItemBarcodeDTO> fetchItemBarcodes(ErpSyncFilter filter) {
		return Collections.emptyList();
	}

	@Override
	public List<ErpLocationDTO> fetchLocations(ErpSyncFilter filter) {
		return Collections.emptyList();
	}

	@Override
	public List<ErpCustomerDTO> fetchCustomers(ErpSyncFilter filter) {
		return Collections.emptyList();
	}

	@Override
	public List<ErpSalesPriceDTO> fetchSalesPrices(ErpSyncFilter filter) {
		return Collections.emptyList();
	}

	@Override
	public List<ErpSalesDiscountDTO> fetchSalesDiscounts(ErpSyncFilter filter) {
		return Collections.emptyList();
	}

	@Override
	public List<ErpDeletionLogEntryDTO> fetchDeletionLog(ErpSyncFilter filter) {
		return Collections.emptyList();
	}

	@Override
	public ErpOperationResult pushCustomer(ErpCustomerDTO customer) {
		return ErpOperationResult.failure(NOT_YET);
	}

	@Override
	public ErpOperationResult pushTicket(ErpTicketDTO ticket) {
		return ErpOperationResult.failure(NOT_YET);
	}

	@Override
	public ErpOperationResult pushTicketHeader(ErpTicketDTO ticket) {
		return ErpOperationResult.failure(NOT_YET);
	}

	@Override
	public ErpOperationResult pushTicketLine(ErpTicketDTO ticket, String externalReference, ErpTicketLineDTO line) {
		return ErpOperationResult.failure(NOT_YET);
	}

	@Override
	public ErpOperationResult updateTicketStatus(String externalReference, boolean posOrder, Boolean posInvoice,
			String fiscalRegistration, String billToName2) {
		return ErpOperationResult.failure(NOT_YET);
	}

	@Override
	public ErpOperationResult pushPaymentHeader(ErpPaymentHeaderDTO headerDTO) {
		return ErpOperationResult.failure(NOT_YET);
	}

	@Override
	public ErpOperationResult pushPaymentLine(String paymentHeaderDocNo, ErpPaymentLineDTO lineDTO) {
		return ErpOperationResult.failure(NOT_YET);
	}

	@Override
	public ErpOperationResult pushReturnHeader(ErpReturnDTO returnDTO) {
		return ErpOperationResult.failure(NOT_YET);
	}

	@Override
	public ErpOperationResult pushReturnLine(ErpReturnDTO returnDTO, String externalReference, ErpReturnLineDTO lineDTO) {
		return ErpOperationResult.failure(NOT_YET);
	}

	@Override
	public ErpOperationResult updateReturnStatus(String externalReference, boolean posOrder) {
		return ErpOperationResult.failure(NOT_YET);
	}

	@Override
	public ErpOperationResult pushSession(ErpSessionDTO sessionDTO) {
		return ErpOperationResult.failure(NOT_YET);
	}
}
