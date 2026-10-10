package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
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

import com.digithink.zsretail.dto.ProcessReturnRequestDTO;
import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.LoyaltyEarningTier;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.LoyaltyProgram;
import com.digithink.zsretail.model.LoyaltyTransaction;
import com.digithink.zsretail.model.ReturnHeader;
import com.digithink.zsretail.model.ReturnLine;
import com.digithink.zsretail.model.ReturnVoucher;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.LoyaltyTransactionType;
import com.digithink.zsretail.model.enumeration.ReturnType;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.digithink.zsretail.repository.CashierSessionRepository;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.repository.LoyaltyProgramRepository;
import com.digithink.zsretail.repository.LoyaltyTransactionRepository;
import com.digithink.zsretail.repository.ReturnHeaderRepository;
import com.digithink.zsretail.repository.ReturnLineRepository;
import com.digithink.zsretail.repository.ReturnVoucherRepository;
import com.digithink.zsretail.repository.SalesHeaderRepository;
import com.digithink.zsretail.repository.SalesLineRepository;
import com.digithink.zsretail.security.CurrentUserProvider;

/**
 * Returns: money refunded and member points. Tickets without loyalty points keep their
 * refund rules unchanged; tickets partly paid with points refund only the money actually
 * paid, give the converted points back, and remove only the points the returned goods earned.
 * Runs the real ReturnHeaderService and LoyaltyService over in-memory stubs.
 */
class ReturnRefundLoyaltyTest {

	private static final double STAMP = 0.1;
	private static final double EPS = 1e-9;

	private ReturnHeaderService returns;
	private final UserAccount cashier = new UserAccount();
	private final CashierSession session = new CashierSession();
	private final Item stampItem = new Item();

	private final Map<String, String> settings = new HashMap<>();
	private final Map<String, SalesHeader> salesByNumber = new HashMap<>();
	private final Map<SalesHeader, List<SalesLine>> linesBySale = new java.util.IdentityHashMap<>(); // tickets mutate, so key by identity
	private final List<ReturnHeader> savedReturns = new ArrayList<>();
	private final List<ReturnLine> savedReturnLines = new ArrayList<>();
	private final List<ReturnVoucher> savedVouchers = new ArrayList<>();
	private final Map<Long, LoyaltyMember> members = new HashMap<>();
	private final List<LoyaltyTransaction> loyaltyTransactions = new ArrayList<>();
	private long nextId;

	@BeforeEach
	void setUp() throws Exception {
		nextId = 1000;
		settings.clear();
		settings.put("LOYALTY_ENABLED", "true");
		settings.put("ENABLE_SIMPLE_RETURN", "true");
		cashier.setUsername("cashier");
		session.setId(7L);
		stampItem.setId(99L);
		stampItem.setItemCode("TAX_STAMP");
		stampItem.setName("Timbre Fiscal");

		GeneralSetupRepository setupRepository = stub(GeneralSetupRepository.class, (m, a) -> {
			if (!"findByCode".equals(m)) return UNHANDLED;
			if (!settings.containsKey(a[0])) return Optional.empty();
			GeneralSetup setup = new GeneralSetup();
			setup.setCode((String) a[0]);
			setup.setValeur(settings.get(a[0]));
			return Optional.of(setup);
		});

		LoyaltyService loyalty = new LoyaltyService();
		inject(loyalty, LoyaltyService.class, "generalSetupRepository", setupRepository);
		inject(loyalty, LoyaltyService.class, "loyaltyMemberRepository", stub(LoyaltyMemberRepository.class, (m, a) -> {
			switch (m) {
				case "findById": return Optional.ofNullable(members.get(a[0]));
				case "save": return a[0];
				default: return UNHANDLED;
			}
		}));
		inject(loyalty, LoyaltyService.class, "loyaltyProgramRepository", stub(LoyaltyProgramRepository.class, (m, a) -> UNHANDLED));
		inject(loyalty, LoyaltyService.class, "loyaltyTransactionRepository", stub(LoyaltyTransactionRepository.class, (m, a) -> {
			switch (m) {
				case "save":
					loyaltyTransactions.add((LoyaltyTransaction) a[0]);
					return a[0];
				case "findByLoyaltyMemberAndSalesHeaderAndType":
					return transactionsOf((LoyaltyMember) a[0], (SalesHeader) a[1], (LoyaltyTransactionType) a[2]);
				case "findTopByLoyaltyMemberAndSalesHeaderAndTypeOrderByCreatedAtDesc": {
					List<LoyaltyTransaction> found = transactionsOf((LoyaltyMember) a[0], (SalesHeader) a[1], (LoyaltyTransactionType) a[2]);
					return found.isEmpty() ? Optional.empty() : Optional.of(found.get(found.size() - 1));
				}
				default: return UNHANDLED;
			}
		}));

		returns = new ReturnHeaderService();
		inject(returns, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "cashier";
			}
		});
		inject(returns, ReturnHeaderService.class, "loyaltyService", loyalty);
		inject(returns, ReturnHeaderService.class, "generalSetupRepository", setupRepository);
		inject(returns, ReturnHeaderService.class, "cashierSessionRepository", stub(CashierSessionRepository.class, (m, a) ->
				"findByCashierAndStatus".equals(m) ? Optional.of(session) : UNHANDLED));
		inject(returns, ReturnHeaderService.class, "salesHeaderRepository", stub(SalesHeaderRepository.class, (m, a) ->
				"findBySalesNumber".equals(m) ? Optional.ofNullable(salesByNumber.get(a[0])) : UNHANDLED));
		inject(returns, ReturnHeaderService.class, "salesLineRepository", stub(SalesLineRepository.class, (m, a) ->
				"findBySalesHeader".equals(m) ? new ArrayList<>(linesBySale.getOrDefault(a[0], new ArrayList<>())) : UNHANDLED));
		inject(returns, ReturnHeaderService.class, "returnHeaderRepository", stub(ReturnHeaderRepository.class, (m, a) -> {
			switch (m) {
				case "save": {
					ReturnHeader h = (ReturnHeader) a[0];
					if (h.getId() == null) h.setId(nextId++);
					if (savedReturns.stream().noneMatch(r -> r == h)) savedReturns.add(h);
					return h;
				}
				case "findAllByOriginalSalesHeader":
					return savedReturns.stream().filter(r -> r.getOriginalSalesHeader() == a[0]).collect(Collectors.toList());
				case "count": return (long) savedReturns.size();
				default: return UNHANDLED;
			}
		}));
		inject(returns, ReturnHeaderService.class, "returnLineRepository", stub(ReturnLineRepository.class, (m, a) -> {
			switch (m) {
				case "save": {
					ReturnLine l = (ReturnLine) a[0];
					if (l.getId() == null) l.setId(nextId++);
					savedReturnLines.add(l);
					return l;
				}
				case "findByReturnHeader":
					return savedReturnLines.stream().filter(l -> l.getReturnHeader() == a[0]).collect(Collectors.toList());
				default: return UNHANDLED;
			}
		}));
		inject(returns, ReturnHeaderService.class, "returnVoucherRepository", stub(ReturnVoucherRepository.class, (m, a) -> {
			switch (m) {
				case "save": {
					ReturnVoucher v = (ReturnVoucher) a[0];
					if (v.getId() == null) v.setId(nextId++);
					savedVouchers.add(v);
					return v;
				}
				case "count": return (long) savedVouchers.size();
				default: return UNHANDLED;
			}
		}));
		inject(returns, ReturnHeaderService.class, "stockService", new StockService() {
			@Override
			public void incrementForReturn(Long itemId, BigDecimal quantity) {
				// stock is not under test
			}
		});
		inject(returns, ReturnHeaderService.class, "stockMovementService", new StockMovementService() {
			@Override
			public void recordSimpleReturn(Long itemId, BigDecimal quantity, Double unitPriceHt, Integer vatPercent,
					Double unitPriceTtc, Long returnHeaderId, CashierSession s) {
				// stock is not under test
			}

			@Override
			public void recordVoucherReturn(Long itemId, BigDecimal quantity, Double unitPriceHt, Integer vatPercent,
					Double unitPriceTtc, Long returnHeaderId, CashierSession s) {
				// stock is not under test
			}
		});
	}

	// ─── Tickets without loyalty points: refund rules unchanged ──────

	@Test
	@DisplayName("No discount: returning one article refunds its price")
	void noDiscountRefund() throws Exception {
		SalesHeader sale = sale(null, 0.0, 0, 0.0, 100.0, 50.0);
		ReturnHeader r = returnLines(sale, ReturnType.SIMPLE_RETURN, 1);
		assertEquals(50.0, r.getTotalReturnAmount().doubleValue(), 1e-6);
		assertNull(r.getDiscountPercentage());
	}

	@Test
	@DisplayName("Percentage discount: the refund applies the ticket's percentage")
	void percentDiscountRefund() throws Exception {
		SalesHeader sale = sale(10.0, 0.0, 0, 0.0, 100.0, 50.0);
		ReturnHeader r = returnLines(sale, ReturnType.SIMPLE_RETURN, 0);
		assertEquals(90.0, r.getTotalReturnAmount().doubleValue(), 1e-6);
		assertEquals(10.0, r.getDiscountPercentage().doubleValue());
	}

	@Test
	@DisplayName("Amount discount: the refund applies the ticket ratio (unchanged formula)")
	void amountDiscountRefund() throws Exception {
		SalesHeader sale = sale(null, 15.0, 0, 0.0, 100.0, 50.0);
		ReturnHeader r = returnLines(sale, ReturnType.SIMPLE_RETURN, 0);
		assertEquals(100.0 * sale.getTotalAmount() / (150.0 + STAMP), r.getTotalReturnAmount().doubleValue(), 1e-9);
		assertNull(r.getDiscountPercentage());
	}

	@Test
	@DisplayName("Return voucher: the voucher amount equals the refund")
	void voucherAmountEqualsRefund() throws Exception {
		SalesHeader sale = sale(10.0, 0.0, 0, 0.0, 100.0, 50.0);
		ReturnHeader r = returnLines(sale, ReturnType.RETURN_VOUCHER, 0);
		assertEquals(1, savedVouchers.size());
		assertEquals(r.getTotalReturnAmount().doubleValue(), savedVouchers.get(0).getVoucherAmount().doubleValue(), EPS);
	}

	@Test
	@DisplayName("Points already spent: removing earned points never makes the balance negative (unchanged)")
	void balanceNeverNegative() throws Exception {
		LoyaltyMember m = member(0);
		SalesHeader sale = sale(null, 0.0, 0, 0.0, 50.0);
		earned(sale, m, flatProgram(), 50);
		returnLines(sale, ReturnType.SIMPLE_RETURN, 0);
		assertEquals(0, m.getLoyaltyPoints().intValue());
	}

	// ─── Tickets partly paid with points ─────────────────────────────

	@Test
	@DisplayName("Full return of a ticket paid partly with points: refund the money paid, give the points back")
	void pointsTicketFullReturn() throws Exception {
		LoyaltyMember m = member(20); // 600 before the sale, -600 converted, +20 earned
		SalesHeader sale = sale(null, 0.0, 600, 30.0, 50.0);
		earned(sale, m, flatProgram(), 20);
		redeemed(sale, m, 600);
		ReturnHeader r = returnLines(sale, ReturnType.SIMPLE_RETURN, 0);
		assertEquals(20.0, r.getTotalReturnAmount().doubleValue(), 1e-9); // money paid for the goods
		assertEquals(60.0, r.getDiscountPercentage().doubleValue(), 1e-9); // 50 x (1 - 60%) = 20, as NAV computes it
		assertEquals(600, m.getLoyaltyPoints().intValue()); // back to the balance before the sale
		assertEquals(600, sumOf(sale, m, LoyaltyTransactionType.ADJUSTED));
		assertEquals(20, sumOf(sale, m, LoyaltyTransactionType.REVERSED));
		LoyaltyTransaction back = transactionsOf(m, sale, LoyaltyTransactionType.ADJUSTED).get(0);
		assertSame(r, back.getReturnHeader());
		assertTrue(back.getBalanceAfter() > back.getBalanceBefore(), "shown as + in the history");
	}

	@Test
	@DisplayName("Points and a 10% cart discount: 100 DT goods, 30 DT in points, the full return refunds 60 DT, not 90")
	void pointsTicketWithPercentDiscount() throws Exception {
		LoyaltyMember m = member(60);
		SalesHeader sale = sale(10.0, 0.0, 600, 30.0, 100.0);
		earned(sale, m, flatProgram(), 60);
		redeemed(sale, m, 600);
		ReturnHeader r = returnLines(sale, ReturnType.RETURN_VOUCHER, 0);
		assertEquals(60.0, r.getTotalReturnAmount().doubleValue(), 1e-9);
		assertEquals(60.0, savedVouchers.get(0).getVoucherAmount().doubleValue(), 1e-9);
		assertEquals(600, m.getLoyaltyPoints().intValue());
	}

	@Test
	@DisplayName("Partial returns of a points ticket: money and points go back in proportion, the last return completes")
	void pointsTicketPartialReturns() throws Exception {
		LoyaltyMember m = member(20);
		SalesHeader sale = sale(null, 0.0, 600, 30.0, 30.0, 20.0);
		earned(sale, m, flatProgram(), 20);
		redeemed(sale, m, 600);

		ReturnHeader first = returnLines(sale, ReturnType.SIMPLE_RETURN, 0); // 30 of 50 DT of goods
		assertEquals(12.0, first.getTotalReturnAmount().doubleValue(), 1e-9); // 30 x 20/50
		assertEquals(360, sumOf(sale, m, LoyaltyTransactionType.ADJUSTED)); // 60% of 600
		assertEquals(12, sumOf(sale, m, LoyaltyTransactionType.REVERSED)); // keeps floor(8.1) = 8

		ReturnHeader second = returnLines(sale, ReturnType.SIMPLE_RETURN, 1); // the remaining 20 DT
		assertEquals(8.0, second.getTotalReturnAmount().doubleValue(), 1e-9);
		assertEquals(600, sumOf(sale, m, LoyaltyTransactionType.ADJUSTED));
		assertEquals(20, sumOf(sale, m, LoyaltyTransactionType.REVERSED));
		assertEquals(600, m.getLoyaltyPoints().intValue());
	}

	@Test
	@DisplayName("Tiered program: returning 200 of 650 DT recalculates the points on the 450 DT kept")
	void tieredPartialReturns() throws Exception {
		LoyaltyMember m = member(1300);
		SalesHeader sale = sale(null, 0.0, 0, 0.0, 200.0, 450.0);
		earned(sale, m, tieredProgram(), 1300);

		returnLines(sale, ReturnType.SIMPLE_RETURN, 0);
		assertEquals(625, sumOf(sale, m, LoyaltyTransactionType.REVERSED)); // 1300 - 450 x 1.5
		assertEquals(675, m.getLoyaltyPoints().intValue());

		returnLines(sale, ReturnType.SIMPLE_RETURN, 1);
		assertEquals(1300, sumOf(sale, m, LoyaltyTransactionType.REVERSED)); // never more than earned
		assertEquals(0, m.getLoyaltyPoints().intValue());
	}

	// ─── 2.2.1: decimal quantities are not returned yet ──────────────

	@Test
	@DisplayName("2.2.1: a line sold with decimals is refused naming the item, nothing written; the whole line of the same ticket returns as in 2.2.0")
	void decimalSoldLineRefused() throws Exception {
		SalesHeader sale = sale(null, 0.0, 0, 0.0, 10.0, 50.0);
		SalesLine bulk = linesBySale.get(sale).get(0);
		bulk.setQuantity(new BigDecimal("0.2"));
		bulk.getItem().setItemCode("VH52-1L");
		bulk.getItem().setName("V H 52 1L");

		IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> returnLines(sale, ReturnType.SIMPLE_RETURN, 0, 1));
		assertEquals("Item VH52-1L (V H 52 1L): the quantity 0.2 has decimals, and decimal quantities are not supported"
				+ " in returns yet.", refused.getMessage());
		assertTrue(savedReturns.isEmpty(), "nothing written");
		assertTrue(savedReturnLines.isEmpty(), "nothing written");

		ReturnHeader r = returnLines(sale, ReturnType.SIMPLE_RETURN, 1);
		assertEquals(50.0, r.getTotalReturnAmount().doubleValue(), 1e-6);
		assertEquals(1, savedReturnLines.size());
		assertEquals(Integer.valueOf(1), savedReturnLines.get(0).getQuantity());
	}

	@Test
	@DisplayName("2.2.1: a decimal quantity asked on a whole line is refused naming the item (0.5 was read as 0 until 2.2.0); 1.0 is 1")
	void decimalAskedRefused() throws Exception {
		SalesHeader sale = sale(null, 0.0, 0, 0.0, 50.0);
		SalesLine line = linesBySale.get(sale).get(0);
		ProcessReturnRequestDTO.ReturnLineDTO dto = new ProcessReturnRequestDTO.ReturnLineDTO();
		dto.setSalesLineId(line.getId());
		dto.setQuantity(new BigDecimal("0.5"));
		ProcessReturnRequestDTO request = new ProcessReturnRequestDTO();
		request.setTicketNumber(sale.getSalesNumber());
		request.setReturnType(ReturnType.SIMPLE_RETURN);
		request.setReturnLines(new ArrayList<>(Arrays.asList(dto)));
		IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> returns.processReturn(request, cashier));
		assertTrue(refused.getMessage().startsWith("Item " + line.getItem().getItemCode() + " ("), refused.getMessage());
		assertTrue(refused.getMessage().contains("the quantity 0.5 has decimals"), refused.getMessage());
		assertTrue(savedReturns.isEmpty());

		dto.setQuantity(new BigDecimal("1.0"));
		ReturnHeader r = returns.processReturn(request, cashier);
		assertEquals(50.0, r.getTotalReturnAmount().doubleValue(), 1e-6);
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	/**
	 * A completed ticket with one line per amount (TTC, quantity 1) plus the 0.100 DT stamp line.
	 * Total = goods after the header discount + stamp - points deduction, as the till sends it.
	 */
	private SalesHeader sale(Double discountPct, double discountAmount, int pointsRedeemed, double deduction,
			double... goods) {
		SalesHeader s = new SalesHeader();
		s.setId(nextId++);
		s.setSalesNumber("T-" + s.getId());
		s.setStatus(TransactionStatus.COMPLETED);
		s.setSalesDate(LocalDateTime.now());
		double gross = Arrays.stream(goods).sum();
		double afterHeader = discountPct != null ? gross * (1 - discountPct / 100.0) : gross - discountAmount;
		s.setDiscountPercentage(discountPct);
		s.setDiscountAmount(discountAmount);
		s.setTotalAmount(afterHeader + STAMP - deduction);
		if (pointsRedeemed > 0) {
			s.setLoyaltyPointsRedeemed(pointsRedeemed);
			s.setLoyaltyDeductionAmount(deduction);
		}
		List<SalesLine> lines = new ArrayList<>();
		for (double amount : goods) {
			Item item = new Item();
			item.setId(nextId++);
			item.setItemCode("ART-" + item.getId());
			item.setName("Article " + item.getId());
			lines.add(line(s, item, amount));
		}
		lines.add(line(s, stampItem, STAMP));
		salesByNumber.put(s.getSalesNumber(), s);
		linesBySale.put(s, lines);
		return s;
	}

	private SalesLine line(SalesHeader s, Item item, double ttc) {
		SalesLine l = new SalesLine();
		l.setId(nextId++);
		l.setSalesHeader(s);
		l.setItem(item);
		l.setQuantity(BigDecimal.ONE);
		l.setUnitPrice(ttc);
		l.setLineTotal(ttc);
		l.setVatPercent(0);
		l.setVatAmount(0.0);
		l.setUnitPriceIncludingVat(ttc);
		l.setLineTotalIncludingVat(ttc);
		return l;
	}

	/** Returns the article lines at the given indexes (stamp line excluded), whole quantity. */
	private ReturnHeader returnLines(SalesHeader sale, ReturnType type, int... indexes) throws Exception {
		List<SalesLine> lines = linesBySale.get(sale);
		List<ProcessReturnRequestDTO.ReturnLineDTO> dtos = new ArrayList<>();
		for (int i : indexes) {
			ProcessReturnRequestDTO.ReturnLineDTO dto = new ProcessReturnRequestDTO.ReturnLineDTO();
			dto.setSalesLineId(lines.get(i).getId());
			dto.setQuantity(java.math.BigDecimal.ONE);
			dtos.add(dto);
		}
		ProcessReturnRequestDTO request = new ProcessReturnRequestDTO();
		request.setTicketNumber(sale.getSalesNumber());
		request.setReturnType(type);
		request.setReturnLines(dtos);
		return returns.processReturn(request, cashier);
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

	private void earned(SalesHeader sale, LoyaltyMember m, LoyaltyProgram program, int points) {
		sale.setLoyaltyMember(m);
		sale.setLoyaltyPointsEarned(points);
		loyaltyTransactions.add(tx(sale, m, program, LoyaltyTransactionType.EARNED, points));
		m.setTotalPointsEarned(m.getTotalPointsEarned() + points);
	}

	private void redeemed(SalesHeader sale, LoyaltyMember m, int points) {
		loyaltyTransactions.add(tx(sale, m, null, LoyaltyTransactionType.REDEEMED, points));
		m.setTotalPointsRedeemed(m.getTotalPointsRedeemed() + points);
	}

	private LoyaltyTransaction tx(SalesHeader sale, LoyaltyMember m, LoyaltyProgram program,
			LoyaltyTransactionType type, int points) {
		LoyaltyTransaction t = new LoyaltyTransaction();
		t.setId(nextId++);
		t.setLoyaltyMember(m);
		t.setLoyaltyProgram(program);
		t.setSalesHeader(sale);
		t.setType(type);
		t.setPoints(points);
		return t;
	}

	private List<LoyaltyTransaction> transactionsOf(LoyaltyMember m, SalesHeader sale, LoyaltyTransactionType type) {
		return loyaltyTransactions.stream()
				.filter(t -> t.getLoyaltyMember() == m && t.getSalesHeader() == sale && t.getType() == type)
				.collect(Collectors.toList());
	}

	private int sumOf(SalesHeader sale, LoyaltyMember m, LoyaltyTransactionType type) {
		return transactionsOf(m, sale, type).stream().mapToInt(LoyaltyTransaction::getPoints).sum();
	}

	/** 1 point per DT, no tiers. */
	private LoyaltyProgram flatProgram() {
		LoyaltyProgram p = new LoyaltyProgram();
		p.setId(nextId++);
		p.setPointsPerDinar(1.0);
		p.setPointValueMillimes(50);
		p.setEarningTiers(new ArrayList<>());
		return p;
	}

	/** The client's tiers: 1 pt/DT, above 200 DT 1.5, above 500 DT 2. */
	private LoyaltyProgram tieredProgram() {
		LoyaltyProgram p = flatProgram();
		p.setEarningTiers(new ArrayList<>(Arrays.asList(
				new LoyaltyEarningTier(200.0, 1.5), new LoyaltyEarningTier(500.0, 2.0))));
		return p;
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
