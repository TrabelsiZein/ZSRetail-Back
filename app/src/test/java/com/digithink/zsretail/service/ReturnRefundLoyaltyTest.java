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
	private QuantityPolicy quantityPolicy;
	private final List<BigDecimal> stockBack = new ArrayList<>();
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
		quantityPolicy = new QuantityPolicy(); // the real policy, ALLOW_DECIMAL_QUANTITY read from the settings map
		inject(quantityPolicy, QuantityPolicy.class, "generalSetupRepository", setupRepository);
		inject(returns, ReturnHeaderService.class, "quantityPolicy", quantityPolicy);
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
				stockBack.add(quantity); // what goes back to stock, as asked
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

	// ─── 2.2.2: returns with decimal quantities ──────────────────────

	/** A line of the ticket sold with a decimal quantity, amounts as the server stores them (TTC rounded, HT from it). */
	private SalesLine decimalLine(SalesHeader sale, int index, String quantity, double unitHt, double lineTtc) {
		SalesLine line = linesBySale.get(sale).get(index);
		line.setQuantity(new BigDecimal(quantity));
		line.setUnitPrice(unitHt);
		line.setUnitPriceIncludingVat(unitHt * 1.19);
		line.setLineTotal(lineTtc / 1.19);
		line.setLineTotalIncludingVat(lineTtc);
		line.setVatPercent(19);
		return line;
	}

	private ReturnLine storedLine(ReturnHeader header) {
		return savedReturnLines.stream().filter(l -> l.getReturnHeader() == header).findFirst().get();
	}

	@Test
	@DisplayName("2.2.2: sold 0.5, returned 0.2 then 0.3 (amounts to 3 decimals, the last takes what is left), a third 0.1 refused; stock, vouchers and points")
	void decimalSoldLineReturnedInParts() throws Exception {
		settings.put(QuantityPolicy.SETTING, "true");
		LoyaltyMember m = member(7);
		SalesHeader sale = sale(null, 0.0, 0, 0.0, 7.979);
		SalesLine bulk = decimalLine(sale, 0, "0.5", 13.41, 7.979); // 0.5 x 15.9579 TTC = 7.97895, rounded 7.979
		earned(sale, m, flatProgram(), 7);

		ReturnHeader first = returnQuantity(sale, 0, "0.2", ReturnType.RETURN_VOUCHER);
		ReturnLine firstLine = storedLine(first);
		assertEquals(new BigDecimal("0.2"), firstLine.getQuantity());
		assertEquals(3.192, firstLine.getLineTotalIncludingVat(), 0.0); // 7.979 / 0.5 x 0.2 = 3.1916
		assertEquals(2.682, firstLine.getLineTotal(), 0.0); // 6.70504... / 0.5 x 0.2 = 2.68201...
		assertEquals(3.192, savedVouchers.get(0).getVoucherAmount(), 0.0);
		assertEquals(3, sumOf(sale, m, LoyaltyTransactionType.REVERSED)); // keeps floor(4.787) = 4 of 7

		ReturnHeader second = returnQuantity(sale, 0, "0.3", ReturnType.RETURN_VOUCHER);
		ReturnLine secondLine = storedLine(second);
		assertEquals(new BigDecimal("0.3"), secondLine.getQuantity());
		assertEquals(4.787, secondLine.getLineTotalIncludingVat(), 0.0); // 7.979 - 3.192
		assertEquals(4.023, secondLine.getLineTotal(), 0.0); // 6.70504... - 2.682, rounded
		assertEquals(4.787, savedVouchers.get(1).getVoucherAmount(), 0.0);
		assertEquals(bulk.getLineTotalIncludingVat(), savedVouchers.get(0).getVoucherAmount()
				+ savedVouchers.get(1).getVoucherAmount(), 1e-12, "the vouchers add up to the line paid");
		assertEquals(7, sumOf(sale, m, LoyaltyTransactionType.REVERSED));
		assertEquals(0, m.getLoyaltyPoints().intValue());

		IllegalArgumentException third = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> returnQuantity(sale, 0, "0.1", ReturnType.RETURN_VOUCHER));
		assertEquals("Return quantity (0.1) cannot exceed remaining returnable quantity (0)", third.getMessage());
		assertEquals(2, savedReturns.size(), "nothing written");
		assertEquals(Arrays.asList(new BigDecimal("0.2"), new BigDecimal("0.3")), stockBack);
		assertEquals(0, new BigDecimal("0.5").compareTo(stockBack.get(0).add(stockBack.get(1))), "the 0.5 sold is back");
	}

	@Test
	@DisplayName("2.2.2: sold 2, return 1.5: refused with the setting off (nothing written, never rounded to 2), accepted with it on; the 0.5 left closes the line")
	void wholeLineReturnedWithDecimals() throws Exception {
		SalesHeader sale = sale(null, 0.0, 0, 0.0, 40.0);
		SalesLine line = linesBySale.get(sale).get(0);
		line.setQuantity(new BigDecimal("2.000"));
		line.setUnitPrice(16.807);
		line.setUnitPriceIncludingVat(20.0);
		line.setLineTotal(33.61);

		IllegalArgumentException off = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> returnQuantity(sale, 0, "1.5"));
		assertEquals("Quantity 1.5 of item " + line.getItem().getItemCode() + " (" + line.getItem().getName()
				+ "): decimal quantities are not allowed in this store (General Setup, Allow decimal quantities)",
				off.getMessage());
		assertTrue(savedReturns.isEmpty() && savedReturnLines.isEmpty() && stockBack.isEmpty(), "nothing written");

		settings.put(QuantityPolicy.SETTING, "true");
		ReturnHeader part = returnQuantity(sale, 0, "1.5");
		assertEquals(new BigDecimal("1.5"), storedLine(part).getQuantity());
		assertEquals(30.0, storedLine(part).getLineTotalIncludingVat(), 0.0);
		assertEquals(25.208, storedLine(part).getLineTotal(), 0.0); // 33.61 / 2 x 1.5 = 25.2075, half up
		assertEquals(30.0, part.getTotalReturnAmount(), 0.0);

		ReturnHeader rest = returnQuantity(sale, 0, "0.5");
		assertEquals(10.0, storedLine(rest).getLineTotalIncludingVat(), 0.0);
		assertEquals(8.402, storedLine(rest).getLineTotal(), 0.0); // 33.61 - 25.208
		assertEquals(Arrays.asList(new BigDecimal("1.5"), new BigDecimal("0.5")), stockBack);
	}

	@Test
	@DisplayName("2.2.2: sold 1.5, return 1 (a whole quantity of a decimal line): rounded to 3 decimals; the 0.5 left takes what is left")
	void decimalLineReturnedWhole() throws Exception {
		settings.put(QuantityPolicy.SETTING, "true");
		SalesHeader sale = sale(null, 0.0, 0, 0.0, 23.869);
		decimalLine(sale, 0, "1.5", 13.372, 23.869);
		ReturnHeader one = returnQuantity(sale, 0, "1");
		assertEquals(15.913, storedLine(one).getLineTotalIncludingVat(), 0.0); // 23.869 / 1.5 = 15.91266...
		ReturnHeader rest = returnQuantity(sale, 0, "0.5");
		assertEquals(7.956, storedLine(rest).getLineTotalIncludingVat(), 0.0); // 23.869 - 15.913
	}

	@Test
	@DisplayName("2.2.2: setting off, a line sold with decimals is not returnable (named, nothing written); the whole line of the same ticket returns as in 2.2.1")
	void decimalSoldLineSettingOff() throws Exception {
		SalesHeader sale = sale(null, 0.0, 0, 0.0, 10.0, 50.0);
		SalesLine bulk = decimalLine(sale, 0, "0.2", 42.017, 10.0);
		bulk.getItem().setItemCode("VH52-1L");
		bulk.getItem().setName("V H 52 1L");

		IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> returnQuantity(sale, 0, "0.2"));
		assertEquals("Item VH52-1L (V H 52 1L): the quantity 0.2 has decimals, and decimal quantities are not allowed in"
				+ " this store (General Setup, Allow decimal quantities).", refused.getMessage());
		assertTrue(savedReturns.isEmpty() && savedReturnLines.isEmpty(), "nothing written");

		ReturnHeader r = returnLines(sale, ReturnType.SIMPLE_RETURN, 1);
		assertEquals(50.0, r.getTotalReturnAmount().doubleValue(), 1e-6);
		assertEquals(BigDecimal.ONE, savedReturnLines.get(0).getQuantity());
	}

	@Test
	@DisplayName("2.2.2: more than 3 decimals refused even with the setting on; 1.0 asked is 1, stored and read as 1")
	void decimalsBeyondThreeRefused() throws Exception {
		settings.put(QuantityPolicy.SETTING, "true");
		SalesHeader sale = sale(null, 0.0, 0, 0.0, 50.0);
		IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> returnQuantity(sale, 0, "0.1234"));
		assertTrue(refused.getMessage().endsWith(": at most 3 decimals"), refused.getMessage());
		assertTrue(savedReturns.isEmpty());

		ReturnHeader r = returnQuantity(sale, 0, "1.0");
		assertEquals(50.0, r.getTotalReturnAmount(), 0.0);
		assertEquals("1", storedLine(r).getQuantity().toPlainString());
	}

	@Test
	@DisplayName("2.2.2: ticket details: a decimal line after a return of 0.2 (remaining 0.3, amount left); a whole line keeps its 2.2.1 values")
	@SuppressWarnings("unchecked")
	void ticketDetails() throws Exception {
		settings.put(QuantityPolicy.SETTING, "true");
		SalesHeader sale = sale(null, 0.0, 0, 0.0, 7.979, 60.0);
		decimalLine(sale, 0, "0.5", 13.41, 7.979);
		SalesLine whole = linesBySale.get(sale).get(1);
		whole.setQuantity(new BigDecimal("3.000"));
		returnQuantity(sale, 0, "0.2");
		returnQuantity(sale, 1, "1");

		com.digithink.zsretail.controller.ReturnHeaderAPI api = new com.digithink.zsretail.controller.ReturnHeaderAPI();
		for (String name : new String[] { "salesHeaderRepository", "salesLineRepository", "returnLineRepository",
				"returnHeaderRepository", "quantityPolicy" }) {
			Field from = ReturnHeaderService.class.getDeclaredField(name);
			from.setAccessible(true);
			inject(api, com.digithink.zsretail.controller.ReturnHeaderAPI.class, name, from.get(returns));
		}
		inject(api, com.digithink.zsretail.controller._BaseController.class, "service", returns);
		com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper()
				.findAndRegisterModules();

		Map<String, Object> body = (Map<String, Object>) api.getTicketDetails(sale.getSalesNumber()).getBody();
		List<Map<String, Object>> lines = (List<Map<String, Object>>) body.get("salesLines");
		com.fasterxml.jackson.databind.JsonNode bulk = json.valueToTree(lines.get(0));
		assertEquals("0.5", bulk.get("quantity").asText());
		assertEquals("0.2", bulk.get("returnedQuantity").asText());
		assertEquals("0.3", bulk.get("remainingQuantity").asText());
		assertEquals(4.787, bulk.get("remainingLineTotalIncludingVat").asDouble(), 1e-12);
		assertTrue(bulk.get("decimalReturned").asBoolean());
		assertNull(bulk.get("returnable"), "returnable: no flag, as every returnable line");

		com.fasterxml.jackson.databind.JsonNode wholeLine = json.valueToTree(lines.get(1));
		assertEquals("3", wholeLine.get("quantity").toString(), "JSON 3, never 3.000");
		assertEquals("1", wholeLine.get("returnedQuantity").toString());
		assertEquals("2", wholeLine.get("remainingQuantity").toString());
		assertEquals(40.0, wholeLine.get("remainingLineTotalIncludingVat").asDouble(), 1e-12);
		assertTrue(!wholeLine.get("decimalReturned").asBoolean());

		// setting off: the decimal line is listed, not returnable, with the reason; the whole line unchanged
		settings.put(QuantityPolicy.SETTING, "false");
		body = (Map<String, Object>) api.getTicketDetails(sale.getSalesNumber()).getBody();
		lines = (List<Map<String, Object>>) body.get("salesLines");
		assertEquals(Boolean.FALSE, lines.get(0).get("returnable"));
		assertEquals("0.2", json.valueToTree(lines.get(0)).get("returnedQuantity").asText());
		assertTrue(String.valueOf(lines.get(0).get("notReturnableReason")).contains("not allowed in this store"));
		assertEquals("2", json.valueToTree(lines.get(1)).get("remainingQuantity").toString());
	}

	// ─── 2.2.2: a whole return gives the values of 2.2.1 ─────────────

	/** Sold 3 at 20.000 TTC (16.807 HT, 19%), returned 1 then 2: stored line, copy JSON and hash, NAV payload of 2.2.1. */
	@Test
	@DisplayName("2.2.2: a whole return (sold 3, return 1 then 2) stores, copies and exports to NAV exactly as 2.2.1")
	void wholeReturnAsIn221() throws Exception {
		SalesHeader sale = sale(null, 0.0, 0, 0.0, 60.0);
		SalesLine line = linesBySale.get(sale).get(0);
		line.setQuantity(new BigDecimal("3.000"));
		line.setUnitPrice(16.807);
		line.setUnitPriceIncludingVat(20.0);
		line.setLineTotal(50.42);
		line.setLineTotalIncludingVat(60.0);
		line.setVatPercent(19);
		line.setVatAmount(9.58);
		line.setDiscountPercentage(5.0);
		line.getItem().setItemCode("ITM-100");
		line.getItem().setName("Soap");

		ReturnHeader first = returnQuantity(sale, 0, "1");
		ReturnHeader second = returnQuantity(sale, 0, "2");
		assertEquals(WHOLE_RETURN_221[0] + "\n----\n" + WHOLE_RETURN_221[1], pinned(first) + "\n----\n" + pinned(second));
	}

	/** The values of {@link #wholeReturnAsIn221} produced by the 2.2.1 code (captured on 2026-10-10), one per return. */
	private static final String[] WHOLE_RETURN_221 = {
			"stored 1 16.807 20.0 16.80666666666667 20.0 total 20.0 pct null\n"
					+ "copy {\"cashierLogin\":\"cashier\",\"cashierName\":null,\"discountPercentage\":null,\"lines\":[{\"itemCode\":\"ITM-100\",\"itemName\":\"Soap\",\"lineNo\":1,\"lineTotal\":16.80666666666667,\"lineTotalIncludingVat\":20.0,\"notes\":\"\",\"quantity\":1,\"unitPrice\":16.807,\"unitPriceIncludingVat\":20.0}],\"notes\":null,\"originalSalesNumber\":\"T-1000\",\"returnDate\":\"2026-10-10T09:30:00\",\"returnNumber\":\"RET-202610-000001\",\"returnType\":\"SIMPLE_RETURN\",\"sessionNumber\":null,\"status\":\"COMPLETED\",\"totalReturnAmount\":20.0,\"voucherAmount\":null,\"voucherExpiryDate\":null,\"voucherNumber\":null}\n"
					+ "hash 1a3393ab288903c9080544517e5f452e8c3fbfd57bd1145bef80e887ddc4f31c\n"
					+ "nav header {\"Document_Type\":\"Return Order\",\"Location_Code\":\"MAG01\",\"Posting_Date\":\"2026-10-10\",\"POS_Document_No\":\"RET-202610-000001\",\"Ticket_Amount\":20.0}\n"
					+ "nav line {\"Document_Type\":\"Return Order\",\"Document_No\":\"RO-0001\",\"Type\":\"Item\",\"No\":\"ITM-100\",\"Quantity\":1.0,\"Return_Qty_to_Receive\":1.0,\"Unit_Price\":16.807,\"Line_Discount_Percent\":5.0,\"Location_Code\":\"MAG01\"}",
			"stored 2 16.807 20.0 33.61333333333334 40.0 total 40.0 pct null\n"
					+ "copy {\"cashierLogin\":\"cashier\",\"cashierName\":null,\"discountPercentage\":null,\"lines\":[{\"itemCode\":\"ITM-100\",\"itemName\":\"Soap\",\"lineNo\":1,\"lineTotal\":33.61333333333334,\"lineTotalIncludingVat\":40.0,\"notes\":\"\",\"quantity\":2,\"unitPrice\":16.807,\"unitPriceIncludingVat\":20.0}],\"notes\":null,\"originalSalesNumber\":\"T-1000\",\"returnDate\":\"2026-10-10T09:30:00\",\"returnNumber\":\"RET-202610-000001\",\"returnType\":\"SIMPLE_RETURN\",\"sessionNumber\":null,\"status\":\"COMPLETED\",\"totalReturnAmount\":40.0,\"voucherAmount\":null,\"voucherExpiryDate\":null,\"voucherNumber\":null}\n"
					+ "hash d14a983855324d1f9f5b33906d6b4bd519d6353615f16006f5f8d16cd7213e31\n"
					+ "nav header {\"Document_Type\":\"Return Order\",\"Location_Code\":\"MAG01\",\"Posting_Date\":\"2026-10-10\",\"POS_Document_No\":\"RET-202610-000001\",\"Ticket_Amount\":40.0}\n"
					+ "nav line {\"Document_Type\":\"Return Order\",\"Document_No\":\"RO-0001\",\"Type\":\"Item\",\"No\":\"ITM-100\",\"Quantity\":2.0,\"Return_Qty_to_Receive\":2.0,\"Unit_Price\":16.807,\"Line_Discount_Percent\":5.0,\"Location_Code\":\"MAG01\"}" };

	/** Stored line, copy JSON and hash, NAV header and line JSON of one return, dates and numbers fixed. */
	private String pinned(ReturnHeader header) throws Exception {
		header.setReturnNumber("RET-202610-000001");
		header.setReturnDate(LocalDateTime.of(2026, 10, 10, 9, 30));
		List<ReturnLine> lines = savedReturnLines.stream().filter(l -> l.getReturnHeader() == header)
				.collect(Collectors.toList());
		ReturnLine stored = lines.get(0);
		StringBuilder pin = new StringBuilder();
		pin.append("stored ").append(stored.getQuantity()).append(' ').append(stored.getUnitPrice()).append(' ')
				.append(stored.getUnitPriceIncludingVat()).append(' ').append(stored.getLineTotal()).append(' ')
				.append(stored.getLineTotalIncludingVat()).append(" total ").append(header.getTotalReturnAmount())
				.append(" pct ").append(header.getDiscountPercentage()).append('\n');

		com.digithink.zsretail.headoffice.dto.ReturnCopyDTO copy =
				com.digithink.zsretail.holink.service.SalesCopyMapper.returnCopy(header, lines);
		com.fasterxml.jackson.databind.ObjectMapper sorted = new com.fasterxml.jackson.databind.ObjectMapper()
				.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
				.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
				.configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
		java.lang.reflect.Method hash = com.digithink.zsretail.holink.service.SalesPushService.class
				.getDeclaredMethod("hash", Object.class);
		hash.setAccessible(true);
		pin.append("copy ").append(sorted.writeValueAsString(copy)).append('\n');
		pin.append("hash ").append(hash.invoke(null, copy)).append('\n');

		// EMTOP: ReturnExportService's own conversion, then the NAV mapper and the NAV client's JSON settings
		GeneralSetupService setup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return "DEFAULT_LOCATION".equals(code) ? "MAG01" : "RESPONSIBILITY_CENTER".equals(code) ? "RC01" : null;
			}
		};
		ReturnLineRepository lineRepository = stub(ReturnLineRepository.class, (m, a) ->
				"findByReturnHeader".equals(m) ? lines : UNHANDLED);
		com.digithink.zsretail.erp.service.ReturnExportService export =
				new com.digithink.zsretail.erp.service.ReturnExportService(null, null, lineRepository, setup);
		java.lang.reflect.Method toHeader = export.getClass().getDeclaredMethod("toErpReturnDTO", ReturnHeader.class);
		toHeader.setAccessible(true);
		java.lang.reflect.Method toLine = export.getClass().getDeclaredMethod("toErpReturnLineDTO", ReturnLine.class);
		toLine.setAccessible(true);
		com.digithink.zsretail.erp.dynamicsnav.mapper.DynamicsNavMapper nav =
				new com.digithink.zsretail.erp.dynamicsnav.mapper.DynamicsNavMapper();
		com.fasterxml.jackson.databind.ObjectMapper navJson = new com.fasterxml.jackson.databind.ObjectMapper()
				.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
				.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
				.configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
		pin.append("nav header ").append(navJson.writeValueAsString(nav.toReturnHeaderDTO(
				(com.digithink.zsretail.erp.dto.ErpReturnDTO) toHeader.invoke(export, header)))).append('\n');
		pin.append("nav line ").append(navJson.writeValueAsString(nav.toReturnLineDTO(
				(com.digithink.zsretail.erp.dto.ErpReturnLineDTO) toLine.invoke(export, stored), "RO-0001")));
		return pin.toString();
	}

	/** One return of the given quantities ("1", "0.2"), one per article line index starting at {@code index}. */
	private ReturnHeader returnQuantity(SalesHeader sale, int index, String quantity) throws Exception {
		return returnQuantity(sale, index, quantity, ReturnType.SIMPLE_RETURN);
	}

	private ReturnHeader returnQuantity(SalesHeader sale, int index, String quantity, ReturnType type) throws Exception {
		ProcessReturnRequestDTO.ReturnLineDTO dto = new ProcessReturnRequestDTO.ReturnLineDTO();
		dto.setSalesLineId(linesBySale.get(sale).get(index).getId());
		dto.setQuantity(new BigDecimal(quantity));
		ProcessReturnRequestDTO request = new ProcessReturnRequestDTO();
		request.setTicketNumber(sale.getSalesNumber());
		request.setReturnType(type);
		request.setReturnLines(new ArrayList<>(Arrays.asList(dto)));
		return returns.processReturn(request, cashier);
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
