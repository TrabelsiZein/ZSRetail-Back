package com.digithink.zsretail.erp.navpospages.connector;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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
import com.digithink.zsretail.erp.dto.ErpSupplyInvoiceDTO;
import com.digithink.zsretail.erp.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.dto.ErpTicketDTO;
import com.digithink.zsretail.erp.dto.ErpTicketLineDTO;
import com.digithink.zsretail.erp.dto.PullOperationResult;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.sync.NavPosPagesSync;
import com.digithink.zsretail.erp.navpospages.sync.NavPosRun;
import com.digithink.zsretail.erp.spi.ErpConnector;

/**
 * ERP catalogue, step 5: the ERP connector of a Dynamics NAV / Business Central whose web services are the "POS pages"
 * (ItemCategory, PointStockPOS, ItemBarCodePOS). Read only: it never writes to the ERP. Exists only with
 * erp.navpospages.enabled=true, on a head office whose catalogue only comes from the ERP (NavPosPagesStartupCheck);
 * {@code @Primary} so that it wins over NoOpErpConnector (also present, since erp.dynamicsnav.enabled is false) without
 * changing it.
 * <p>
 * Step 6: the four catalogue fetches (families, sub-families, items, barcodes) take only the changes against the head
 * office tables ({@link NavPosPagesSync}); families and sub-families hand them to the import, the items and barcode runs
 * apply them themselves in packets and hand nothing to the job. The summary of each run is the response written to the
 * communications log ({@link #getLastPullOperationResult()}), never the list. Invoices from the ERP, step (a): the
 * franchise invoices read by number ({@link #fetchSupplyInvoices}), not run by any job yet. Every other fetch answers an
 * empty list, every push or update a failure.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
public class NavPosPagesConnector implements ErpConnector {

	static final String READ_ONLY = "The navpospages ERP connector is read only: it sends nothing to the ERP.";

	/** The summary of the last fetch of this thread, for the communications log (like the Dynamics NAV connector). */
	private static final ThreadLocal<PullOperationResult<?>> LAST_PULL = new ThreadLocal<>();

	private final NavPosPagesSync sync;

	public NavPosPagesConnector(NavPosPagesSync sync) {
		this.sync = sync;
	}

	@Override
	public List<ErpItemFamilyDTO> fetchItemFamilies(ErpSyncFilter filter) {
		return changes(sync::families);
	}

	@Override
	public List<ErpItemSubFamilyDTO> fetchItemSubFamilies(ErpSyncFilter filter) {
		return changes(sync::subFamilies);
	}

	/** The items run applies its changes itself, in packets ({@link NavPosPagesSync#items}): nothing left for the job. */
	@Override
	public List<ErpItemDTO> fetchItems(ErpSyncFilter filter) {
		changes(sync::items);
		return Collections.emptyList();
	}

	/** The barcode run applies its changes itself, until it has caught up ({@link NavPosPagesSync#barcodes}). */
	@Override
	public List<ErpItemBarcodeDTO> fetchItemBarcodes(ErpSyncFilter filter) {
		changes(sync::barcodes);
		return Collections.emptyList();
	}

	/** Invoices from the ERP, step (a): the invoices after the highest number of each year ({@link NavPosPagesSync#invoices}). */
	@Override
	public List<ErpSupplyInvoiceDTO> fetchSupplyInvoices(Map<String, String> highestByYear) {
		return changes(() -> sync.invoices(highestByYear));
	}

	/** 2.2.1: given invoices again, by number ({@link NavPosPagesSync#invoicesByNumbers}). */
	@Override
	public List<ErpSupplyInvoiceDTO> fetchSupplyInvoicesByNumbers(List<String> numbers) {
		return changes(() -> sync.invoicesByNumbers(numbers));
	}

	/** One run: the rows handed to the import; its summary kept for the communications log. */
	private <T> List<T> changes(Supplier<NavPosRun<T>> run) {
		LAST_PULL.remove();
		NavPosRun<T> result = run.get();
		LAST_PULL.set(new PullOperationResult<>(result.getHanded(), result.getUrl(), result.getSummary()));
		return result.getHanded();
	}

	@Override
	public PullOperationResult<?> getLastPullOperationResult() {
		return LAST_PULL.get();
	}

	@Override
	public void clearLastPullOperationResult() {
		LAST_PULL.remove();
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
