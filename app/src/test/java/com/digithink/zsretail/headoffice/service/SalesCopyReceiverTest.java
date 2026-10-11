package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.dto.PaymentCopyDTO;
import com.digithink.zsretail.headoffice.dto.ReturnCopyDTO;
import com.digithink.zsretail.headoffice.dto.ReturnLineCopyDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.headoffice.dto.SessionCopyDTO;
import com.digithink.zsretail.headoffice.dto.SessionCountCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketLineCopyDTO;
import com.digithink.zsretail.headoffice.model.HoReturn;
import com.digithink.zsretail.headoffice.model.HoSession;
import com.digithink.zsretail.headoffice.model.HoTicket;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoReturnRepository;
import com.digithink.zsretail.headoffice.repository.HoSessionRepository;
import com.digithink.zsretail.headoffice.repository.HoTicketRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.model._BaseEntity;

/**
 * Head office plan, task 2.3: the copies are saved by store + document number. A repeated push creates one row; a
 * changed document is replaced, lines and payments included, not duplicated; two stores with the same document number
 * do not collide; each document has its own transaction and result, so one bad document does not block the batch.
 * Plain JUnit with in-memory repositories (no Spring context).
 */
class SalesCopyReceiverTest {

	private static final LocalDateTime SOLD = LocalDateTime.of(2026, 10, 3, 10, 15);

	private final Map<Long, HoTicket> ticketTable = new LinkedHashMap<>();
	private final Map<Long, HoReturn> returnTable = new LinkedHashMap<>();
	private final Map<Long, HoSession> sessionTable = new LinkedHashMap<>();
	private final List<String> failingNumbers = new ArrayList<>();
	private int transactions;
	private SalesCopyReceiver receiver;

	private final Store rs01 = store(1L, "RS01");
	private final Store rs02 = store(2L, "RS02");

	@BeforeEach
	void setUp() {
		HoTicketRepository tickets = stub(HoTicketRepository.class, (method, args) -> {
			switch (method) {
				case "findByStoreIdAndSalesNumber":
					return find(ticketTable, args, HoTicket::getStore, HoTicket::getSalesNumber);
				case "save":
					return save(ticketTable, (HoTicket) args[0], ((HoTicket) args[0]).getSalesNumber());
				default:
					return UNHANDLED;
			}
		});
		HoReturnRepository returns = stub(HoReturnRepository.class, (method, args) -> {
			switch (method) {
				case "findByStoreIdAndReturnNumber":
					return find(returnTable, args, HoReturn::getStore, HoReturn::getReturnNumber);
				case "save":
					return save(returnTable, (HoReturn) args[0], ((HoReturn) args[0]).getReturnNumber());
				default:
					return UNHANDLED;
			}
		});
		HoSessionRepository sessions = stub(HoSessionRepository.class, (method, args) -> {
			switch (method) {
				case "findByStoreIdAndSessionNumber":
					return find(sessionTable, args, HoSession::getStore, HoSession::getSessionNumber);
				case "save":
					return save(sessionTable, (HoSession) args[0], ((HoSession) args[0]).getSessionNumber());
				default:
					return UNHANDLED;
			}
		});
		// getOne only: the principal is never saved through the store repository
		StoreRepository stores = stub(StoreRepository.class, (method, args) -> {
			if ("getOne".equals(method)) {
				return store((Long) args[0], null);
			}
			return UNHANDLED;
		});
		TransactionOperations counting = new TransactionOperations() {
			@Override
			public <T> T execute(TransactionCallback<T> action) {
				transactions++;
				return action.doInTransaction(new SimpleTransactionStatus());
			}
		};
		receiver = new SalesCopyReceiver(tickets, returns, sessions, stores, counting);
	}

	private static Store store(Long id, String code) {
		Store store = new Store();
		store.setId(id);
		store.setCode(code);
		store.setName(code);
		return store;
	}

	private static <E> Optional<E> find(Map<Long, E> table, Object[] args, Function<E, Store> store,
			Function<E, String> number) {
		return table.values().stream()
				.filter(row -> Objects.equals(store.apply(row).getId(), args[0]) && number.apply(row).equals(args[1]))
				.findFirst();
	}

	private <E extends _BaseEntity> E save(Map<Long, E> table, E row, String number) {
		if (failingNumbers.contains(number)) {
			throw new DataIntegrityViolationException("could not execute statement",
					new SQLException("String or binary data would be truncated."));
		}
		if (row.getId() == null) {
			row.setId((long) table.size() + 1);
		}
		table.put(row.getId(), row);
		return row;
	}

	// --- Copies ---

	private static TicketCopyDTO ticket(String number, double total, String... itemCodes) {
		TicketCopyDTO copy = new TicketCopyDTO();
		copy.setSalesNumber(number);
		copy.setSalesDate(SOLD);
		copy.setStatus("COMPLETED");
		copy.setTotalAmount(total);
		copy.setCashierLogin("cashier1");
		for (String code : itemCodes) {
			TicketLineCopyDTO line = new TicketLineCopyDTO();
			line.setLineNo(copy.getLines().size() + 1);
			line.setItemCode(code);
			line.setItemName("Item " + code);
			line.setQuantity(BigDecimal.ONE);
			copy.getLines().add(line);
		}
		copy.getPayments().add(payment("ESP", total));
		return copy;
	}

	private static PaymentCopyDTO payment(String code, double amount) {
		PaymentCopyDTO payment = new PaymentCopyDTO();
		payment.setPaymentMethodCode(code);
		payment.setAmount(amount);
		return payment;
	}

	private List<HoTicket> ticketsOf(Store store) {
		return ticketTable.values().stream().filter(t -> t.getStore().getId().equals(store.getId()))
				.collect(Collectors.toList());
	}

	private static void assertAllAccepted(List<SalesCopyResultDTO> results, String... numbers) {
		assertEquals(numbers.length, results.size());
		for (int i = 0; i < numbers.length; i++) {
			assertEquals(SalesCopyResultDTO.accepted(numbers[i]), results.get(i));
		}
	}

	// --- Tickets ---

	@Test
	@DisplayName("A repeated push creates one row, with its lines and payments once")
	void repeatedPushCreatesOneRow() {
		assertAllAccepted(receiver.receiveTickets(rs01, Arrays.asList(ticket("T-1", 30.0, "A", "B"))), "T-1");
		assertAllAccepted(receiver.receiveTickets(rs01, Arrays.asList(ticket("T-1", 30.0, "A", "B"))), "T-1");

		assertEquals(1, ticketTable.size());
		HoTicket row = ticketTable.values().iterator().next();
		assertEquals(2, row.getLines().size());
		assertEquals(1, row.getPayments().size());
	}

	@Test
	@DisplayName("A changed document replaces the content of its row: header, lines and payments; same row")
	void changedDocumentReplaced() {
		receiver.receiveTickets(rs01, Arrays.asList(ticket("T-1", 30.0, "A", "B")));
		HoTicket first = ticketTable.values().iterator().next();
		Long id = first.getId();
		LocalDateTime createdAt = first.getCreatedAt();

		TicketCopyDTO changed = ticket("T-1", 60.0, "C");
		changed.setStatus("CANCELLED");
		changed.getLines().get(0).setQuantity(BigDecimal.valueOf(3));
		changed.getPayments().add(payment("CHQ", 10.0));
		assertAllAccepted(receiver.receiveTickets(rs01, Arrays.asList(changed)), "T-1");

		assertEquals(1, ticketTable.size());
		HoTicket row = ticketTable.get(id);
		assertSame(first, row, "the same row");
		assertEquals("CANCELLED", row.getStatus());
		assertEquals(60.0, row.getTotalAmount());
		assertEquals(1, row.getLines().size(), "old lines removed");
		assertEquals("C", row.getLines().get(0).getItemCode());
		assertEquals(BigDecimal.valueOf(3), row.getLines().get(0).getQuantity());
		assertEquals(1, row.getLines().get(0).getLineNo());
		assertSame(row, row.getLines().get(0).getTicket());
		assertEquals(2, row.getPayments().size());
		assertEquals(Arrays.asList(1, 2), row.getPayments().stream().map(p -> p.getLineNo()).collect(Collectors.toList()));
		assertEquals("CHQ", row.getPayments().get(1).getPaymentMethodCode());
		assertEquals(createdAt, row.getCreatedAt(), "first reception kept");
		assertFalse(row.getUpdatedAt().isBefore(createdAt));
	}

	@Test
	@DisplayName("Two stores with the same sales number do not collide; a push replaces only the sender's row")
	void twoStoresSameNumber() {
		receiver.receiveTickets(rs01, Arrays.asList(ticket("T-1", 10.0, "A")));
		receiver.receiveTickets(rs02, Arrays.asList(ticket("T-1", 20.0, "B")));
		receiver.receiveTickets(rs01, Arrays.asList(ticket("T-1", 11.0, "A")));

		assertEquals(2, ticketTable.size());
		assertEquals(1, ticketsOf(rs01).size());
		assertEquals(11.0, ticketsOf(rs01).get(0).getTotalAmount());
		assertEquals(1, ticketsOf(rs02).size());
		assertEquals(20.0, ticketsOf(rs02).get(0).getTotalAmount());
		assertEquals("B", ticketsOf(rs02).get(0).getLines().get(0).getItemCode());
	}

	@Test
	@DisplayName("One bad document does not block the batch: one result per document in order, the good ones saved")
	void badDocumentDoesNotBlockBatch() {
		TicketCopyDTO noNumber = ticket(" ", 1.0, "A");
		TicketCopyDTO noItem = ticket("T-4", 1.0, "A", "");
		TicketCopyDTO noMethod = ticket("T-5", 1.0, "A");
		noMethod.getPayments().get(0).setPaymentMethodCode(null);
		TicketCopyDTO noDate = ticket("T-6", 1.0);
		noDate.setSalesDate(null);

		List<SalesCopyResultDTO> results = receiver.receiveTickets(rs01, Arrays.asList(ticket("T-1", 1.0, "A"),
				noNumber, ticket("T-3", 1.0, "A"), noItem, null, noMethod, noDate));

		assertEquals(Arrays.asList(SalesCopyResultDTO.accepted("T-1"),
				SalesCopyResultDTO.rejected(" ", "salesNumber is required"),
				SalesCopyResultDTO.accepted("T-3"),
				SalesCopyResultDTO.rejected("T-4", "line 2: itemCode is required"),
				SalesCopyResultDTO.rejected(null, "empty document"),
				SalesCopyResultDTO.rejected("T-5", "payment 1: paymentMethodCode is required"),
				SalesCopyResultDTO.rejected("T-6", "salesDate is required")), results);
		assertEquals(Arrays.asList("T-1", "T-3"),
				ticketTable.values().stream().map(HoTicket::getSalesNumber).collect(Collectors.toList()));
		assertEquals(6, transactions, "one transaction per document (none for the empty one)");
	}

	@Test
	@DisplayName("A document refused by the database is rejected with the cause; the next ones are saved")
	void databaseFailure() {
		failingNumbers.add("T-2");

		List<SalesCopyResultDTO> results = receiver.receiveTickets(rs01,
				Arrays.asList(ticket("T-1", 1.0, "A"), ticket("T-2", 1.0, "A"), ticket("T-3", 1.0, "A")));

		assertTrue(results.get(0).isAccepted());
		assertEquals(SalesCopyResultDTO.rejected("T-2", "SQLException: String or binary data would be truncated."),
				results.get(1));
		assertTrue(results.get(2).isAccepted());
		assertEquals(2, ticketTable.size());
	}

	@Test
	@DisplayName("The row belongs to the authenticated store (by id); the principal is never saved; empty batch")
	void storeFromPrincipal() {
		receiver.receiveTickets(rs02, Arrays.asList(ticket("T-9", 5.0, "A")));

		HoTicket row = ticketTable.values().iterator().next();
		assertEquals(2L, row.getStore().getId());
		assertNotSameObject(rs02, row.getStore());
		assertTrue(receiver.receiveTickets(rs01, Collections.emptyList()).isEmpty());
		assertTrue(receiver.receiveTickets(rs01, null).isEmpty());
	}

	private static void assertNotSameObject(Object principal, Object reference) {
		assertFalse(principal == reference, "a reference by id, not the principal");
	}

	@Test
	@DisplayName("A long reason is cut to 500 characters")
	void longReason() {
		StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 70; i++) {
			longText.append("truncated ");
		}
		String reason = SalesCopyReceiver.reason(new IllegalStateException("x", new SQLException(longText.toString())));
		assertEquals(500, reason.length());
		assertTrue(reason.startsWith("SQLException: truncated"));
	}

	// --- Returns and sessions ---

	private static ReturnCopyDTO returnCopy(String number, String... itemCodes) {
		ReturnCopyDTO copy = new ReturnCopyDTO();
		copy.setReturnNumber(number);
		copy.setReturnDate(SOLD);
		copy.setStatus("COMPLETED");
		copy.setReturnType("SIMPLE_RETURN");
		copy.setOriginalSalesNumber("T-1");
		for (String code : itemCodes) {
			ReturnLineCopyDTO line = new ReturnLineCopyDTO();
			line.setItemCode(code);
			line.setQuantity(java.math.BigDecimal.ONE);
			copy.getLines().add(line);
		}
		return copy;
	}

	@Test
	@DisplayName("Returns: a repeat creates one row, a change replaces the lines, two stores do not collide, a bad one is rejected")
	void returns() {
		assertAllAccepted(receiver.receiveReturns(rs01, Arrays.asList(returnCopy("R-1", "A", "B"))), "R-1");
		assertAllAccepted(receiver.receiveReturns(rs01, Arrays.asList(returnCopy("R-1", "C"))), "R-1");
		assertAllAccepted(receiver.receiveReturns(rs02, Arrays.asList(returnCopy("R-1", "D"))), "R-1");

		assertEquals(2, returnTable.size());
		HoReturn row = returnTable.get(1L);
		assertEquals(1L, row.getStore().getId());
		assertEquals(1, row.getLines().size());
		assertEquals("C", row.getLines().get(0).getItemCode());
		assertSame(row, row.getLines().get(0).getReturnCopy());
		assertEquals("T-1", row.getOriginalSalesNumber());

		List<SalesCopyResultDTO> bad = receiver.receiveReturns(rs01, Arrays.asList(returnCopy(null, "A")));
		assertEquals(SalesCopyResultDTO.rejected(null, "returnNumber is required"), bad.get(0));
	}

	private static SessionCopyDTO session(String number, String status, double... countTotals) {
		SessionCopyDTO copy = new SessionCopyDTO();
		copy.setSessionNumber(number);
		copy.setStatus(status);
		copy.setOpenedAt(SOLD.minusHours(8));
		copy.setClosedAt(SOLD);
		for (double total : countTotals) {
			SessionCountCopyDTO count = new SessionCountCopyDTO();
			count.setCounterType("POS_USER");
			count.setLineTotal(total);
			copy.getCounts().add(count);
		}
		return copy;
	}

	@Test
	@DisplayName("Sessions: the TERMINATED copy replaces the CLOSED one, counts included; two stores do not collide")
	void sessions() {
		receiver.receiveSessions(rs01, Arrays.asList(session("S-1", "CLOSED", 100.0)));
		SessionCopyDTO verified = session("S-1", "TERMINATED", 100.0, 50.0);
		verified.setVerifiedByLogin("manager");
		assertAllAccepted(receiver.receiveSessions(rs01, Arrays.asList(verified)), "S-1");
		receiver.receiveSessions(rs02, Arrays.asList(session("S-1", "CLOSED")));

		assertEquals(2, sessionTable.size());
		HoSession row = sessionTable.get(1L);
		assertEquals("TERMINATED", row.getStatus());
		assertEquals("manager", row.getVerifiedByLogin());
		assertEquals(2, row.getCounts().size());
		assertEquals(Integer.valueOf(2), row.getCounts().get(1).getLineNo());
		assertSame(row, row.getCounts().get(0).getSession());
		assertNull(sessionTable.get(2L).getVerifiedByLogin());

		SessionCopyDTO noOpening = session("S-2", "CLOSED");
		noOpening.setOpenedAt(null);
		assertEquals(SalesCopyResultDTO.rejected("S-2", "openedAt is required"),
				receiver.receiveSessions(rs01, Arrays.asList(noOpening)).get(0));
	}

	// --- Stubs ---

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

	// --- 2.2.1: decimal quantities ---

	/** A store sales line as read from its DECIMAL(18,3) column, with the amounts the store computed. */
	private static com.digithink.zsretail.model.SalesLine storeLine(long id, String code, String quantity, double ttc) {
		com.digithink.zsretail.model.Item item = new com.digithink.zsretail.model.Item();
		item.setId(id + 100);
		item.setItemCode(code);
		item.setName("Bulk " + code);
		com.digithink.zsretail.model.SalesLine line = new com.digithink.zsretail.model.SalesLine();
		line.setId(id);
		line.setItem(item);
		line.setQuantity(new BigDecimal(quantity));
		line.setUnitPrice(50.0 / 1.19);
		line.setUnitPriceIncludingVat(50.0);
		line.setVatPercent(19);
		line.setLineTotal(ttc / 1.19);
		line.setVatAmount(ttc - ttc / 1.19);
		line.setLineTotalIncludingVat(ttc);
		return line;
	}

	@Test
	@DisplayName("2.2.1: a ticket with 0.2 and 0.058 arrives at the head office with the same quantities and amounts")
	void decimalTicketArrivesAsSent() throws Exception {
		com.digithink.zsretail.model.SalesHeader header = new com.digithink.zsretail.model.SalesHeader();
		header.setId(77L);
		header.setSalesNumber("T-BULK-1");
		header.setSalesDate(SOLD);
		header.setStatus(com.digithink.zsretail.model.enumeration.TransactionStatus.COMPLETED);
		header.setTotalAmount(12.9);
		com.digithink.zsretail.model.SalesLine twoDecilitres = storeLine(1L, "VH52-1L", "0.200", 10.0);
		com.digithink.zsretail.model.SalesLine fiftyEight = storeLine(2L, "VH52-1L", "0.058", 2.9);
		TicketCopyDTO sentCopy = com.digithink.zsretail.holink.service.SalesCopyMapper.ticket(header,
				Arrays.asList(twoDecilitres, fiftyEight), Collections.emptyList());

		// Over the wire, read back as the head office's Spring mapper does
		com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper()
				.findAndRegisterModules()
				.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
				.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		String wire = json.writeValueAsString(sentCopy);
		assertTrue(wire.contains("\"quantity\":0.2,"), wire);
		assertTrue(wire.contains("\"quantity\":0.058,"), wire);
		TicketCopyDTO received = json.readValue(wire, TicketCopyDTO.class);

		assertAllAccepted(receiver.receiveTickets(rs01, Arrays.asList(received)), "T-BULK-1");
		HoTicket row = ticketsOf(rs01).get(0);
		assertEquals(2, row.getLines().size());
		com.digithink.zsretail.model.SalesLine[] sent = { twoDecilitres, fiftyEight };
		for (int i = 0; i < 2; i++) {
			com.digithink.zsretail.headoffice.model.HoTicketLine line = row.getLines().get(i);
			assertEquals(0, sent[i].getQuantity().compareTo(line.getQuantity()), "quantity " + i);
			assertEquals(sent[i].getQuantity().toPlainString(), line.getQuantity().toPlainString());
			assertEquals(sent[i].getLineTotal(), line.getLineTotal(), 0.0, "stored as sent, not recomputed");
			assertEquals(sent[i].getLineTotalIncludingVat(), line.getLineTotalIncludingVat(), 0.0);
			assertEquals(sent[i].getVatAmount(), line.getVatAmount(), 0.0);
			assertEquals(sent[i].getUnitPrice(), line.getUnitPrice(), 0.0);
		}
		assertEquals("0.2", row.getLines().get(0).getQuantity().toPlainString());
		assertEquals("0.058", row.getLines().get(1).getQuantity().toPlainString());
	}

	@Test
	@DisplayName("2.2.2: a return of 0.2 and 1.5 arrives at the head office with the same quantities and amounts; 1 stays 1")
	void decimalReturnArrivesAsSent() throws Exception {
		com.digithink.zsretail.model.SalesHeader original = new com.digithink.zsretail.model.SalesHeader();
		original.setSalesNumber("T-BULK-1");
		com.digithink.zsretail.model.ReturnHeader header = new com.digithink.zsretail.model.ReturnHeader();
		header.setId(78L);
		header.setReturnNumber("RET-BULK-1");
		header.setReturnDate(SOLD);
		header.setStatus(com.digithink.zsretail.model.enumeration.TransactionStatus.COMPLETED);
		header.setReturnType(com.digithink.zsretail.model.enumeration.ReturnType.RETURN_VOUCHER);
		header.setOriginalSalesHeader(original);
		header.setTotalReturnAmount(43.192);
		String[][] lines = { { "VH52-1L", "0.200", "3.192" }, { "ITM-2", "1.500", "30.0" }, { "ITM-3", "1.000", "10.0" } };
		List<com.digithink.zsretail.model.ReturnLine> storeLines = new java.util.ArrayList<>();
		for (int i = 0; i < lines.length; i++) {
			com.digithink.zsretail.model.ReturnLine line = new com.digithink.zsretail.model.ReturnLine();
			line.setId(100L + i);
			line.setItem(storeLine(100L + i, lines[i][0], "1", 1.0).getItem());
			line.setQuantity(new java.math.BigDecimal(lines[i][1])); // read from the DECIMAL(18,3) column
			line.setLineTotalIncludingVat(Double.valueOf(lines[i][2]));
			line.setLineTotal(Double.valueOf(lines[i][2]) / 1.19);
			storeLines.add(line);
		}
		ReturnCopyDTO sentCopy = com.digithink.zsretail.holink.service.SalesCopyMapper.returnCopy(header, storeLines);

		com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper()
				.findAndRegisterModules()
				.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
				.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		String wire = json.writeValueAsString(sentCopy);
		assertTrue(wire.contains("\"quantity\":0.2,"), wire);
		assertTrue(wire.contains("\"quantity\":1.5,"), wire);
		assertTrue(wire.contains("\"quantity\":1,"), wire); // never 1.000
		ReturnCopyDTO received = json.readValue(wire, ReturnCopyDTO.class);

		assertAllAccepted(receiver.receiveReturns(rs01, Arrays.asList(received)), "RET-BULK-1");
		HoReturn row = returnTable.values().iterator().next();
		assertEquals(3, row.getLines().size());
		for (int i = 0; i < lines.length; i++) {
			com.digithink.zsretail.headoffice.model.HoReturnLine line = row.getLines().get(i);
			assertEquals(storeLines.get(i).getQuantity().toPlainString(), line.getQuantity().toPlainString(), "quantity " + i);
			assertEquals(storeLines.get(i).getLineTotalIncludingVat(), line.getLineTotalIncludingVat(), 0.0);
			assertEquals(storeLines.get(i).getLineTotal(), line.getLineTotal(), 0.0);
		}
		assertEquals("0.2", row.getLines().get(0).getQuantity().toPlainString());
		assertEquals("1", row.getLines().get(2).getQuantity().toPlainString());
	}
}
