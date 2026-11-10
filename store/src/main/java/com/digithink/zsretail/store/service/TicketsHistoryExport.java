package com.digithink.zsretail.store.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.store.dto.TicketsExportDTO;

/**
 * 3.1.2, the Excel export of the tickets history (GET /sales-header/history/export): every ticket of the list's filters
 * ({@link SalesHeaderService#ticketsHistorySpec}), newest first, up to {@link #MAX_TICKETS}, with their lines and their
 * payments by method. A few reads whatever the number of tickets: the tickets in one query (their customer, cashier,
 * session and loyalty card joined), then the lines and the payments by blocks of {@link #BLOCK} tickets. The list itself
 * reads its lines and payments ticket by ticket, fine for a page of 10, not for a month. docs/admin-pages.md,
 * "Tickets History Page", "Excel export".
 */
@Service
public class TicketsHistoryExport {

	/** The tickets of one export: a busy store's month or two; beyond, the newest ones and truncated. */
	public static final int MAX_TICKETS = 20000;

	/** Ids per IN list (SQL Server takes 2,100 parameters). */
	static final int BLOCK = 1000;

	static final String LINES = "SELECT l.salesHeader.id, i.itemCode, i.name, i.variantDescription, f.name, sf.name,"
			+ " l.quantity, l.unitPrice, l.unitPriceIncludingVat, l.discountPercentage, l.discountAmount, l.vatPercent,"
			+ " l.vatAmount, l.lineTotal, l.lineTotalIncludingVat, l.unitCost FROM SalesLine l LEFT JOIN l.item i"
			+ " LEFT JOIN i.itemFamily f LEFT JOIN i.itemSubFamily sf WHERE l.salesHeader.id IN (:ids)"
			+ " ORDER BY l.salesHeader.id, l.id";

	static final String PAYMENTS = "SELECT p.salesHeader.id, m.name, p.totalAmount, m.displayOrder FROM Payment p"
			+ " LEFT JOIN p.paymentMethod m WHERE p.salesHeader.id IN (:ids) ORDER BY p.id";

	@PersistenceContext
	private EntityManager entityManager;

	/** The export of these filters (the list's request values); the lines' unit cost only withCost. */
	@Transactional(readOnly = true)
	public TicketsExportDTO export(Specification<SalesHeader> filters, boolean withCost, int limit) {
		TicketsExportDTO answer = new TicketsExportDTO();
		answer.setLimit(limit);
		answer.setWithCost(withCost);
		answer.setTotalTickets(count(filters));
		answer.setTruncated(answer.getTotalTickets() > limit);
		answer.setTickets(tickets(filters, limit));
		Map<Long, TicketsExportDTO.Ticket> byId = new LinkedHashMap<>();
		answer.getTickets().forEach(ticket -> byId.put(ticket.getId(), ticket));
		List<Long> ids = new ArrayList<>(byId.keySet());
		Map<String, Integer> methodOrder = new HashMap<>();
		for (int from = 0; from < ids.size(); from += BLOCK) {
			List<Long> block = ids.subList(from, Math.min(from + BLOCK, ids.size()));
			for (Object[] row : rows(LINES, block)) {
				TicketsExportDTO.Ticket ticket = byId.get(((Number) row[0]).longValue());
				TicketsExportDTO.Line line = line(row, ticket, withCost);
				ticket.setLineCount(ticket.getLineCount() + 1);
				if (line.getQuantity() != null) {
					ticket.setQuantity(ticket.getQuantity().add(line.getQuantity()));
				}
				answer.getLines().add(line);
			}
			for (Object[] row : rows(PAYMENTS, block)) {
				TicketsExportDTO.Ticket ticket = byId.get(((Number) row[0]).longValue());
				String method = row[1] == null ? "-" : (String) row[1];
				double amount = row[2] == null ? 0 : ((Number) row[2]).doubleValue();
				ticket.getPayments().merge(method, amount, Double::sum);
				methodOrder.putIfAbsent(method, row[3] == null ? Integer.MAX_VALUE : ((Number) row[3]).intValue());
			}
		}
		// The tickets come newest first and their lines by ticket id: the lines in the tickets' order
		Map<Long, Integer> position = new HashMap<>();
		for (int k = 0; k < ids.size(); k++) {
			position.put(ids.get(k), k);
		}
		answer.getLines().sort((a, b) -> Integer.compare(position.get(a.getTicketId()), position.get(b.getTicketId())));
		List<String> methods = new ArrayList<>(methodOrder.keySet());
		methods.sort((a, b) -> {
			int order = Integer.compare(methodOrder.get(a), methodOrder.get(b));
			return order != 0 ? order : a.compareToIgnoreCase(b);
		});
		answer.setPaymentMethods(methods);
		return answer;
	}

	private long count(Specification<SalesHeader> filters) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<Long> query = cb.createQuery(Long.class);
		Root<SalesHeader> root = query.from(SalesHeader.class);
		query.select(cb.count(root)).where(filters.toPredicate(root, query, cb));
		return entityManager.createQuery(query).getSingleResult();
	}

	private List<TicketsExportDTO.Ticket> tickets(Specification<SalesHeader> filters, int limit) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<Tuple> query = cb.createTupleQuery();
		Root<SalesHeader> root = query.from(SalesHeader.class);
		Join<Object, Object> customer = root.join("customer", JoinType.LEFT);
		Join<Object, Object> user = root.join("createdByUser", JoinType.LEFT);
		Join<SalesHeader, CashierSession> session = root.join("cashierSession", JoinType.LEFT);
		Join<Object, Object> cashier = session.join("cashier", JoinType.LEFT);
		Join<Object, Object> member = root.join("loyaltyMember", JoinType.LEFT);
		query.multiselect(root.get("id"), root.get("salesNumber"), root.get("salesDate"), root.get("status"),
				user.get("fullName"), user.get("username"), cashier.get("fullName"), cashier.get("username"),
				session.get("sessionNumber"), customer.get("customerCode"), customer.get("name"),
				member.get("cardNumber"), root.get("subtotal"), root.get("taxAmount"), root.get("discountAmount"),
				root.get("totalAmount"), root.get("paidAmount"), root.get("changeAmount"),
				root.get("loyaltyPointsEarned"), root.get("loyaltyPointsRedeemed"), root.get("synchronizationStatus"),
				root.get("erpNo"), root.get("invoiced"), root.get("invoiceNumber"), root.get("fiscalRegistration"),
				root.get("notes"));
		query.where(filters.toPredicate(root, query, cb));
		query.orderBy(cb.desc(root.get("salesDate")), cb.desc(root.get("id")));
		List<TicketsExportDTO.Ticket> tickets = new ArrayList<>();
		for (Tuple row : entityManager.createQuery(query).setMaxResults(limit).getResultList()) {
			TicketsExportDTO.Ticket ticket = new TicketsExportDTO.Ticket();
			int k = 0;
			ticket.setId((Long) row.get(k++));
			ticket.setSalesNumber((String) row.get(k++));
			ticket.setSalesDate((LocalDateTime) row.get(k++));
			ticket.setStatus(text(row.get(k++)));
			// The ticket's user, else its session's cashier (as the ticket's details)
			String userName = name(row.get(k++), row.get(k++));
			String cashierName = name(row.get(k++), row.get(k++));
			ticket.setCashier(userName != null ? userName : cashierName);
			ticket.setSessionNumber((String) row.get(k++));
			ticket.setCustomerCode((String) row.get(k++));
			ticket.setCustomerName((String) row.get(k++));
			ticket.setLoyaltyCardNumber((String) row.get(k++));
			ticket.setSubtotal((Double) row.get(k++));
			ticket.setTaxAmount((Double) row.get(k++));
			ticket.setDiscountAmount((Double) row.get(k++));
			ticket.setTotalAmount((Double) row.get(k++));
			ticket.setPaidAmount((Double) row.get(k++));
			ticket.setChangeAmount((Double) row.get(k++));
			ticket.setLoyaltyPointsEarned((Integer) row.get(k++));
			ticket.setLoyaltyPointsRedeemed((Integer) row.get(k++));
			ticket.setSynchronizationStatus(text(row.get(k++)));
			ticket.setErpNo((String) row.get(k++));
			ticket.setInvoiced((Boolean) row.get(k++));
			ticket.setInvoiceNumber((String) row.get(k++));
			ticket.setFiscalRegistration((String) row.get(k++));
			ticket.setNotes((String) row.get(k++));
			tickets.add(ticket);
		}
		return tickets;
	}

	private static TicketsExportDTO.Line line(Object[] row, TicketsExportDTO.Ticket ticket, boolean withCost) {
		TicketsExportDTO.Line line = new TicketsExportDTO.Line();
		line.setTicketId(ticket.getId());
		line.setSalesNumber(ticket.getSalesNumber());
		line.setSalesDate(ticket.getSalesDate());
		line.setItemCode((String) row[1]);
		line.setItemName((String) row[2]);
		line.setVariantDescription((String) row[3]);
		line.setFamilyName((String) row[4]);
		line.setSubFamilyName((String) row[5]);
		line.setQuantity((BigDecimal) row[6]);
		line.setUnitPrice((Double) row[7]);
		line.setUnitPriceIncludingVat((Double) row[8]);
		line.setDiscountPercentage((Double) row[9]);
		line.setDiscountAmount((Double) row[10]);
		line.setVatPercent((Integer) row[11]);
		line.setVatAmount((Double) row[12]);
		line.setLineTotal((Double) row[13]);
		line.setLineTotalIncludingVat((Double) row[14]);
		if (withCost) {
			line.setUnitCost((BigDecimal) row[15]);
		}
		return line;
	}

	@SuppressWarnings("unchecked")
	private List<Object[]> rows(String jpql, List<Long> ids) {
		return entityManager.createQuery(jpql).setParameter("ids", ids).getResultList();
	}

	private static String name(Object fullName, Object username) {
		String full = (String) fullName;
		return full != null && !full.trim().isEmpty() ? full.trim() : (String) username;
	}

	private static String text(Object value) {
		return value == null ? null : value.toString();
	}
}
