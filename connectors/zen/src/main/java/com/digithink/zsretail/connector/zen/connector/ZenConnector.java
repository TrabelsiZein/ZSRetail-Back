package com.digithink.zsretail.connector.zen.connector;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.zen.ZenConnectorDescriptor;
import com.digithink.zsretail.connector.zen.sync.ZenInvoices;
import com.digithink.zsretail.connector.zen.sync.ZenStoreOrders;
import com.digithink.zsretail.erp.api.CatalogueRun;
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
 * Item variants (ZEN), step 8: the ERP connector of ZEN's NAV. Read only: it never writes to the ERP. Exists only with
 * erp.connector=zen, on a head office whose catalogue only comes from the ERP (ZenStartupCheck); then the only
 * ErpConnector bean. The catalogue is read by the engine's single job SYNC_CATALOGUE through
 * {@code ZenCatalogueSource}: the catalogue fetches of this interface answer none, as every other fetch but the
 * invoices; every push or update answers a failure (3.1.1: but the stores' orders, {@link ZenStoreOrders}). Step 9: the supply invoices ({@link ZenInvoices}, run by the head
 * office's job IMPORT_SUPPLY_INVOICES when headoffice.supply.source=ERP), each run's summary kept for the
 * communications log as Happyness's.
 */
@Component
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
public class ZenConnector implements ErpConnector {

	static final String READ_ONLY = "The zen ERP connector is read only: it sends nothing to the ERP.";

	/** Step 9: the supply invoices of ZEN's NAV (salesInvoices of its franchise API). */
	private final ZenInvoices invoices;

	/** The summary of the last fetch of this thread, for the communications log (as the other connectors). */
	private static final ThreadLocal<PullOperationResult<?>> LAST_PULL = new ThreadLocal<>();

	/** 3.1.1: the stores' orders (variant stock, sales orders); null in the tests of the invoices. */
	private final ZenStoreOrders orders;

	@Autowired
	public ZenConnector(ZenInvoices invoices, ZenStoreOrders orders) {
		this.invoices = invoices;
		this.orders = orders;
	}

	/** Without the stores' orders: used by the tests of the invoices. */
	public ZenConnector(ZenInvoices invoices) {
		this(invoices, null);
	}

	/** 3.1.1: NAV's stock of the variants of an item at a location ({@link ZenStoreOrders#variantStock}). */
	@Override
	public List<ErpVariantStockDTO> fetchVariantStock(String itemNo, String locationCode) {
		if (orders == null) {
			return ErpConnector.super.fetchVariantStock(itemNo, locationCode);
		}
		return orders.variantStock(itemNo, locationCode);
	}

	/** 3.1.1: a store's order as NAV's sales order, looked up first ({@link ZenStoreOrders#post}). */
	@Override
	public ErpOperationResult postSalesOrder(ErpSalesOrderDTO order) {
		if (orders == null) {
			return ErpConnector.super.postSalesOrder(order);
		}
		return orders.post(order);
	}

	/** Step 9: erp.zen.invoices.seller-name, read by the head office through the connector. */
	@Override
	public String supplyInvoiceSellerName() {
		return invoices.sellerName();
	}

	/** Step 9: the stores' invoices after the highest number of each year ({@link ZenInvoices#invoices}). */
	@Override
	public List<ErpSupplyInvoiceDTO> fetchSupplyInvoices(Map<String, String> highestByYear) {
		return changes(() -> invoices.invoices(highestByYear));
	}

	/** Step 9: given invoices again, by number ({@link ZenInvoices#invoicesByNumbers}). */
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
