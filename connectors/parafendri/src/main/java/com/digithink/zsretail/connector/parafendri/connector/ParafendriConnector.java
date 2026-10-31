package com.digithink.zsretail.connector.parafendri.connector;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.parafendri.ParafendriConnectorDescriptor;
import com.digithink.zsretail.connector.parafendri.client.ParafendriRestClient;
import com.digithink.zsretail.connector.parafendri.mapper.ParafendriMapper;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpConnector;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.ErpStoreLocation;
import com.digithink.zsretail.erp.api.ErpSyncWarningException;
import com.digithink.zsretail.erp.api.dto.ErpCustomerDTO;
import com.digithink.zsretail.erp.api.dto.ErpDeletionLogEntryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSectionDTO;
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
import com.digithink.zsretail.erp.api.dto.PullOperationResult;

import lombok.extern.log4j.Log4j2;

/**
 * ParaFendri, step 0: the ERP connector of a store on ParaFendri's Business Central, the shape of EMTOP's (a store
 * connector, not a head office's catalogue source). Exists only with erp.connector=parafendri: the only ErpConnector
 * bean then. Step 2: the four levels of the tree (catégories, familles, sous-familles, sections) for the job
 * IMPORT_CATALOGUE, each fetch reading the page itemCategories (121 rows: no cache) and keeping its level
 * ({@link ParafendriMapper}); the read goes to the communications log as EMTOP's (the address, then the rows read, kept
 * and left out by reason, and the rows), with one WARN line when a row was left out. Step 3: the items, stockPoints of
 * the store's point de vente ({@link #fetchItems}). Every other fetch answers an empty list, every push or update a
 * failure, so the store's file keeps erp.sync.enabled=false until the jobs are written.
 */
@Component
@ConditionalOnErpConnector(ParafendriConnectorDescriptor.NAME)
@Log4j2
public class ParafendriConnector implements ErpConnector {

	static final String NOT_YET = "The parafendri ERP connector does not send this to the ERP yet: nothing was sent.";

	/** The read of the last fetch of this thread, for the communications log (as EMTOP's connector). */
	private static final ThreadLocal<PullOperationResult<?>> LAST_PULL = new ThreadLocal<>();

	private final ParafendriRestClient client;
	private final ErpStoreLocation storeLocation;
	private final ParafendriMapper mapper = new ParafendriMapper();

	public ParafendriConnector(ParafendriRestClient client, ErpStoreLocation storeLocation) {
		this.client = client;
		this.storeLocation = storeLocation;
	}

	/**
	 * Step 3: the items of the store's point de vente (General Setup DEFAULT_LOCATION, none: a warning, as EMTOP's), the
	 * page stockPoints from the job's checkpoint (filter.updatedAfter, ERP_SYNC_LAST_ITEM: the newest lastModifiedDateTime
	 * of the items it got, as EMTOP's Modified_At), translated by {@link ParafendriMapper#items}. The tree is read again
	 * first (121 rows) so that a code it does not keep is noted and not linked. The communications log gets the address,
	 * the counts and the reasons, not the rows (about 13,000 on a first read).
	 */
	@Override
	public List<ErpItemDTO> fetchItems(ErpSyncFilter filter) {
		String location = storeLocation.defaultLocationCode();
		if (location == null || location.trim().isEmpty()) {
			throw new ErpSyncWarningException("DEFAULT_LOCATION is not configured");
		}
		OffsetDateTime since = filter == null ? null : filter.getUpdatedAfter();
		String address = client.stockPointsUri(location, since).toString();
		LAST_PULL.set(new PullOperationResult<>(null, address, null));
		ErpCategories tree = mapper.categories(client.readItemCategories());
		ErpRead<ErpItemDTO> read = mapper.items(location, client.readStockPoints(location, since), tree);
		Map<String, Object> summary = summary(read);
		summary.remove("rows");
		summary.put("location", location.trim());
		summary.put("since", since == null ? null : since.toString());
		LAST_PULL.set(new PullOperationResult<>(read.getRows(), address, summary));
		if (!read.getLeftOut().isEmpty()) {
			log.warn("ERP connector parafendri: items of stockPoints ({}): {} read, {} kept, left out {}{}", location.trim(),
					read.getRead(), read.getKept(), read.getLeftOut(),
					read.getNotes().isEmpty() ? "" : ", noted " + read.getNotes());
		}
		return read.getRows();
	}

	@Override
	public List<ErpItemCategoryDTO> fetchItemCategories(ErpSyncFilter filter) {
		return level("catégories", ErpCategories::getCategories);
	}

	@Override
	public List<ErpItemFamilyDTO> fetchItemFamilies(ErpSyncFilter filter) {
		return level("familles", ErpCategories::getFamilies);
	}

	@Override
	public List<ErpItemSubFamilyDTO> fetchItemSubFamilies(ErpSyncFilter filter) {
		return level("sous-familles", ErpCategories::getSubFamilies);
	}

	@Override
	public List<ErpItemSectionDTO> fetchItemSections(ErpSyncFilter filter) {
		return level("sections", ErpCategories::getSections);
	}

	/**
	 * One level of the tree: the page read whole (the filter has no use: no checkpoint), the level kept; the address is
	 * handed to the log before the read, so a failed read names it too.
	 */
	private <T> List<T> level(String label, Function<ErpCategories, ErpRead<T>> level) {
		String address = client.itemCategoriesAddress();
		LAST_PULL.set(new PullOperationResult<>(null, address, null));
		ErpRead<T> read = level.apply(mapper.categories(client.readItemCategories()));
		LAST_PULL.set(new PullOperationResult<>(read.getRows(), address, summary(read)));
		if (!read.getLeftOut().isEmpty()) {
			log.warn("ERP connector parafendri: {} of itemCategories: {} read, {} kept, left out {}{}", label,
					read.getRead(), read.getKept(), read.getLeftOut(),
					read.getNotes().isEmpty() ? "" : ", noted " + read.getNotes());
		}
		return read.getRows();
	}

	/** What the communications log shows of one read: the counts, the reasons, the rows kept. */
	static Map<String, Object> summary(ErpRead<?> read) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("read", read.getRead());
		summary.put("kept", read.getKept());
		summary.put("leftOut", read.getLeftOut());
		summary.put("notes", read.getNotes());
		summary.put("rows", read.getRows());
		return summary;
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
