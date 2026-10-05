package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

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
import com.digithink.zsretail.headoffice.service.ConsolidatedSalesService.HistoryQuery;
import com.digithink.zsretail.model.PaymentMethod;
import com.digithink.zsretail.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.repository.PaymentMethodRepository;

/**
 * Head office plan, task 2.5: what the head office pages read. Filters (store, dates, number, status, session),
 * paging, the store code and name on every row, the details, the session totals and the home cards (only finished
 * tickets count). Plain JUnit: the consolidation tables are in-memory lists and the repository stubs apply the same
 * rules as the JPQL queries (which are checked at L2).
 */
class ConsolidatedSalesServiceTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);

	private final Store rs01 = store(1L, "RS01", "Store Tunis");
	private final Store rs02 = store(2L, "RS02", "Store Sousse");
	private final List<HoTicket> ticketTable = new ArrayList<>();
	private final List<HoSession> sessionTable = new ArrayList<>();
	private final List<HoReturn> returnTable = new ArrayList<>();
	private Pageable lastPageable;
	private ConsolidatedSalesService service;

	@BeforeEach
	void setUp() {
		HoTicketRepository tickets = stub(HoTicketRepository.class, (method, args) -> {
			switch (method) {
				case "search":
					return page(ticketTable, args, HoTicket::getStore, HoTicket::getSalesDate, HoTicket::getSalesNumber,
							HoTicket::getStatus, t -> args[5].equals("") || args[5].equals(t.getSessionNumber()),
							(Pageable) args[6]);
				case "findById":
					return ticketTable.stream().filter(t -> t.getId().equals(args[0])).findFirst();
				case "countLines":
					return ticketTable.stream().filter(t -> ((Collection<?>) args[0]).contains(t.getId()))
							.map(t -> new Object[] { t.getId(), (long) t.getLines().size() }).collect(Collectors.toList());
				case "countPayments":
					return ticketTable.stream().filter(t -> ((Collection<?>) args[0]).contains(t.getId()))
							.map(t -> new Object[] { t.getId(), (long) t.getPayments().size() })
							.collect(Collectors.toList());
				case "salesBySession":
					return grouped(ticketTable.stream()
							.filter(t -> ((Collection<?>) args[0]).contains(t.getStore().getId())
									&& ((Collection<?>) args[1]).contains(t.getSessionNumber())
									&& ((Collection<?>) args[2]).contains(t.getStatus()))
							.collect(Collectors.toList()), t -> t.getStore().getId() + "|" + t.getSessionNumber(),
							rows -> new Object[] { rows.get(0).getStore().getId(), rows.get(0).getSessionNumber(),
									(long) rows.size(), rows.stream().mapToDouble(HoTicket::getTotalAmount).sum() });
				case "paymentsBySession":
					return ticketTable.stream()
							.filter(t -> t.getStore().getId().equals(args[0]) && args[1].equals(t.getSessionNumber())
									&& ((Collection<?>) args[2]).contains(t.getStatus()))
							.flatMap(t -> t.getPayments().stream().map(p -> new Object[] { t.getId(),
									p.getPaymentMethodCode(), p.getPaymentMethodName(), p.getAmount() }))
							.collect(Collectors.toList());
				case "salesBetween":
					List<HoTicket> sold = ticketTable.stream().filter(t -> !t.getSalesDate().isBefore((LocalDateTime) args[0])
							&& t.getSalesDate().isBefore((LocalDateTime) args[1])
							&& ((Collection<?>) args[2]).contains(t.getStatus())).collect(Collectors.toList());
					return single(sold.size(), sold.isEmpty() ? null : sold.stream().mapToDouble(HoTicket::getTotalAmount).sum());
				case "findByStoreIdInAndSalesNumberIn":
					return ticketTable.stream().filter(t -> ((Collection<?>) args[0]).contains(t.getStore().getId())
							&& ((Collection<?>) args[1]).contains(t.getSalesNumber())).collect(Collectors.toList());
				case "countByStatus":
					return ticketTable.stream().filter(t -> t.getStatus().equals(args[0])).count();
				default:
					return UNHANDLED;
			}
		});
		HoSessionRepository sessions = stub(HoSessionRepository.class, (method, args) -> {
			switch (method) {
				case "search":
					return page(sessionTable, args, HoSession::getStore, HoSession::getOpenedAt,
							HoSession::getSessionNumber, HoSession::getStatus, s -> true, (Pageable) args[5]);
				case "findById":
					return sessionTable.stream().filter(s -> s.getId().equals(args[0])).findFirst();
				case "countByStatus":
					return sessionTable.stream().filter(s -> s.getStatus().equals(args[0])).count();
				default:
					return UNHANDLED;
			}
		});
		HoReturnRepository returns = stub(HoReturnRepository.class, (method, args) -> {
			switch (method) {
				case "search":
					return page(returnTable, args, HoReturn::getStore, HoReturn::getReturnDate, HoReturn::getReturnNumber,
							HoReturn::getStatus, r -> args[5].equals("") || args[5].equals(r.getSessionNumber()),
							(Pageable) args[6]);
				case "findById":
					return returnTable.stream().filter(r -> r.getId().equals(args[0])).findFirst();
				case "returnsBySession":
					return grouped(returnTable.stream()
							.filter(r -> ((Collection<?>) args[0]).contains(r.getStore().getId())
									&& ((Collection<?>) args[1]).contains(r.getSessionNumber())
									&& ((Collection<?>) args[2]).contains(r.getStatus()))
							.collect(Collectors.toList()),
							r -> r.getStore().getId() + "|" + r.getSessionNumber() + "|" + r.getReturnType(),
							rows -> new Object[] { rows.get(0).getStore().getId(), rows.get(0).getSessionNumber(),
									rows.get(0).getReturnType(), (long) rows.size(),
									rows.stream().mapToDouble(HoReturn::getTotalReturnAmount).sum() });
				case "returnsBetween":
					List<HoReturn> made = returnTable.stream().filter(r -> !r.getReturnDate().isBefore((LocalDateTime) args[0])
							&& r.getReturnDate().isBefore((LocalDateTime) args[1])
							&& ((Collection<?>) args[2]).contains(r.getStatus())).collect(Collectors.toList());
					return single(made.size(),
							made.isEmpty() ? null : made.stream().mapToDouble(HoReturn::getTotalReturnAmount).sum());
				default:
					return UNHANDLED;
			}
		});
		StoreRepository stores = stub(StoreRepository.class, (method, args) -> {
			if ("findAll".equals(method)) {
				List<Store> all = new ArrayList<>(Arrays.asList(rs02, rs01));
				if (args.length == 1) {
					assertEquals(Sort.by("code"), args[0]);
					all.sort(Comparator.comparing(Store::getCode));
				}
				return all;
			}
			return UNHANDLED;
		});
		// The head office's own payment methods, as ZZDataInitializer seeds them: the cash method is CLIENT_ESPECES
		PaymentMethodRepository paymentMethods = stub(PaymentMethodRepository.class, (method, args) -> {
			if ("findByType".equals(method)) {
				assertEquals(PaymentMethodType.CLIENT_ESPECES, args[0]);
				PaymentMethod cash = new PaymentMethod();
				cash.setCode("CLIENT_ESPECES");
				cash.setName("Client Espèce");
				cash.setType(PaymentMethodType.CLIENT_ESPECES);
				return Optional.of(cash);
			}
			return UNHANDLED;
		});
		service = new ConsolidatedSalesService(tickets, sessions, returns, stores, paymentMethods);
	}

	/** The JPQL search, in memory: store (0 = all), dates inclusive, lower-case LIKE on the number, status ("" = any). */
	private <E> PageImpl<E> page(List<E> table, Object[] args, Function<E, Store> store, Function<E, LocalDateTime> date,
			Function<E, String> number, Function<E, String> status, Predicate<E> more, Pageable pageable) {
		lastPageable = pageable;
		long storeId = (Long) args[0];
		String pattern = ((String) args[3]).replace("%", "");
		List<E> matching = table.stream()
				.filter(e -> storeId == 0 || store.apply(e).getId() == storeId)
				.filter(e -> !date.apply(e).isBefore((LocalDateTime) args[1]) && !date.apply(e).isAfter((LocalDateTime) args[2]))
				.filter(e -> number.apply(e).toLowerCase().contains(pattern))
				.filter(e -> args[4].equals("") || args[4].equals(status.apply(e)))
				.filter(more)
				.sorted(Comparator.comparing(date).reversed())
				.collect(Collectors.toList());
		int from = (int) Math.min(pageable.getOffset(), matching.size());
		int to = Math.min(from + pageable.getPageSize(), matching.size());
		return new PageImpl<>(matching.subList(from, to), pageable, matching.size());
	}

	private static <E> List<Object[]> grouped(List<E> rows, Function<E, String> key, Function<List<E>, Object[]> row) {
		Map<String, List<E>> groups = rows.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
		return groups.values().stream().map(row).collect(Collectors.toList());
	}

	private static List<Object[]> single(long count, Double sum) {
		List<Object[]> rows = new ArrayList<>();
		rows.add(new Object[] { count, sum });
		return rows;
	}

	// --- Data ---

	private static Store store(Long id, String code, String name) {
		Store store = new Store();
		store.setId(id);
		store.setCode(code);
		store.setName(name);
		return store;
	}

	private HoTicket ticket(Store store, String number, LocalDateTime date, String status, double total,
			String session) {
		HoTicket ticket = new HoTicket();
		ticket.setId((long) ticketTable.size() + 1);
		ticket.setStore(store);
		ticket.setSalesNumber(number);
		ticket.setSalesDate(date);
		ticket.setStatus(status);
		ticket.setTotalAmount(total);
		ticket.setSessionNumber(session);
		ticketTable.add(ticket);
		return ticket;
	}

	private HoSession session(Store store, String number, LocalDateTime openedAt, String status) {
		HoSession session = new HoSession();
		session.setId((long) sessionTable.size() + 1);
		session.setStore(store);
		session.setSessionNumber(number);
		session.setOpenedAt(openedAt);
		session.setStatus(status);
		sessionTable.add(session);
		return session;
	}

	private HoReturn returnCopy(Store store, String number, LocalDateTime date, String type, double total,
			String session, String original) {
		HoReturn row = new HoReturn();
		row.setId((long) returnTable.size() + 1);
		row.setStore(store);
		row.setReturnNumber(number);
		row.setReturnDate(date);
		row.setStatus("COMPLETED");
		row.setReturnType(type);
		row.setTotalReturnAmount(total);
		row.setSessionNumber(session);
		row.setOriginalSalesNumber(original);
		returnTable.add(row);
		return row;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> content(Map<String, Object> page) {
		return (List<Map<String, Object>>) page.get("content");
	}

	private static List<Object> column(Map<String, Object> page, String key) {
		return content(page).stream().map(row -> row.get(key)).collect(Collectors.toList());
	}

	private static HistoryQuery query(Long storeId, String from, String to, String number, String status) {
		return HistoryQuery.of(null, null, storeId, from, to, number, status, null);
	}

	// --- Filters and paging ---

	@Test
	@DisplayName("Tickets: filter by store, by dates (a day, or a time), by number and status; newest first; store code and name on every row")
	void ticketFilters() {
		ticket(rs01, "RS01-0001", LocalDateTime.of(2026, 9, 30, 23, 59, 59), "COMPLETED", 10, "S1");
		ticket(rs01, "RS01-0002", LocalDateTime.of(2026, 10, 1, 0, 0), "CANCELLED", 20, "S1");
		ticket(rs02, "RS02-0001", LocalDateTime.of(2026, 10, 1, 12, 0), "COMPLETED", 30, "S9");
		ticket(rs02, "RS02-0002", LocalDateTime.of(2026, 10, 2, 9, 0), "COMPLETED", 40, "S9");

		assertEquals(Arrays.asList("RS02-0002", "RS02-0001", "RS01-0002", "RS01-0001"),
				column(service.tickets(query(null, null, null, null, null)), "salesNumber"));
		assertEquals(Arrays.asList("RS01-0002", "RS01-0001"),
				column(service.tickets(query(1L, null, null, null, null)), "salesNumber"));
		assertEquals(Arrays.asList("RS02-0001", "RS01-0002"),
				column(service.tickets(query(null, "2026-10-01", "2026-10-01", null, null)), "salesNumber"),
				"a day is from 00:00 to the end of the day");
		assertEquals(Arrays.asList("RS02-0002", "RS02-0001"),
				column(service.tickets(query(null, "2026-10-01T12:00", null, null, null)), "salesNumber"));
		assertEquals(Arrays.asList("RS02-0002", "RS02-0001"),
				column(service.tickets(query(null, null, null, "rs02", null)), "salesNumber"), "number contains, any case");
		assertEquals(Arrays.asList("RS01-0002"),
				column(service.tickets(query(null, null, null, null, "cancelled")), "salesNumber"));
		assertEquals(4, content(service.tickets(query(null, null, null, null, " All "))).size(), "all = any status");
		assertEquals(Arrays.asList("RS02-0002", "RS02-0001"), column(service
				.tickets(HistoryQuery.of(null, null, 2L, null, null, null, null, "S9")), "salesNumber"), "session");
		assertTrue(content(service.tickets(query(99L, null, null, null, null))).isEmpty(), "unknown store: nothing");

		Map<String, Object> row = content(service.tickets(query(2L, null, null, null, null))).get(0);
		assertEquals(2L, row.get("storeId"));
		assertEquals("RS02", row.get("storeCode"));
		assertEquals("Store Sousse", row.get("storeName"));
		assertEquals(Sort.by(Sort.Order.desc("salesDate"), Sort.Order.desc("id")), lastPageable.getSort());
	}

	@Test
	@DisplayName("Paging: page from 0, size 10 by default and at most 200; totalElements, totalPages, number, size")
	void paging() {
		for (int i = 1; i <= 25; i++) {
			ticket(rs01, String.format("T-%02d", i), LocalDateTime.of(2026, 10, 1, 8, 0).plusMinutes(i), "COMPLETED", 1, null);
		}

		Map<String, Object> first = service.tickets(query(null, null, null, null, null));
		assertEquals(10, content(first).size());
		assertEquals(25L, first.get("totalElements"));
		assertEquals(3, first.get("totalPages"));
		assertEquals(0, first.get("number"));
		assertEquals(10, first.get("size"));
		assertEquals(Arrays.asList("content", "totalElements", "totalPages", "number", "size"),
				new ArrayList<>(first.keySet()));

		Map<String, Object> last = service.tickets(HistoryQuery.of(2, 10, null, null, null, null, null, null));
		assertEquals(Arrays.asList("T-05", "T-04", "T-03", "T-02", "T-01"), column(last, "salesNumber"));
		assertEquals(2, last.get("number"));

		assertEquals(200, HistoryQuery.of(0, 5000, null, null, null, null, null, null).getSize());
		assertEquals(10, HistoryQuery.of(0, 0, null, null, null, null, null, null).getSize());
		assertThrows(IllegalArgumentException.class, () -> HistoryQuery.of(-1, 10, null, null, null, null, null, null));
	}

	@Test
	@DisplayName("Query parsing: absent filters become bounds that are never null; a bad date is refused with its name")
	void parsing() {
		HistoryQuery none = query(null, " ", null, "", null);
		assertEquals(0L, none.getStoreId());
		assertEquals(ConsolidatedSalesService.NO_START, none.getDateFrom());
		assertEquals(ConsolidatedSalesService.NO_END, none.getDateTo());
		assertEquals("%", none.getNumber());
		assertEquals("", none.getStatus());
		assertEquals("", none.getSessionNumber());

		HistoryQuery day = query(3L, "2026-09-01", "2026-09-30", " Ab1 ", "completed");
		assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), day.getDateFrom());
		assertEquals(LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_999_900), day.getDateTo());
		assertEquals("%ab1%", day.getNumber());
		assertEquals("COMPLETED", day.getStatus());
		assertEquals(LocalDateTime.of(2026, 9, 30, 14, 30), query(null, null, "2026-09-30T14:30", null, null).getDateTo());

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> query(null, "30/09/2026", null, null, null));
		assertTrue(e.getMessage().startsWith("Invalid dateFrom '30/09/2026'"), e.getMessage());
	}

	// --- Rows and details ---

	@Test
	@DisplayName("Ticket row and detail: store field names (customer, createdByUser, cashierSession, item, paymentMethod), counts, lines in order")
	void ticketRowAndDetail() {
		HoTicket ticket = ticket(rs01, "RS01-0007", LocalDateTime.of(2026, 10, 1, 10, 0), "COMPLETED", 100, "S1");
		ticket.setCustomerCode("C00042");
		ticket.setCustomerName("Société Ben Ali");
		ticket.setCashierLogin("cashier1");
		ticket.setCashierName("Amira");
		ticket.setLoyaltyCardNumber("LYL-000017");
		ticket.setLoyaltyMemberName("Sami T");
		ticket.setPromotionCode("PROMO-10");
		for (int i = 1; i <= 2; i++) {
			HoTicketLine line = new HoTicketLine();
			line.setLineNo(i);
			line.setItemCode("ITM-" + i);
			line.setItemName("Item " + i);
			line.setQuantity(i);
			line.setPromotionCode(i == 1 ? "PROMO-SOAP" : null);
			ticket.getLines().add(line);
		}
		HoTicketPayment payment = new HoTicketPayment();
		payment.setLineNo(1);
		payment.setPaymentMethodCode("ESP");
		payment.setPaymentMethodName("Espèces");
		payment.setAmount(100.0);
		ticket.getPayments().add(payment);
		ticket(rs01, "RS01-0008", LocalDateTime.of(2026, 10, 1, 11, 0), "COMPLETED", 5, null);

		List<Map<String, Object>> rows = content(service.tickets(query(null, null, null, null, null)));
		Map<String, Object> row = rows.get(1);
		assertEquals("RS01-0007", row.get("salesNumber"));
		assertEquals(map("customerCode", "C00042", "name", "Société Ben Ali"), row.get("customer"));
		assertEquals(map("username", "cashier1", "fullName", "Amira"), row.get("createdByUser"));
		assertEquals(map("sessionNumber", "S1"), row.get("cashierSession"));
		assertEquals(2L, row.get("salesLinesCount"));
		assertEquals(1L, row.get("paymentsCount"));
		assertNull(rows.get(0).get("customer"), "walk-in: no customer object");
		assertNull(rows.get(0).get("cashierSession"));
		assertEquals(0L, rows.get(0).get("salesLinesCount"));

		Map<String, Object> detail = service.ticket(ticket.getId()).get();
		assertEquals("RS01", detail.get("storeCode"));
		assertEquals(map("cardNumber", "LYL-000017", "name", "Sami T"), detail.get("loyaltyMember"));
		assertEquals(map("code", "PROMO-10", "name", null), detail.get("promotion"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> lines = (List<Map<String, Object>>) detail.get("salesLines");
		assertEquals(Arrays.asList(1, 2), lines.stream().map(l -> l.get("lineNo")).collect(Collectors.toList()));
		assertEquals(map("itemCode", "ITM-1", "name", "Item 1"), lines.get(0).get("item"));
		assertEquals(map("code", "PROMO-SOAP"), lines.get(0).get("promotion"));
		assertNull(lines.get(1).get("promotion"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> payments = (List<Map<String, Object>>) detail.get("payments");
		assertEquals(map("code", "ESP", "name", "Espèces"), payments.get(0).get("paymentMethod"));
		assertEquals(100.0, payments.get(0).get("totalAmount"));
		assertFalse(service.ticket(999L).isPresent());
	}

	@Test
	@DisplayName("Session row: sales and returns of its own store and session (finished only), differences as on the store page; detail with count lines")
	void sessionTotals() {
		HoSession session = session(rs01, "S1", LocalDateTime.of(2026, 10, 1, 8, 0), "TERMINATED");
		session.setCashierLogin("cashier1");
		session.setOpeningCash(100.0);
		session.setPosUserClosureCash(260.0);
		session.setResponsibleClosureCash(250.0);
		HoSessionCount count = new HoSessionCount();
		count.setLineNo(1);
		count.setCounterType("POS_USER");
		count.setLineTotal(260.0);
		session.getCounts().add(count);
		session(rs02, "S1", LocalDateTime.of(2026, 10, 1, 9, 0), "CLOSED"); // same number, other store
		ticket(rs01, "T-1", LocalDateTime.of(2026, 10, 1, 9, 0), "COMPLETED", 120, "S1");
		ticket(rs01, "T-2", LocalDateTime.of(2026, 10, 1, 9, 5), "COMPLETED", 50, "S1");
		ticket(rs01, "T-3", LocalDateTime.of(2026, 10, 1, 9, 9), "CANCELLED", 999, "S1");
		ticket(rs02, "T-9", LocalDateTime.of(2026, 10, 1, 9, 9), "COMPLETED", 777, "S1");
		returnCopy(rs01, "R-1", LocalDateTime.of(2026, 10, 1, 10, 0), "SIMPLE_RETURN", 10, "S1", "T-1");
		returnCopy(rs01, "R-2", LocalDateTime.of(2026, 10, 1, 10, 5), "RETURN_VOUCHER", 15, "S1", "T-2");

		Map<String, Object> row = content(service.sessions(query(1L, null, null, null, null))).get(0);
		assertEquals("RS01", row.get("storeCode"));
		assertEquals("cashier1", row.get("cashierFullName"), "the login when there is no name, as on the store page");
		assertEquals(2L, row.get("salesCount"));
		assertEquals(170.0, row.get("totalSalesAmount"));
		assertEquals(2L, row.get("returnsCount"));
		assertEquals(25.0, row.get("totalReturnsAmount"));
		assertEquals(10.0, row.get("simpleReturnsAmount"));
		assertEquals(15.0, row.get("voucherReturnsAmount"));
		assertEquals(0.0, row.get("cashDifference"), "260 - (100 + 170 - 10)");
		assertEquals(-10.0, row.get("responsibleDifference"));

		Map<String, Object> other = content(service.sessions(query(2L, null, null, null, null))).get(0);
		assertEquals(777.0, other.get("totalSalesAmount"), "no mix between stores with the same session number");
		assertNull(other.get("cashDifference"), "no count, no difference");

		Map<String, Object> detail = service.session(session.getId()).get();
		assertEquals(170.0, detail.get("totalSalesAmount"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> counts = (List<Map<String, Object>>) detail.get("counts");
		assertEquals(1, counts.size());
		assertEquals("POS_USER", counts.get(0).get("counterType"));
		assertNull(counts.get(0).get("paymentMethod"), "cash");
	}

	// --- Session details: payment summary (the store's session details block) ---

	private static void pay(HoTicket ticket, String code, String name, double amount) {
		HoTicketPayment payment = new HoTicketPayment();
		payment.setLineNo(ticket.getPayments().size() + 1);
		payment.setPaymentMethodCode(code);
		payment.setPaymentMethodName(name);
		payment.setAmount(amount);
		ticket.getPayments().add(payment);
	}

	/** A count line; code null = a cash denomination line, as the store sends it. */
	private static void count(HoSession session, String counterType, String code, String name, double total) {
		HoSessionCount count = new HoSessionCount();
		count.setLineNo(session.getCounts().size() + 1);
		count.setCounterType(counterType);
		count.setPaymentMethodCode(code);
		count.setPaymentMethodName(name);
		count.setLineTotal(total);
		session.getCounts().add(count);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Map<String, Object>> summary(HoSession session) {
		List<Map<String, Object>> rows = (List<Map<String, Object>>) service.session(session.getId()).get()
				.get("paymentSummary");
		Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
		rows.forEach(row -> byCode.put((String) row.get("code"), row));
		return byCode;
	}

	@Test
	@DisplayName("Payment summary, the real case of SESSION261005001 at HS01: two cash tickets, the cashier counted 833 for 843 expected")
	void paymentSummaryRealCase() {
		Store hs01 = store(1L, "HS01", "Franchise 1");
		HoSession session = session(hs01, "SESSION261005001", LocalDateTime.of(2026, 10, 5, 9, 0), "CLOSED");
		session.setOpeningCash(10.0);
		session.setRealCash(843.0); // the store's expected cash: 10 + 595 + 238
		session.setPosUserClosureCash(833.0);
		pay(ticket(hs01, "T-1", LocalDateTime.of(2026, 10, 5, 9, 10), "COMPLETED", 595, "SESSION261005001"),
				"CLIENT_ESPECES", "Client Espèce", 595);
		pay(ticket(hs01, "T-2", LocalDateTime.of(2026, 10, 5, 9, 20), "COMPLETED", 238, "SESSION261005001"),
				"CLIENT_ESPECES", "Client Espèce", 238);
		count(session, "POS_USER", null, null, 833); // the cashier's cash count: denomination lines, no method code

		Map<String, Map<String, Object>> rows = summary(session);
		assertEquals(Arrays.asList("CLIENT_ESPECES"), new ArrayList<>(rows.keySet()), "one row: cash");
		Map<String, Object> cash = rows.get("CLIENT_ESPECES");
		assertEquals("Client Espèce", cash.get("name"));
		assertEquals(2L, cash.get("ticketCount"));
		assertEquals(843.0, cash.get("systemAmount"));
		assertEquals(true, cash.get("isEspece"));
		assertEquals(833.0, cash.get("posClosureAmount"));
		assertEquals(-10.0, cash.get("deltaPOS"));
		assertNull(cash.get("respClosureAmount"));
		assertNull(cash.get("deltaResp"));
		assertEquals(false, cash.get("countOnly"));
		assertEquals(false, cash.get("systemOnly"));
		assertEquals(false, service.session(session.getId()).get().get("hasPerMethodRespClosure"));
	}

	@Test
	@DisplayName("Payment summary: cash and other methods, the cashier's count then the responsible's; a count line without a method is cash; same row names as the store")
	void paymentSummary() {
		HoSession session = session(rs01, "S5", LocalDateTime.of(2026, 10, 1, 8, 0), "CLOSED");
		session.setOpeningCash(10.0);
		session.setRealCash(130.0); // 10 + 100 + 20 in cash
		HoTicket t1 = ticket(rs01, "T-1", LocalDateTime.of(2026, 10, 1, 9, 0), "COMPLETED", 115, "S5");
		pay(t1, "CLIENT_ESPECES", "Client Espèce", 100);
		pay(t1, "TICKET_RESTAURANT", "Ticket restaurant", 15);
		HoTicket t2 = ticket(rs01, "T-2", LocalDateTime.of(2026, 10, 1, 9, 5), "COMPLETED", 70, "S5");
		pay(t2, "CLIENT_TPE", "Client TPE", 50);
		pay(t2, "CLIENT_ESPECES", "Client Espèce", 20);
		pay(ticket(rs01, "T-3", LocalDateTime.of(2026, 10, 1, 9, 9), "CANCELLED", 999, "S5"), "CLIENT_TPE", "Client TPE", 999);
		pay(ticket(rs02, "T-9", LocalDateTime.of(2026, 10, 1, 9, 9), "COMPLETED", 777, "S5"), "CLIENT_TPE", "Client TPE", 777);
		// The cashier's count only: cash in two denomination lines (no method), the card terminal, a cheque never paid
		count(session, "POS_USER", null, null, 100);
		count(session, "POS_USER", null, null, 30);
		count(session, "POS_USER", "CLIENT_TPE", "Client TPE", 45);
		count(session, "POS_USER", "CLIENT_CHEQUE", "Client Chèque", 12);

		Map<String, Map<String, Object>> rows = summary(session);
		assertEquals(Arrays.asList("CLIENT_ESPECES", "TICKET_RESTAURANT", "CLIENT_TPE", "CLIENT_CHEQUE"),
				new ArrayList<>(rows.keySet()), "the union of the paid and the counted methods");
		assertEquals(Arrays.asList("code", "name", "ticketCount", "systemAmount", "isEspece", "posClosureAmount", "deltaPOS",
				"respClosureAmount", "deltaResp", "countOnly", "systemOnly"), new ArrayList<>(rows.get("CLIENT_TPE").keySet()),
				"the store's names, without syncStatus and erpNo");

		Map<String, Object> cash = rows.get("CLIENT_ESPECES");
		assertEquals(2L, cash.get("ticketCount"), "distinct tickets");
		assertEquals(130.0, cash.get("systemAmount"), "cash: the session's realCash, not the sum of the payments");
		assertEquals(130.0, cash.get("posClosureAmount"), "the two lines without a method");
		assertEquals(0.0, cash.get("deltaPOS"));
		assertNull(cash.get("respClosureAmount"));

		Map<String, Object> card = rows.get("CLIENT_TPE");
		assertEquals(false, card.get("isEspece"));
		assertEquals(1L, card.get("ticketCount"), "the cancelled ticket and the other store's ticket do not count");
		assertEquals(50.0, card.get("systemAmount"));
		assertEquals(-5.0, card.get("deltaPOS"));
		assertNull(card.get("deltaResp"));

		Map<String, Object> meal = rows.get("TICKET_RESTAURANT");
		assertEquals(true, meal.get("systemOnly"));
		assertEquals(false, meal.get("countOnly"));
		assertNull(meal.get("posClosureAmount"));
		assertNull(meal.get("deltaPOS"));

		Map<String, Object> cheque = rows.get("CLIENT_CHEQUE");
		assertEquals("Client Chèque", cheque.get("name"));
		assertEquals(0L, cheque.get("ticketCount"));
		assertEquals(0.0, cheque.get("systemAmount"));
		assertEquals(true, cheque.get("countOnly"));
		assertEquals(false, cheque.get("systemOnly"));
		assertEquals(false, service.session(session.getId()).get().get("hasPerMethodRespClosure"));

		// Then the responsible's count: cash without a method, the card terminal
		count(session, "RESPONSIBLE", null, null, 125);
		count(session, "RESPONSIBLE", "CLIENT_TPE", "Client TPE", 50);
		rows = summary(session);
		assertEquals(125.0, rows.get("CLIENT_ESPECES").get("respClosureAmount"));
		assertEquals(-5.0, rows.get("CLIENT_ESPECES").get("deltaResp"));
		assertEquals(50.0, rows.get("CLIENT_TPE").get("respClosureAmount"));
		assertEquals(0.0, rows.get("CLIENT_TPE").get("deltaResp"));
		assertNull(rows.get("CLIENT_CHEQUE").get("respClosureAmount"));
		assertEquals(true, service.session(session.getId()).get().get("hasPerMethodRespClosure"));

		Map<String, Object> detail = service.session(session.getId()).get();
		assertEquals(6, ((List<?>) detail.get("counts")).size(), "the raw count lines are still returned");
		assertEquals(185.0, detail.get("totalSalesAmount"));
	}

	@Test
	@DisplayName("Return row and detail: the returned ticket found at the head office (same store), voucher, lines")
	void returnRowAndDetail() {
		HoTicket original = ticket(rs01, "T-1", LocalDateTime.of(2026, 9, 30, 9, 0), "COMPLETED", 40, "S1");
		ticket(rs02, "T-1", LocalDateTime.of(2026, 9, 30, 9, 0), "COMPLETED", 999, "S7");
		HoReturn header = returnCopy(rs01, "R-1", LocalDateTime.of(2026, 10, 1, 10, 0), "RETURN_VOUCHER", 15, "S2", "T-1");
		header.setVoucherNumber("BON-0009");
		header.setVoucherAmount(15.0);
		HoReturnLine line = new HoReturnLine();
		line.setLineNo(1);
		line.setItemCode("ITM-1");
		line.setItemName("Soap");
		line.setQuantity(1);
		header.getLines().add(line);
		returnCopy(rs01, "R-2", LocalDateTime.of(2026, 10, 1, 11, 0), "SIMPLE_RETURN", 5, "S2", "T-404");

		List<Map<String, Object>> rows = content(service.returns(query(1L, null, null, null, null)));
		Map<String, Object> row = rows.get(1);
		@SuppressWarnings("unchecked")
		Map<String, Object> ticketRef = (Map<String, Object>) row.get("originalSalesHeader");
		assertEquals(original.getId(), ticketRef.get("id"));
		assertEquals(40.0, ticketRef.get("totalAmount"), "the ticket of the same store");
		assertEquals("BON-0009", ((Map<?, ?>) row.get("returnVoucher")).get("voucherNumber"));
		@SuppressWarnings("unchecked")
		Map<String, Object> missing = (Map<String, Object>) rows.get(0).get("originalSalesHeader");
		assertEquals("T-404", missing.get("salesNumber"));
		assertNull(missing.get("id"), "not received at the head office");
		assertNull(rows.get(0).get("returnVoucher"));

		Map<String, Object> detail = service.returnDetail(header.getId()).get();
		assertEquals("R-1", ((Map<?, ?>) detail.get("returnHeader")).get("returnNumber"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> lines = (List<Map<String, Object>>) detail.get("returnLines");
		assertEquals(map("itemCode", "ITM-1", "name", "Soap"), lines.get(0).get("item"));
		assertFalse(service.returnDetail(42L).isPresent());
	}

	// --- Store filter and home ---

	@Test
	@DisplayName("Store options: id, code, name, active, by code")
	void storeOptions() {
		List<Map<String, Object>> options = service.storeOptions();
		assertEquals(Arrays.asList("RS01", "RS02"), options.stream().map(o -> o.get("code")).collect(Collectors.toList()));
		assertEquals(Arrays.asList("id", "code", "name", "active", "ownership"), new ArrayList<>(options.get(0).keySet()),
				"task 3.6: ownership last (null while the store has reported nothing)");
	}

	@Test
	@DisplayName("Home cards: all stores, today only; only finished tickets count; completed returns of today; zeros when empty")
	void dashboard() {
		DashboardTodayDTO empty = service.dashboardToday(TODAY);
		assertEquals(0L, empty.getTodaySalesCount());
		assertEquals(0.0, empty.getTodaySalesAmount());

		ticket(rs01, "T-1", TODAY.atTime(0, 0), "COMPLETED", 10, null);
		ticket(rs02, "T-2", TODAY.atTime(23, 59, 59), "COMPLETED", 20.5, null);
		ticket(rs02, "T-3", TODAY.atTime(12, 0), "REFUNDED", 4, null);
		ticket(rs01, "T-4", TODAY.atTime(13, 0), "CANCELLED", 1000, null);
		ticket(rs01, "T-5", TODAY.minusDays(1).atTime(23, 59), "COMPLETED", 1000, null);
		ticket(rs01, "T-6", TODAY.plusDays(1).atStartOfDay(), "COMPLETED", 1000, null);
		returnCopy(rs01, "R-1", TODAY.atTime(15, 0), "SIMPLE_RETURN", 3, null, "T-1");
		returnCopy(rs02, "R-2", TODAY.minusDays(1).atTime(15, 0), "SIMPLE_RETURN", 99, null, "T-2");

		DashboardTodayDTO cards = service.dashboardToday(TODAY);
		assertEquals(3L, cards.getTodaySalesCount());
		assertEquals(34.5, cards.getTodaySalesAmount());
		assertEquals(1L, cards.getTodayReturnsCount());
		assertEquals(3.0, cards.getTodayReturnsAmount());
		assertEquals(0L, cards.getOpenSessionsCount(), "sessions arrive once closed");
		assertEquals(0L, cards.getPendingTicketsCount(), "parked tickets are never sent");
	}

	// --- Stubs ---

	private static Map<String, Object> map(Object... keysAndValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}

	private interface Handler {
		Object handle(String method, Object[] args);
	}

	private static final Object UNHANDLED = new Object();

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				case "toString": return type.getSimpleName() + "Stub";
				default: break;
			}
			Object result = handler.handle(method.getName(), args == null ? new Object[0] : args);
			if (result == UNHANDLED) {
				throw new UnsupportedOperationException("Unexpected call: " + type.getSimpleName() + "." + method.getName());
			}
			return result;
		});
	}
}
