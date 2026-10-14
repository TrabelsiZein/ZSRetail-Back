package com.digithink.zsretail.connector.zen.connector;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.zen.ZenConnectorDescriptor;
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
import com.digithink.zsretail.erp.api.dto.ErpSupplyInvoiceDTO;
import com.digithink.zsretail.erp.api.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.api.dto.ErpTicketDTO;
import com.digithink.zsretail.erp.api.dto.ErpTicketLineDTO;

/**
 * Item variants (ZEN), step 8: the ERP connector of ZEN's NAV. Read only: it never writes to the ERP. Exists only with
 * erp.connector=zen, on a head office whose catalogue only comes from the ERP (ZenStartupCheck); then the only
 * ErpConnector bean. The catalogue is read by the engine's single job SYNC_CATALOGUE through
 * {@code ZenCatalogueSource}: the catalogue fetches of this interface answer none, as every other fetch; every push or
 * update answers a failure.
 */
@Component
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
public class ZenConnector implements ErpConnector {

	static final String READ_ONLY = "The zen ERP connector is read only: it sends nothing to the ERP.";

	// TODO step 9: the supply invoices of ZEN's NAV (No + "~" + Variant_Code on each line), and the ability
	// SUPPLY_INVOICES in ZenConnectorDescriptor. Until then none: headoffice.supply.source=ERP is refused with zen.
	@Override
	public List<ErpSupplyInvoiceDTO> fetchSupplyInvoices(Map<String, String> highestByYear) {
		return Collections.emptyList();
	}

	// TODO step 9: with fetchSupplyInvoices.
	@Override
	public List<ErpSupplyInvoiceDTO> fetchSupplyInvoicesByNumbers(List<String> numbers) {
		return Collections.emptyList();
	}

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
		return ErpOperationResult.failure(READ_ONLY);
	}

	@Override
	public ErpOperationResult pushTicket(ErpTicketDTO ticket) {
		return ErpOperationResult.failure(READ_ONLY);
	}

	@Override
	public ErpOperationResult pushTicketHeader(ErpTicketDTO ticket) {
		return ErpOperationResult.failure(READ_ONLY);
	}

	@Override
	public ErpOperationResult pushTicketLine(ErpTicketDTO ticket, String externalReference, ErpTicketLineDTO line) {
		return ErpOperationResult.failure(READ_ONLY);
	}

	@Override
	public ErpOperationResult updateTicketStatus(String externalReference, boolean posOrder, Boolean posInvoice,
			String fiscalRegistration, String billToName2) {
		return ErpOperationResult.failure(READ_ONLY);
	}

	@Override
	public ErpOperationResult pushPaymentHeader(ErpPaymentHeaderDTO headerDTO) {
		return ErpOperationResult.failure(READ_ONLY);
	}

	@Override
	public ErpOperationResult pushPaymentLine(String paymentHeaderDocNo, ErpPaymentLineDTO lineDTO) {
		return ErpOperationResult.failure(READ_ONLY);
	}

	@Override
	public ErpOperationResult pushReturnHeader(ErpReturnDTO returnDTO) {
		return ErpOperationResult.failure(READ_ONLY);
	}

	@Override
	public ErpOperationResult pushReturnLine(ErpReturnDTO returnDTO, String externalReference, ErpReturnLineDTO lineDTO) {
		return ErpOperationResult.failure(READ_ONLY);
	}

	@Override
	public ErpOperationResult updateReturnStatus(String externalReference, boolean posOrder) {
		return ErpOperationResult.failure(READ_ONLY);
	}

	@Override
	public ErpOperationResult pushSession(ErpSessionDTO sessionDTO) {
		return ErpOperationResult.failure(READ_ONLY);
	}
}
