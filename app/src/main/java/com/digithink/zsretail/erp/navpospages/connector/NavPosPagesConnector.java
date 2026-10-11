package com.digithink.zsretail.erp.navpospages.connector;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.erp.api.CatalogueRun;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpConnector;
import com.digithink.zsretail.erp.api.ErpConnectors;
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
import com.digithink.zsretail.erp.api.dto.PullOperationResult;
import com.digithink.zsretail.erp.navpospages.sync.NavPosPagesInvoices;

/**
 * ERP catalogue, step 5: the ERP connector of a Dynamics NAV / Business Central whose web services are the "POS pages"
 * (ItemCategory, PointStockPOS, ItemBarCodePOS). Read only: it never writes to the ERP. Exists only with
 * erp.navpospages.enabled=true (or erp.connector=navpospages), on a head office whose catalogue only comes from the ERP
 * (NavPosPagesStartupCheck). Code cleanup, step 2: the only ErpConnector bean then (the three are mutually exclusive by
 * the connector in effect; no more {@code @Primary} over NoOpErpConnector).
 * <p>
 * Step 6: the four catalogue fetches (families, sub-families, items, barcodes) take only the changes against the head
 * office tables; since code cleanup step 3 the shared catalogue engine runs them through its single job SYNC_CATALOGUE
 * (erp/engine/CatalogueJob), this connector giving it its pages ({@code NavPosPagesCatalogueSource}) and settings. Code
 * cleanup step 5: the four catalogue fetches of the ErpConnector answer none, like the other fetches (their four jobs
 * are refused on the only installation this connector exists on, a head office whose catalogue only comes from the
 * ERP: HeadOfficeErpJobs offers SYNC_CATALOGUE only). Invoices from the ERP, step (a): the
 * franchise invoices read by number ({@link #fetchSupplyInvoices}), not run by any job yet. Every other fetch answers an
 * empty list, every push or update a failure.
 */
@Component
@ConditionalOnErpConnector(ErpConnectors.HAPPYNESS)
public class NavPosPagesConnector implements ErpConnector {

	static final String READ_ONLY = "The navpospages ERP connector is read only: it sends nothing to the ERP.";

	/** Invoices from the ERP: the thin part of this connector (its invoices page and numbers). */
	private final NavPosPagesInvoices invoices;

	/** The summary of the last fetch of this thread, for the communications log (like the Dynamics NAV connector). */
	private static final ThreadLocal<PullOperationResult<?>> LAST_PULL = new ThreadLocal<>();

	public NavPosPagesConnector(NavPosPagesInvoices invoices) {
		this.invoices = invoices;
	}

	/** Code cleanup, step 2: erp.navpospages.invoices.seller-name, read by the head office through the connector. */
	@Override
	public String supplyInvoiceSellerName() {
		return invoices.sellerName();
	}

	/** Invoices from the ERP, step (a): the invoices after the highest number of each year ({@link NavPosPagesInvoices#invoices}). */
	@Override
	public List<ErpSupplyInvoiceDTO> fetchSupplyInvoices(Map<String, String> highestByYear) {
		return changes(() -> invoices.invoices(highestByYear));
	}

	/** 2.2.1: given invoices again, by number ({@link NavPosPagesInvoices#invoicesByNumbers}). */
	@Override
	public List<ErpSupplyInvoiceDTO> fetchSupplyInvoicesByNumbers(List<String> numbers) {
		return changes(() -> invoices.invoicesByNumbers(numbers));
	}

	/** One run: the rows handed to the import; its summary kept for the communications log. */
	private <T> List<T> changes(Supplier<CatalogueRun<T>> run) {
		LAST_PULL.remove();
		CatalogueRun<T> result = run.get();
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

	/** The catalogue is read by the engine's single job, not through these fetches: none, no summary. */
	@Override
	public List<ErpItemFamilyDTO> fetchItemFamilies(ErpSyncFilter filter) {
		LAST_PULL.remove(); // no run: nothing for the communications log
		return Collections.emptyList();
	}

	@Override
	public List<ErpItemSubFamilyDTO> fetchItemSubFamilies(ErpSyncFilter filter) {
		LAST_PULL.remove(); // no run: nothing for the communications log
		return Collections.emptyList();
	}

	@Override
	public List<ErpItemDTO> fetchItems(ErpSyncFilter filter) {
		LAST_PULL.remove(); // no run: nothing for the communications log
		return Collections.emptyList();
	}

	@Override
	public List<ErpItemBarcodeDTO> fetchItemBarcodes(ErpSyncFilter filter) {
		LAST_PULL.remove(); // no run: nothing for the communications log
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
