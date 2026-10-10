package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.dto.PricingResult;
import com.digithink.zsretail.dto.ProcessSaleRequestDTO;
import com.digithink.zsretail.erp.service.SessionExportService;
import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.LoyaltyEarningTier;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.LoyaltyProgram;
import com.digithink.zsretail.model.LoyaltyTransaction;
import com.digithink.zsretail.model.Payment;
import com.digithink.zsretail.model.PaymentMethod;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.digithink.zsretail.repository.CashierSessionRepository;
import com.digithink.zsretail.repository.CustomerRepository;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.repository.LoyaltyProgramRepository;
import com.digithink.zsretail.repository.LoyaltyTransactionRepository;
import com.digithink.zsretail.repository.PaymentMethodRepository;
import com.digithink.zsretail.repository.SalesHeaderRepository;
import com.digithink.zsretail.repository.SalesLineRepository;
import com.digithink.zsretail.security.CurrentUserProvider;

/**
 * Loyalty points and fiscal stamp on both ways a sale is completed: a normal sale
 * (processCompleteSale) and a parked ticket completed later (completePendingSale).
 * Runs the real SalesHeaderService and LoyaltyService over in-memory stubs
 * (no Spring, no database, no mocking library).
 */
class SaleCompletionLoyaltyStampTest {

	private static final long ITEM_ID = 10L;
	private static final long CUSTOMER_ID = 5L;
	private static final long CASH_METHOD_ID = 1L;

	private SalesHeaderService sales;
	private final UserAccount cashier = new UserAccount();
	private final CashierSession session = new CashierSession();
	private final Customer customer = new Customer();
	private final Item item = new Item();
	private final Item stampItem = new Item();
	private final PaymentMethod cash = new PaymentMethod();

	private final Map<String, String> settings = new HashMap<>();
	private final Map<Long, SalesHeader> headers = new HashMap<>();
	private final List<SalesLine> savedLines = new ArrayList<>();
	private final List<SalesHeader> linesDeletedFor = new ArrayList<>();
	private final List<Payment> savedPayments = new ArrayList<>();
	private final List<Long> stockDecrementedItemIds = new ArrayList<>();
	private final List<SalesHeader> exportedHeaders = new ArrayList<>();
	private final List<Double> deductionSeenByExport = new ArrayList<>();
	private final Map<Long, LoyaltyMember> members = new HashMap<>();
	private final List<LoyaltyTransaction> loyaltyTransactions = new ArrayList<>();
	private LoyaltyProgram program;
	private long nextId;

	@BeforeEach
	void setUp() throws Exception {
		nextId = 1000;
		settings.clear();
		settings.put("LOYALTY_ENABLED", "true");
		settings.put("ENABLE_TAX_STAMP", "true");
		settings.put("TAX_STAMP_VALUE_MILLIMES", "100");
		settings.put("DEFAULT_LOCATION", "LOC001");

		cashier.setUsername("cashier");
		session.setId(7L);
		customer.setId(CUSTOMER_ID);
		item.setId(ITEM_ID);
		item.setItemCode("ART-10");
		item.setName("Article 10");
		item.setDefaultVAT(0);
		stampItem.setId(99L);
		stampItem.setItemCode("TAX_STAMP");
		stampItem.setName("Timbre Fiscal");
		cash.setId(CASH_METHOD_ID);
		cash.setName("Cash");
		cash.setType(PaymentMethodType.CLIENT_ESPECES);
		program = flatProgram();

		LoyaltyMemberRepository memberRepository = stub(LoyaltyMemberRepository.class, (m, a) -> {
			switch (m) {
				case "findById": return Optional.ofNullable(members.get(a[0]));
				case "save": return a[0];
				default: return UNHANDLED;
			}
		});
		GeneralSetupRepository setupRepository = stub(GeneralSetupRepository.class, (m, a) -> {
			if (!"findByCode".equals(m)) return UNHANDLED;
			if (!settings.containsKey(a[0])) return Optional.empty();
			GeneralSetup setup = new GeneralSetup();
			setup.setCode((String) a[0]);
			setup.setValeur(settings.get(a[0]));
			return Optional.of(setup);
		});

		LoyaltyService loyalty = new LoyaltyService();
		inject(loyalty, LoyaltyService.class, "loyaltyMemberRepository", memberRepository);
		inject(loyalty, LoyaltyService.class, "generalSetupRepository", setupRepository);
		inject(loyalty, LoyaltyService.class, "loyaltyProgramRepository", stub(LoyaltyProgramRepository.class, (m, a) ->
				"findCurrentActivePrograms".equals(m) ? new ArrayList<>(Collections.singletonList(program)) : UNHANDLED));
		inject(loyalty, LoyaltyService.class, "loyaltyTransactionRepository", stub(LoyaltyTransactionRepository.class, (m, a) -> {
			if (!"save".equals(m)) return UNHANDLED;
			loyaltyTransactions.add((LoyaltyTransaction) a[0]);
			return a[0];
		}));

		sales = new SalesHeaderService();
		inject(sales, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "cashier";
			}
		});
		inject(sales, SalesHeaderService.class, "loyaltyService", loyalty);
		inject(sales, SalesHeaderService.class, "loyaltyMemberRepository", memberRepository);
		inject(sales, SalesHeaderService.class, "generalSetupRepository", setupRepository);
		inject(sales, SalesHeaderService.class, "salesHeaderRepository", stub(SalesHeaderRepository.class, (m, a) -> {
			switch (m) {
				case "save": {
					SalesHeader h = (SalesHeader) a[0];
					if (h.getId() == null) h.setId(nextId++);
					headers.put(h.getId(), h);
					return h;
				}
				case "findById": return Optional.ofNullable(headers.get(a[0]));
				case "countBySalesDateGreaterThanEqual": return 0L;
				default: return UNHANDLED;
			}
		}));
		inject(sales, SalesHeaderService.class, "salesLineRepository", stub(SalesLineRepository.class, (m, a) -> {
			if (!"deleteBySalesHeader".equals(m)) return UNHANDLED;
			linesDeletedFor.add((SalesHeader) a[0]);
			return null;
		}));
		inject(sales, SalesHeaderService.class, "itemRepository", stub(ItemRepository.class, (m, a) -> {
			switch (m) {
				case "findById": return ITEM_ID == (Long) a[0] ? Optional.of(item) : Optional.empty();
				case "findByItemCode": return "TAX_STAMP".equals(a[0]) ? Optional.of(stampItem) : Optional.empty();
				default: return UNHANDLED;
			}
		}));
		inject(sales, SalesHeaderService.class, "customerRepository", stub(CustomerRepository.class, (m, a) ->
				"findById".equals(m) ? (CUSTOMER_ID == (Long) a[0] ? Optional.of(customer) : Optional.empty()) : UNHANDLED));
		inject(sales, SalesHeaderService.class, "paymentMethodRepository", stub(PaymentMethodRepository.class, (m, a) ->
				"findById".equals(m) ? (CASH_METHOD_ID == (Long) a[0] ? Optional.of(cash) : Optional.empty()) : UNHANDLED));
		inject(sales, SalesHeaderService.class, "cashierSessionRepository", stub(CashierSessionRepository.class, (m, a) ->
				"findByCashierAndStatus".equals(m) ? Optional.of(session) : UNHANDLED));
		inject(sales, SalesHeaderService.class, "salesLineService", new SalesLineService() {
			@Override
			public SalesLine save(SalesLine line) {
				if (line.getId() == null) line.setId(nextId++);
				savedLines.add(line);
				return line;
			}
		});
		inject(sales, SalesHeaderService.class, "paymentService", new PaymentService() {
			@Override
			public Payment save(Payment payment) {
				if (payment.getId() == null) payment.setId(nextId++);
				savedPayments.add(payment);
				return payment;
			}
		});
		inject(sales, SalesHeaderService.class, "stockService", new StockService() {
			@Override
			public void decrementForSale(Long itemId, BigDecimal quantity) {
				stockDecrementedItemIds.add(itemId);
			}
		});
		inject(sales, SalesHeaderService.class, "stockMovementService", new StockMovementService() {
			@Override
			public void recordSale(Long itemId, BigDecimal quantity, Double unitPriceHt, Integer vatPercent, Double unitPriceTtc,
					Long salesHeaderId, CashierSession cashierSession) {
				// recorded through decrementForSale
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
					public void createPaymentHeadersAndLinesAsync(List<Payment> payments, SalesHeader salesHeader) {
						exportedHeaders.add(salesHeader);
						deductionSeenByExport.add(salesHeader.getLoyaltyDeductionAmount());
					}
				});
	}

	// ─── Normal sale: current behaviour must not change ──────────────

	@Test
	@DisplayName("Normal sale: stamp line added, flat points on the total including the stamp")
	void normalSaleFlatProgram() throws Exception {
		LoyaltyMember m = member(0);
		SalesHeader sale = sales.processCompleteSale(request(150, m, 0), cashier);
		assertStampLineOnce(sale);
		assertEquals(150, sale.getLoyaltyPointsEarned().intValue()); // floor(150.1 x 1)
		assertEquals(150, m.getLoyaltyPoints().intValue());
		assertSame(m, sale.getLoyaltyMember());
		assertEquals(Collections.singletonList(ITEM_ID), stockDecrementedItemIds);
		assertEquals(1, exportedHeaders.size());
	}

	@Test
	@DisplayName("Normal sale: tiered program earns on the amount without the stamp")
	void normalSaleTieredProgram() throws Exception {
		program = tieredProgram();
		LoyaltyMember m = member(0);
		SalesHeader sale = sales.processCompleteSale(request(350, m, 0), cashier);
		assertEquals(525, sale.getLoyaltyPointsEarned().intValue());
		assertEquals(525, m.getLoyaltyPoints().intValue());
	}

	@Test
	@DisplayName("Normal sale: converted points are debited before the NAV export sees the ticket")
	void normalSaleRedemption() throws Exception {
		LoyaltyMember m = member(1000);
		SalesHeader sale = sales.processCompleteSale(request(150, m, 600), cashier); // 30 DT off, pays 120.100
		assertEquals(600, sale.getLoyaltyPointsRedeemed().intValue());
		assertEquals(30.0, sale.getLoyaltyDeductionAmount().doubleValue());
		assertEquals(120, sale.getLoyaltyPointsEarned().intValue());
		assertEquals(1000 - 600 + 120, m.getLoyaltyPoints().intValue());
		assertEquals(Collections.singletonList(30.0), deductionSeenByExport);
	}

	@Test
	@DisplayName("Normal sale without a card: stamp line only, no loyalty")
	void normalSaleWithoutMember() throws Exception {
		SalesHeader sale = sales.processCompleteSale(request(150, null, 0), cashier);
		assertStampLineOnce(sale);
		assertNull(sale.getLoyaltyMember());
		assertTrue(loyaltyTransactions.isEmpty());
	}

	@Test
	@DisplayName("Normal sale with the stamp disabled: no stamp line, tiers use the full total")
	void normalSaleStampDisabled() throws Exception {
		settings.put("ENABLE_TAX_STAMP", "false");
		program = tieredProgram();
		LoyaltyMember m = member(0);
		SalesHeader sale = sales.processCompleteSale(request(350, m, 0), cashier);
		assertEquals(0, stampLines(sale).size());
		assertEquals(525, sale.getLoyaltyPointsEarned().intValue());
	}

	@Test
	@DisplayName("2.2.1, one tax stamp rule: TRUE and 1 add the stamp line as true does (the till's reading); 0 adds none")
	void stampSettingReadings() throws Exception {
		for (String on : new String[] { "TRUE", "1" }) {
			settings.put("ENABLE_TAX_STAMP", on);
			assertStampLineOnce(sales.processCompleteSale(request(150, null, 0), cashier));
		}
		settings.put("ENABLE_TAX_STAMP", "0");
		assertEquals(0, stampLines(sales.processCompleteSale(request(150, null, 0), cashier)).size());
	}

	// ─── Parked ticket completed later ───────────────────────────────

	@Test
	@DisplayName("Parked ticket: completing it earns points and adds the stamp line once")
	void parkedTicketEarnsPoints() throws Exception {
		program = tieredProgram();
		LoyaltyMember m = member(0);
		SalesHeader parked = parkedTicket();
		SalesHeader sale = sales.completePendingSale(parked.getId(), request(350, m, 0), cashier);
		assertEquals(TransactionStatus.COMPLETED, sale.getStatus());
		assertEquals(Collections.singletonList(parked), linesDeletedFor);
		assertStampLineOnce(sale);
		assertSame(m, sale.getLoyaltyMember());
		assertEquals(525, sale.getLoyaltyPointsEarned().intValue());
		assertEquals(525, m.getLoyaltyPoints().intValue());
		assertEquals(Collections.singletonList(ITEM_ID), stockDecrementedItemIds);
		assertEquals(1, exportedHeaders.size());
	}

	@Test
	@DisplayName("Parked ticket: converted points are debited before the NAV export sees the ticket")
	void parkedTicketRedemption() throws Exception {
		LoyaltyMember m = member(1000);
		SalesHeader parked = parkedTicket();
		SalesHeader sale = sales.completePendingSale(parked.getId(), request(150, m, 600), cashier);
		assertEquals(600, sale.getLoyaltyPointsRedeemed().intValue());
		assertEquals(30.0, sale.getLoyaltyDeductionAmount().doubleValue());
		assertEquals(1000 - 600 + 120, m.getLoyaltyPoints().intValue());
		assertEquals(Collections.singletonList(30.0), deductionSeenByExport);
	}

	@Test
	@DisplayName("Parked ticket without a card: stamp line added once, no loyalty")
	void parkedTicketWithoutMember() throws Exception {
		SalesHeader parked = parkedTicket();
		SalesHeader sale = sales.completePendingSale(parked.getId(), request(150, null, 0), cashier);
		assertStampLineOnce(sale);
		assertNull(sale.getLoyaltyMember());
		assertTrue(loyaltyTransactions.isEmpty());
	}

	@Test
	@DisplayName("Parked ticket with the stamp disabled: no stamp line, tiers use the full total")
	void parkedTicketStampDisabled() throws Exception {
		settings.put("ENABLE_TAX_STAMP", "false");
		program = tieredProgram();
		LoyaltyMember m = member(0);
		SalesHeader parked = parkedTicket();
		SalesHeader sale = sales.completePendingSale(parked.getId(), request(350, m, 0), cashier);
		assertEquals(0, stampLines(sale).size());
		assertEquals(525, sale.getLoyaltyPointsEarned().intValue());
	}

	@Test
	@DisplayName("A ticket that is not parked cannot be completed as parked (unchanged)")
	void completedTicketIsRejected() throws Exception {
		SalesHeader parked = parkedTicket();
		parked.setStatus(TransactionStatus.COMPLETED);
		assertThrows(IllegalStateException.class,
				() -> sales.completePendingSale(parked.getId(), request(150, null, 0), cashier));
	}

	// ─── Conversion limit: the till and the server must agree ────────

	@Test
	@DisplayName("Conversion: 600 points (30 DT) on a 50 DT ticket is accepted")
	void largeConversionAccepted() throws Exception {
		LoyaltyMember m = member(600);
		SalesHeader sale = sales.processCompleteSale(request(50, m, 600), cashier); // pays 20.100
		assertEquals(600, sale.getLoyaltyPointsRedeemed().intValue());
		assertEquals(30.0, sale.getLoyaltyDeductionAmount().doubleValue());
		assertEquals(20, m.getLoyaltyPoints().intValue()); // 600 - 600 + floor(20.1)
	}

	@Test
	@DisplayName("Conversion: points can pay all the goods, the fiscal stamp is still paid")
	void conversionCoversAllGoods() throws Exception {
		LoyaltyMember m = member(600);
		SalesHeader sale = sales.processCompleteSale(request(30, m, 600), cashier); // pays only the 0.100 stamp
		assertEquals(30.0, sale.getLoyaltyDeductionAmount().doubleValue());
		assertEquals(0, m.getLoyaltyPoints().intValue());
	}

	@Test
	@DisplayName("Conversion: exactly at the program limit is accepted, one point more is refused with its reason")
	void conversionAtAndAboveLimit() throws Exception {
		program.setMaximumRedemptionPercentage(50.0);
		program.setMinimumRedemptionPoints(100);
		LoyaltyMember m = member(1000);
		SalesHeader sale = sales.processCompleteSale(request(40, m, 400), cashier); // 20 DT = 50% of 40 DT
		assertEquals(20.0, sale.getLoyaltyDeductionAmount().doubleValue());

		LoyaltyMember other = member(1000);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> sales.processCompleteSale(request(40, other, 401), cashier));
		assertTrue(e.getMessage().contains("exceeds maximum"), e.getMessage());
		assertEquals(1000, other.getLoyaltyPoints().intValue());
	}

	@Test
	@DisplayName("Conversion refused (balance too low): the sale fails with the real reason")
	void refusedConversionFailsWithReason() {
		LoyaltyMember m = member(500);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> sales.processCompleteSale(request(150, m, 600), cashier));
		assertTrue(e.getMessage().contains("Insufficient points"), e.getMessage());
	}

	@Test
	@DisplayName("Conversion below the 600-point minimum is refused with its reason (unchanged rule)")
	void belowMinimumRefusedWithReason() {
		LoyaltyMember m = member(1000);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> sales.processCompleteSale(request(150, m, 500), cashier));
		assertTrue(e.getMessage().contains("Minimum redemption is 600"), e.getMessage());
		assertEquals(1000, m.getLoyaltyPoints().intValue());
	}

	@Test
	@DisplayName("Parked ticket: 600 points (30 DT) on a 50 DT ticket is accepted")
	void parkedTicketLargeConversionAccepted() throws Exception {
		LoyaltyMember m = member(600);
		SalesHeader parked = parkedTicket();
		SalesHeader sale = sales.completePendingSale(parked.getId(), request(50, m, 600), cashier);
		assertEquals(30.0, sale.getLoyaltyDeductionAmount().doubleValue());
		assertEquals(20, m.getLoyaltyPoints().intValue());
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	/**
	 * What the till sends: one article line of {@code goods} DT, the 0.100 DT stamp included in
	 * the total when enabled, minus the points deduction (50 millimes per point).
	 */
	private ProcessSaleRequestDTO request(double goods, LoyaltyMember m, int pointsToRedeem) {
		double stamp = "true".equals(settings.get("ENABLE_TAX_STAMP")) ? 0.1 : 0.0;
		double total = goods + stamp - pointsToRedeem * 50 / 1000.0;
		item.setUnitPrice(goods);

		ProcessSaleRequestDTO.SaleLineDTO line = new ProcessSaleRequestDTO.SaleLineDTO();
		line.setItemId(ITEM_ID);
		line.setQuantity(BigDecimal.ONE);
		line.setUnitPrice(goods);
		line.setLineTotal(goods);
		line.setVatPercent(0);
		line.setVatAmount(0.0);
		line.setUnitPriceIncludingVat(goods);
		line.setLineTotalIncludingVat(goods);

		ProcessSaleRequestDTO.PaymentDTO payment = new ProcessSaleRequestDTO.PaymentDTO();
		payment.setPaymentMethodId(CASH_METHOD_ID);
		payment.setAmount(total);

		ProcessSaleRequestDTO r = new ProcessSaleRequestDTO();
		r.setSubtotal(goods);
		r.setTaxAmount(0.0);
		r.setTotalAmount(total);
		r.setPaidAmount(total);
		r.setChangeAmount(0.0);
		r.setCustomerId(CUSTOMER_ID);
		r.setLines(new ArrayList<>(Collections.singletonList(line)));
		r.setPayments(new ArrayList<>(Collections.singletonList(payment)));
		r.setLoyaltyMemberId(m != null ? m.getId() : null);
		r.setLoyaltyPointsToRedeem(pointsToRedeem);
		return r;
	}

	private SalesHeader parkedTicket() {
		SalesHeader h = new SalesHeader();
		h.setId(nextId++);
		h.setSalesNumber("LOC001-PARKED");
		h.setStatus(TransactionStatus.PENDING);
		h.setCashierSession(session);
		h.setCustomer(customer);
		headers.put(h.getId(), h);
		return h;
	}

	private LoyaltyMember member(int points) {
		LoyaltyMember m = new LoyaltyMember();
		m.setId(nextId++);
		m.setCardNumber("LYL-" + m.getId());
		m.setLoyaltyPoints(points);
		m.setTotalPointsEarned(0);
		m.setTotalPointsRedeemed(0);
		m.setActive(true);
		members.put(m.getId(), m);
		return m;
	}

	/** 1 point per DT, 1 point = 50 millimes, conversion from 600 points, no cap. */
	private LoyaltyProgram flatProgram() {
		LoyaltyProgram p = new LoyaltyProgram();
		p.setId(nextId++);
		p.setProgramCode("PRG");
		p.setName("Program");
		p.setPointsPerDinar(1.0);
		p.setPointValueMillimes(50);
		p.setMinimumRedemptionPoints(600);
		p.setMaximumRedemptionPercentage(100.0);
		p.setEarningTiers(new ArrayList<>());
		p.setActive(true);
		return p;
	}

	/** Same program with the client's tiers: above 200 DT 1.5, above 500 DT 2. */
	private LoyaltyProgram tieredProgram() {
		LoyaltyProgram p = flatProgram();
		p.setEarningTiers(new ArrayList<>(Arrays.asList(
				new LoyaltyEarningTier(200.0, 1.5), new LoyaltyEarningTier(500.0, 2.0))));
		return p;
	}

	private List<SalesLine> stampLines(SalesHeader sale) {
		return savedLines.stream()
				.filter(l -> l.getSalesHeader() == sale && "TAX_STAMP".equals(l.getItem().getItemCode()))
				.collect(Collectors.toList());
	}

	private void assertStampLineOnce(SalesHeader sale) {
		List<SalesLine> stamps = stampLines(sale);
		assertEquals(1, stamps.size(), "exactly one fiscal stamp line");
		SalesLine stamp = stamps.get(0);
		assertEquals(1, stamp.getQuantity().intValue());
		assertEquals(0.1, stamp.getUnitPrice().doubleValue());
		assertEquals(0.1, stamp.getLineTotalIncludingVat().doubleValue());
		assertEquals(0, stamp.getVatPercent().intValue());
	}

	// ─── Stub plumbing ───────────────────────────────────────────────

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

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
