package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.digithink.zsretail.core.dto.PricingResult;
import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.BarcodeLayout;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodBehaviour;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.repository.AcceptedTicketRepository;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.CustomerRepository;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.PricingService;
import com.digithink.zsretail.core.service.StockMovementService;
import com.digithink.zsretail.core.service.StockService;
import com.digithink.zsretail.core.service.TicketBarcodeParser.Reading;
import com.digithink.zsretail.core.service.TicketBarcodeParser.Status;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.dto.ProcessSaleRequestDTO;
import com.digithink.zsretail.store.erp.service.SessionExportService;
import com.digithink.zsretail.store.model.ScannedTicketPayment;
import com.digithink.zsretail.store.repository.ScannedTicketPaymentRepository;
import com.digithink.zsretail.store.support.InMemoryTillRights;

/**
 * 3.2.0, step 4: a sale paid with scanned tickets, through the real processCompleteSale and ScannedTicketService (the
 * repositories in memory; the scanned tickets' store keeps the database's rule: one live barcode). Real-shaped barcodes:
 * 0310070002600002897009796069 Servimax Repas 7 DT of 2026 (valid, 10 %: 6.300), 0310070002500002897009796069 the
 * same of 2025 (expired since 2026-02-01); the clock is 2026-11-12.
 */
class ScannedTicketSaleTest {

	private static final String VALID = "0310070002600002897009796069";
	private static final String VALID_2 = "0310070002600002897009796070";
	private static final String EXPIRED = "0310070002500002897009796069";
	private static final String GIFT = "0420100002608037484068000487";
	private static final String INACTIVE = "0210050002600000000000000001";

	private SalesHeaderService sales;
	private ScannedTicketService tickets;
	private final UserAccount cashier = new UserAccount();
	private final CashierSession session = new CashierSession();
	private final Customer customer = new Customer();
	private final Item goods = new Item();
	private final Map<Long, PaymentMethod> methods = new HashMap<>();
	private final Map<Long, SalesHeader> headers = new HashMap<>();
	private final List<SalesLine> savedLines = new ArrayList<>();
	private final List<Payment> savedPayments = new ArrayList<>();
	private final List<AcceptedTicket> lines = new ArrayList<>();
	private final List<ScannedTicketPayment> scanned = new ArrayList<>();
	/** The other till: its sale takes the ticket between this one's check and its insert. */
	private boolean otherTillTakesIt;
	private long nextId = 1000;

	private PaymentMethod cash;
	private PaymentMethod card;
	private PaymentMethod meal;
	private PaymentMethod gift;

	@BeforeEach
	void setUp() throws Exception {
		cashier.setUsername("amira");
		cashier.setFullName("Amira Ben Salem");
		session.setId(7L);
		session.setSessionNumber("SESSION261112001");
		customer.setId(5L);
		goods.setId(10L);
		goods.setItemCode("ART-10");
		goods.setName("Article");
		goods.setDefaultVAT(0);
		cash = method(1L, "CLIENT_ESPECES", PaymentMethodType.CLIENT_ESPECES, PaymentMethodBehaviour.AMOUNT);
		card = method(2L, "CLIENT_TPE", PaymentMethodType.CLIENT_TPE, PaymentMethodBehaviour.AMOUNT);
		meal = method(3L, "TICKET_RESTAURANT", null, PaymentMethodBehaviour.SCANNED_TICKETS);
		gift = method(4L, "CHEQUE_CADEAU", null, PaymentMethodBehaviour.SCANNED_TICKETS);
		lines.add(line(30L, meal, "031", "Servimax Repas", 10, true));
		lines.add(line(31L, gift, "042", "Sodexo Cadeau", 0, true));
		lines.add(line(32L, meal, "021", "Joker Repas", 10, false));

		PaymentService payments = new PaymentService() {
			@Override
			public Payment save(Payment payment) {
				if (payment.getId() == null) {
					payment.setId(nextId++);
					savedPayments.add(payment);
				}
				return payment;
			}
		};
		AcceptedTicketRepository lineRepository = mock(AcceptedTicketRepository.class);
		when(lineRepository.findAll()).thenAnswer(i -> new ArrayList<>(lines));
		ScannedTicketPaymentRepository scannedRepository = mock(ScannedTicketPaymentRepository.class);
		when(scannedRepository.findFirstByLiveBarcode(any())).thenAnswer(i -> otherTillTakesIt ? Optional.empty()
				: scanned.stream().filter(s -> i.getArgument(0).equals(s.getLiveBarcode())).findFirst());
		when(scannedRepository.saveAndFlush(any(ScannedTicketPayment.class))).thenAnswer(i -> {
			ScannedTicketPayment row = i.getArgument(0);
			if (otherTillTakesIt || scanned.stream().anyMatch(s -> row.getLiveBarcode().equals(s.getLiveBarcode()))) {
				throw new DataIntegrityViolationException("Cannot insert duplicate key row in object"
						+ " 'dbo.scanned_ticket_payment' with unique index 'ux_scanned_ticket_live_barcode'");
			}
			row.setId(nextId++);
			scanned.add(row);
			return row;
		});
		tickets = new ScannedTicketService();
		inject(tickets, ScannedTicketService.class, "acceptedTicketRepository", lineRepository);
		inject(tickets, ScannedTicketService.class, "scannedTicketPaymentRepository", scannedRepository);
		inject(tickets, ScannedTicketService.class, "paymentService", payments);
		tickets.clock = Clock.fixed(LocalDate.of(2026, 11, 12).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant(),
				ZoneId.systemDefault());

		sales = new SalesHeaderService();
		inject(sales, SalesHeaderService.class, "tillRightsGuard", InMemoryTillRights.allowAll());
		inject(sales, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "amira";
			}
		});
		inject(sales, SalesHeaderService.class, "generalSetupRepository", proxy(GeneralSetupRepository.class,
				(m, a) -> "findByCode".equals(m) ? Optional.empty() : UNHANDLED));
		inject(sales, SalesHeaderService.class, "salesHeaderRepository", proxy(SalesHeaderRepository.class, (m, a) -> {
			switch (m) {
				case "save": {
					SalesHeader h = (SalesHeader) a[0];
					if (h.getId() == null) h.setId(nextId++);
					headers.put(h.getId(), h);
					return h;
				}
				case "findById": return Optional.ofNullable(headers.get(a[0]));
				case "countBySalesDateGreaterThanEqual": return (long) headers.size();
				default: return UNHANDLED;
			}
		}));
		inject(sales, SalesHeaderService.class, "salesLineRepository", proxy(SalesLineRepository.class, (m, a) ->
				"findBySalesHeader".equals(m) ? savedLines.stream().filter(l -> l.getSalesHeader() == a[0])
						.collect(Collectors.toList()) : UNHANDLED));
		inject(sales, SalesHeaderService.class, "itemRepository", proxy(ItemRepository.class, (m, a) -> {
			switch (m) {
				case "findById": return 10L == (Long) a[0] ? Optional.of(goods) : Optional.empty();
				case "findByItemCode": return Optional.empty();
				default: return UNHANDLED;
			}
		}));
		inject(sales, SalesHeaderService.class, "customerRepository", proxy(CustomerRepository.class, (m, a) ->
				"findById".equals(m) ? Optional.of(customer) : UNHANDLED));
		inject(sales, SalesHeaderService.class, "paymentMethodRepository", proxy(PaymentMethodRepository.class, (m, a) ->
				"findById".equals(m) ? Optional.ofNullable(methods.get(a[0])) : UNHANDLED));
		inject(sales, SalesHeaderService.class, "cashierSessionRepository", proxy(CashierSessionRepository.class, (m, a) ->
				"findByCashierAndStatus".equals(m) ? Optional.of(session) : UNHANDLED));
		inject(sales, SalesHeaderService.class, "salesLineService", new SalesLineService() {
			@Override
			public SalesLine save(SalesLine line) {
				if (line.getId() == null) line.setId(nextId++);
				savedLines.add(line);
				return line;
			}
		});
		inject(sales, SalesHeaderService.class, "paymentService", payments);
		inject(sales, SalesHeaderService.class, "scannedTicketService", tickets);
		inject(sales, SalesHeaderService.class, "stockService", new StockService() {
			@Override
			public void decrementForSale(Long itemId, BigDecimal quantity) {
			}
		});
		inject(sales, SalesHeaderService.class, "stockMovementService", new StockMovementService() {
			@Override
			public void recordSale(Long itemId, BigDecimal quantity, Double unitPriceHt, Integer vatPercent,
					Double unitPriceTtc, Long salesHeaderId, CashierSession cashierSession) {
			}
		});
		inject(sales, SalesHeaderService.class, "pricingService", new PricingService() {
			@Override
			public PricingResult calculateItemPrice(Item i, Customer c, BigDecimal quantity, String responsibilityCenter) {
				PricingResult r = new PricingResult();
				r.setUnitPrice(i.getUnitPrice());
				r.setPriceIncludesVat(true);
				r.setSource("BASE_PRICE");
				return r;
			}
		});
		inject(sales, SalesHeaderService.class, "sessionExportService",
				new SessionExportService(null, null, null, null, null, null, null, null) {
					@Override
					public void createPaymentHeadersAndLinesAsync(List<Payment> p, SalesHeader s) {
					}
				});
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private PaymentMethod method(Long id, String code, PaymentMethodType type, PaymentMethodBehaviour behaviour) {
		PaymentMethod method = new PaymentMethod();
		method.setId(id);
		method.setCode(code);
		method.setName(code);
		method.setType(type);
		method.setBehaviour(behaviour);
		methods.put(id, method);
		return method;
	}

	private static AcceptedTicket line(Long id, PaymentMethod method, String code, String issuer, int deduction,
			boolean active) {
		AcceptedTicket line = new AcceptedTicket();
		line.setId(id);
		line.setPaymentMethod(method);
		line.setTicketCode(code);
		line.setIssuerName(issuer);
		line.setDeductionPercent(BigDecimal.valueOf(deduction));
		line.setValidityMonths(1);
		line.setLayout(BarcodeLayout.standard());
		line.setActive(active);
		return line;
	}

	private static ProcessSaleRequestDTO.PaymentDTO amount(PaymentMethod method, double amount) {
		ProcessSaleRequestDTO.PaymentDTO payment = new ProcessSaleRequestDTO.PaymentDTO();
		payment.setPaymentMethodId(method.getId());
		payment.setAmount(amount);
		return payment;
	}

	private static ProcessSaleRequestDTO.PaymentDTO scan(PaymentMethod method, String... barcodes) {
		ProcessSaleRequestDTO.PaymentDTO payment = new ProcessSaleRequestDTO.PaymentDTO();
		payment.setPaymentMethodId(method.getId());
		payment.setBarcodes(Arrays.asList(barcodes));
		return payment;
	}

	/** A sale of one article at {@code total} TND (no VAT, no stamp), paid as given. */
	private SalesHeader sell(double total, ProcessSaleRequestDTO.PaymentDTO... payments) throws Exception {
		goods.setUnitPrice(total);
		ProcessSaleRequestDTO.SaleLineDTO line = new ProcessSaleRequestDTO.SaleLineDTO();
		line.setItemId(10L);
		line.setQuantity(BigDecimal.ONE);
		line.setUnitPrice(total);
		line.setLineTotal(total);
		line.setVatPercent(0);
		line.setVatAmount(0.0);
		line.setUnitPriceIncludingVat(total);
		line.setLineTotalIncludingVat(total);
		ProcessSaleRequestDTO request = new ProcessSaleRequestDTO();
		request.setSubtotal(total);
		request.setTaxAmount(0.0);
		request.setTotalAmount(total);
		request.setCustomerId(5L);
		request.setLines(new ArrayList<>(Arrays.asList(line)));
		request.setPayments(new ArrayList<>(Arrays.asList(payments)));
		return sales.processCompleteSale(request, cashier);
	}

	private List<Payment> paymentsOf(SalesHeader sale) {
		return savedPayments.stream().filter(p -> p.getSalesHeader() == sale).collect(Collectors.toList());
	}

	private ScannedTicketPayment ticketOf(Payment payment) {
		return scanned.stream().filter(s -> s.getPayment() == payment).findFirst().orElse(null);
	}

	private String refusal(Executable call) {
		return assertThrows(IllegalArgumentException.class, call::run).getMessage();
	}

	interface Executable {
		void run() throws Exception;
	}

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	// ─── Tests ───────────────────────────────────────────────────────

	@Test
	@DisplayName("12.000 paid with two Servimax 7.000 of 2026: 6.300 then 5.700 applied, 0.600 excess, no change")
	void twoTicketsPayTwelve() throws Exception {
		SalesHeader sale = sell(12.0, scan(meal, VALID, VALID_2));
		List<Payment> payments = paymentsOf(sale);
		assertEquals(2, payments.size(), "one payment per ticket");
		assertEquals(Arrays.asList(6.3, 5.7), payments.stream().map(Payment::getTotalAmount).collect(Collectors.toList()));
		assertEquals(Arrays.asList(VALID, VALID_2),
				payments.stream().map(Payment::getTitleNumber).collect(Collectors.toList()), "titleNo = the barcode");
		ScannedTicketPayment second = ticketOf(payments.get(1));
		assertEquals(new BigDecimal("7.000"), second.getFaceValue());
		assertEquals(0, BigDecimal.TEN.compareTo(second.getDeductionPercent()));
		assertEquals(new BigDecimal("6.300"), second.getCountedValue());
		assertEquals(new BigDecimal("5.700"), second.getAppliedAmount());
		assertEquals(new BigDecimal("0.600"), second.getExcessAmount());
		assertEquals("Servimax Repas", second.getIssuerName());
		assertEquals("031", second.getTicketCode());
		assertEquals(2026, second.getPrintedYear());
		assertEquals(VALID_2, second.getLiveBarcode());
		assertEquals(30L, second.getAcceptedTicket().getId());
		assertEquals(new BigDecimal("0.000"), ticketOf(payments.get(0)).getExcessAmount());
		assertEquals(12.0, sale.getPaidAmount(), 1e-9);
		assertEquals(0.0, sale.getChangeAmount(), 1e-9, "tickets never give change");
	}

	@Test
	@DisplayName("A ticket and cash: the ticket first, the change from the cash only (whatever the order sent)")
	void ticketThenCash() throws Exception {
		SalesHeader sale = sell(10.0, amount(cash, 5.0), scan(meal, VALID));
		assertEquals(6.3, ticketOf(paymentsOf(sale).stream().filter(p -> p.getPaymentMethod() == meal).findFirst().get())
				.getAppliedAmount().doubleValue(), 1e-9);
		assertEquals(11.3, sale.getPaidAmount(), 1e-9);
		assertEquals(1.3, sale.getChangeAmount(), 1e-9, "5 of cash for 3.700 left: 1.300 back, from the cash");
	}

	@Test
	@DisplayName("The amount the till sends for the tickets is ignored: the server counts them")
	void amountIgnored() throws Exception {
		ProcessSaleRequestDTO.PaymentDTO tickets = scan(meal, VALID);
		tickets.setAmount(99.0);
		SalesHeader sale = sell(20.0, tickets, amount(cash, 13.7));
		assertEquals(6.3, paymentsOf(sale).get(0).getTotalAmount(), 1e-9);
	}

	@Test
	@DisplayName("Nothing left to pay: the ticket is refused (a card covered it all), nothing saved")
	void nothingLeft() {
		String message = refusal(() -> sell(12.0, amount(card, 12.0), scan(meal, VALID)));
		assertTrue(message.contains(VALID) && message.contains("déjà entièrement payée"), message);
		assertTrue(scanned.isEmpty());
	}

	@Test
	@DisplayName("Refused, each naming the barcode: twice in the sale, expired, unknown, another method, switched off, length, digits")
	void refusals() {
		assertTrue(refusal(() -> sell(20.0, scan(meal, VALID, VALID))).contains("scanné deux fois"));
		String expired = refusal(() -> sell(20.0, scan(meal, EXPIRED)));
		assertTrue(expired.contains(EXPIRED) && expired.contains("périmé : valable jusqu'au 31/01/2026"), expired);
		assertTrue(refusal(() -> sell(20.0, scan(meal, "0990070002600002897009796069"))).contains("inconnu"));
		String other = refusal(() -> sell(20.0, scan(meal, GIFT)));
		assertTrue(other.contains("« CHEQUE_CADEAU », pas avec « TICKET_RESTAURANT »"), other);
		assertTrue(refusal(() -> sell(20.0, scan(meal, INACTIVE))).contains("désactivé"));
		String length = refusal(() -> sell(20.0, scan(meal, VALID.substring(0, 27))));
		assertTrue(length.contains("longueur incorrecte (27 chiffres, 28 attendus)"), length);
		assertTrue(refusal(() -> sell(20.0, scan(meal, "03100700026A0002897009796069"))).contains("chiffres uniquement"));
		assertTrue(refusal(() -> sell(20.0, scan(meal))).contains("Aucun ticket scanné"));
		assertTrue(refusal(() -> sell(20.0, scan(cash, VALID))).contains("scanned-tickets method only"));
		// Nothing of a refused sale is kept: processCompleteSale's transaction is rolled back (not in memory here)
	}

	@Test
	@DisplayName("A ticket held by a sale is refused on another one, with that sale's number and date")
	void alreadyUsed() throws Exception {
		SalesHeader first = sell(12.0, scan(meal, VALID), amount(cash, 5.7));
		String message = refusal(() -> sell(8.0, scan(meal, VALID)));
		assertTrue(message.startsWith("Ticket " + VALID + " déjà utilisé sur la vente " + first.getSalesNumber() + " du "),
				message);
	}

	@Test
	@DisplayName("Two tills, the same ticket at the same moment: the database refuses the second, answered 'already used'")
	void twoTillsRace() {
		otherTillTakesIt = true; // the check saw it free, the other till's sale holds it at the insert
		String message = refusal(() -> sell(12.0, scan(meal, VALID), amount(cash, 5.7)));
		assertEquals("Ticket " + VALID + " déjà utilisé : il vient d'être encaissé sur une autre caisse.", message);
	}

	@Test
	@DisplayName("The till's check: the reading, ALREADY_USED once a sale holds it (sale, session, cashier); reserves nothing")
	void check() throws Exception {
		Reading free = tickets.check(meal, VALID);
		assertEquals(Status.OK, free.getStatus());
		assertEquals(new BigDecimal("6.300"), free.getCountedValue());
		assertEquals(LocalDate.of(2027, 1, 31), free.getValidUntil());
		assertNull(free.getUsedSaleNumber());
		assertTrue(scanned.isEmpty(), "nothing reserved");
		assertEquals(Status.EXPIRED, tickets.check(meal, EXPIRED).getStatus());
		assertEquals(Status.OTHER_METHOD, tickets.check(meal, GIFT).getStatus());

		SalesHeader sale = sell(7.0, scan(meal, VALID), amount(cash, 0.7));
		Reading used = tickets.check(meal, VALID);
		assertEquals(Status.ALREADY_USED, used.getStatus());
		assertEquals(sale.getSalesNumber(), used.getUsedSaleNumber());
		assertEquals("SESSION261112001", used.getUsedSessionNumber());
		assertEquals("Amira Ben Salem", used.getUsedCashier());
		assertTrue(used.getUsedAt() != null);
		// The admin's test keeps the line's reading, the sale beside
		Reading admin = tickets.test(meal, VALID, LocalDate.of(2026, 11, 12));
		assertEquals(Status.OK, admin.getStatus());
		assertEquals(sale.getSalesNumber(), admin.getUsedSaleNumber());
	}

	@Test
	@DisplayName("Split bill and payment change: refused to and from a scanned-tickets method")
	void splitAndChange() {
		assertTrue(assertThrows(IllegalArgumentException.class, () -> SalesHeaderService.refuseScannedTickets(meal))
				.getMessage().contains("ni pour une addition partagée ni pour un changement"));
		SalesHeaderService.refuseScannedTickets(cash);
		SalesHeaderService.refuseScannedTickets(null);
	}
}
