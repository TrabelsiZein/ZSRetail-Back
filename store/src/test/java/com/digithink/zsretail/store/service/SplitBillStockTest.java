package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.math.BigDecimal;
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

import com.digithink.zsretail.core.dto.PricingResult;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.Promotion;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.CustomerRepository;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.core.repository.PromotionRepository;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.PricingService;
import com.digithink.zsretail.core.service.StockMovementService;
import com.digithink.zsretail.core.service.StockService;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.dto.ProcessSaleRequestDTO;
import com.digithink.zsretail.store.dto.SplitBillRequestDTO;
import com.digithink.zsretail.store.erp.service.SessionExportService;
import com.digithink.zsretail.store.support.InMemoryTillRights;

/**
 * 3.1.2, the split bill of a table ticket (splitAndPay): the lines paid apart are a completed sale like any other, so
 * their stock goes out with their SALE movements (before, never: a table paid in parts kept its stock), and they keep
 * their discount source and promotion. The lines left go out when the rest is paid, once. Runs the real
 * SalesHeaderService over in-memory stubs (no Spring, no database).
 */
class SplitBillStockTest {

	private static final long CUSTOMER_ID = 5L;
	private static final long CASH_METHOD_ID = 1L;

	private SalesHeaderService sales;
	private final UserAccount cashier = new UserAccount();
	private final CashierSession session = new CashierSession();
	private final Customer customer = new Customer();
	private final Map<Long, Item> items = new HashMap<>();
	private final Map<Long, Promotion> promotions = new HashMap<>();
	private final PaymentMethod cash = new PaymentMethod();

	private final Map<String, String> settings = new HashMap<>();
	private final Map<Long, SalesHeader> headers = new HashMap<>();
	private final List<SalesLine> savedLines = new ArrayList<>();
	/** "item quantity" per stock decrement, in order. */
	private final List<String> stockOut = new ArrayList<>();
	/** "item quantity unitHt vat% unitTtc ticketId sessionId" per SALE movement, in order. */
	private final List<String> movements = new ArrayList<>();
	private long nextId;

	private Item soup;
	private Item fish;
	private Item wine;
	private Promotion promotion;

	@BeforeEach
	void setUp() throws Exception {
		nextId = 1000;
		settings.put("ENABLE_TAX_STAMP", "false");
		settings.put("DEFAULT_LOCATION", "LOC001");

		cashier.setUsername("cashier");
		session.setId(7L);
		customer.setId(CUSTOMER_ID);
		soup = item(10L);
		fish = item(11L);
		wine = item(12L);
		promotion = new Promotion();
		promotion.setId(70L);
		promotions.put(70L, promotion);
		cash.setId(CASH_METHOD_ID);
		cash.setName("Cash");
		cash.setType(PaymentMethodType.CLIENT_ESPECES);

		sales = new SalesHeaderService();
		inject(sales, SalesHeaderService.class, "tillRightsGuard", InMemoryTillRights.allowAll());
		inject(sales, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "cashier";
			}
		});
		inject(sales, SalesHeaderService.class, "generalSetupRepository", proxy(GeneralSetupRepository.class, (m, a) -> {
			if (!"findByCode".equals(m)) return UNHANDLED;
			if (!settings.containsKey(a[0])) return Optional.empty();
			GeneralSetup setup = new GeneralSetup();
			setup.setCode((String) a[0]);
			setup.setValeur(settings.get(a[0]));
			return Optional.of(setup);
		}));
		inject(sales, SalesHeaderService.class, "salesHeaderRepository", proxy(SalesHeaderRepository.class, (m, a) -> {
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
		inject(sales, SalesHeaderService.class, "salesLineRepository", proxy(SalesLineRepository.class, (m, a) -> {
			switch (m) {
				case "deleteBySalesHeader": savedLines.removeIf(l -> l.getSalesHeader() == a[0]); return null;
				case "findBySalesHeader": return linesOf((SalesHeader) a[0]);
				case "delete": savedLines.removeIf(l -> l == a[0]); return null;
				default: return UNHANDLED;
			}
		}));
		inject(sales, SalesHeaderService.class, "itemRepository", proxy(ItemRepository.class, (m, a) ->
				"findById".equals(m) ? Optional.ofNullable(items.get(a[0])) : UNHANDLED));
		inject(sales, SalesHeaderService.class, "promotionRepository", proxy(PromotionRepository.class, (m, a) ->
				"findById".equals(m) ? Optional.ofNullable(promotions.get(a[0])) : UNHANDLED));
		inject(sales, SalesHeaderService.class, "customerRepository", proxy(CustomerRepository.class, (m, a) ->
				"findById".equals(m) ? (CUSTOMER_ID == (Long) a[0] ? Optional.of(customer) : Optional.empty()) : UNHANDLED));
		inject(sales, SalesHeaderService.class, "paymentMethodRepository", proxy(PaymentMethodRepository.class, (m, a) ->
				"findById".equals(m) ? (CASH_METHOD_ID == (Long) a[0] ? Optional.of(cash) : Optional.empty()) : UNHANDLED));
		inject(sales, SalesHeaderService.class, "cashierSessionRepository", proxy(CashierSessionRepository.class, (m, a) ->
				"findByCashierAndStatus".equals(m) ? Optional.of(session) : UNHANDLED));
		inject(sales, SalesHeaderService.class, "salesLineService", new SalesLineService() {
			@Override
			public SalesLine save(SalesLine line) {
				if (line.getId() == null) line.setId(nextId++);
				if (savedLines.stream().noneMatch(l -> l == line)) savedLines.add(line);
				return line;
			}
		});
		inject(sales, SalesHeaderService.class, "paymentService", new PaymentService() {
			@Override
			public Payment save(Payment payment) {
				if (payment.getId() == null) payment.setId(nextId++);
				return payment;
			}
		});
		inject(sales, SalesHeaderService.class, "stockService", new StockService() {
			@Override
			public void decrementForSale(Long itemId, BigDecimal quantity) {
				stockOut.add(itemId + " " + quantity.toPlainString());
			}
		});
		inject(sales, SalesHeaderService.class, "stockMovementService", new StockMovementService() {
			@Override
			public void recordSale(Long itemId, BigDecimal quantity, Double unitPriceHt, Integer vatPercent, Double unitPriceTtc,
					Long salesHeaderId, CashierSession cashierSession) {
				movements.add(itemId + " " + quantity.toPlainString() + " " + unitPriceHt + " " + vatPercent + " " + unitPriceTtc
						+ " " + salesHeaderId + " " + cashierSession.getId());
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
						// not under test here
					}
				});
	}

	@Test
	@DisplayName("The lines paid apart: their stock goes out with their SALE movements on the split ticket; the line left keeps its stock")
	void splitLinesLeaveTheStock() throws Exception {
		SalesHeader table = heldTable();
		SalesHeader paid = sales.splitAndPay(table.getId(), split(table, soup, fish), cashier);

		assertEquals(TransactionStatus.COMPLETED, paid.getStatus());
		assertEquals(Arrays.asList("10 2", "11 1"), stockOut);
		assertEquals(Arrays.asList("10 2 10.0 0 10.0 " + paid.getId() + " 7", "11 1 10.0 0 10.0 " + paid.getId() + " 7"),
				movements);
		assertEquals(Collections.singletonList(wine), linesOf(table).stream().map(SalesLine::getItem)
				.collect(Collectors.toList()), "the wine is still on the table");
		assertEquals(TransactionStatus.PENDING, table.getStatus());
	}

	@Test
	@DisplayName("The rest paid later goes out once: each dish leaves the stock once over the two tickets")
	void restPaidLaterGoesOutOnce() throws Exception {
		SalesHeader table = heldTable();
		sales.splitAndPay(table.getId(), split(table, soup, fish), cashier);
		sales.completePendingSale(table.getId(), request(wine), cashier);

		assertEquals(Arrays.asList("10 2", "11 1", "12 1"), stockOut);
		assertEquals(3, movements.size());
	}

	@Test
	@DisplayName("All the lines paid apart: the table is cancelled, every line went out with the split ticket")
	void wholeTableSplit() throws Exception {
		SalesHeader table = heldTable();
		sales.splitAndPay(table.getId(), split(table, soup, fish, wine), cashier);

		assertEquals(Arrays.asList("10 2", "11 1", "12 1"), stockOut);
		assertEquals(TransactionStatus.CANCELLED, table.getStatus());
	}

	@Test
	@DisplayName("The split lines keep their discount source and promotion; a line without stays without")
	void splitLinesKeepTheirDiscountSource() throws Exception {
		SalesHeader table = heldTable();
		SalesHeader paid = sales.splitAndPay(table.getId(), split(table, soup, fish, wine), cashier);

		SalesLine promo = lineOf(paid, soup);
		assertEquals("PROMOTION", promo.getDiscountSource());
		assertSame(promotion, promo.getPromotion());
		assertEquals("MANUAL", lineOf(paid, fish).getDiscountSource());
		assertNull(lineOf(paid, fish).getPromotion());
		assertNull(lineOf(paid, wine).getDiscountSource());
		assertNull(lineOf(paid, wine).getPromotion());
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private Item item(long id) {
		Item i = new Item();
		i.setId(id);
		i.setItemCode("ART-" + id);
		i.setName("Article " + id);
		i.setDefaultVAT(0);
		i.setUnitPrice(10.0);
		items.put(id, i);
		return i;
	}

	/** A table held: 2 soups at a promotion, a fish at a manual discount, a wine. */
	private SalesHeader heldTable() throws Exception {
		ProcessSaleRequestDTO r = request(soup, fish, wine);
		r.setTableNumber(4);
		r.getLines().get(0).setQuantity(new BigDecimal("2"));
		r.getLines().get(0).setLineTotal(20.0);
		r.getLines().get(0).setLineTotalIncludingVat(20.0);
		r.getLines().get(0).setDiscountSource("PROMOTION");
		r.getLines().get(0).setPromotionId(70L);
		r.getLines().get(1).setDiscountSource("MANUAL");
		return sales.savePendingSale(r, cashier);
	}

	private SplitBillRequestDTO split(SalesHeader table, Item... paidItems) {
		List<Long> ids = new ArrayList<>();
		for (Item i : paidItems) {
			ids.add(lineOf(table, i).getId());
		}
		SplitBillRequestDTO split = new SplitBillRequestDTO();
		split.setSelectedLineIds(ids);
		split.setSubtotal(30.0);
		split.setTaxAmount(0.0);
		split.setTotalAmount(30.0);
		split.setPaidAmount(30.0);
		split.setChangeAmount(0.0);
		SplitBillRequestDTO.PaymentDTO payment = new SplitBillRequestDTO.PaymentDTO();
		payment.setPaymentMethodId(CASH_METHOD_ID);
		payment.setAmount(30.0);
		split.setPayments(new ArrayList<>(Collections.singletonList(payment)));
		return split;
	}

	/** What the till sends: one line of 10 DT per item, VAT 0. */
	private ProcessSaleRequestDTO request(Item... lineItems) {
		List<ProcessSaleRequestDTO.SaleLineDTO> lines = new ArrayList<>();
		for (Item i : lineItems) {
			ProcessSaleRequestDTO.SaleLineDTO line = new ProcessSaleRequestDTO.SaleLineDTO();
			line.setItemId(i.getId());
			line.setQuantity(BigDecimal.ONE);
			line.setUnitPrice(10.0);
			line.setLineTotal(10.0);
			line.setVatPercent(0);
			line.setVatAmount(0.0);
			line.setUnitPriceIncludingVat(10.0);
			line.setLineTotalIncludingVat(10.0);
			lines.add(line);
		}
		double total = 10.0 * lineItems.length;
		ProcessSaleRequestDTO.PaymentDTO payment = new ProcessSaleRequestDTO.PaymentDTO();
		payment.setPaymentMethodId(CASH_METHOD_ID);
		payment.setAmount(total);

		ProcessSaleRequestDTO r = new ProcessSaleRequestDTO();
		r.setSubtotal(total);
		r.setTaxAmount(0.0);
		r.setTotalAmount(total);
		r.setPaidAmount(total);
		r.setChangeAmount(0.0);
		r.setCustomerId(CUSTOMER_ID);
		r.setLines(lines);
		r.setPayments(new ArrayList<>(Collections.singletonList(payment)));
		return r;
	}

	private List<SalesLine> linesOf(SalesHeader header) {
		return savedLines.stream().filter(l -> l.getSalesHeader() == header).collect(Collectors.toList());
	}

	private SalesLine lineOf(SalesHeader header, Item item) {
		return linesOf(header).stream().filter(l -> l.getItem() == item).findFirst()
				.orElseThrow(() -> new AssertionError("no line of " + item.getItemCode()));
	}

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
