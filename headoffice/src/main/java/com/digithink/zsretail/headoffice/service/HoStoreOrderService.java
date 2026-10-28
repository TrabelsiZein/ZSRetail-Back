package com.digithink.zsretail.headoffice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.link.dto.SalesCopyResultDTO;
import com.digithink.zsretail.core.link.dto.StoreOrderDTO;
import com.digithink.zsretail.core.link.dto.StoreOrderStatusCopyDTO;
import com.digithink.zsretail.core.link.dto.VariantStockDTO;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.service.ItemBarcodeService;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.erp.api.ErpConnector;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderDTO;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderResult;
import com.digithink.zsretail.erp.api.dto.ErpVariantStockDTO;
import com.digithink.zsretail.headoffice.dto.HoStoreOrderDTO;
import com.digithink.zsretail.headoffice.enumeration.HoStoreOrderStatus;
import com.digithink.zsretail.headoffice.enumeration.PostedMode;
import com.digithink.zsretail.headoffice.model.HoStoreOrder;
import com.digithink.zsretail.headoffice.model.HoStoreOrderLine;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoStoreOrderRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.1.1: the stores' orders at the head office ("Commandes centrale"), on a head office with headoffice.storeorders.post
 * MANUAL or ERP. The reception (POST /ho/orders, the store authenticated by its key): an order is taken once per store
 * and number, the same number again answers the order already here; a store without an ERP customer number, an empty
 * order or another location than the store's is refused ({accepted: false} with the reason: the store shows it, nothing
 * saved). The live stock of a store's variants asks the connector with its code and ERP customer number (refused, as
 * the reception, without the number; 503 without the connector). The admin actions: "mark posted"
 * (MANUAL mode), "reject" (both modes, never posted afterwards); "retry now" is the job's ({@link HoStoreOrderPosting}).
 * <p>
 * What became of an order goes down to its store only (domain SUPPLY, record ORD:&lt;number&gt;, kind STORE_ORDER), recorded
 * in the transaction of each change to POSTED, ERROR, REJECTED or DELIVERED; REJECTED goes down as ERROR with its message
 * (the store has no such status). DELIVERED: the invoice import found the order's ERP number in an ERP invoice
 * ({@link #deliveredBy}). See docs/modules/store-orders.md.
 */
@Service
@ConditionalOnInstallation(Installation.HEAD_OFFICE_STORE_ORDERS)
public class HoStoreOrderService implements DownDomainProvider, StoreOrderDeliveries {

	static final ObjectMapper COPY_MAPPER = new ObjectMapper();
	static final int TIMEOUT_SECONDS = 15;
	static final int DEFAULT_SIZE = 20;
	static final int MAX_SIZE = 200;
	static final String HEAD_OFFICE = "Head office";

	/** The statuses that go down to the store: what the head office did with the order. */
	static final List<HoStoreOrderStatus> COPIED_STATUSES = Arrays.asList(HoStoreOrderStatus.POSTED,
			HoStoreOrderStatus.ERROR, HoStoreOrderStatus.REJECTED, HoStoreOrderStatus.DELIVERED);

	/** The sorts of the list, by the page's name. */
	static final Map<String, String> SORTS = new LinkedHashMap<>();
	static {
		SORTS.put("receivedAt", "receivedAt");
		SORTS.put("number", "number");
		SORTS.put("status", "status");
		SORTS.put("postedAt", "postedAt");
		SORTS.put("store", "storeId");
	}

	private final HoStoreOrderRepository orders;
	private final StoreRepository stores;
	/** The ERP's stock of the variants of an item at a location (the connector in effect). */
	private final VariantStockReader stockReader;
	private final Supplier<CopiesDownFeed> feed;
	private final TransactionOperations transactions;
	private final Supplier<LocalDateTime> clock;
	/** headoffice.storeorders.post=ERP: the job posts; "mark posted" is refused. */
	private final boolean byErp;

	/** The connector's read of the variants' stock, as this service calls it. */
	public interface VariantStockReader {
		List<ErpVariantStockDTO> read(String itemNo, String locationCode, String customerNo);
	}

	@Autowired
	public HoStoreOrderService(HoStoreOrderRepository orders, StoreRepository stores, ErpConnector connector,
			ObjectProvider<CopiesDownFeed> feed, PlatformTransactionManager transactionManager,
			ApplicationModeService mode) {
		this(orders, stores, connector::fetchVariantStock, feed::getIfAvailable, timed(transactionManager),
				LocalDateTime::now, mode.isStoreOrdersByErp());
	}

	/** With given parts: used by the tests (feed null: nothing recorded). */
	public HoStoreOrderService(HoStoreOrderRepository orders, StoreRepository stores, VariantStockReader stockReader,
			Supplier<CopiesDownFeed> feed, TransactionOperations transactions, Supplier<LocalDateTime> clock,
			boolean byErp) {
		this.orders = orders;
		this.stores = stores;
		this.stockReader = stockReader;
		this.feed = feed;
		this.transactions = transactions;
		this.clock = clock;
		this.byErp = byErp;
	}

	private static TransactionTemplate timed(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	/** True in ERP mode (the job posts), false in MANUAL mode. */
	public boolean isByErp() {
		return byErp;
	}

	/** The store cannot be answered as it is (no ERP customer number): 409 for the caller, with the reason. */
	public static class StoreRefusedException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public StoreRefusedException(String message) {
			super(message);
		}
	}

	/** The ERP does not answer what was asked, or answers an error: 503 for the caller. */
	public static class ErpUnavailableException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public ErpUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	// ─── Reception (POST /ho/orders) ─────────────────────────────

	/**
	 * Takes a store's order once: accepted (the order saved SENT, or already here for this store and number), or refused
	 * with the reason, nothing saved. Never throws for the order's content.
	 */
	public SalesCopyResultDTO receive(Store store, StoreOrderDTO order) {
		String number = order == null || order.getNumber() == null ? "" : order.getNumber().trim();
		if (number.isEmpty() || number.length() > HoStoreOrder.NUMBER_LENGTH) {
			return SalesCopyResultDTO.rejected(number.isEmpty() ? null : number, "order without a valid number");
		}
		Optional<HoStoreOrder> known = orders.findByStoreIdAndNumber(store.getId(), number);
		if (known.isPresent()) {
			return SalesCopyResultDTO.accepted(number); // sent again after a lost answer: the order already here
		}
		String refusal = refusalOf(store, order);
		if (refusal != null) {
			return SalesCopyResultDTO.rejected(number, refusal);
		}
		try {
			transactions.executeWithoutResult(status -> orders.save(newOrder(store, number, order)));
		} catch (DataIntegrityViolationException e) {
			// The same order received twice at once: the other request saved it
			if (!orders.findByStoreIdAndNumber(store.getId(), number).isPresent()) {
				throw e;
			}
		}
		return SalesCopyResultDTO.accepted(number);
	}

	private static boolean hasCustomerNo(Store store) {
		return store.getErpCustomerNo() != null && !store.getErpCustomerNo().trim().isEmpty();
	}

	/** The refusal of a store without an ERP customer number: its orders and its variants' stock. */
	private static String noCustomerNo(Store store) {
		return "Store " + store.getCode() + " has no ERP customer number at the head office: the head office must set it"
				+ " (Stores page) before the ERP can take its orders";
	}

	/** Why the head office will never take this order; null when it takes it. */
	static String refusalOf(Store store, StoreOrderDTO order) {
		if (!hasCustomerNo(store)) {
			return noCustomerNo(store);
		}
		String location = order.getLocationCode() == null ? "" : order.getLocationCode().trim();
		if (!location.isEmpty() && !location.equalsIgnoreCase(store.getCode())) {
			return "location " + location + " is not the store's (" + store.getCode() + ")";
		}
		if (order.getLines() == null || order.getLines().isEmpty()) {
			return "order without a line";
		}
		for (StoreOrderDTO.Line line : order.getLines()) {
			if (line == null || line.getItemCode() == null || line.getItemCode().trim().isEmpty()) {
				return "a line without an item code";
			}
			if (line.getQuantity() == null || line.getQuantity().signum() <= 0
					|| Quantities.decimals(line.getQuantity()) > Quantities.SCALE) {
				return "item " + line.getItemCode().trim() + ": the quantity must be above 0, at most "
						+ Quantities.SCALE + " decimals";
			}
		}
		return null;
	}

	private HoStoreOrder newOrder(Store store, String number, StoreOrderDTO dto) {
		HoStoreOrder order = new HoStoreOrder();
		order.setStoreId(store.getId());
		order.setNumber(number);
		order.setStatus(HoStoreOrderStatus.SENT);
		order.setLocationCode(cut(blankToNull(dto.getLocationCode()), 50));
		order.setSentAt(timeOf(dto.getSentAt()));
		order.setReceivedAt(clock.get());
		order.setNote(cut(blankToNull(dto.getNote()), HoStoreOrder.NOTE_LENGTH));
		order.setCreatedBy(store.getCode());
		BigDecimal total = BigDecimal.ZERO;
		for (StoreOrderDTO.Line dtoLine : dto.getLines()) {
			HoStoreOrderLine line = new HoStoreOrderLine();
			line.setOrder(order);
			line.setItemCode(cut(dtoLine.getItemCode().trim(), 100));
			line.setNavItemNo(blankToNull(dtoLine.getNavItemNo()));
			line.setVariantCode(blankToNull(dtoLine.getVariantCode()));
			line.setDescription(cut(blankToNull(dtoLine.getDescription()), 255));
			line.setVariantDescription(cut(blankToNull(dtoLine.getVariantDescription()), 255));
			line.setQuantity(Quantities.normalize(dtoLine.getQuantity()));
			line.setCreatedBy(store.getCode());
			order.getLines().add(line);
			total = total.add(line.getQuantity());
		}
		order.setLineCount(order.getLines().size());
		order.setTotalQty(Quantities.normalize(total));
		return order;
	}

	/** yyyy-MM-ddTHH:mm:ss as the store sends it; null when absent or unreadable (the store's clock, information only). */
	static LocalDateTime timeOf(String text) {
		if (text == null || text.trim().isEmpty()) {
			return null;
		}
		try {
			return LocalDateTime.parse(text.trim());
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	// ─── Live variant stock (GET /ho/orders/variant-stock) ───────

	/**
	 * The ERP's stock of every variant of an ERP item number for the store (its code and its ERP customer number), asked
	 * now; the details and size order as the connector gave them, an unknown quantity kept null. 400 without a number;
	 * {@link StoreRefusedException} for a store without an ERP customer number (the reception's refusal);
	 * {@link ErpUnavailableException} when the connector cannot read it or the ERP fails (no partial answer).
	 */
	public List<VariantStockDTO> variantStock(Store store, String navItemNo) {
		if (navItemNo == null || navItemNo.trim().isEmpty()) {
			throw new IllegalArgumentException("navItemNo is required");
		}
		if (!hasCustomerNo(store)) {
			throw new StoreRefusedException(noCustomerNo(store));
		}
		List<ErpVariantStockDTO> rows;
		try {
			rows = stockReader.read(navItemNo.trim(), store.getCode(), store.getErpCustomerNo().trim());
		} catch (RuntimeException e) {
			throw new ErpUnavailableException("The ERP's stock of " + navItemNo.trim() + " at " + store.getCode()
					+ " could not be read: " + e.getMessage(), e);
		}
		List<VariantStockDTO> answer = new ArrayList<>();
		for (ErpVariantStockDTO row : rows == null ? Collections.<ErpVariantStockDTO>emptyList() : rows) {
			if (row != null) {
				answer.add(new VariantStockDTO(row.getItemNo(), row.getVariantCode(),
						row.getQuantity() == null ? null : Quantities.normalize(row.getQuantity()), row.getDetails(),
						row.getSizeOrder()));
			}
		}
		return answer;
	}

	// ─── The ERP's order of a store's order ──────────────────────

	/**
	 * The sales order of the ERP for this order, built inside a transaction (its lines are a lazy collection): the
	 * store's location and ERP customer number, the order number as the external document number, the store's send
	 * date (else the reception's), each line's ERP item number and variant. IllegalStateException when the store has
	 * no ERP customer number any more.
	 */
	static ErpSalesOrderDTO salesOrderOf(HoStoreOrder order, Store store) {
		if (store == null || store.getErpCustomerNo() == null || store.getErpCustomerNo().trim().isEmpty()) {
			throw new IllegalStateException("Store " + (store == null ? order.getStoreId() : store.getCode())
					+ " has no ERP customer number at the head office: set it on the Stores page");
		}
		ErpSalesOrderDTO sales = new ErpSalesOrderDTO();
		sales.setLocationCode(store.getCode());
		sales.setCustomerNo(store.getErpCustomerNo().trim());
		sales.setExternalDocumentNo(order.getNumber());
		LocalDateTime day = order.getSentAt() != null ? order.getSentAt() : order.getReceivedAt();
		sales.setOrderDate(day == null ? LocalDate.now() : day.toLocalDate());
		for (HoStoreOrderLine line : order.getLines()) {
			sales.getLines().add(new ErpSalesOrderDTO.Line(itemNoOf(line),
					line.getVariantCode() == null ? "" : line.getVariantCode(), line.getQuantity()));
		}
		return sales;
	}

	/** The line's ERP item number; without one, its item code up to the variant's ~ (navItemNo~variantCode). */
	static String itemNoOf(HoStoreOrderLine line) {
		if (line.getNavItemNo() != null && !line.getNavItemNo().trim().isEmpty()) {
			return line.getNavItemNo().trim();
		}
		String code = line.getItemCode().trim();
		int cut = code.indexOf('~');
		return cut < 0 ? code : code.substring(0, cut);
	}

	/** Builds the ERP's order of an order waiting (SENT or ERROR), in a read transaction; empty when not waiting. */
	public Optional<ErpSalesOrderDTO> salesOrderToPost(Long id) {
		return Optional.ofNullable(transactions.execute(status -> orders.findById(id)
				.filter(order -> order.getStatus() == HoStoreOrderStatus.SENT || order.getStatus() == HoStoreOrderStatus.ERROR)
				.map(order -> salesOrderOf(order, stores.findById(order.getStoreId()).orElse(null))).orElse(null)));
	}

	/**
	 * The outcome of one posting by the job or "retry now", in its own transaction, the row locked: success, POSTED with
	 * the ERP's number (also when the order was rejected meanwhile: the ERP has it); failure, ERROR with the reason
	 * (the order left as it is when no longer waiting). Recorded for the store when what it sees changed. Answers the
	 * order.
	 */
	public Optional<HoStoreOrderDTO> recordPosting(Long id, String navOrderNo, String failure) {
		return recordPosting(id, navOrderNo, null, failure);
	}

	/**
	 * The same with the ERP's answer (null: none): on success its message on the order and, per line (matched by ERP item
	 * number and variant), the quantity it accepted and its message, kept and sent down with the status.
	 */
	public Optional<HoStoreOrderDTO> recordPosting(Long id, String navOrderNo, ErpSalesOrderResult answer,
			String failure) {
		return Optional.ofNullable(transactions.execute(status -> orders.findForUpdate(id).map(order -> {
			LocalDateTime at = clock.get();
			order.setPostAttempts(order.getPostAttempts() + 1);
			order.setLastAttemptAt(at);
			String before = seenByStore(order);
			if (failure == null && navOrderNo != null && !navOrderNo.trim().isEmpty()) {
				order.setStatus(HoStoreOrderStatus.POSTED);
				order.setNavOrderNo(cut(navOrderNo.trim(), 50));
				order.setPostedAt(at);
				order.setPostedMode(PostedMode.ERP);
				order.setErrorMessage(null);
				if (answer != null) {
					keepAnswer(order, answer);
				}
			} else if (order.getStatus() == HoStoreOrderStatus.SENT || order.getStatus() == HoStoreOrderStatus.ERROR) {
				order.setStatus(HoStoreOrderStatus.ERROR);
				order.setErrorMessage(cut(failure == null ? "the ERP gave no order number" : failure,
						HoStoreOrder.ERROR_LENGTH));
			}
			order.setUpdatedBy(HEAD_OFFICE);
			orders.save(order);
			if (!Objects.equals(before, seenByStore(order))) {
				recordDown(order);
			}
			return viewOf(order);
		}).orElse(null)));
	}

	/**
	 * The ERP's answer kept on the order: its message, and per line (by ERP item number and variant) the quantity accepted
	 * (added up when the ERP gives the line twice, to 3 decimals) and the messages (joined); a line the ERP did not answer
	 * keeps none.
	 */
	static void keepAnswer(HoStoreOrder order, ErpSalesOrderResult answer) {
		order.setNavMessage(cut(blankToNull(answer.getOrderMessage()), HoStoreOrder.NAV_MESSAGE_LENGTH));
		Map<String, BigDecimal> accepted = new HashMap<>();
		Map<String, String> messages = new HashMap<>();
		for (ErpSalesOrderResult.Line line : answer.getLines()) {
			String key = lineKey(line.getItemNo(), line.getVariantCode());
			if (line.getAcceptedQty() != null) {
				accepted.merge(key, line.getAcceptedQty(), BigDecimal::add);
			}
			String message = blankToNull(line.getMessage());
			if (message != null) {
				messages.merge(key, message, (first, second) -> first + " / " + second);
			}
		}
		for (HoStoreOrderLine line : order.getLines()) {
			String key = lineKey(itemNoOf(line), line.getVariantCode());
			BigDecimal quantity = accepted.get(key);
			line.setNavAcceptedQty(quantity == null ? null : quantity.setScale(Quantities.SCALE, RoundingMode.HALF_UP));
			line.setNavLineMessage(cut(messages.get(key), HoStoreOrder.NAV_MESSAGE_LENGTH));
		}
	}

	private static String lineKey(String itemNo, String variantCode) {
		return (itemNo == null ? "" : itemNo.trim()) + "\u0000" + (variantCode == null ? "" : variantCode.trim());
	}

	// ─── Delivered (the ERP's invoice of a posted order) ─────────

	/**
	 * The invoice import read an ERP invoice whose orderNo is this ERP order number: each POSTED order of that number
	 * becomes DELIVERED, invoice_no the invoice's number, delivered_at its posting date (the head office's day when it has
	 * none), and goes down to its store. Inside the import's transaction (the invoice and its orders saved together), the
	 * rows locked. An order of another store than the invoice's customer (customer numbers both known and different) is
	 * left as it is: a number typed by hand in MANUAL mode may be wrong. An order not POSTED (waiting, rejected, delivered
	 * already by an earlier invoice) is left as it is.
	 */
	@Override
	public int deliveredBy(String navOrderNo, String invoiceNo, LocalDate postingDate, String customerNo) {
		String orderNo = blankToNull(navOrderNo);
		String invoice = blankToNull(invoiceNo);
		if (orderNo == null || invoice == null) {
			return 0;
		}
		Integer changed = transactions.execute(status -> {
			int count = 0;
			for (HoStoreOrder order : orders.findForDelivery(orderNo, HoStoreOrderStatus.POSTED)) {
				if (!ofCustomer(order, blankToNull(customerNo))) {
					continue;
				}
				order.setStatus(HoStoreOrderStatus.DELIVERED);
				order.setInvoiceNo(cut(invoice, HoStoreOrder.INVOICE_NO_LENGTH));
				order.setDeliveredAt(postingDate != null ? postingDate : clock.get().toLocalDate());
				order.setUpdatedBy(HEAD_OFFICE);
				orders.save(order);
				recordDown(order);
				count++;
			}
			return count;
		});
		return changed == null ? 0 : changed;
	}

	/** True unless the order's store and the invoice name two different ERP customer numbers. */
	private boolean ofCustomer(HoStoreOrder order, String customerNo) {
		if (customerNo == null) {
			return true;
		}
		Store store = stores.findById(order.getStoreId()).orElse(null);
		String storeCustomer = store == null ? null : blankToNull(store.getErpCustomerNo());
		return storeCustomer == null || storeCustomer.equalsIgnoreCase(customerNo);
	}

	// ─── Admin actions ───────────────────────────────────────────

	/**
	 * MANUAL mode: the order is in the ERP under this number, typed by the head office: POSTED, posted_mode MANUAL. 409 in
	 * ERP mode or for an order that is not waiting (SENT, ERROR); 400 without a number.
	 */
	public Optional<HoStoreOrderDTO> markPosted(Long id, String navOrderNo, String user) {
		if (byErp) {
			throw new IllegalStateException("The orders are posted by the ERP job on this head office"
					+ " (headoffice.storeorders.post=ERP): use \"retry now\"");
		}
		String number = navOrderNo == null ? "" : navOrderNo.trim();
		if (number.isEmpty() || number.length() > 50) {
			throw new IllegalArgumentException("The ERP's order number is required (at most 50 characters)");
		}
		return Optional.ofNullable(transactions.execute(status -> orders.findForUpdate(id).map(order -> {
			requireWaiting(order, "marked posted");
			order.setStatus(HoStoreOrderStatus.POSTED);
			order.setNavOrderNo(number);
			order.setPostedAt(clock.get());
			order.setPostedMode(PostedMode.MANUAL);
			order.setErrorMessage(null);
			order.setUpdatedBy(user == null ? HEAD_OFFICE : user);
			orders.save(order);
			recordDown(order);
			return viewOf(order);
		}).orElse(null)));
	}

	/**
	 * Both modes: the head office refuses the order with this message: REJECTED, never posted; the store sees ERROR with
	 * the message. 409 for an order that is not waiting (SENT, ERROR); 400 without a message.
	 */
	public Optional<HoStoreOrderDTO> reject(Long id, String message, String user) {
		String text = message == null ? "" : message.trim();
		if (text.isEmpty()) {
			throw new IllegalArgumentException("The message to the store is required");
		}
		return Optional.ofNullable(transactions.execute(status -> orders.findForUpdate(id).map(order -> {
			requireWaiting(order, "rejected");
			order.setStatus(HoStoreOrderStatus.REJECTED);
			order.setErrorMessage(cut(text, HoStoreOrder.ERROR_LENGTH));
			order.setUpdatedBy(user == null ? HEAD_OFFICE : user);
			orders.save(order);
			recordDown(order);
			return viewOf(order);
		}).orElse(null)));
	}

	private static void requireWaiting(HoStoreOrder order, String action) {
		if (order.getStatus() != HoStoreOrderStatus.SENT && order.getStatus() != HoStoreOrderStatus.ERROR) {
			throw new IllegalStateException("Order " + order.getNumber() + " is " + order.getStatus()
					+ ": only an order waiting (SENT, ERROR) is " + action);
		}
	}

	// ─── Reads ───────────────────────────────────────────────────

	/**
	 * A page {content, totalElements, totalPages, number, size} without the lines. storeId null: every store; status
	 * blank or ALL: every one; from / to: the reception day, both included; sort receivedAt (the default), number,
	 * status, postedAt or store; dir asc or desc (the default); search: in the order number, the ERP order number, a
	 * line's item code, ERP item number or name (blank: every order).
	 */
	public Map<String, Object> list(Long storeId, String status, LocalDate from, LocalDate to, String search, String sort,
			String dir, Integer page, Integer size) {
		HoStoreOrderStatus wanted = statusOf(status);
		String text = ItemBarcodeService.containsPattern(search);
		String property = SORTS.getOrDefault(sort == null ? "" : sort.trim(), "receivedAt");
		Sort.Direction direction = "asc".equalsIgnoreCase(dir == null ? "" : dir.trim()) ? Sort.Direction.ASC
				: Sort.Direction.DESC;
		PageRequest request = PageRequest.of(page == null ? 0 : Math.max(page, 0),
				size == null ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE),
				Sort.by(direction, property).and(Sort.by(Sort.Direction.DESC, "id")));
		return transactions.execute(tx -> {
			Page<HoStoreOrder> found = orders.findPage(storeId == null ? 1L : 0L, storeId, wanted == null ? 1L : 0L,
					wanted, from == null ? null : from.atStartOfDay(), to == null ? null : to.plusDays(1).atStartOfDay(),
					text == null ? 1L : 0L, text == null ? "%" : text, request);
			Map<Long, Store> byId = new HashMap<>();
			for (Store store : stores.findAllById(found.getContent().stream().map(HoStoreOrder::getStoreId)
					.collect(Collectors.toSet()))) {
				byId.put(store.getId(), store);
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("content", found.getContent().stream().map(o -> HoStoreOrderDTO.row(o, byId.get(o.getStoreId())))
					.collect(Collectors.toList()));
			result.put("totalElements", found.getTotalElements());
			result.put("totalPages", found.getTotalPages());
			result.put("number", found.getNumber());
			result.put("size", found.getSize());
			return result;
		});
	}

	private static HoStoreOrderStatus statusOf(String status) {
		if (status == null || status.trim().isEmpty() || "ALL".equalsIgnoreCase(status.trim())) {
			return null;
		}
		try {
			return HoStoreOrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Unknown status: " + status);
		}
	}

	/** One order with its lines. */
	public Optional<HoStoreOrderDTO> get(Long id) {
		return Optional.ofNullable(transactions.execute(status -> orders.findById(id).map(this::viewOf).orElse(null)));
	}

	private HoStoreOrderDTO viewOf(HoStoreOrder order) {
		return HoStoreOrderDTO.of(order, stores.findById(order.getStoreId()).orElse(null));
	}

	// ─── Copies down (domain SUPPLY, records ORD:) ───────────────

	/** Inside the change's transaction: the order recorded for its store only. Without the copies down, nothing. */
	private void recordDown(HoStoreOrder order) {
		CopiesDownFeed copies = feed == null ? null : feed.get();
		if (copies != null) {
			copies.recordChange(DataDomain.SUPPLY, StoreOrderStatusCopyDTO.recordCode(order.getNumber()),
					StoreTargets.of(Collections.singletonList(order.getStoreId())));
		}
	}

	/** What the store sees of the order: its status as the copy gives it, the ERP's number, the message, NAV's answer. */
	private static String seenByStore(HoStoreOrder order) {
		StoreOrderStatusCopyDTO copy = copyOf(order);
		return copy.getStatus() + "|" + copy.getNavOrderNo() + "|" + copy.getErrorMessage() + "|" + copy.getNavMessage()
				+ "|" + copy.getLines() + "|" + copy.getInvoiceNo() + "|" + copy.getDeliveredAt();
	}

	/**
	 * The copy of an order for its store: REJECTED goes down as ERROR with its message; NAV's answer on the order and its
	 * lines (the lines only when NAV answered one).
	 */
	static StoreOrderStatusCopyDTO copyOf(HoStoreOrder order) {
		StoreOrderStatusCopyDTO copy = new StoreOrderStatusCopyDTO();
		copy.setOrderNumber(order.getNumber());
		HoStoreOrderStatus status = order.getStatus();
		copy.setStatus(status == HoStoreOrderStatus.REJECTED ? HoStoreOrderStatus.ERROR.name()
				: status == null ? null : status.name());
		copy.setNavOrderNo(order.getNavOrderNo());
		copy.setErrorMessage(order.getErrorMessage());
		copy.setNavMessage(order.getNavMessage());
		copy.setInvoiceNo(order.getInvoiceNo());
		copy.setDeliveredAt(order.getDeliveredAt() == null ? null : order.getDeliveredAt().toString());
		if (order.getLines().stream()
				.anyMatch(line -> line.getNavAcceptedQty() != null || line.getNavLineMessage() != null)) {
			copy.setLines(order.getLines().stream().map(line -> new StoreOrderStatusCopyDTO.Line(line.getItemCode(),
					line.getNavAcceptedQty(), line.getNavLineMessage())).collect(Collectors.toList()));
		}
		LocalDateTime at = order.getUpdatedAt();
		copy.setStatusAt(at == null ? null : at.withNano(0).toString());
		return copy;
	}

	@Override
	public DataDomain getDomain() {
		return DataDomain.SUPPLY;
	}

	/**
	 * The orders of this store among the codes ORD:&lt;number&gt;, POSTED, ERROR, REJECTED or DELIVERED; any other code has no entry
	 * here (another provider of the domain answers it, or it is answered as removed).
	 */
	@Override
	public Map<String, JsonNode> load(Store store, List<String> codes) {
		List<String> numbers = codes.stream().filter(c -> c != null && c.startsWith(StoreOrderStatusCopyDTO.RECORD_PREFIX))
				.map(c -> c.substring(StoreOrderStatusCopyDTO.RECORD_PREFIX.length())).collect(Collectors.toList());
		Map<String, JsonNode> copies = new HashMap<>();
		if (!numbers.isEmpty()) {
			for (HoStoreOrder order : orders.findForStore(store.getId(), numbers, COPIED_STATUSES)) {
				copies.put(StoreOrderStatusCopyDTO.recordCode(order.getNumber()), COPY_MAPPER.valueToTree(copyOf(order)));
			}
		}
		return copies;
	}

	/** Every order posted, in error, rejected or delivered, to its store (the startup backfill). */
	@Override
	public Map<String, StoreTargets> currentTargets() {
		Map<String, StoreTargets> targets = new LinkedHashMap<>();
		for (Object[] row : orders.findTargets(COPIED_STATUSES)) {
			targets.put(StoreOrderStatusCopyDTO.recordCode((String) row[0]),
					StoreTargets.of(Collections.singletonList(((Number) row[1]).longValue())));
		}
		return targets;
	}

	private static String blankToNull(String value) {
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

	static String cut(String text, int length) {
		return text == null || text.length() <= length ? text : text.substring(0, length);
	}
}
