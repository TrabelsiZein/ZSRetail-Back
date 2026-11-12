package com.digithink.zsretail.store.erp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.erp.enumeration.ErpCommunicationStatus;
import com.digithink.zsretail.core.erp.enumeration.ErpSyncOperation;
import com.digithink.zsretail.core.erp.service.ErpCommunicationService;
import com.digithink.zsretail.core.erp.service.ErpSynchronizationManager;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.SynchronizationStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.PaymentRepository;
import com.digithink.zsretail.core.repository.ReturnHeaderRepository;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.erp.api.ErpConnector;
import com.digithink.zsretail.erp.api.dto.ErpOperationResult;
import com.digithink.zsretail.erp.api.dto.ErpPaymentHeaderDTO;
import com.digithink.zsretail.erp.api.dto.ErpPaymentLineDTO;
import com.digithink.zsretail.erp.api.dto.ErpSessionDTO;
import com.digithink.zsretail.store.erp.model.PaymentHeader;
import com.digithink.zsretail.store.erp.model.PaymentLine;
import com.digithink.zsretail.store.erp.repository.PaymentHeaderRepository;
import com.digithink.zsretail.store.erp.repository.PaymentLineRepository;

/**
 * 3.2.0, step 3b: the NAV sync, simulated. The real SessionExportService and ErpSynchronizationManager, the rows in
 * memory, a recording fake ErpConnector (NAV) that can refuse a payment class, the ERP sync tracking recorded. The rows
 * are made as the till makes them (createPaymentHeadersAndLinesAsync after each sale), the export runs as the job does
 * (exportSessions over the TERMINATED sessions not totally synchronised).
 */
class SessionExportNavSimulationTest {

	private final List<CashierSession> sessions = new ArrayList<>();
	private final List<SalesHeader> sales = new ArrayList<>();
	private final List<Payment> payments = new ArrayList<>();
	private final List<PaymentHeader> headers = new ArrayList<>();
	private final List<PaymentLine> lines = new ArrayList<>();
	private long nextId = 1;

	/** What NAV received, in order. */
	private final List<ErpSessionDTO> navSessions = new ArrayList<>();
	private final List<ErpPaymentHeaderDTO> navHeaders = new ArrayList<>();
	private final List<ErpPaymentLineDTO> navLines = new ArrayList<>();
	/** The payment classes NAV refuses (a code it does not have). */
	private final Set<String> unknownInNav = new HashSet<>();

	private final ErpCommunicationService tracking = mock(ErpCommunicationService.class);
	private SessionExportService service;

	private final CashierSession session = new CashierSession();
	private final Customer passenger = new Customer();
	private final PaymentMethod cash = method("CLIENT_ESPECES", PaymentMethodType.CLIENT_ESPECES, true);
	private final PaymentMethod pluxee = method("CARTE_PLUXEE", null, true);
	private final PaymentMethod notSent = method("BON_INTERNE", null, false);

	private static PaymentMethod method(String code, PaymentMethodType type, boolean sendToErp) {
		PaymentMethod method = new PaymentMethod();
		method.setCode(code);
		method.setName(code);
		method.setType(type);
		method.setSendToErp(sendToErp);
		return method;
	}

	@BeforeEach
	void setUp() {
		session.setId(nextId++);
		session.setSessionNumber("SESSION261112001");
		session.setStatus(SessionStatus.TERMINATED);
		session.setSynchronizationStatus(SynchronizationStatus.NOT_SYNCHED);
		sessions.add(session);
		passenger.setCustomerCode("PASSAGER");

		CashierSessionRepository sessionRepository = mock(CashierSessionRepository.class);
		when(sessionRepository.findByIdForUpdate(any())).thenAnswer(i -> Optional.of(session));
		when(sessionRepository.save(any(CashierSession.class))).thenAnswer(i -> i.getArgument(0));
		when(sessionRepository.findByStatusInAndSynchronizationStatusNot(any(), eq(SynchronizationStatus.TOTALLY_SYNCHED)))
				.thenAnswer(i -> sessions.stream()
						.filter(s -> s.getSynchronizationStatus() != SynchronizationStatus.TOTALLY_SYNCHED)
						.collect(Collectors.toList()));
		SalesHeaderRepository salesRepository = mock(SalesHeaderRepository.class);
		when(salesRepository.findByCashierSession(any())).thenAnswer(i -> new ArrayList<>(sales));
		PaymentRepository paymentRepository = mock(PaymentRepository.class);
		when(paymentRepository.findBySalesHeader(any())).thenAnswer(i -> payments.stream()
				.filter(p -> p.getSalesHeader() == i.getArgument(0)).collect(Collectors.toList()));
		when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
		ReturnHeaderRepository returnRepository = mock(ReturnHeaderRepository.class);
		PaymentHeaderRepository headerRepository = mock(PaymentHeaderRepository.class);
		when(headerRepository.findByCashierSession(any())).thenAnswer(i -> new ArrayList<>(headers));
		when(headerRepository.save(any(PaymentHeader.class))).thenAnswer(i -> {
			PaymentHeader header = i.getArgument(0);
			if (header.getId() == null) {
				header.setId(nextId++);
				headers.add(header);
			}
			return header;
		});
		org.mockito.Mockito.doAnswer(i -> headers.removeAll((List<?>) i.getArgument(0))).when(headerRepository)
				.deleteAll(any());
		PaymentLineRepository lineRepository = mock(PaymentLineRepository.class);
		when(lineRepository.findByPaymentHeader(any())).thenAnswer(i -> lines.stream()
				.filter(l -> l.getPaymentHeader() == i.getArgument(0)).collect(Collectors.toList()));
		when(lineRepository.save(any(PaymentLine.class))).thenAnswer(i -> {
			PaymentLine line = i.getArgument(0);
			if (line.getId() == null) {
				line.setId(nextId++);
				lines.add(line);
			}
			return line;
		});
		org.mockito.Mockito.doAnswer(i -> lines.removeAll((List<?>) i.getArgument(0))).when(lineRepository).deleteAll(any());
		GeneralSetupService setup = mock(GeneralSetupService.class);
		when(setup.findValueByCode("DEFAULT_LOCATION")).thenReturn("MAG");

		ErpConnector nav = mock(ErpConnector.class);
		when(nav.pushSession(any())).thenAnswer(i -> {
			navSessions.add(i.getArgument(0));
			return ErpOperationResult.success("1");
		});
		when(nav.pushPaymentHeader(any())).thenAnswer(i -> {
			ErpPaymentHeaderDTO header = i.getArgument(0);
			navHeaders.add(header);
			return unknownInNav.contains(header.getPaymentClass())
					? ErpOperationResult.failure("The Payment Class " + header.getPaymentClass() + " does not exist.")
					: ErpOperationResult.success("ENC-" + navHeaders.size());
		});
		when(nav.pushPaymentLine(anyString(), any())).thenAnswer(i -> {
			navLines.add(i.getArgument(1));
			return ErpOperationResult.success(i.getArgument(0));
		});
		ErpSynchronizationManager manager = new ErpSynchronizationManager(nav, tracking);
		service = new SessionExportService(manager, sessionRepository, setup, salesRepository, returnRepository,
				paymentRepository, headerRepository, lineRepository);
	}

	/** A completed sale of the session, its payments made, then the rows the till makes after it. */
	private SalesHeader sell(String number, Object... methodAndAmount) {
		SalesHeader sale = new SalesHeader();
		sale.setId(nextId++);
		sale.setSalesNumber(number);
		sale.setCashierSession(session);
		sale.setCustomer(passenger);
		sale.setStatus(TransactionStatus.COMPLETED);
		sale.setChangeAmount(0.0);
		sales.add(sale);
		List<Payment> salePayments = new ArrayList<>();
		double total = 0;
		for (int i = 0; i < methodAndAmount.length; i += 2) {
			total += (Double) methodAndAmount[i + 1];
			Payment payment = new Payment();
			payment.setId(nextId++);
			payment.setSalesHeader(sale);
			payment.setPaymentMethod((PaymentMethod) methodAndAmount[i]);
			payment.setTotalAmount((Double) methodAndAmount[i + 1]);
			payments.add(payment);
			salePayments.add(payment);
		}
		sale.setTotalAmount(total); // the session DTO sums the tickets' totals
		service.createPaymentHeadersAndLinesAsync(salePayments, sale);
		return sale;
	}

	private Payment paymentOf(SalesHeader sale, PaymentMethod method) {
		return payments.stream().filter(p -> p.getSalesHeader() == sale && p.getPaymentMethod() == method).findFirst()
				.get();
	}

	private PaymentHeader header(String paymentClass) {
		return headers.stream().filter(h -> paymentClass.equals(h.getPaymentClass())).findFirst().orElse(null);
	}

	private List<ErpPaymentLineDTO> navLinesOf(String docNo) {
		return navLines.stream().filter(l -> docNo.equals(l.getDocNo())).collect(Collectors.toList());
	}

	@Test
	@DisplayName("1. An added method, sent: its code is the class, one line per payment with titleNo, dueDate, drawerName")
	void addedMethodSent() {
		SalesHeader sale = sell("T-1", cash, 20.0, pluxee, 30.0);
		Payment card = paymentOf(sale, pluxee);
		card.setTitleNumber("SLIP-778812");
		card.setDueDate(LocalDate.of(2026, 11, 30));
		card.setDrawerName("Amira Ben Salem");
		// the rows were made at the sale: give them the details as the till sends them (one sale, rows rebuilt)
		service.rebuildSessionPaymentRows(session);
		service.exportSessions();

		assertEquals(1, navSessions.size());
		assertEquals(Arrays.asList("CLIENT_ESPECES", "CARTE_PLUXEE"),
				navHeaders.stream().map(ErpPaymentHeaderDTO::getPaymentClass).sorted((a, b) -> b.compareTo(a))
						.collect(Collectors.toList()));
		String pluxeeDoc = header("CARTE_PLUXEE").getErpNo();
		List<ErpPaymentLineDTO> pluxeeLines = navLinesOf(pluxeeDoc);
		assertEquals(1, pluxeeLines.size());
		ErpPaymentLineDTO line = pluxeeLines.get(0);
		assertEquals("PASSAGER", line.getCustNo());
		assertEquals(0, new BigDecimal("30").compareTo(line.getAmount()));
		assertEquals("SESSION261112001", line.getFenceNo());
		assertEquals("T-1", line.getTicketNo());
		assertEquals("SLIP-778812", line.getTitleNo());
		assertEquals(LocalDate.of(2026, 11, 30), line.getDueDate());
		assertEquals("Amira Ben Salem", line.getDrawerName());
		ErpPaymentLineDTO cashLine = navLinesOf(header("CLIENT_ESPECES").getErpNo()).get(0);
		assertEquals(0, new BigDecimal("20").compareTo(cashLine.getAmount()));
		assertNull(cashLine.getTitleNo());
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, session.getSynchronizationStatus());
		assertTrue(headers.stream().allMatch(h -> h.getSynchronizationStatus() == SynchronizationStatus.TOTALLY_SYNCHED));
		assertTrue(payments.stream().allMatch(p -> Boolean.TRUE.equals(p.getSynched())), "every payment marked synched");
	}

	@Test
	@DisplayName("2. An added method not sent: no header, no line; a session of such payments only ends TOTALLY_SYNCHED")
	void addedMethodNotSent() {
		sell("T-1", notSent, 30.0);
		assertTrue(headers.isEmpty() && lines.isEmpty());
		service.exportSessions();
		assertEquals(1, navSessions.size(), "the session itself is sent");
		assertTrue(navHeaders.isEmpty() && navLines.isEmpty());
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, session.getSynchronizationStatus());
	}

	@Test
	@DisplayName("2b. A ticket paid with cash and a method not sent: only the cash goes")
	void mixedNotSent() {
		sell("T-1", cash, 20.0, notSent, 30.0);
		service.exportSessions();
		assertEquals(Arrays.asList("CLIENT_ESPECES"),
				navHeaders.stream().map(ErpPaymentHeaderDTO::getPaymentClass).collect(Collectors.toList()));
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, session.getSynchronizationStatus());
	}

	@Test
	@DisplayName("3. NAV refuses one class: the others sent, it stays NOT_SYNCHED, the session PARTIALLY; the next run retries only it")
	void classUnknownInNav() {
		sell("T-1", cash, 20.0, pluxee, 30.0);
		sell("T-2", pluxee, 12.5);
		unknownInNav.add("CARTE_PLUXEE");
		service.exportSessions();

		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, header("CLIENT_ESPECES").getSynchronizationStatus());
		assertEquals(SynchronizationStatus.NOT_SYNCHED, header("CARTE_PLUXEE").getSynchronizationStatus());
		assertEquals(SynchronizationStatus.PARTIALLY_SYNCHED, session.getSynchronizationStatus());
		assertEquals(1, navLines.size(), "the cash line only");
		verify(tracking, atLeastOnce()).logOperation(eq(ErpSyncOperation.EXPORT_PAYMENT_HEADER), any(), isNull(),
				eq(ErpCommunicationStatus.ERROR), isNull(), eq("The Payment Class CARTE_PLUXEE does not exist."), any(),
				any());

		// The class created in NAV: the next run sends the session once only, then the refused header and its two lines
		unknownInNav.clear();
		navHeaders.clear();
		navLines.clear();
		service.exportSessions();
		assertEquals(1, navSessions.size(), "the session is not sent again");
		assertEquals(Arrays.asList("CARTE_PLUXEE"),
				navHeaders.stream().map(ErpPaymentHeaderDTO::getPaymentClass).collect(Collectors.toList()));
		assertEquals(2, navLines.size());
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, session.getSynchronizationStatus());
	}

	@Test
	@DisplayName("4. The cheque off (ERP_SKIP_CHEQUE_PAYMENTS true before the upgrade) is not sent; the return voucher never")
	void chequeAndVoucher() {
		PaymentMethod cheque = method("CLIENT_CHEQUE", PaymentMethodType.CLIENT_CHEQUE, false);
		PaymentMethod voucher = method("RETURN_VOUCHER", PaymentMethodType.RETURN_VOUCHER, true); // a row set by hand
		sell("T-1", cheque, 40.0);
		sell("T-2", voucher, 15.0, cash, 5.0);
		service.exportSessions();
		assertEquals(Arrays.asList("CLIENT_ESPECES"),
				navHeaders.stream().map(ErpPaymentHeaderDTO::getPaymentClass).collect(Collectors.toList()));
		assertEquals(1, navSessions.get(0).getReturnCashedCount(), "the session still counts the voucher by its type");
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, session.getSynchronizationStatus());
		cheque.setSendToErp(true);
		assertTrue(SessionExportService.sentToErp(cheque), "a cheque switched on is sent");
		voucher.setSendToErp(null);
		assertTrue(!SessionExportService.sentToErp(voucher) && SessionExportService.sentToErp(method("X", null, true)));
	}

	@Test
	@DisplayName("5. Payment change to and from an added method: the rebuild gives the rows of the methods sent")
	void paymentChangeRebuild() {
		SalesHeader a = sell("T-1", cash, 20.0);
		SalesHeader b = sell("T-2", notSent, 30.0);
		assertEquals(1, lines.size(), "T-2 had no row");

		// T-2: BON_INTERNE (not sent) -> CARTE_PLUXEE (sent): its row is made (it was lost before 3.2.0, step 3b)
		paymentOf(b, notSent).setPaymentMethod(pluxee);
		service.rebuildSessionPaymentRows(session, b);
		assertEquals(30.0, lines.stream().filter(l -> "CARTE_PLUXEE".equals(l.getPaymentHeader().getPaymentClass()))
				.findFirst().get().getAmount(), 1e-9);

		// T-1: cash -> BON_INTERNE (not sent): its cash row goes
		Payment cashOfA = paymentOf(a, cash);
		cashOfA.setPaymentMethod(notSent);
		service.rebuildSessionPaymentRows(session, a);
		assertEquals(Arrays.asList("CARTE_PLUXEE"),
				headers.stream().map(PaymentHeader::getPaymentClass).collect(Collectors.toList()));
		assertEquals(1, lines.size());

		// A split ticket never gets rows, even when its payment changes
		SalesHeader split = new SalesHeader();
		split.setId(nextId++);
		split.setSalesNumber("T-9");
		split.setNotes("Split from T-8");
		split.setCashierSession(session);
		split.setCustomer(passenger);
		split.setStatus(TransactionStatus.COMPLETED);
		split.setChangeAmount(0.0);
		split.setTotalAmount(9.0);
		sales.add(split);
		Payment splitPayment = new Payment();
		splitPayment.setId(nextId++);
		splitPayment.setSalesHeader(split);
		splitPayment.setPaymentMethod(pluxee);
		splitPayment.setTotalAmount(9.0);
		payments.add(splitPayment);
		service.rebuildSessionPaymentRows(session, split);
		assertEquals(1, lines.size(), "the split ticket stays out");

		service.exportSessions();
		assertEquals(Arrays.asList("CARTE_PLUXEE"),
				navHeaders.stream().map(ErpPaymentHeaderDTO::getPaymentClass).collect(Collectors.toList()));
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, session.getSynchronizationStatus());
	}
	// ─── 3.2.0, step 4: scanned tickets ──────────────────────────────

	private static final String SERVIMAX_1 = "0310070002600002897009796069";
	private static final String SERVIMAX_2 = "0310070002600002897009796070";

	/** The real ScannedTicketService: the Servimax Repas line (10 %), its payments into this test's rows, clock 2026-11-12. */
	private com.digithink.zsretail.store.service.ScannedTicketService ticketService(PaymentMethod method) throws Exception {
		com.digithink.zsretail.core.model.AcceptedTicket servimax = new com.digithink.zsretail.core.model.AcceptedTicket();
		servimax.setId(30L);
		servimax.setPaymentMethod(method);
		servimax.setTicketCode("031");
		servimax.setIssuerName("Servimax Repas");
		servimax.setDeductionPercent(BigDecimal.TEN);
		servimax.setValidityMonths(1);
		servimax.setLayout(com.digithink.zsretail.core.model.BarcodeLayout.standard());
		servimax.setActive(true);
		com.digithink.zsretail.core.repository.AcceptedTicketRepository lineRepository = mock(
				com.digithink.zsretail.core.repository.AcceptedTicketRepository.class);
		when(lineRepository.findAll()).thenReturn(Arrays.asList(servimax));
		com.digithink.zsretail.store.repository.ScannedTicketPaymentRepository scannedRepository = mock(
				com.digithink.zsretail.store.repository.ScannedTicketPaymentRepository.class);
		when(scannedRepository.findFirstByLiveBarcode(any())).thenReturn(Optional.empty());
		when(scannedRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
		com.digithink.zsretail.store.service.PaymentService paymentService = new com.digithink.zsretail.store.service.PaymentService() {
			@Override
			public Payment save(Payment payment) {
				payment.setId(nextId++);
				payments.add(payment);
				return payment;
			}
		};
		com.digithink.zsretail.store.service.ScannedTicketService tickets = new com.digithink.zsretail.store.service.ScannedTicketService();
		for (Object[] field : new Object[][] { { "acceptedTicketRepository", lineRepository },
				{ "scannedTicketPaymentRepository", scannedRepository }, { "paymentService", paymentService },
				{ "clock", java.time.Clock.fixed(java.time.Instant.parse("2026-11-12T09:00:00Z"), java.time.ZoneOffset.UTC) } }) {
			java.lang.reflect.Field f = com.digithink.zsretail.store.service.ScannedTicketService.class
					.getDeclaredField((String) field[0]);
			f.setAccessible(true);
			f.set(tickets, field[1]);
		}
		return tickets;
	}

	private SalesHeader openSale(String number, double total) {
		SalesHeader sale = new SalesHeader();
		sale.setId(nextId++);
		sale.setSalesNumber(number);
		sale.setCashierSession(session);
		sale.setCustomer(passenger);
		sale.setStatus(TransactionStatus.COMPLETED);
		sale.setChangeAmount(0.0);
		sale.setTotalAmount(total);
		sales.add(sale);
		return sale;
	}

	@Test
	@DisplayName("E. 12.000 paid with two Servimax 7.000 of 2026: one header of the method, two lines 6.300 and 5.700, titleNo the barcodes")
	void scannedTicketsToNav() throws Exception {
		PaymentMethod meal = method("TICKET_RESTAURANT", null, true);
		meal.setId(3L);
		meal.setBehaviour(com.digithink.zsretail.core.model.enumeration.PaymentMethodBehaviour.SCANNED_TICKETS);
		SalesHeader sale = openSale("T-1", 12.0);
		List<Payment> paid = ticketService(meal).pay(sale, meal, Arrays.asList(SERVIMAX_1, SERVIMAX_2),
				new BigDecimal("12.000"), null, new HashSet<>());
		service.createPaymentHeadersAndLinesAsync(paid, sale);
		service.exportSessions();

		assertEquals(Arrays.asList("TICKET_RESTAURANT"),
				navHeaders.stream().map(ErpPaymentHeaderDTO::getPaymentClass).collect(Collectors.toList()));
		assertEquals(2, navLines.size(), "one line per ticket");
		assertEquals(Arrays.asList(SERVIMAX_1, SERVIMAX_2),
				navLines.stream().map(ErpPaymentLineDTO::getTitleNo).collect(Collectors.toList()));
		assertEquals(28, navLines.get(0).getTitleNo().length(), "the whole barcode, never cut on our side");
		assertEquals(0, new BigDecimal("6.3").compareTo(navLines.get(0).getAmount()));
		assertEquals(0, new BigDecimal("5.7").compareTo(navLines.get(1).getAmount()), "the part applied, not the 6.300 counted");
		assertEquals("PASSAGER", navLines.get(1).getCustNo());
		assertEquals("T-1", navLines.get(1).getTicketNo());
		assertEquals("SESSION261112001", navLines.get(1).getFenceNo());
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, session.getSynchronizationStatus());
	}

	@Test
	@DisplayName("E. Scanned tickets of a method not sent to the ERP: no header, no line; the session TOTALLY_SYNCHED")
	void scannedTicketsNotSent() throws Exception {
		PaymentMethod meal = method("TICKET_RESTAURANT", null, false);
		meal.setId(3L);
		meal.setBehaviour(com.digithink.zsretail.core.model.enumeration.PaymentMethodBehaviour.SCANNED_TICKETS);
		SalesHeader sale = openSale("T-1", 12.0);
		List<Payment> paid = ticketService(meal).pay(sale, meal, Arrays.asList(SERVIMAX_1, SERVIMAX_2),
				new BigDecimal("12.000"), null, new HashSet<>());
		service.createPaymentHeadersAndLinesAsync(paid, sale);
		service.exportSessions();
		assertTrue(navHeaders.isEmpty() && navLines.isEmpty());
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, session.getSynchronizationStatus());
	}
}
