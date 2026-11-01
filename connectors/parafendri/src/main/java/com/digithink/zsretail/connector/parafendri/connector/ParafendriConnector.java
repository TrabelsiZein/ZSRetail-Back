package com.digithink.zsretail.connector.parafendri.connector;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import com.digithink.zsretail.connector.parafendri.ParafendriConnectorDescriptor;
import com.digithink.zsretail.connector.parafendri.client.ParafendriReadException;
import com.digithink.zsretail.connector.parafendri.client.ParafendriRestClient;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriBarcodeRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriCustomerRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriSalesOrder;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriSalesOrderRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriShipAndInvoiceRow;
import com.digithink.zsretail.connector.parafendri.mapper.ParafendriMapper;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpConnector;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.ErpStoreLocation;
import com.digithink.zsretail.erp.api.ErpSyncWarningException;
import com.digithink.zsretail.erp.api.dto.ErpCustomerDTO;
import com.digithink.zsretail.erp.api.dto.ErpDeletionLogEntryDTO;
import com.digithink.zsretail.erp.api.dto.ErpInvoiceResult;
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
 * the store's point de vente ({@link #fetchItems}). Step 4: the barcodes ({@link #fetchItemBarcodes}). Step 6: a
 * ticket and its lines posted in one call as a sales order ({@link #pushTicket}), looked up first so never twice. Step
 * 7: the invoice of a ticket prepared for one, shipAndInvoice ({@link #requestInvoice}), looked up first too. The
 * locations of the company ({@link #fetchLocations}) and the one customer of the point de vente ({@link
 * #fetchCustomers}), which the till needs as its default customer (PASSENGER_CUSTOMER). Every
 * other fetch answers an empty list, every other push or update a failure, so the store's file keeps
 * erp.sync.enabled=false until the jobs are written.
 */
@Component
@ConditionalOnErpConnector(ParafendriConnectorDescriptor.NAME)
@Log4j2
public class ParafendriConnector implements ErpConnector {

	static final String NOT_YET = "The parafendri ERP connector does not send this to the ERP yet: nothing was sent.";

	/** The read of the last fetch of this thread, for the communications log (as EMTOP's connector). */
	private static final ThreadLocal<PullOperationResult<?>> LAST_PULL = new ThreadLocal<>();

	/**
	 * Step 4: the item numbers the items part of the job's run got from its checkpoint, for the barcodes part that comes
	 * next on the same thread (set by {@link #fetchItems}, taken and cleared by {@link #fetchItemBarcodes}).
	 */
	private static final ThreadLocal<List<String>> ITEMS_OF_THIS_RUN = new ThreadLocal<>();

	private final ParafendriRestClient client;
	private final ErpStoreLocation storeLocation;
	private final ParafendriMapper mapper = new ParafendriMapper();
	/** Step 6: { location, customerNo } once read (customerNo(location)). */
	private volatile String[] customerOfLocation;

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
		ITEMS_OF_THIS_RUN.remove();
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
		// Step 4: on a run from a checkpoint, the items it got have their barcodes read too (fetchItemBarcodes)
		if (since != null) {
			ITEMS_OF_THIS_RUN.set(read.getRows().stream().map(ErpItemDTO::getCode).collect(Collectors.toList()));
		}
		return read.getRows();
	}

	/**
	 * Step 4: the barcodes. barCodes is company-wide (no location): every row on a first run (no checkpoint), else the
	 * rows changed since the job's checkpoint ERP_SYNC_LAST_ITEM_BARCODE (lastModifiedDateTime ge), plus every barcode of
	 * the items the items part of this run got from its own checkpoint ({@link #fetchItems}, the same thread just before):
	 * an item added to the point de vente later, or fixed later, has its barcode read although that row did not change
	 * (the checkpoint moved past it when the import left it out, its item not being here then). A row read twice (in both
	 * reads) is kept once. A barcode of an item this store does not have is kept: the import leaves it out. The
	 * communications log gets the address, the counts and the reasons, not the rows.
	 */
	@Override
	public List<ErpItemBarcodeDTO> fetchItemBarcodes(ErpSyncFilter filter) {
		List<String> runItems = ITEMS_OF_THIS_RUN.get();
		ITEMS_OF_THIS_RUN.remove();
		OffsetDateTime since = filter == null ? null : filter.getUpdatedAfter();
		List<String> asked = since == null || runItems == null ? Collections.emptyList() : runItems;
		String address = client.barcodesUri(since).toString();
		LAST_PULL.set(new PullOperationResult<>(null, address, null));
		List<ParafendriBarcodeRow> fromCheckpoint = client.readBarcodes(since);
		List<ParafendriBarcodeRow> forItems = asked.isEmpty() ? Collections.emptyList()
				: client.readBarcodesOfItems(asked);
		Map<String, ParafendriBarcodeRow> rows = new LinkedHashMap<>();
		int twice = 0;
		for (ParafendriBarcodeRow row : concat(fromCheckpoint, forItems)) {
			String key = row.getId() == null || row.getId().trim().isEmpty() ? "barCode:" + row.getBarCode()
					: row.getId().trim();
			if (rows.putIfAbsent(key, row) != null) {
				twice++;
			}
		}
		ErpRead<ErpItemBarcodeDTO> read = mapper.barcodes(new ArrayList<>(rows.values()));
		Map<String, Object> summary = summary(read);
		summary.remove("rows");
		summary.put("since", since == null ? null : since.toString());
		summary.put("readFromCheckpoint", fromCheckpoint.size());
		summary.put("itemsAsked", asked.size());
		summary.put("readForItems", forItems.size());
		summary.put("sameRowTwice", twice);
		LAST_PULL.set(new PullOperationResult<>(read.getRows(), address, summary));
		if (!read.getLeftOut().isEmpty()) {
			log.warn("ERP connector parafendri: barCodes: {} read, {} kept, left out {}{}", read.getRead(),
					read.getKept(), read.getLeftOut(), read.getNotes().isEmpty() ? "" : ", noted " + read.getNotes());
		}
		return read.getRows();
	}

	/**
	 * The run's items forgotten (the tests, between two cases on one thread). Not part of clearLastPullOperationResult:
	 * the manager clears that after every read, between the items part and the barcodes part.
	 */
	static void clearItemsOfThisRun() {
		ITEMS_OF_THIS_RUN.remove();
	}

	private static <T> List<T> concat(List<T> first, List<T> second) {
		List<T> all = new ArrayList<>(first);
		all.addAll(second);
		return all;
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

	/**
	 * Every location of the company, the page the startup check reads (six rows), always whole: the core keeps no
	 * checkpoint for the locations (ErpLocationDTO carries no time) and a row never changed is dated 0001-01-01, which a
	 * delta would miss. None is marked as the default: the admin picks it on the Emplacements page, as at EMTOP.
	 */
	@Override
	public List<ErpLocationDTO> fetchLocations(ErpSyncFilter filter) {
		String address = client.locationsAddress();
		LAST_PULL.set(new PullOperationResult<>(null, address, null));
		ErpRead<ErpLocationDTO> read = mapper.locations(client.readLocations());
		LAST_PULL.set(new PullOperationResult<>(read.getRows(), address, summary(read)));
		if (!read.getLeftOut().isEmpty()) {
			log.warn("ERP connector parafendri: locations: {} read, {} kept, left out {}", read.getRead(),
					read.getKept(), read.getLeftOut());
		}
		return read.getRows();
	}

	/**
	 * One row only, the account of the store's point de vente (DEFAULT_LOCATION): its customerNo read from stockPoints
	 * (the read pushTicket uses, fresh here), then customers?$filter=number eq 'X'; Business Central refusing that filter,
	 * the page read whole and that number kept. Never another point de vente's account. Always read (one row): from the
	 * job's checkpoint, a point de vente changed for one whose account changed earlier would never be read. No
	 * DEFAULT_LOCATION or no customerNo: no row, one WARN.
	 */
	@Override
	public List<ErpCustomerDTO> fetchCustomers(ErpSyncFilter filter) {
		String location = storeLocation.defaultLocationCode();
		location = location == null ? "" : location.trim();
		if (location.isEmpty()) {
			log.warn("ERP connector parafendri: no customer read: DEFAULT_LOCATION is not configured");
			return Collections.emptyList();
		}
		String customerNo = client.readCustomerNo(location);
		if (customerNo == null) {
			log.warn("ERP connector parafendri: no customer read: no customerNo on the stockPoints rows of location {}",
					location);
			return Collections.emptyList();
		}
		customerOfLocation = new String[] { location, customerNo };
		String address = client.customerUri(customerNo).toString();
		LAST_PULL.set(new PullOperationResult<>(null, address, null));
		List<ParafendriCustomerRow> rows;
		boolean wholePage = false;
		try {
			rows = client.readCustomer(customerNo);
		} catch (ParafendriReadException e) {
			if (!notFilterable(e)) {
				throw e;
			}
			wholePage = true;
			address = client.customersAddress();
			LAST_PULL.set(new PullOperationResult<>(null, address, null));
			rows = client.readCustomers();
		}
		ErpRead<ErpCustomerDTO> read = mapper.customer(customerNo, rows);
		Map<String, Object> summary = summary(read);
		summary.put("location", location);
		summary.put("customerNo", customerNo);
		summary.put("wholePage", wholePage);
		LAST_PULL.set(new PullOperationResult<>(read.getRows(), address, summary));
		if (read.getRows().isEmpty()) {
			log.warn("ERP connector parafendri: customer {} of location {} not found in customers ({} read)", customerNo,
					location, read.getRead());
		} else if (wholePage) {
			log.warn("ERP connector parafendri: customers: number is not filterable in BC, the page was read whole ({}"
					+ " rows) for customer {}", read.getRead(), customerNo);
		}
		return read.getRows();
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
	public ErpOperationResult pushTicket(ErpTicketDTO ticket) {
		String address = client.salesOrdersAddress();
		String ticketNo = ticket == null || ticket.getTicketNumber() == null ? "" : ticket.getTicketNumber().trim();
		String location = storeLocation.defaultLocationCode();
		location = location == null ? "" : location.trim();
		if (ticketNo.isEmpty() || location.isEmpty()) {
			return ErpOperationResult.failure("parafendri: the ticket was not sent: "
					+ (ticketNo.isEmpty() ? "it has no number" : "DEFAULT_LOCATION is not configured"), ticket, null,
					address);
		}
		ParafendriSalesOrder order = null;
		List<String> warnings = new ArrayList<>();
		try {
			// Never twice: a ticket already in Business Central (posted by an earlier run whose answer was lost) is
			// answered with its order, nothing posted. Business Central refusing the filter (ticketNo not a stored,
			// filterable field): posted anyway, the run warned once (TicketExportService)
			ParafendriSalesOrderRow known = null;
			try {
				known = client.readSalesOrdersOfTicket(ticketNo).stream().filter(row -> notBlank(row.getNumber()))
						.findFirst().orElse(null);
			} catch (ParafendriReadException e) {
				if (!notFilterable(e)) {
					throw e;
				}
				warnings.add(NO_DOUBLE_GUARD);
			}
			if (known != null) {
				ErpOperationResult result = ErpOperationResult.success(known.getNumber().trim(), ticket, known, address);
				result.setMessage("parafendri: ticket " + ticketNo + " is already in Business Central as sales order "
						+ known.getNumber().trim() + " (orderStatus " + known.getOrderStatus() + "): not posted again");
				return result;
			}
			String customerNo = customerNo(location);
			if (customerNo == null) {
				return warned(ErpOperationResult.failure("parafendri: the ticket " + ticketNo + " was not sent: no"
						+ " customerNo on the stockPoints rows of location " + location, ticket, null, address), warnings);
			}
			order = mapper.salesOrder(ticket, customerNo, location, warnings);
			ParafendriSalesOrderRow created = client.postSalesOrder(order);
			if (created == null || !notBlank(created.getNumber())) {
				return warned(ErpOperationResult.failure("parafendri: Business Central answered the ticket " + ticketNo
						+ " without a sales order number", order, created, address), warnings);
			}
			ErpOperationResult result = ErpOperationResult.success(created.getNumber().trim(), order, created, address);
			result.setMessage("parafendri: ticket " + ticketNo + " posted as sales order " + created.getNumber().trim()
					+ " (orderStatus " + created.getOrderStatus() + ")");
			return warned(result, warnings);
		} catch (ParafendriReadException e) {
			return warned(ErpOperationResult.failure(e.getMessage(), order != null ? order : ticket, null, address),
					warnings);
		}
	}

	/**
	 * Step 7: the invoice of a ticket already in Business Central (externalReference: its sales order number, the
	 * ticket's ERP number). Looked up first, shipAndInvoice?$filter=orderNo eq 'X' (its key), so never made twice: a row
	 * with its invoice number answers, nothing posted. Else one POST { orderNo }: Business Central ships and invoices the
	 * order and answers the invoice number and the PDF in base64 (decoded here). The page has no field for the fiscal
	 * registration or the invoice name: they are said in the message of the result and in one warning per export run
	 * ({@link #NO_FISCAL_FIELDS}). A failed lookup posts nothing. The communications log gets the answer without the PDF.
	 */
	@Override
	public ErpInvoiceResult requestInvoice(String externalReference, String fiscalRegistration,
			String invoiceCustomerName) {
		String address = client.shipAndInvoiceAddress();
		String orderNo = externalReference == null ? "" : externalReference.trim();
		if (orderNo.isEmpty()) {
			return ErpInvoiceResult.of(ErpOperationResult.failure(
					"parafendri: no invoice asked: the ticket has no sales order number", null, null, address));
		}
		Map<String, String> request = ParafendriRestClient.shipAndInvoiceRequest(orderNo);
		try {
			ParafendriShipAndInvoiceRow known = client.readShipAndInvoice(orderNo).stream()
					.filter(row -> notBlank(row.getInvoiceNo())).findFirst().orElse(null);
			if (known != null) {
				return invoiced(known, request, address, "parafendri: sales order " + orderNo
						+ " is already invoiced in Business Central as " + known.getInvoiceNo().trim()
						+ ": not asked again");
			}
			ParafendriShipAndInvoiceRow made = client.postShipAndInvoice(orderNo);
			if (made == null || !notBlank(made.getInvoiceNo())) {
				return ErpInvoiceResult.of(ErpOperationResult.failure("parafendri: Business Central answered the invoice"
						+ " of sales order " + orderNo + " without an invoice number", request,
						made == null ? null : withoutPdf(made), address));
			}
			ErpInvoiceResult result = invoiced(made, request, address, "parafendri: sales order " + orderNo
					+ " shipped and invoiced as " + made.getInvoiceNo().trim()
					+ notSent(fiscalRegistration, invoiceCustomerName));
			if (notBlank(fiscalRegistration) || notBlank(invoiceCustomerName)) {
				result.addWarning(NO_FISCAL_FIELDS);
			}
			return result;
		} catch (ParafendriReadException e) {
			return ErpInvoiceResult.of(ErpOperationResult.failure(e.getMessage(), request, null, address));
		}
	}

	/** Step 7: said once per export run when an invoice was asked with a fiscal registration or name not sendable. */
	public static final String NO_FISCAL_FIELDS = "shipAndInvoice has no fiscal fields — ask the BC contact";

	/** Step 7: a row with its invoice number as a success, the PDF decoded (none, or not readable: said, no PDF). */
	private static ErpInvoiceResult invoiced(ParafendriShipAndInvoiceRow row, Object request, String address,
			String message) {
		byte[] pdf = null;
		String unreadable = null;
		if (notBlank(row.getInvoicePdfBase64())) {
			try {
				pdf = Base64.getMimeDecoder().decode(row.getInvoicePdfBase64().trim());
			} catch (IllegalArgumentException e) {
				unreadable = e.getMessage();
			}
		}
		ErpInvoiceResult result = ErpInvoiceResult.invoiced(row.getInvoiceNo().trim(), pdf, request, withoutPdf(row),
				address);
		result.setMessage(message + (pdf != null && pdf.length > 0 ? ""
				: unreadable != null ? "; its PDF could not be read (" + unreadable + ")" : "; no PDF in the answer"));
		return result;
	}

	/** Step 7: the row for the communications log, the PDF's base64 replaced by its length. */
	static Map<String, Object> withoutPdf(ParafendriShipAndInvoiceRow row) {
		Map<String, Object> logged = new LinkedHashMap<>();
		logged.put("orderNo", row.getOrderNo());
		logged.put("invoiceNo", row.getInvoiceNo());
		logged.put("lastModifiedDateTime", row.getLastModifiedDateTime());
		logged.put("invoicePdfBase64", notBlank(row.getInvoicePdfBase64())
				? row.getInvoicePdfBase64().trim().length() + " characters, not logged"
				: null);
		return logged;
	}

	/** Step 7: the fiscal registration and invoice name that shipAndInvoice cannot take, for the message. */
	private static String notSent(String fiscalRegistration, String invoiceCustomerName) {
		if (!notBlank(fiscalRegistration) && !notBlank(invoiceCustomerName)) {
			return "";
		}
		return "; not sent (shipAndInvoice has no field for them): fiscal registration "
				+ (notBlank(fiscalRegistration) ? fiscalRegistration.trim() : "-") + ", invoice name "
				+ (notBlank(invoiceCustomerName) ? invoiceCustomerName.trim() : "-");
	}

	/** Step 6: said once per export run when Business Central does not let the lookup filter on ticketNo. */
	public static final String NO_DOUBLE_GUARD = "no-double guard unavailable: ticketNo is not filterable in BC — ask the"
			+ " BC contact to make ticketNo a stored, filterable field";

	/**
	 * Business Central's answer to a filter on a field it cannot filter: HTTP 400, "Field 'ticketNo' is not filterable"
	 * in its status text (as seen live) or in its body (BadRequest_NotSupported).
	 */
	public static boolean notFilterable(ParafendriReadException e) {
		if (!Integer.valueOf(400).equals(e.getStatus())) {
			return false;
		}
		String body = e.getCause() instanceof HttpStatusCodeException
				? ((HttpStatusCodeException) e.getCause()).getResponseBodyAsString()
				: "";
		return String.valueOf(e.getMessage()).contains("is not filterable")
				|| String.valueOf(body).contains("is not filterable");
	}

	private static ErpOperationResult warned(ErpOperationResult result, List<String> warnings) {
		warnings.forEach(result::addWarning);
		return result;
	}

	/**
	 * Step 6: the point de vente's customer in Business Central (customerNo of its stockPoints rows), read once per
	 * location and kept; null when there is none (not kept: read again next time).
	 */
	private String customerNo(String location) {
		String[] known = customerOfLocation;
		if (known != null && known[0].equals(location)) {
			return known[1];
		}
		String customerNo = client.readCustomerNo(location);
		if (customerNo != null) {
			customerOfLocation = new String[] { location, customerNo };
		}
		return customerNo;
	}

	private static boolean notBlank(String value) {
		return value != null && !value.trim().isEmpty();
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
