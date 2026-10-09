package com.digithink.zsretail.service;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static com.digithink.zsretail.support.InMemoryStock.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
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
import com.digithink.zsretail.exception.InsufficientStockException;
import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.Payment;
import com.digithink.zsretail.model.PaymentMethod;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.model.StockMovement;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.model.enumeration.StockMovementType;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.digithink.zsretail.repository.CashierSessionRepository;
import com.digithink.zsretail.repository.CustomerRepository;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.repository.PaymentMethodRepository;
import com.digithink.zsretail.repository.SalesHeaderRepository;
import com.digithink.zsretail.repository.SalesLineRepository;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.InMemoryStock;
import com.digithink.zsretail.utils.Quantities;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 2.2.1, decimal quantities, step 1: a sale of a bulk item (0.2 or 0.058 of a litre) on the store, from the till's
 * request to the stock. Runs the real SalesHeaderService, StockService, StockMovementService and QuantityPolicy over
 * in-memory stubs (no Spring, no database). The item is priced 50.000 TND the litre, VAT 19%.
 */
class SaleDecimalQuantityTest {

	private static final long CUSTOMER_ID = 5L;
	private static final long CASH_METHOD_ID = 1L;
	private static final double LITRE_TTC = 50.0;
	private static final int VAT = 19;
	/** The unit price excluding VAT the till holds (the engine converts a price including VAT this way). */
	private static final double LITRE_HT = LITRE_TTC / (1.0 + (VAT / 100.0));

	private SalesHeaderService sales;
	private InMemoryStock stock;
	private Item bulk;
	private final UserAccount cashier = new UserAccount();
	private final CashierSession session = new CashierSession();
	private final Customer customer = new Customer();
	private final PaymentMethod cash = new PaymentMethod();
	private final Map<String, String> settings = new HashMap<>();
	private final Map<Long, SalesHeader> headers = new HashMap<>();
	private final List<SalesLine> savedLines = new ArrayList<>();
	private long nextId;

	@BeforeEach
	void setUp() {
		nextId = 1000;
		settings.clear();
		settings.put(QuantityPolicy.SETTING, "true");
		settings.put("ALLOW_NEGATIVE_STOCK", "true");
		settings.put("DEFAULT_LOCATION", "LOC001");

		cashier.setUsername("cashier");
		session.setId(7L);
		customer.setId(CUSTOMER_ID);
		cash.setId(CASH_METHOD_ID);
		cash.setName("Cash");
		cash.setType(PaymentMethodType.CLIENT_ESPECES);

		InMemoryCatalogue catalogue = new InMemoryCatalogue(1);
		bulk = catalogue.item("VH52-1L", LITRE_TTC, null);
		bulk.setDefaultVAT(VAT);
		bulk.setStockQuantity(BigDecimal.valueOf(5));
		stock = new InMemoryStock(catalogue);

		GeneralSetupRepository setup = proxy(GeneralSetupRepository.class, (m, a) -> {
			if (!"findByCode".equals(m)) {
				return UNHANDLED;
			}
			if (!settings.containsKey(a[0])) {
				return Optional.empty();
			}
			GeneralSetup row = new GeneralSetup();
			row.setCode((String) a[0]);
			row.setValeur(settings.get(a[0]));
			return Optional.of(row);
		});
		QuantityPolicy policy = new QuantityPolicy();
		set(policy, QuantityPolicy.class, "generalSetupRepository", setup);

		StockService stockService = stock.stockService();
		set(stockService, StockService.class, "generalSetupService", new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return settings.get(code);
			}
		});

		sales = new SalesHeaderService();
		set(sales, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "cashier";
			}
		});
		set(sales, SalesHeaderService.class, "quantityPolicy", policy);
		set(sales, SalesHeaderService.class, "generalSetupRepository", setup);
		set(sales, SalesHeaderService.class, "itemRepository", stock.itemRepository());
		set(sales, SalesHeaderService.class, "stockService", stockService);
		set(sales, SalesHeaderService.class, "stockMovementService", stock.stockMovementService());
		set(sales, SalesHeaderService.class, "salesHeaderRepository", proxy(SalesHeaderRepository.class, (m, a) -> {
			switch (m) {
				case "save": {
					SalesHeader h = (SalesHeader) a[0];
					if (h.getId() == null) {
						h.setId(nextId++);
					}
					headers.put(h.getId(), h);
					return h;
				}
				case "findById":
					return Optional.ofNullable(headers.get(a[0]));
				case "countBySalesDateGreaterThanEqual":
					return 0L;
				default:
					return UNHANDLED;
			}
		}));
		set(sales, SalesHeaderService.class, "salesLineRepository", proxy(SalesLineRepository.class, (m, a) -> {
			if (!"deleteBySalesHeader".equals(m)) {
				return UNHANDLED;
			}
			savedLines.removeIf(line -> line.getSalesHeader() == a[0]);
			return null;
		}));
		set(sales, SalesHeaderService.class, "customerRepository", proxy(CustomerRepository.class, (m, a) ->
				"findById".equals(m) ? (CUSTOMER_ID == (Long) a[0] ? Optional.of(customer) : Optional.empty()) : UNHANDLED));
		set(sales, SalesHeaderService.class, "paymentMethodRepository", proxy(PaymentMethodRepository.class, (m, a) ->
				"findById".equals(m) ? (CASH_METHOD_ID == (Long) a[0] ? Optional.of(cash) : Optional.empty()) : UNHANDLED));
		set(sales, SalesHeaderService.class, "cashierSessionRepository", proxy(CashierSessionRepository.class, (m, a) ->
				"findByCashierAndStatus".equals(m) ? Optional.of(session) : UNHANDLED));
		set(sales, SalesHeaderService.class, "salesLineService", new SalesLineService() {
			@Override
			public SalesLine save(SalesLine line) {
				if (line.getId() == null) {
					line.setId(nextId++);
				}
				savedLines.add(line);
				return line;
			}
		});
		set(sales, SalesHeaderService.class, "paymentService", new PaymentService() {
			@Override
			public Payment save(Payment payment) {
				if (payment.getId() == null) {
					payment.setId(nextId++);
				}
				return payment;
			}
		});
		set(sales, SalesHeaderService.class, "pricingService", new PricingService() {
			@Override
			public PricingResult calculateItemPrice(Item i, Customer c, BigDecimal quantity, String responsibilityCenter) {
				PricingResult r = new PricingResult();
				r.setUnitPrice(i.getUnitPrice());
				r.setPriceIncludesVat(true);
				r.setSource("BASE_PRICE");
				return r;
			}
		});
		set(sales, SalesHeaderService.class, "sessionExportService",
				new SessionExportService(null, null, null, null, null, null, null, null) {
					@Override
					public void createPaymentHeadersAndLinesAsync(List<Payment> payments, SalesHeader salesHeader) {
						// no ERP here
					}
				});
	}

	// ─── Decimal quantities ──────────────────────────────────────────

	@Test
	@DisplayName("0.2 L at 50.000 the litre: 10.000 TND including VAT, amounts computed here, stock 5 to 4.8")
	void sellTwoDecilitres() throws Exception {
		SalesHeader sale = sales.processCompleteSale(request(line("0.2", 999.0)), cashier);

		SalesLine line = only(sale);
		assertQuantity("0.2", line.getQuantity());
		assertEquals(10.0, line.getLineTotalIncludingVat(), 0.0, "rounded to the millime, not the till's 999");
		assertEquals(10.0 / 1.19, line.getLineTotal(), 1e-9);
		assertEquals(10.0 - 10.0 / 1.19, line.getVatAmount(), 1e-9);
		assertEquals(10.0, line.getLineTotal() + line.getVatAmount(), 1e-9);
		assertEquals(LITRE_HT, line.getUnitPrice(), 0.0);
		assertEquals(LITRE_TTC, line.getUnitPriceIncludingVat(), 1e-9);
		assertNull(line.getDiscountAmount());
		assertQuantity("4.8", stock.quantityOf("VH52-1L"));
		StockMovement movement = stock.movements(StockMovementType.SALE).get(0);
		assertQuantity("0.2", movement.getQuantity());
	}

	@Test
	@DisplayName("0.058 L at 50.000 the litre: 2.900 TND, stock 5 to 4.942")
	void sellFiftyEightMillilitres() throws Exception {
		SalesHeader sale = sales.processCompleteSale(request(line("0.058", 1.0)), cashier);

		SalesLine line = only(sale);
		assertEquals(2.9, line.getLineTotalIncludingVat(), 0.0);
		assertEquals(2.9 / 1.19, line.getLineTotal(), 1e-9);
		assertQuantity("4.942", stock.quantityOf("VH52-1L"));
	}

	@Test
	@DisplayName("A 10% discount on 0.2 L: 10.000 minus 1.000 = 9.000 TND including VAT")
	void percentageDiscount() throws Exception {
		ProcessSaleRequestDTO.SaleLineDTO line = line("0.2", 10.0);
		line.setDiscountPercentage(10.0);
		line.setDiscountAmount(123.0); // the till's amount is not taken
		SalesHeader sale = sales.processCompleteSale(request(line), cashier);

		SalesLine saved = only(sale);
		assertEquals(1.0, saved.getDiscountAmount(), 1e-9);
		assertEquals(10.0, saved.getDiscountPercentage(), 0.0);
		assertEquals(9.0, saved.getLineTotalIncludingVat(), 1e-9);
		assertEquals(9.0 / 1.19, saved.getLineTotal(), 1e-9);
	}

	@Test
	@DisplayName("A fixed discount of 2.000 on 0.2 L: 8.000 TND including VAT")
	void fixedDiscount() throws Exception {
		ProcessSaleRequestDTO.SaleLineDTO line = line("0.2", 10.0);
		line.setDiscountAmount(2.0);
		SalesHeader sale = sales.processCompleteSale(request(line), cashier);

		assertEquals(8.0, only(sale).getLineTotalIncludingVat(), 1e-9);
	}

	@Test
	@DisplayName("Half a millime rounds up: 0.5 L at 12.345 the litre is 6.173, not 6.172")
	void halfMillimeRoundsUp() throws Exception {
		ProcessSaleRequestDTO.SaleLineDTO line = line("0.5", 6.0);
		line.setUnitPrice(12.345 / (1.0 + (VAT / 100.0)));
		SalesHeader sale = sales.processCompleteSale(request(line), cashier);

		assertEquals(6.173, only(sale).getLineTotalIncludingVat(), 0.0);
	}

	@Test
	@DisplayName("Parked ticket with 0.2 L: saved, then completed at 10.000 TND whatever the till sends")
	void parkedTicket() throws Exception {
		SalesHeader parked = sales.savePendingSale(request(line("0.2", 999.0)), cashier);
		assertEquals(10.0, only(parked).getLineTotalIncludingVat(), 0.0);
		assertQuantity("5", stock.quantityOf("VH52-1L"), "a parked ticket does not touch the stock");

		SalesHeader sale = sales.completePendingSale(parked.getId(), request(line("0.2", 999.0)), cashier);
		assertEquals(TransactionStatus.COMPLETED, sale.getStatus());
		assertEquals(10.0, only(sale).getLineTotalIncludingVat(), 0.0);
		assertQuantity("4.8", stock.quantityOf("VH52-1L"));
	}

	@Test
	@DisplayName("Stock 0.1 without negative stock: selling 0.2 is refused with the quantities written plainly")
	void insufficientDecimalStock() {
		settings.put("ALLOW_NEGATIVE_STOCK", "false");
		bulk.setStockQuantity(new BigDecimal("0.100"));

		InsufficientStockException e = assertThrows(InsufficientStockException.class,
				() -> sales.processCompleteSale(request(line("0.2", 10.0)), cashier));
		assertTrue(e.getMessage().contains("requested=0.2"), e.getMessage());
		assertQuantity("0.1", stock.quantityOf("VH52-1L"));
	}

	// ─── Refusals ────────────────────────────────────────────────────

	@Test
	@DisplayName("Setting off (the default): 0.2 is refused before anything is written")
	void refusedWhenSettingOff() {
		settings.put(QuantityPolicy.SETTING, "false");

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> sales.processCompleteSale(request(line("0.2", 10.0)), cashier));
		assertTrue(e.getMessage().contains("decimal quantities are not allowed"), e.getMessage());
		assertTrue(e.getMessage().contains("VH52-1L"), e.getMessage());
		assertTrue(headers.isEmpty(), "no ticket");
		assertTrue(savedLines.isEmpty(), "no line");
		assertQuantity("5", stock.quantityOf("VH52-1L"));
		assertTrue(stock.movements.isEmpty());
	}

	@Test
	@DisplayName("Setting absent (an older database before its first start): refused as when off")
	void refusedWhenSettingAbsent() {
		settings.remove(QuantityPolicy.SETTING);

		assertThrows(IllegalArgumentException.class,
				() -> sales.savePendingSale(request(line("0.2", 10.0)), cashier));
		assertTrue(headers.isEmpty());
	}

	@Test
	@DisplayName("More than 3 decimals is refused even when decimals are allowed")
	void fourDecimalsRefused() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> sales.processCompleteSale(request(line("0.0581", 10.0)), cashier));
		assertTrue(e.getMessage().contains("at most 3 decimals"), e.getMessage());
		assertTrue(headers.isEmpty());
	}

	// ─── Whole quantities: exactly as 2.2.0 ──────────────────────────

	@Test
	@DisplayName("Whole quantity, setting off: the till's amounts are stored untouched, stock and movement by 2")
	void wholeQuantityUnchanged() throws Exception {
		settings.put(QuantityPolicy.SETTING, "false");
		ProcessSaleRequestDTO.SaleLineDTO line = line("2", 0);
		// What the till of 2.2.0 sends for 2 x 50.000 with 7% off (float noise included)
		double gross = LITRE_HT * 2 * (1 + VAT / 100.0);
		double discount = gross * (7.0 / 100);
		double ttc = Math.max(0, gross - discount);
		double ht = ttc / (1 + VAT / 100.0);
		line.setDiscountPercentage(7.0);
		line.setDiscountAmount(discount);
		line.setLineTotal(ht);
		line.setVatAmount(ttc - ht);
		line.setUnitPriceIncludingVat(LITRE_HT * (1 + VAT / 100.0));
		line.setLineTotalIncludingVat(ttc);

		SalesHeader sale = sales.processCompleteSale(request(line), cashier);

		SalesLine saved = only(sale);
		assertQuantity("2", saved.getQuantity());
		assertEquals(ht, saved.getLineTotal(), 0.0);
		assertEquals(ttc - ht, saved.getVatAmount(), 0.0);
		assertEquals(ttc, saved.getLineTotalIncludingVat(), 0.0);
		assertEquals(discount, saved.getDiscountAmount(), 0.0);
		assertEquals(LITRE_HT * (1 + VAT / 100.0), saved.getUnitPriceIncludingVat(), 0.0);
		assertQuantity("3", stock.quantityOf("VH52-1L"));
		assertQuantity("2", stock.movements(StockMovementType.SALE).get(0).getQuantity());
	}

	@Test
	@DisplayName("Whole quantity on a parked ticket: line total = unit price x quantity, as 2.2.0 (no rounding)")
	void wholeParkedTicketUnchanged() throws Exception {
		settings.put(QuantityPolicy.SETTING, "false");
		ProcessSaleRequestDTO.SaleLineDTO line = line("3", 0);
		line.setVatAmount(null);
		line.setLineTotalIncludingVat(null);
		line.setUnitPriceIncludingVat(null);

		SalesHeader parked = sales.savePendingSale(request(line), cashier);

		SalesLine saved = only(parked);
		double unitPriceHT = LITRE_TTC / (1.0 + (VAT / 100.0)); // applyPricingToSalesLine of 2.2.0
		Integer quantity = 3;
		assertEquals(unitPriceHT * quantity, saved.getLineTotal(), 0.0, "2.2.0: unitPriceHT * lineDTO.getQuantity()");
	}

	@Test
	@DisplayName("JSON of a line: 2.000 from the database travels as 2, 0.200 as 0.2")
	void jsonKeepsWholeQuantitiesWhole() throws Exception {
		ObjectMapper json = new ObjectMapper().findAndRegisterModules();
		SalesLine line = new SalesLine();
		line.setQuantity(new BigDecimal("2.000"));
		assertTrue(json.writeValueAsString(line).contains("\"quantity\":2,"), json.writeValueAsString(line));
		line.setQuantity(new BigDecimal("0.200"));
		assertTrue(json.writeValueAsString(line).contains("\"quantity\":0.2,"), json.writeValueAsString(line));
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	/** A line as the till sends it: the bulk item, this quantity, the litre price, and its own (ignored) total. */
	private ProcessSaleRequestDTO.SaleLineDTO line(String quantity, double tillTotalIncludingVat) {
		ProcessSaleRequestDTO.SaleLineDTO line = new ProcessSaleRequestDTO.SaleLineDTO();
		line.setItemId(bulk.getId());
		line.setQuantity(new BigDecimal(quantity));
		line.setUnitPrice(LITRE_HT);
		line.setVatPercent(VAT);
		line.setLineTotal(tillTotalIncludingVat / 1.19);
		line.setVatAmount(tillTotalIncludingVat - tillTotalIncludingVat / 1.19);
		line.setUnitPriceIncludingVat(LITRE_TTC);
		line.setLineTotalIncludingVat(tillTotalIncludingVat);
		return line;
	}

	private ProcessSaleRequestDTO request(ProcessSaleRequestDTO.SaleLineDTO line) {
		ProcessSaleRequestDTO.PaymentDTO payment = new ProcessSaleRequestDTO.PaymentDTO();
		payment.setPaymentMethodId(CASH_METHOD_ID);
		payment.setAmount(10.0);
		ProcessSaleRequestDTO r = new ProcessSaleRequestDTO();
		r.setSubtotal(10.0 / 1.19);
		r.setTaxAmount(10.0 - 10.0 / 1.19);
		r.setTotalAmount(10.0);
		r.setPaidAmount(10.0);
		r.setChangeAmount(0.0);
		r.setCustomerId(CUSTOMER_ID);
		r.setLines(new ArrayList<>(Collections.singletonList(line)));
		r.setPayments(new ArrayList<>(Collections.singletonList(payment)));
		return r;
	}

	private SalesLine only(SalesHeader sale) {
		List<SalesLine> lines = savedLines.stream().filter(l -> l.getSalesHeader() == sale).collect(Collectors.toList());
		assertEquals(1, lines.size());
		assertSame(bulk, lines.get(0).getItem());
		return lines.get(0);
	}

	/** Same value, and written without trailing zeros. */
	private static void assertQuantity(String expected, BigDecimal actual) {
		assertQuantity(expected, actual, null);
	}

	private static void assertQuantity(String expected, BigDecimal actual, String message) {
		assertEquals(expected, Quantities.plain(actual), message);
		assertEquals(expected, actual.toPlainString(), "read through the getter: " + message);
	}
}
