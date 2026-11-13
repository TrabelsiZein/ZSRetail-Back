package com.digithink.zsretail.store.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Tuple;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.store.model.ScannedTicketPayment;

/**
 * 3.2.0, step 7: the history of the scanned tickets taken (meal tickets, gift cheques), read-only, over
 * scanned_ticket_payment and the copies taken at the sale: one row per ticket, newest first (the payment's time, when
 * the ticket was taken). Filters: the period (on the payment's time), the issuer (the copy's name), the payment method,
 * the cashier (who took the payment); a barcode search (the whole barcode or its last digits) ignores the period. The
 * totals of the whole filtered result: tickets, face total, deduction (face - counted), counted, applied, excess kept.
 * One query builds the rows (a projection, no entity loaded per row), one the count and the sums.
 * Docs: docs/modules/cash-sessions.md, "Scanned tickets taken".
 */
@Service
public class ScannedTicketsHistoryService {

	/** The export's limit (as the tickets history's): the newest rows, the answer says it was cut. */
	public static final int MAX_EXPORT = TicketsHistoryExport.MAX_TICKETS;

	@PersistenceContext
	private EntityManager entityManager;

	/** The filters, parsed once; a barcode search leaves the period out. */
	public static final class Filters {
		LocalDateTime from;
		LocalDateTime to;
		String issuer;
		Long paymentMethodId;
		Long cashierId;
		String barcode;

		/**
		 * The page's parameters: dates as the tickets history sends them ("2026-11-13T00:00", or a day), ids as numbers;
		 * an empty or unreadable value is no filter.
		 */
		public static Filters of(String dateFrom, String dateTo, String issuer, String paymentMethodId,
				String cashierId, String barcode) {
			Filters filters = new Filters();
			filters.barcode = blankToNull(barcode);
			filters.from = dateTime(dateFrom, false);
			filters.to = dateTime(dateTo, true);
			filters.issuer = blankToNull(issuer);
			filters.paymentMethodId = id(paymentMethodId);
			filters.cashierId = id(cashierId);
			return filters;
		}
	}

	/** One page of rows, newest first, and the totals of the whole filtered result. */
	@Transactional(readOnly = true)
	public Map<String, Object> page(Filters filters, int page, int size) {
		int pageSize = Math.max(1, Math.min(size, 500));
		int pageNumber = Math.max(0, page);
		Map<String, Object> totals = totals(filters);
		long total = (long) totals.get("ticketsCount");
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("content", rows(filters, pageNumber * pageSize, pageSize));
		result.put("totalElements", total);
		result.put("totalPages", (total + pageSize - 1) / pageSize);
		result.put("number", pageNumber);
		result.put("size", pageSize);
		result.put("totals", totals);
		return result;
	}

	/** Every row of the filters (the newest {@code limit} at most), for the Excel file; truncated when cut. */
	@Transactional(readOnly = true)
	public Map<String, Object> export(Filters filters, int limit) {
		Map<String, Object> totals = totals(filters);
		long total = (long) totals.get("ticketsCount");
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("rows", rows(filters, 0, limit));
		result.put("total", total);
		result.put("truncated", total > limit);
		result.put("limit", limit);
		result.put("totals", totals);
		return result;
	}

	/** The issuers met in the tickets taken (the copies' names), for the filter. */
	@Transactional(readOnly = true)
	public List<String> issuers() {
		return entityManager.createQuery("SELECT DISTINCT t.issuerName FROM ScannedTicketPayment t ORDER BY t.issuerName",
				String.class).getResultList();
	}

	// ─── Queries ─────────────────────────────────────────────────────

	private List<Map<String, Object>> rows(Filters filters, int first, int max) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<Tuple> query = cb.createTupleQuery();
		Root<ScannedTicketPayment> t = query.from(ScannedTicketPayment.class);
		Join<ScannedTicketPayment, Payment> p = t.join("payment");
		Join<Payment, PaymentMethod> m = p.join("paymentMethod");
		Join<Payment, SalesHeader> h = p.join("salesHeader");
		Join<SalesHeader, CashierSession> s = h.join("cashierSession", JoinType.LEFT);
		Join<Payment, UserAccount> u = p.join("createdByUser", JoinType.LEFT);
		query.multiselect(t.get("id").alias("id"), p.get("paymentDate").alias("paidAt"), h.get("id").alias("saleId"),
				h.get("salesNumber").alias("salesNumber"), s.get("sessionNumber").alias("sessionNumber"),
				u.get("fullName").alias("fullName"), u.get("username").alias("username"),
				m.get("id").alias("paymentMethodId"), m.get("name").alias("paymentMethodName"),
				t.get("issuerName").alias("issuerName"), t.get("ticketCode").alias("ticketCode"),
				t.get("barcode").alias("barcode"), t.get("printedYear").alias("printedYear"),
				t.get("faceValue").alias("faceValue"), t.get("deductionPercent").alias("deductionPercent"),
				t.get("countedValue").alias("countedValue"), t.get("appliedAmount").alias("appliedAmount"),
				t.get("excessAmount").alias("excessAmount"));
		query.where(where(cb, t, p, filters));
		query.orderBy(cb.desc(p.get("paymentDate")), cb.desc(t.get("id")));
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Tuple tuple : entityManager.createQuery(query).setFirstResult(first).setMaxResults(max).getResultList()) {
			rows.add(row(tuple));
		}
		return rows;
	}

	private Map<String, Object> totals(Filters filters) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<Tuple> query = cb.createTupleQuery();
		Root<ScannedTicketPayment> t = query.from(ScannedTicketPayment.class);
		Join<ScannedTicketPayment, Payment> p = t.join("payment");
		query.multiselect(cb.count(t), cb.sum(t.<BigDecimal>get("faceValue")), cb.sum(t.<BigDecimal>get("countedValue")),
				cb.sum(t.<BigDecimal>get("appliedAmount")), cb.sum(t.<BigDecimal>get("excessAmount")));
		query.where(where(cb, t, p, filters));
		Tuple sums = entityManager.createQuery(query).getSingleResult();
		BigDecimal face = millimes(sums.get(1));
		BigDecimal counted = millimes(sums.get(2));
		Map<String, Object> totals = new LinkedHashMap<>();
		totals.put("ticketsCount", ((Number) sums.get(0)).longValue());
		totals.put("faceTotal", face);
		totals.put("deduction", face.subtract(counted));
		totals.put("counted", counted);
		totals.put("applied", millimes(sums.get(3)));
		totals.put("excess", millimes(sums.get(4)));
		return totals;
	}

	/** The filters as predicates; a barcode search (the end of the barcode) leaves the period out. */
	private static Predicate[] where(CriteriaBuilder cb, Root<ScannedTicketPayment> t,
			Join<ScannedTicketPayment, Payment> p, Filters filters) {
		List<Predicate> predicates = new ArrayList<>();
		if (filters.barcode != null) {
			predicates.add(cb.like(t.get("barcode"), "%" + escapeLike(filters.barcode), '\\'));
		} else {
			if (filters.from != null) {
				predicates.add(cb.greaterThanOrEqualTo(p.get("paymentDate"), filters.from));
			}
			if (filters.to != null) {
				predicates.add(cb.lessThanOrEqualTo(p.get("paymentDate"), filters.to));
			}
		}
		if (filters.issuer != null) {
			predicates.add(cb.equal(t.get("issuerName"), filters.issuer));
		}
		if (filters.paymentMethodId != null) {
			predicates.add(cb.equal(p.get("paymentMethod").get("id"), filters.paymentMethodId));
		}
		if (filters.cashierId != null) {
			predicates.add(cb.equal(p.get("createdByUser").get("id"), filters.cashierId));
		}
		return predicates.toArray(new Predicate[0]);
	}

	private static Map<String, Object> row(Tuple tuple) {
		BigDecimal face = millimes(tuple.get("faceValue"));
		BigDecimal counted = millimes(tuple.get("countedValue"));
		String fullName = (String) tuple.get("fullName");
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", tuple.get("id"));
		row.put("paidAt", tuple.get("paidAt"));
		row.put("saleId", tuple.get("saleId"));
		row.put("salesNumber", tuple.get("salesNumber"));
		row.put("sessionNumber", tuple.get("sessionNumber"));
		row.put("cashierName", fullName != null && !fullName.trim().isEmpty() ? fullName : tuple.get("username"));
		row.put("paymentMethodId", tuple.get("paymentMethodId"));
		row.put("paymentMethodName", tuple.get("paymentMethodName"));
		row.put("issuerName", tuple.get("issuerName"));
		row.put("ticketCode", tuple.get("ticketCode"));
		row.put("barcode", tuple.get("barcode"));
		row.put("printedYear", tuple.get("printedYear"));
		row.put("faceValue", face);
		row.put("deductionPercent", tuple.get("deductionPercent"));
		row.put("deduction", face.subtract(counted));
		row.put("countedValue", counted);
		row.put("appliedAmount", millimes(tuple.get("appliedAmount")));
		row.put("excessAmount", millimes(tuple.get("excessAmount")));
		return row;
	}

	// ─── Helpers ─────────────────────────────────────────────────────

	private static BigDecimal millimes(Object value) {
		BigDecimal amount = value instanceof BigDecimal ? (BigDecimal) value
				: value instanceof Number ? BigDecimal.valueOf(((Number) value).doubleValue()) : BigDecimal.ZERO;
		return amount.setScale(3, RoundingMode.HALF_UP);
	}

	private static String escapeLike(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static String blankToNull(String text) {
		return text == null || text.trim().isEmpty() ? null : text.trim();
	}

	private static Long id(String text) {
		String value = blankToNull(text);
		if (value == null || "all".equalsIgnoreCase(value)) {
			return null;
		}
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** A "yyyy-MM-ddTHH:mm[:ss]" or a day (the start of it, or its end for {@code end}); null when absent or unreadable. */
	static LocalDateTime dateTime(String text, boolean end) {
		String value = blankToNull(text);
		if (value == null) {
			return null;
		}
		try {
			return LocalDateTime.parse(value);
		} catch (DateTimeParseException e) {
			try {
				LocalDate day = LocalDate.parse(value);
				return end ? day.atTime(23, 59, 59) : day.atStartOfDay();
			} catch (DateTimeParseException e2) {
				return null;
			}
		}
	}
}
