package com.digithink.zsretail.erp.navpospages.connector;

import java.util.Collections;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

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
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.spi.ErpConnector;

/**
 * ERP catalogue, step 5: the ERP connector of a Dynamics NAV / Business Central whose web services are the "POS pages"
 * (ItemCategory, PointStockPOS, ItemBarCodePOS). Read only: it never writes to the ERP. Exists only with
 * erp.navpospages.enabled=true, on a head office whose catalogue only comes from the ERP (NavPosPagesStartupCheck);
 * {@code @Primary} so that it wins over NoOpErpConnector (also present, since erp.dynamicsnav.enabled is false) without
 * changing it.
 * <p>
 * In this step every fetch answers an empty list (nothing is handed to the import) and every push or update a failure:
 * the pages are read and translated by {@link com.digithink.zsretail.erp.navpospages.reader.NavPosPagesReader}. Step 6
 * makes the four catalogue fetches (families, sub-families, items, barcodes) return the changes.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
public class NavPosPagesConnector implements ErpConnector {

	static final String READ_ONLY = "The navpospages ERP connector is read only: it sends nothing to the ERP.";

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
