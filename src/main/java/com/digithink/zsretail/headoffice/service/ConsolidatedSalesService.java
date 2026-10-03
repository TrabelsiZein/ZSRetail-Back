package com.digithink.zsretail.headoffice.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.dto.DashboardTodayDTO;
import com.digithink.zsretail.headoffice.model.HoReturn;
import com.digithink.zsretail.headoffice.model.HoReturnLine;
import com.digithink.zsretail.headoffice.model.HoSession;
import com.digithink.zsretail.headoffice.model.HoSessionCount;
import com.digithink.zsretail.headoffice.model.HoTicket;
import com.digithink.zsretail.headoffice.model.HoTicketLine;
import com.digithink.zsretail.headoffice.model.HoTicketPayment;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoReturnRepository;
import com.digithink.zsretail.headoffice.repository.HoSessionRepository;
import com.digithink.zsretail.headoffice.repository.HoTicketRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.model.enumeration.ReturnType;
import com.digithink.zsretail.model.enumeration.SessionStatus;
import com.digithink.zsretail.model.enumeration.TransactionStatus;

import lombok.Getter;

/**
 * Head office plan, task 2.5: what the head office pages read (tickets, sessions and returns of every store, home
 * cards). Head office only; reads the consolidation tables only (ho_ticket, ho_return, ho_session and their lines),
 * never the store tables. Field names follow the store's own history endpoints where the data is the same, so the
 * pages can follow the store pages; every row adds storeId, storeCode and storeName. The contract is in
 * docs/modules/head-office.md, "Consolidated sales API".
 */
@Service
@ConditionalOnHeadOffice
public class ConsolidatedSalesService {

	/** Tickets that count as sales: the finished statuses of the store side (step 2). */
	static final List<String> FINISHED_TICKETS = Arrays.asList(TransactionStatus.COMPLETED.name(),
			TransactionStatus.REFUNDED.name());

	static final List<String> FINISHED_RETURNS = Collections.singletonList(TransactionStatus.COMPLETED.name());

	static final int DEFAULT_SIZE = 10;
	static final int MAX_SIZE = 200;

	/** Bounds used when a date filter is absent: the queries never take a null date. */
	static final LocalDateTime NO_START = LocalDateTime.of(1900, 1, 1, 0, 0);
	static final LocalDateTime NO_END = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

	private final HoTicketRepository tickets;
	private final HoSessionRepository sessions;
	private final HoReturnRepository returns;
	private final StoreRepository stores;

	public ConsolidatedSalesService(HoTicketRepository tickets, HoSessionRepository sessions,
			HoReturnRepository returns, StoreRepository stores) {
		this.tickets = tickets;
		this.sessions = sessions;
		this.returns = returns;
		this.stores = stores;
	}

	// --- Lists ---

	@Transactional(readOnly = true)
	public Map<String, Object> tickets(HistoryQuery query) {
		Page<HoTicket> page = tickets.search(query.getStoreId(), query.getDateFrom(), query.getDateTo(),
				query.getNumber(), query.getStatus(), query.getSessionNumber(),
				query.pageable(Sort.by(Sort.Order.desc("salesDate"), Sort.Order.desc("id"))));
		Map<Long, Store> storesById = storesById();
		List<Long> ids = page.getContent().stream().map(HoTicket::getId).collect(Collectors.toList());
		Map<Long, Long> lineCounts = ids.isEmpty() ? Collections.emptyMap() : counts(tickets.countLines(ids));
		Map<Long, Long> paymentCounts = ids.isEmpty() ? Collections.emptyMap() : counts(tickets.countPayments(ids));
		List<Map<String, Object>> rows = new ArrayList<>();
		for (HoTicket ticket : page.getContent()) {
			Map<String, Object> row = ticketRow(ticket, storesById);
			row.put("salesLinesCount", lineCounts.getOrDefault(ticket.getId(), 0L));
			row.put("paymentsCount", paymentCounts.getOrDefault(ticket.getId(), 0L));
			rows.add(row);
		}
		return pageOf(page, rows);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> sessions(HistoryQuery query) {
		Page<HoSession> page = sessions.search(query.getStoreId(), query.getDateFrom(), query.getDateTo(),
				query.getNumber(), query.getStatus(),
				query.pageable(Sort.by(Sort.Order.desc("openedAt"), Sort.Order.desc("id"))));
		Map<Long, Store> storesById = storesById();
		Map<String, SessionTotals> totals = sessionTotals(page.getContent());
		List<Map<String, Object>> rows = new ArrayList<>();
		for (HoSession session : page.getContent()) {
			rows.add(sessionRow(session, storesById, totals.getOrDefault(key(session), new SessionTotals())));
		}
		return pageOf(page, rows);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> returns(HistoryQuery query) {
		Page<HoReturn> page = returns.search(query.getStoreId(), query.getDateFrom(), query.getDateTo(),
				query.getNumber(), query.getStatus(), query.getSessionNumber(),
				query.pageable(Sort.by(Sort.Order.desc("returnDate"), Sort.Order.desc("id"))));
		Map<Long, Store> storesById = storesById();
		Map<String, HoTicket> originals = originalTickets(page.getContent());
		List<Map<String, Object>> rows = new ArrayList<>();
		for (HoReturn row : page.getContent()) {
			rows.add(returnRow(row, storesById, originals));
		}
		return pageOf(page, rows);
	}

	// --- Details ---

	@Transactional(readOnly = true)
	public Optional<Map<String, Object>> ticket(Long id) {
		return tickets.findById(id).map(ticket -> {
			Map<String, Object> detail = ticketRow(ticket, storesById());
			detail.put("completedDate", ticket.getCompletedDate());
			detail.put("discountSource", ticket.getDiscountSource());
			detail.put("promotion", ticket.getPromotionCode() == null && ticket.getPromotionName() == null ? null
					: object("code", ticket.getPromotionCode(), "name", ticket.getPromotionName()));
			detail.put("loyaltyMember", ticket.getLoyaltyCardNumber() == null ? null
					: object("cardNumber", ticket.getLoyaltyCardNumber(), "name", ticket.getLoyaltyMemberName()));
			detail.put("loyaltyPointsEarned", ticket.getLoyaltyPointsEarned());
			detail.put("loyaltyPointsRedeemed", ticket.getLoyaltyPointsRedeemed());
			detail.put("loyaltyDeductionAmount", ticket.getLoyaltyDeductionAmount());
			detail.put("tableNumber", ticket.getTableNumber());
			List<Map<String, Object>> lines = new ArrayList<>();
			for (HoTicketLine line : ticket.getLines()) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("lineNo", line.getLineNo());
				row.put("item", object("itemCode", line.getItemCode(), "name", line.getItemName()));
				row.put("quantity", line.getQuantity());
				row.put("unitPrice", line.getUnitPrice());
				row.put("discountPercentage", line.getDiscountPercentage());
				row.put("discountAmount", line.getDiscountAmount());
				row.put("discountSource", line.getDiscountSource());
				row.put("promotion", line.getPromotionCode() == null ? null : object("code", line.getPromotionCode()));
				row.put("vatPercent", line.getVatPercent());
				row.put("vatAmount", line.getVatAmount());
				row.put("unitPriceIncludingVat", line.getUnitPriceIncludingVat());
				row.put("lineTotal", line.getLineTotal());
				row.put("lineTotalIncludingVat", line.getLineTotalIncludingVat());
				lines.add(row);
			}
			detail.put("salesLines", lines);
			List<Map<String, Object>> payments = new ArrayList<>();
			for (HoTicketPayment payment : ticket.getPayments()) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("lineNo", payment.getLineNo());
				row.put("paymentMethod",
						object("code", payment.getPaymentMethodCode(), "name", payment.getPaymentMethodName()));
				row.put("totalAmount", payment.getAmount());
				row.put("paymentDate", payment.getPaymentDate());
				row.put("titleNumber", payment.getTitleNumber());
				row.put("dueDate", payment.getDueDate());
				payments.add(row);
			}
			detail.put("payments", payments);
			return detail;
		});
	}

	@Transactional(readOnly = true)
	public Optional<Map<String, Object>> session(Long id) {
		return sessions.findById(id).map(session -> {
			SessionTotals totals = sessionTotals(Collections.singletonList(session)).getOrDefault(key(session),
					new SessionTotals());
			Map<String, Object> detail = new LinkedHashMap<>();
			detail.put("session", sessionRow(session, storesById(), totals));
			detail.put("salesCount", totals.salesCount);
			detail.put("totalSalesAmount", totals.salesAmount);
			detail.put("returnsCount", totals.returnsCount);
			detail.put("totalReturnsAmount", totals.returnsAmount);
			detail.put("simpleReturnsAmount", totals.simpleReturnsAmount);
			detail.put("voucherReturnsAmount", totals.voucherReturnsAmount);
			List<Map<String, Object>> counts = new ArrayList<>();
			for (HoSessionCount count : session.getCounts()) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("lineNo", count.getLineNo());
				row.put("counterType", count.getCounterType());
				row.put("paymentMethod", count.getPaymentMethodCode() == null ? null
						: object("code", count.getPaymentMethodCode(), "name", count.getPaymentMethodName()));
				row.put("denominationValue", count.getDenominationValue());
				row.put("quantity", count.getQuantity());
				row.put("lineTotal", count.getLineTotal());
				row.put("referenceNumber", count.getReferenceNumber());
				counts.add(row);
			}
			detail.put("counts", counts);
			return detail;
		});
	}

	@Transactional(readOnly = true)
	public Optional<Map<String, Object>> returnDetail(Long id) {
		return returns.findById(id).map(header -> {
			Map<String, Object> detail = new LinkedHashMap<>();
			detail.put("returnHeader",
					returnRow(header, storesById(), originalTickets(Collections.singletonList(header))));
			List<Map<String, Object>> lines = new ArrayList<>();
			for (HoReturnLine line : header.getLines()) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("lineNo", line.getLineNo());
				row.put("item", object("itemCode", line.getItemCode(), "name", line.getItemName()));
				row.put("quantity", line.getQuantity());
				row.put("unitPrice", line.getUnitPrice());
				row.put("unitPriceIncludingVat", line.getUnitPriceIncludingVat());
				row.put("lineTotal", line.getLineTotal());
				row.put("lineTotalIncludingVat", line.getLineTotalIncludingVat());
				row.put("notes", line.getNotes());
				lines.add(row);
			}
			detail.put("returnLines", lines);
			return detail;
		});
	}

	// --- Filter and home ---

	/** The stores for the page filters: id, code, name, active; by code. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> storeOptions() {
		List<Map<String, Object>> options = new ArrayList<>();
		for (Store store : stores.findAll(Sort.by("code"))) {
			options.add(object("id", store.getId(), "code", store.getCode(), "name", store.getName(), "active",
					store.getActive()));
		}
		return options;
	}

	/**
	 * The home cards of the head office, all stores together, with the same fields as the store's
	 * GET admin/dashboard/today. Sales: finished tickets sold today; returns: completed returns made today.
	 */
	@Transactional(readOnly = true)
	public DashboardTodayDTO dashboardToday(LocalDate today) {
		LocalDateTime from = today.atStartOfDay();
		LocalDateTime to = today.plusDays(1).atStartOfDay();
		Object[] sales = first(tickets.salesBetween(from, to, FINISHED_TICKETS));
		Object[] returned = first(returns.returnsBetween(from, to, FINISHED_RETURNS));
		return new DashboardTodayDTO(longOf(sales[0]), doubleOf(sales[1]),
				sessions.countByStatus(SessionStatus.OPENED.name()), longOf(returned[0]), doubleOf(returned[1]),
				tickets.countByStatus(TransactionStatus.PENDING.name()));
	}

	// --- Rows ---

	private static Map<String, Object> ticketRow(HoTicket ticket, Map<Long, Store> storesById) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", ticket.getId());
		putStore(row, ticket.getStore(), storesById);
		row.put("salesNumber", ticket.getSalesNumber());
		row.put("salesDate", ticket.getSalesDate());
		row.put("subtotal", ticket.getSubtotal());
		row.put("taxAmount", ticket.getTaxAmount());
		row.put("discountAmount", ticket.getDiscountAmount());
		row.put("discountPercentage", ticket.getDiscountPercentage());
		row.put("totalAmount", ticket.getTotalAmount());
		row.put("paidAmount", ticket.getPaidAmount());
		row.put("changeAmount", ticket.getChangeAmount());
		row.put("status", ticket.getStatus());
		row.put("notes", ticket.getNotes());
		row.put("invoiced", ticket.getInvoiced());
		row.put("invoiceNumber", ticket.getInvoiceNumber());
		row.put("customer", ticket.getCustomerCode() == null && ticket.getCustomerName() == null ? null
				: object("customerCode", ticket.getCustomerCode(), "name", ticket.getCustomerName()));
		row.put("createdByUser", user(ticket.getCashierLogin(), ticket.getCashierName()));
		row.put("cashierSession", ticket.getSessionNumber() == null ? null
				: object("sessionNumber", ticket.getSessionNumber()));
		putReception(row, ticket.getCreatedAt(), ticket.getUpdatedAt());
		return row;
	}

	private static Map<String, Object> sessionRow(HoSession session, Map<Long, Store> storesById,
			SessionTotals totals) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", session.getId());
		putStore(row, session.getStore(), storesById);
		row.put("sessionNumber", session.getSessionNumber());
		row.put("cashierFullName", session.getCashierName() != null ? session.getCashierName() : session.getCashierLogin());
		row.put("cashierUsername", session.getCashierLogin());
		row.put("openedAt", session.getOpenedAt());
		row.put("closedAt", session.getClosedAt());
		row.put("status", session.getStatus());
		row.put("openingCash", session.getOpeningCash());
		row.put("realCash", session.getRealCash());
		row.put("posUserClosureCash", session.getPosUserClosureCash());
		row.put("responsibleClosureCash", session.getResponsibleClosureCash());
		row.put("salesCount", totals.salesCount);
		row.put("totalSalesAmount", totals.salesAmount);
		row.put("returnsCount", totals.returnsCount);
		row.put("totalReturnsAmount", totals.returnsAmount);
		row.put("simpleReturnsAmount", totals.simpleReturnsAmount);
		row.put("voucherReturnsAmount", totals.voucherReturnsAmount);
		// Same as the store's session history: system = opening cash + sales - simple returns, rounded to 2 decimals
		double system = (session.getOpeningCash() == null ? 0.0 : session.getOpeningCash()) + totals.salesAmount
				- totals.simpleReturnsAmount;
		row.put("cashDifference", session.getPosUserClosureCash() == null ? null
				: Math.round((session.getPosUserClosureCash() - system) * 100.0) / 100.0);
		row.put("responsibleDifference", session.getResponsibleClosureCash() == null ? null
				: Math.round((session.getResponsibleClosureCash() - system) * 100.0) / 100.0);
		row.put("verificationNotes", session.getVerificationNotes());
		row.put("verifiedByName",
				session.getVerifiedByName() != null ? session.getVerifiedByName() : session.getVerifiedByLogin());
		row.put("verifiedAt", session.getVerifiedAt());
		putReception(row, session.getCreatedAt(), session.getUpdatedAt());
		return row;
	}

	private static Map<String, Object> returnRow(HoReturn header, Map<Long, Store> storesById,
			Map<String, HoTicket> originals) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", header.getId());
		Long storeId = putStore(row, header.getStore(), storesById);
		row.put("returnNumber", header.getReturnNumber());
		row.put("returnDate", header.getReturnDate());
		row.put("returnType", header.getReturnType());
		row.put("totalReturnAmount", header.getTotalReturnAmount());
		row.put("notes", header.getNotes());
		row.put("status", header.getStatus());
		row.put("discountPercentage", header.getDiscountPercentage());
		HoTicket original = originals.get(storeId + "|" + header.getOriginalSalesNumber());
		Map<String, Object> originalRow = new LinkedHashMap<>();
		originalRow.put("id", original == null ? null : original.getId());
		originalRow.put("salesNumber", header.getOriginalSalesNumber());
		originalRow.put("salesDate", original == null ? null : original.getSalesDate());
		originalRow.put("totalAmount", original == null ? null : original.getTotalAmount());
		row.put("originalSalesHeader", originalRow);
		row.put("returnVoucher", header.getVoucherNumber() == null ? null
				: object("voucherNumber", header.getVoucherNumber(), "voucherAmount", header.getVoucherAmount(),
						"expiryDate", header.getVoucherExpiryDate()));
		row.put("createdByUser", user(header.getCashierLogin(), header.getCashierName()));
		row.put("cashierSession", header.getSessionNumber() == null ? null
				: object("sessionNumber", header.getSessionNumber()));
		putReception(row, header.getCreatedAt(), header.getUpdatedAt());
		return row;
	}

	/** storeId, storeCode, storeName; returns the store id. */
	private static Long putStore(Map<String, Object> row, Store reference, Map<Long, Store> storesById) {
		Long storeId = reference == null ? null : reference.getId();
		Store store = storesById.get(storeId);
		row.put("storeId", storeId);
		row.put("storeCode", store == null ? null : store.getCode());
		row.put("storeName", store == null ? null : store.getName());
		return storeId;
	}

	/** First and last reception at the head office (head office clock). */
	private static void putReception(Map<String, Object> row, LocalDateTime first, LocalDateTime last) {
		row.put("receivedAt", first);
		row.put("lastReceivedAt", last);
	}

	private static Map<String, Object> user(String login, String name) {
		return login == null && name == null ? null : object("username", login, "fullName", name != null ? name : login);
	}

	// --- Helpers ---

	private Map<Long, Store> storesById() {
		return stores.findAll().stream().collect(Collectors.toMap(Store::getId, Function.identity()));
	}

	/** Sales and returns of each session of the page, from its own store's tickets and returns. */
	private Map<String, SessionTotals> sessionTotals(List<HoSession> page) {
		Map<String, SessionTotals> totals = new HashMap<>();
		if (page.isEmpty()) {
			return totals;
		}
		Set<Long> storeIds = new HashSet<>();
		Set<String> numbers = new HashSet<>();
		for (HoSession session : page) {
			storeIds.add(session.getStore().getId());
			numbers.add(session.getSessionNumber());
			totals.put(key(session), new SessionTotals());
		}
		for (Object[] row : tickets.salesBySession(storeIds, numbers, FINISHED_TICKETS)) {
			SessionTotals total = totals.get(row[0] + "|" + row[1]);
			if (total != null) { // the store and number lists are crossed
				total.salesCount = longOf(row[2]);
				total.salesAmount = doubleOf(row[3]);
			}
		}
		for (Object[] row : returns.returnsBySession(storeIds, numbers, FINISHED_RETURNS)) {
			SessionTotals total = totals.get(row[0] + "|" + row[1]);
			if (total != null) {
				double amount = doubleOf(row[4]);
				total.returnsCount += longOf(row[3]);
				total.returnsAmount += amount;
				if (ReturnType.SIMPLE_RETURN.name().equals(row[2])) {
					total.simpleReturnsAmount += amount;
				} else if (ReturnType.RETURN_VOUCHER.name().equals(row[2])) {
					total.voucherReturnsAmount += amount;
				}
			}
		}
		return totals;
	}

	/** The head office tickets returned by these returns, by "storeId|salesNumber". */
	private Map<String, HoTicket> originalTickets(List<HoReturn> page) {
		Set<Long> storeIds = new HashSet<>();
		Set<String> numbers = new HashSet<>();
		for (HoReturn header : page) {
			if (header.getOriginalSalesNumber() != null) {
				storeIds.add(header.getStore().getId());
				numbers.add(header.getOriginalSalesNumber());
			}
		}
		Map<String, HoTicket> originals = new HashMap<>();
		if (!numbers.isEmpty()) {
			for (HoTicket ticket : tickets.findByStoreIdInAndSalesNumberIn(storeIds, numbers)) {
				originals.put(ticket.getStore().getId() + "|" + ticket.getSalesNumber(), ticket);
			}
		}
		return originals;
	}

	private static String key(HoSession session) {
		return session.getStore().getId() + "|" + session.getSessionNumber();
	}

	private static Map<String, Object> pageOf(Page<?> page, List<Map<String, Object>> rows) {
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("content", rows);
		answer.put("totalElements", page.getTotalElements());
		answer.put("totalPages", page.getTotalPages());
		answer.put("number", page.getNumber());
		answer.put("size", page.getSize());
		return answer;
	}

	private static Map<Long, Long> counts(List<Object[]> rows) {
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] row : rows) {
			counts.put((Long) row[0], longOf(row[1]));
		}
		return counts;
	}

	/** Keys and values in turn; keeps the order. */
	private static Map<String, Object> object(Object... keysAndValues) {
		Map<String, Object> object = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			object.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return object;
	}

	private static Object[] first(List<Object[]> rows) {
		return rows == null || rows.isEmpty() || rows.get(0) == null ? new Object[] { 0L, null } : rows.get(0);
	}

	private static long longOf(Object value) {
		return value == null ? 0L : ((Number) value).longValue();
	}

	private static double doubleOf(Object value) {
		return value == null ? 0.0 : ((Number) value).doubleValue();
	}

	private static final class SessionTotals {
		long salesCount;
		double salesAmount;
		long returnsCount;
		double returnsAmount;
		double simpleReturnsAmount;
		double voucherReturnsAmount;
	}

	/**
	 * The filters and paging of a list, parsed from the request. Never null for the queries: absent dates become
	 * {@link #NO_START} / {@link #NO_END}, an absent store 0, absent texts "" (and "%" for the number pattern).
	 */
	@Getter
	public static final class HistoryQuery {

		private final int page;
		private final int size;
		private final long storeId;
		private final LocalDateTime dateFrom;
		private final LocalDateTime dateTo;
		/** Lower-case LIKE pattern: "%" + number + "%", or "%". */
		private final String number;
		/** Upper-case status name, or "" for any. */
		private final String status;
		/** Exact session number, or "" for any. */
		private final String sessionNumber;

		private HistoryQuery(int page, int size, long storeId, LocalDateTime dateFrom, LocalDateTime dateTo,
				String number, String status, String sessionNumber) {
			this.page = page;
			this.size = size;
			this.storeId = storeId;
			this.dateFrom = dateFrom;
			this.dateTo = dateTo;
			this.number = number;
			this.status = status;
			this.sessionNumber = sessionNumber;
		}

		/**
		 * Dates as yyyy-MM-dd (from: start of the day, to: end of the day) or yyyy-MM-ddTHH:mm[:ss] (exact). Status
		 * blank or "all" (any case) means any status. Page from 0; size 10 by default, 1 to 200. Throws
		 * IllegalArgumentException (answered 400) on a date that cannot be read or a page below 0.
		 */
		public static HistoryQuery of(Integer page, Integer size, Long storeId, String dateFrom, String dateTo,
				String number, String status, String sessionNumber) {
			int pageNumber = page == null ? 0 : page;
			if (pageNumber < 0) {
				throw new IllegalArgumentException("page must be 0 or more");
			}
			int pageSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
			String statusValue = blank(status) || "all".equalsIgnoreCase(status.trim()) ? ""
					: status.trim().toUpperCase();
			return new HistoryQuery(pageNumber, pageSize, storeId == null ? 0L : storeId,
					date(dateFrom, "dateFrom", false), date(dateTo, "dateTo", true),
					blank(number) ? "%" : "%" + number.trim().toLowerCase() + "%", statusValue,
					blank(sessionNumber) ? "" : sessionNumber.trim());
		}

		PageRequest pageable(Sort sort) {
			return PageRequest.of(page, size, sort);
		}

		private static LocalDateTime date(String value, String name, boolean end) {
			if (blank(value)) {
				return end ? NO_END : NO_START;
			}
			String text = value.trim();
			try {
				if (text.length() == 10) {
					LocalDate day = LocalDate.parse(text);
					// 23:59:59.9999999: the last instant SQL Server's datetime2 keeps (nanoseconds would round up)
					return end ? day.atTime(23, 59, 59, 999_999_900) : day.atStartOfDay();
				}
				return LocalDateTime.parse(text);
			} catch (DateTimeParseException e) {
				throw new IllegalArgumentException(
						"Invalid " + name + " '" + value + "': expected yyyy-MM-dd or yyyy-MM-ddTHH:mm");
			}
		}

		private static boolean blank(String value) {
			return value == null || value.trim().isEmpty();
		}
	}
}
