package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * 3.1.2, the unit cost frozen on the ticket line (SalesLine.unitCost): the item's CMP when the ticket is completed, on
 * the three ways a ticket is completed (a sale, a held ticket paid later, a split bill), never on a held ticket, and
 * never moved by a later CMP. Runs the real SalesHeaderService over in-memory stubs (no Spring, no database).
 */
class SaleUnitCostTest {

	private static final long CUSTOMER_ID = 5L;
	private static final long CASH_METHOD_ID = 1L;

	private SalesHeaderService sales;
	private final UserAccount cashier = new UserAccount();
	private final CashierSession session = new CashierSession();
	private final Customer customer = new Customer();
	private final Map<Long, Item> items = new HashMap<>();
	private final Item stampItem = new Item();
	private final PaymentMethod cash = new PaymentMethod();

	private final Map<String, String> settings = new HashMap<>();
	private final Map<Long, SalesHeader> headers = new HashMap<>();
	private final List<SalesLine> savedLines = new ArrayList<>();
	private long nextId;

	private Item costed;
	private Item component;
	private Item noCost;

	@BeforeEach
	void setUp() throws Exception {
		nextId = 1000;
		settings.put("ENABLE_TAX_STAMP", "true");
		settings.put("TAX_STAMP_VALUE_MILLIMES", "100");
		settings.put("DEFAULT_LOCATION", "LOC001");

		cashier.setUsername("cashier");
		session.setId(7L);
		customer.setId(CUSTOMER_ID);
		costed = item(10L, 7.1235);
		component = item(11L, 2.5);
		noCost = item(12L, null);
		stampItem.setId(99L);
		stampItem.setItemCode("TAX_STAMP");
		stampItem.setName("Timbre Fiscal");
		stampItem.setCostPrice(0.05); // never copied: the stamp is no goods
		cash.setId(CASH_METHOD_ID);
		cash.setName("Cash");
		cash.setType(PaymentMethodType.CLIENT_ESPECES);

		GeneralSetupRepository setupRepository = proxy(GeneralSetupRepository.class, (m, a) -> {
			if (!"findByCode".equals(m)) return UNHANDLED;
			if (!settings.containsKey(a[0])) return Optional.empty();
			GeneralSetup setup = new GeneralSetup();
			setup.setCode((String) a[0]);
			setup.setValeur(settings.get(a[0]));
			return Optional.of(setup);
		});

		sales = new SalesHeaderService();
		inject(sales, SalesHeaderService.class, "tillRightsGuard", InMemoryTillRights.allowAll());
		inject(sales, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "cashier";
			}
		});
		inject(sales, SalesHeaderService.class, "generalSetupRepository", setupRepository);
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
		inject(sales, SalesHeaderService.class, "itemRepository", proxy(ItemRepository.class, (m, a) -> {
			switch (m) {
				case "findById": return Optional.ofNullable(items.get(a[0]));
				case "findByItemCode": return "TAX_STAMP".equals(a[0]) ? Optional.of(stampItem) : Optional.empty();
				default: return UNHANDLED;
			}
		}));
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
				// not under test here
			}
		});
		inject(sales, SalesHeaderService.class, "stockMovementService", new StockMovementService() {
			@Override
			public void recordSale(Long itemId, BigDecimal quantity, Double unitPriceHt, Integer vatPercent, Double unitPriceTtc,
					Long salesHeaderId, CashierSession cashierSession) {
				// not under test here
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
	@DisplayName("A sale: each line takes its item's CMP, half up to 3 decimals; the free line too; an item without CMP and the stamp give null")
	void saleCopiesTheCmp() throws Exception {
		ProcessSaleRequestDTO r = request(costed, component, noCost);
		r.getLines().get(0).setFreeQuantity(1);
		SalesHeader sale = sales.processCompleteSale(r, cashier);

		assertEquals(new BigDecimal("7.124"), lineOf(sale, costed, 10.0).getUnitCost());
		assertEquals(new BigDecimal("7.124"), lineOf(sale, costed, 0.0).getUnitCost(), "the free line: goods given cost too");
		assertEquals(new BigDecimal("2.500"), lineOf(sale, component, 10.0).getUnitCost());
		assertNull(lineOf(sale, noCost, 10.0).getUnitCost(), "no CMP: the cost is unknown");
		assertNull(lineOf(sale, stampItem, 0.1).getUnitCost(), "the tax stamp has no cost");
	}

	@Test
	@DisplayName("A pack sold: the till sends its components, each line takes its own component's CMP (no pack line, no pack rule)")
	void packComponentsTakeTheirOwnCost() throws Exception {
		SalesHeader sale = sales.processCompleteSale(request(costed, component, component), cashier);
		List<SalesLine> componentLines = linesOf(sale).stream().filter(l -> l.getItem() == component)
				.collect(Collectors.toList());
		assertEquals(2, componentLines.size());
		componentLines.forEach(l -> assertEquals(new BigDecimal("2.500"), l.getUnitCost()));
		assertEquals(new BigDecimal("7.124"), lineOf(sale, costed, 10.0).getUnitCost());
	}

	@Test
	@DisplayName("A held ticket has no cost, saved or saved again; it takes the CMP of the moment it is paid")
	void heldTicketTakesTheCostWhenPaid() throws Exception {
		SalesHeader held = sales.savePendingSale(request(costed), cashier);
		assertNull(lineOf(held, costed, 10.0).getUnitCost());
		sales.updatePendingSale(held.getId(), request(costed), cashier);
		assertNull(lineOf(held, costed, 10.0).getUnitCost());

		costed.setCostPrice(8.0); // a reception moved the CMP while the ticket was held
		SalesHeader paid = sales.completePendingSale(held.getId(), request(costed), cashier);
		assertEquals(TransactionStatus.COMPLETED, paid.getStatus());
		assertEquals(new BigDecimal("8.000"), lineOf(paid, costed, 10.0).getUnitCost());
	}

	@Test
	@DisplayName("A cancelled held ticket keeps no cost")
	void cancelledHeldTicketHasNoCost() throws Exception {
		SalesHeader held = sales.savePendingSale(request(costed), cashier);
		sales.cancelPendingSale(held.getId(), cashier);
		assertEquals(TransactionStatus.CANCELLED, held.getStatus());
		assertNull(lineOf(held, costed, 10.0).getUnitCost());
	}

	@Test
	@DisplayName("A split bill: the lines paid apart take the CMP of the moment they are paid; the lines left stay without cost")
	void splitBillTakesTheCost() throws Exception {
		SalesHeader held = sales.savePendingSale(request(costed, component), cashier);
		SalesLine selected = lineOf(held, costed, 10.0);

		SplitBillRequestDTO split = new SplitBillRequestDTO();
		split.setSelectedLineIds(new ArrayList<>(Collections.singletonList(selected.getId())));
		split.setSubtotal(10.0);
		split.setTaxAmount(0.0);
		split.setTotalAmount(10.0);
		split.setPaidAmount(10.0);
		split.setChangeAmount(0.0);
		SplitBillRequestDTO.PaymentDTO payment = new SplitBillRequestDTO.PaymentDTO();
		payment.setPaymentMethodId(CASH_METHOD_ID);
		payment.setAmount(10.0);
		split.setPayments(new ArrayList<>(Collections.singletonList(payment)));
		SalesHeader paid = sales.splitAndPay(held.getId(), split, cashier);

		assertEquals(TransactionStatus.COMPLETED, paid.getStatus());
		assertEquals(new BigDecimal("7.124"), lineOf(paid, costed, 10.0).getUnitCost());
		assertNull(lineOf(held, component, 10.0).getUnitCost(), "still held: no cost");
	}

	@Test
	@DisplayName("A CMP change after the sale leaves the line's cost as it was")
	void laterCmpLeavesTheLine() throws Exception {
		SalesHeader sale = sales.processCompleteSale(request(costed), cashier);
		costed.setCostPrice(12.0);
		sales.processCompleteSale(request(costed), cashier); // the next ticket takes the new CMP
		assertEquals(new BigDecimal("7.124"), lineOf(sale, costed, 10.0).getUnitCost());
		assertTrue(savedLines.stream().anyMatch(l -> new BigDecimal("12.000").equals(l.getUnitCost())));
	}

	@Test
	@DisplayName("frozenCost: null item, null CMP and the stamp give null; a negative CMP is copied as it is")
	void frozenCostRule() {
		assertNull(SalesHeaderService.frozenCost(null));
		assertNull(SalesHeaderService.frozenCost(noCost));
		assertNull(SalesHeaderService.frozenCost(stampItem));
		assertEquals(new BigDecimal("0.000"), SalesHeaderService.frozenCost(item(20L, 0.0)));
		assertEquals(new BigDecimal("-1.250"), SalesHeaderService.frozenCost(item(21L, -1.25)));
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private Item item(long id, Double cost) {
		Item i = new Item();
		i.setId(id);
		i.setItemCode("ART-" + id);
		i.setName("Article " + id);
		i.setDefaultVAT(0);
		i.setUnitPrice(10.0);
		i.setCostPrice(cost);
		items.put(id, i);
		return i;
	}

	/** What the till sends: one line of 10 DT per item, the 0.100 DT stamp in the total. */
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
		double total = 10.0 * lineItems.length + 0.1;
		ProcessSaleRequestDTO.PaymentDTO payment = new ProcessSaleRequestDTO.PaymentDTO();
		payment.setPaymentMethodId(CASH_METHOD_ID);
		payment.setAmount(total);

		ProcessSaleRequestDTO r = new ProcessSaleRequestDTO();
		r.setSubtotal(10.0 * lineItems.length);
		r.setTaxAmount(0.0);
		r.setTotalAmount(total);
		r.setPaidAmount(total);
		r.setChangeAmount(0.0);
		r.setCustomerId(CUSTOMER_ID);
		r.setLines(lines);
		r.setPayments(new ArrayList<>(Arrays.asList(payment)));
		return r;
	}

	private List<SalesLine> linesOf(SalesHeader header) {
		return savedLines.stream().filter(l -> l.getSalesHeader() == header).collect(Collectors.toList());
	}

	private SalesLine lineOf(SalesHeader header, Item item, double lineTotalIncludingVat) {
		return linesOf(header).stream()
				.filter(l -> l.getItem() == item && l.getLineTotalIncludingVat() == lineTotalIncludingVat)
				.findFirst().orElseThrow(() -> new AssertionError("no line of " + item.getItemCode()));
	}

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
