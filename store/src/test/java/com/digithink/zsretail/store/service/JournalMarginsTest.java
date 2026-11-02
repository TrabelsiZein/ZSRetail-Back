package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.ReturnLine;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.core.repository.PaymentRepository;
import com.digithink.zsretail.core.repository.ReturnHeaderRepository;
import com.digithink.zsretail.core.repository.ReturnLineRepository;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.CompanyInformationService;
import com.digithink.zsretail.core.support.TestModes;
import com.digithink.zsretail.store.controller.CashierSessionAPI;
import com.digithink.zsretail.store.dto.DayJournalDTO;
import com.digithink.zsretail.store.dto.DayJournalDTO.JournalLine;
import com.digithink.zsretail.store.dto.DayRowDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.1.2, the Journal's gains (JournalCosts): the day journal (DayJournalService, from the lines it reads) and the day list
 * (StoreDaysService, from one projection of the lines per kind) over the same data, through the real services and
 * in-memory repositories. A product A at 19 % (CMP 6.000), a product B sold before 3.1.2 (no cost), a service S (no cost
 * by nature), the tax stamp:
 * <ul>
 * <li>T1: 2 A (23.800 TTC) + 1 S (5.950) + the stamp, 29.850 paid: HT 25.000, cost 12.000, margin 13.000, 52.0 %.</li>
 * <li>T2: 1 A + 1 B (11.900 each) + the stamp, 10 % ticket discount, 21.520 paid: factor 0.9, the costed HT 9.000, cost
 * 6.000, margin 3.000, one line without cost, no %.</li>
 * <li>R1: one A of T1 given back, 11.900: HT -10.000, cost -6.000, margin -4.000.</li>
 * </ul>
 */
class JournalMarginsTest {

	private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
	private static final LocalDate OLD_DAY = LocalDate.of(2026, 11, 1);

	private final Item a = item("ART-A", ItemType.PRODUCT);
	private final Item b = item("ART-B", ItemType.PRODUCT);
	private final Item service = item("SRV", ItemType.SERVICE);
	private final Item stampItem = item("TAX_STAMP", ItemType.PRODUCT);
	private final CashierSession session = new CashierSession();
	private final PaymentMethod cash = new PaymentMethod();

	private final List<SalesHeader> sales = new ArrayList<>();
	private final List<SalesLine> lines = new ArrayList<>();
	private final List<ReturnHeader> returns = new ArrayList<>();
	private final List<ReturnLine> returnLines = new ArrayList<>();
	private long nextId = 1;
	/** Calls of the two line projections (the day list's gains). */
	private int projectionCalls;

	private DayJournalService journal;
	private StoreDaysService days;

	@BeforeEach
	void setUp() throws Exception {
		session.setId(7L);
		session.setSessionNumber("S1");
		cash.setId(1L);
		cash.setCode("ESP");
		cash.setName("Espèces");
		cash.setType(PaymentMethodType.CLIENT_ESPECES);

		SalesHeader t1 = sale("T1", DAY.atTime(10, 0), 29.85);
		line(t1, a, 2, 23.8, "6.000");
		line(t1, service, 1, 5.95, null);
		line(t1, stampItem, 1, 0.1, null);
		SalesHeader t2 = sale("T2", DAY.atTime(11, 0), 21.52);
		t2.setDiscountPercentage(10.0);
		line(t2, a, 1, 11.9, "6.000");
		line(t2, b, 1, 11.9, null);
		line(t2, stampItem, 1, 0.1, null);
		ReturnHeader r1 = ret("R1", DAY.atTime(12, 0), 11.9);
		returnLine(r1, a, 1, 11.9, 10.0, "6.000");
		// a day before 3.1.2: no cost anywhere
		SalesHeader old = sale("T0", OLD_DAY.atTime(9, 0), 23.9);
		line(old, b, 2, 23.8, null);
		line(old, stampItem, 1, 0.1, null);
		// a pending ticket: never counted
		SalesHeader pending = sale("TP", DAY.atTime(13, 0), 11.9);
		pending.setStatus(TransactionStatus.PENDING);
		line(pending, a, 1, 11.9, "6.000");

		journal = new DayJournalService();
		days = new StoreDaysService();
		SalesHeaderRepository headers = proxy(SalesHeaderRepository.class, (m, x) -> {
			switch (m) {
				case "findFinishedBetween":
					return finished((LocalDateTime) x[0], (LocalDateTime) x[1]);
				case "findByStatusAndSalesDateGreaterThanEqualAndSalesDateLessThanOrderBySalesDateAsc":
				case "findSalesDatesByStatusBetween":
					return new ArrayList<>();
				case "findFinishedTimesAndTotals":
					return finished((LocalDateTime) x[0], (LocalDateTime) x[1]).stream()
							.map(s -> new Object[] { s.getSalesDate(), s.getTotalAmount() }).collect(Collectors.toList());
				default:
					return UNHANDLED;
			}
		});
		SalesLineRepository salesLines = proxy(SalesLineRepository.class, (m, x) -> {
			if ("findBySalesHeaderIn".equals(m)) {
				return lines.stream().filter(l -> ((List<?>) x[0]).contains(l.getSalesHeader())).collect(Collectors.toList());
			}
			if ("findCostLinesOfFinishedBetween".equals(m)) {
				projectionCalls++;
				List<SalesHeader> inRange = finished((LocalDateTime) x[0], (LocalDateTime) x[1]);
				return lines.stream().filter(l -> inRange.contains(l.getSalesHeader())).map(l -> new Object[] {
						l.getSalesHeader().getId(), l.getSalesHeader().getSalesDate(), l.getSalesHeader().getTotalAmount(),
						l.getItem().getItemCode(), l.getItem().getType(), l.getLineTotalIncludingVat(), l.getLineTotal(),
						l.getVatPercent(), l.getQuantity(), l.getUnitCost() }).collect(Collectors.toList());
			}
			return UNHANDLED;
		});
		ReturnHeaderRepository returnHeaders = proxy(ReturnHeaderRepository.class, (m, x) -> {
			switch (m) {
				case "findByReturnDateGreaterThanEqualAndReturnDateLessThanOrderByReturnDateAsc":
					return returnsIn((LocalDateTime) x[0], (LocalDateTime) x[1]);
				case "findReturnTimesAndAmounts":
					return returnsIn((LocalDateTime) x[0], (LocalDateTime) x[1]).stream()
							.map(r -> new Object[] { r.getReturnDate(), r.getTotalReturnAmount() }).collect(Collectors.toList());
				default:
					return UNHANDLED;
			}
		});
		ReturnLineRepository returnLineRepository = proxy(ReturnLineRepository.class, (m, x) -> {
			if ("findByReturnHeaderIn".equals(m)) {
				return returnLines.stream().filter(l -> ((List<?>) x[0]).contains(l.getReturnHeader()))
						.collect(Collectors.toList());
			}
			if ("findCostLinesOfReturnsBetween".equals(m)) {
				projectionCalls++;
				List<ReturnHeader> inRange = returnsIn((LocalDateTime) x[0], (LocalDateTime) x[1]);
				return returnLines.stream().filter(l -> inRange.contains(l.getReturnHeader())).map(l -> new Object[] {
						l.getReturnHeader().getId(), l.getReturnHeader().getReturnDate(),
						l.getReturnHeader().getTotalReturnAmount(), l.getItem().getItemCode(), l.getItem().getType(),
						l.getLineTotalIncludingVat(), l.getLineTotal(), l.getQuantity(), l.getUnitCost() })
						.collect(Collectors.toList());
			}
			return UNHANDLED;
		});
		inject(journal, DayJournalService.class, "salesHeaderRepository", headers);
		inject(journal, DayJournalService.class, "salesLineRepository", salesLines);
		inject(journal, DayJournalService.class, "paymentRepository",
				proxy(PaymentRepository.class, (m, x) -> "findBySalesHeaderIn".equals(m) ? new ArrayList<>() : UNHANDLED));
		inject(journal, DayJournalService.class, "returnHeaderRepository", returnHeaders);
		inject(journal, DayJournalService.class, "returnLineRepository", returnLineRepository);
		inject(journal, DayJournalService.class, "paymentMethodRepository",
				proxy(PaymentMethodRepository.class, (m, x) -> "findAll".equals(m) ? Arrays.asList(cash) : UNHANDLED));
		inject(journal, DayJournalService.class, "generalSetupRepository",
				proxy(GeneralSetupRepository.class, (m, x) -> "findByCode".equals(m) ? Optional.empty() : UNHANDLED));
		inject(journal, DayJournalService.class, "companyInformationService", new CompanyInformationService(null) {
			@Override
			public CompanyInformationDTO get() {
				return new CompanyInformationDTO();
			}
		});
		inject(days, StoreDaysService.class, "salesHeaderRepository", headers);
		inject(days, StoreDaysService.class, "returnHeaderRepository", returnHeaders);
		inject(days, StoreDaysService.class, "salesLineRepository", salesLines);
		inject(days, StoreDaysService.class, "returnLineRepository", returnLineRepository);
		inject(days, StoreDaysService.class, "cashierSessionRepository", proxy(CashierSessionRepository.class, (m, x) -> {
			switch (m) {
				case "findByStatusInAndClosedAtGreaterThanEqualAndClosedAtLessThanOrderByOpenedAtAsc":
				case "findByStatusAndOpenedAtGreaterThanEqualAndOpenedAtLessThan":
					return new ArrayList<>();
				case "countByStatus":
					return 0L;
				default:
					return UNHANDLED;
			}
		}));
	}

	@Test
	@DisplayName("Day journal: each ticket and return with its cost, margin and lines without cost; the day added up")
	void dayJournal() {
		DayJournalDTO dto = journal.build(DAY, true);
		JournalLine t1 = line(dto, "T1");
		assertEquals(12.0, t1.getCost(), 0.0);
		assertEquals(13.0, t1.getMargin(), 0.0005);
		assertEquals(52.0, t1.getMarginPercent(), 0.0);
		assertEquals(Long.valueOf(0), t1.getLinesWithoutCost(), "the service counts at 0, the stamp never");
		JournalLine t2 = line(dto, "T2");
		assertEquals(6.0, t2.getCost(), 0.0);
		assertEquals(3.0, t2.getMargin(), 0.0005, "the ticket discount is in the HT: 10.000 x 0.9 - 6.000");
		assertNull(t2.getMarginPercent(), "a line without cost: no %");
		assertEquals(Long.valueOf(1), t2.getLinesWithoutCost());
		JournalLine r1 = line(dto, "R1");
		assertEquals(-6.0, r1.getCost(), 0.0);
		assertEquals(-4.0, r1.getMargin(), 0.0005);
		DayJournalDTO.Totals totals = dto.getTotals();
		assertEquals(12.0, totals.getCost(), 0.0, "12 + 6 - 6");
		assertEquals(12.0, totals.getMargin(), 0.0005, "13 + 3 - 4");
		assertNull(totals.getMarginPercent(), "the day has a line without cost");
		assertEquals(Long.valueOf(1), totals.getLinesWithoutCost());
		assertEquals(25.0, t1.getTotalHT(), 0.0005, "the Journal's own HT unchanged");
	}

	@Test
	@DisplayName("The day list: the same numbers as the day journal's totals, from the projection of the lines")
	void dayListEqualsTheJournal() {
		DayRowDTO row = day(days.buildMonth(YearMonth.from(DAY), true), DAY);
		DayJournalDTO.Totals totals = journal.build(DAY, true).getTotals();
		assertEquals(totals.getCost(), row.getCost());
		assertEquals(totals.getMargin(), row.getMargin());
		assertEquals(totals.getMarginPercent(), row.getMarginPercent());
		assertEquals(totals.getLinesWithoutCost(), row.getLinesWithoutCost());
	}

	@Test
	@DisplayName("Margin %: a day whose every line has a cost: margin / HT (tickets - returns), one decimal")
	void marginPercent() {
		sales.removeIf(s -> "T2".equals(s.getSalesNumber()));
		DayJournalDTO.Totals totals = journal.build(DAY, true).getTotals();
		assertEquals(9.0, totals.getMargin(), 0.0005, "13 - 4");
		assertEquals(60.0, totals.getMarginPercent(), 0.0, "9 / (25 - 10)");
		assertEquals(Long.valueOf(0), totals.getLinesWithoutCost());
		assertEquals(60.0, day(days.buildMonth(YearMonth.from(DAY), true), DAY).getMarginPercent(), 0.0);
	}

	@Test
	@DisplayName("A day before 3.1.2 (no costed line): the four fields null on its ticket, its totals and its row")
	void dayWithoutCost() {
		DayJournalDTO dto = journal.build(OLD_DAY, true);
		JournalLine t0 = line(dto, "T0");
		assertNull(t0.getCost());
		assertNull(t0.getMargin());
		assertNull(t0.getMarginPercent());
		assertNull(t0.getLinesWithoutCost(), "no costed line: no marker either");
		assertNull(dto.getTotals().getCost());
		assertNull(dto.getTotals().getLinesWithoutCost());
		DayRowDTO row = day(days.buildMonth(YearMonth.from(OLD_DAY), true), OLD_DAY);
		assertNull(row.getCost());
		assertNull(row.getMargin());
		assertNull(row.getMarginPercent());
		assertNull(row.getLinesWithoutCost());
	}

	/**
	 * A day with 3 tickets without any costed line and 1 costed ticket with one line without cost: T3 two lines of B, T4
	 * one of B and a service (cost 0 by nature), T5 one of B and the stamp, T6 one of A (cost 6) and one of B.
	 */
	private static final LocalDate MIXED_DAY = LocalDate.of(2026, 11, 4);

	private void mixedDay() {
		SalesHeader t3 = sale("T3", MIXED_DAY.atTime(9, 0), 23.8);
		line(t3, b, 1, 11.9, null);
		line(t3, b, 1, 11.9, null);
		SalesHeader t4 = sale("T4", MIXED_DAY.atTime(10, 0), 17.85);
		line(t4, b, 1, 11.9, null);
		line(t4, service, 1, 5.95, null);
		SalesHeader t5 = sale("T5", MIXED_DAY.atTime(11, 0), 12.0);
		line(t5, b, 1, 11.9, null);
		line(t5, stampItem, 1, 0.1, null);
		SalesHeader t6 = sale("T6", MIXED_DAY.atTime(12, 0), 23.8);
		line(t6, a, 1, 11.9, "6.000");
		line(t6, b, 1, 11.9, null);
	}

	@Test
	@DisplayName("The day's lines without cost are those of ALL its documents (3 uncosted tickets + 1 costed): 5; a ticket keeps its own rule")
	void dayCountsEveryLineWithoutCost() {
		mixedDay();
		DayJournalDTO dto = journal.build(MIXED_DAY, true);
		JournalLine t3 = line(dto, "T3");
		assertNull(t3.getCost(), "a ticket without costed line: the four fields null");
		assertNull(t3.getLinesWithoutCost());
		assertEquals(Long.valueOf(1), line(dto, "T6").getLinesWithoutCost());
		DayJournalDTO.Totals totals = dto.getTotals();
		assertEquals(6.0, totals.getCost(), 0.0);
		assertEquals(Long.valueOf(5), totals.getLinesWithoutCost(), "T3 2 + T4 1 (the service not) + T5 1 (the stamp not) + T6 1");
		assertNull(totals.getMarginPercent());
		DayRowDTO row = day(days.buildMonth(YearMonth.from(MIXED_DAY), true), MIXED_DAY);
		assertEquals(totals.getLinesWithoutCost(), row.getLinesWithoutCost(), "the list equal to the journal");
		assertEquals(totals.getCost(), row.getCost());
		assertEquals(totals.getMargin(), row.getMargin());
	}

	@Test
	@DisplayName("The month (the .xlsx total row): the same rule over every document of the month, the day before 3.1.2 included")
	void monthGains() {
		mixedDay();
		JournalCosts.Result month = days.monthGains(YearMonth.from(DAY));
		assertEquals(18.0, month.costValue(), 0.0, "12 (the 3rd) + 6 (the 4th)");
		assertEquals(Long.valueOf(7), month.linesWithoutCost, "1 (T2) + 1 (T0, the 1st) + 5 (the 4th)");
		assertNull(month.marginPercent);
	}

	@Test
	@DisplayName("Without margins (no right, or the ERP keeps the stock): no field, absent from the JSON, the projection never read")
	void withoutMargins() throws Exception {
		DayJournalDTO dto = journal.build(DAY);
		String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(dto);
		for (String key : new String[] { "\"cost\"", "\"margin\"", "\"marginPercent\"", "\"linesWithoutCost\"" }) {
			assertFalse(json.contains(key), key);
		}
		List<DayRowDTO> rows = days.buildMonth(YearMonth.from(DAY));
		assertEquals(0, projectionCalls, "no gains asked: the lines are not read");
		DayRowDTO row = day(rows, DAY);
		assertNull(row.getCost());
		assertFalse(new ObjectMapper().findAndRegisterModules().writeValueAsString(rows).contains("\"margin\""));
		assertEquals(2, row.getTicketsCount(), "the day itself unchanged");
	}

	@Test
	@DisplayName("The API: the gains only where the store keeps its own stock and for read:admin-margins")
	void apiRule() throws Exception {
		assertTrue(api(TestModes.standalone(), "read:admin-margins").withMargins());
		assertFalse(api(TestModes.standalone(), "write:admin-item-cost").withMargins(), "the Journal: read:admin-margins only");
		assertFalse(api(TestModes.standalone()).withMargins());
		assertFalse(api(TestModes.erp(), "read:admin-margins").withMargins(), "the ERP keeps the stock");
	}

	@Test
	@DisplayName("JournalCosts: a ticket with no goods, a return without lines: nothing; the stamp never a line")
	void edges() {
		assertNull(JournalCosts.ticket(Collections.emptyList(), 0.1).cost);
		assertNull(JournalCosts.returned(Collections.emptyList(), 5.0).cost);
		JournalCosts.Result onlyStamp = JournalCosts
				.ticket(Collections.singletonList(new JournalCosts.Line("TAX_STAMP", null, 0.1, 0.1, 0, BigDecimal.ONE, null)), 0.1);
		assertNull(onlyStamp.linesWithoutCost);
		JournalCosts.Result discountItem = JournalCosts.ticket(Arrays.asList(
				new JournalCosts.Line("A", ItemType.PRODUCT, 11.9, 10.0, 19, BigDecimal.ONE, new BigDecimal("6.000")),
				new JournalCosts.Line("D", ItemType.DISCOUNT, -1.19, -1.0, 19, BigDecimal.ONE, null)), 10.71);
		assertEquals(6.0, discountItem.costValue(), 0.0);
		assertEquals(3.0, discountItem.marginValue(), 0.0005, "a discount item: no cost, its negative HT lowers the margin");
		assertEquals(Long.valueOf(0), discountItem.linesWithoutCost);
	}

	@Test
	@DisplayName("A day's cost and margin: the sum of its rows as shown (each to the millime), not the rounding of the unrounded sums")
	void daySumsTheRowsAsShown() {
		// 0.5 x 2.001 = 1.0005 per ticket, shown 1.001; margin 2 - 1.0005 = 0.9995, shown 1.000
		JournalCosts.Result first = JournalCosts.ticket(Collections.singletonList(
				new JournalCosts.Line("A", ItemType.PRODUCT, 2.0, 2.0, 0, new BigDecimal("0.5"), new BigDecimal("2.001"))), 2.0);
		JournalCosts.Result second = JournalCosts.ticket(Collections.singletonList(
				new JournalCosts.Line("A", ItemType.PRODUCT, 2.0, 2.0, 0, new BigDecimal("0.5"), new BigDecimal("2.001"))), 2.0);
		assertEquals(1.001, first.costValue(), 0.0);
		assertEquals(1.0, first.marginValue(), 0.0);
		JournalCosts.Result day = JournalCosts.day(Arrays.asList(first, second));
		assertEquals(2.002, day.costValue(), 0.0, "1.001 + 1.001 (the unrounded 2.001 is what the rows do not show)");
		assertEquals(2.0, day.marginValue(), 0.0, "1.000 + 1.000 (not 1.999)");
		assertEquals(first.costValue() + second.costValue(), day.costValue(), 1e-9);
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private CashierSessionAPI api(ApplicationModeService mode, String... permissions) throws Exception {
		AppRole role = new AppRole();
		role.setPermissions(new HashSet<>(Arrays.asList(permissions)));
		UserAccount user = new UserAccount();
		user.setUsername("admin");
		user.setAppRole(role);
		CashierSessionAPI api = new CashierSessionAPI();
		inject(api, CashierSessionAPI.class, "applicationModeService", mode);
		inject(api, CashierSessionAPI.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return user;
			}
		});
		return api;
	}

	private List<SalesHeader> finished(LocalDateTime from, LocalDateTime to) {
		return sales.stream().filter(s -> s.getStatus() != TransactionStatus.PENDING
				&& s.getStatus() != TransactionStatus.CANCELLED && !s.getSalesDate().isBefore(from)
				&& s.getSalesDate().isBefore(to)).sorted(Comparator.comparing(SalesHeader::getSalesDate))
				.collect(Collectors.toList());
	}

	private List<ReturnHeader> returnsIn(LocalDateTime from, LocalDateTime to) {
		return returns.stream().filter(r -> !r.getReturnDate().isBefore(from) && r.getReturnDate().isBefore(to))
				.collect(Collectors.toList());
	}

	private static Item item(String code, ItemType type) {
		Item item = new Item();
		item.setItemCode(code);
		item.setType(type);
		return item;
	}

	private SalesHeader sale(String number, LocalDateTime date, double total) {
		SalesHeader sale = new SalesHeader();
		sale.setId(nextId++);
		sale.setSalesNumber(number);
		sale.setCashierSession(session);
		sale.setStatus(TransactionStatus.COMPLETED);
		sale.setSalesDate(date);
		sale.setTotalAmount(total);
		sale.setDiscountAmount(0.0);
		sale.setChangeAmount(0.0);
		sales.add(sale);
		return sale;
	}

	/** A line at 19 % (the stamp at 0 %): its TTC, its HT = TTC / 1.19, its frozen unit cost. */
	private void line(SalesHeader sale, Item item, int quantity, double ttc, String unitCost) {
		int rate = "TAX_STAMP".equals(item.getItemCode()) ? 0 : 19;
		SalesLine line = new SalesLine();
		line.setId(nextId++);
		line.setSalesHeader(sale);
		line.setItem(item);
		line.setQuantity(BigDecimal.valueOf(quantity));
		line.setVatPercent(rate);
		line.setLineTotalIncludingVat(ttc);
		line.setLineTotal(ttc / (1 + rate / 100.0));
		line.setUnitPrice(line.getLineTotal() / quantity);
		line.setUnitPriceIncludingVat(ttc / quantity);
		line.setUnitCost(unitCost == null ? null : new BigDecimal(unitCost));
		lines.add(line);
	}

	private ReturnHeader ret(String number, LocalDateTime date, double refund) {
		ReturnHeader ret = new ReturnHeader();
		ret.setId(nextId++);
		ret.setReturnNumber(number);
		ret.setReturnType(ReturnType.SIMPLE_RETURN);
		ret.setReturnDate(date);
		ret.setCashierSession(session);
		ret.setTotalReturnAmount(refund);
		returns.add(ret);
		return ret;
	}

	private void returnLine(ReturnHeader ret, Item item, int quantity, double ttc, double ht, String unitCost) {
		ReturnLine line = new ReturnLine();
		line.setId(nextId++);
		line.setReturnHeader(ret);
		line.setItem(item);
		line.setQuantity(BigDecimal.valueOf(quantity));
		line.setLineTotal(ht);
		line.setLineTotalIncludingVat(ttc);
		line.setUnitCost(unitCost == null ? null : new BigDecimal(unitCost));
		returnLines.add(line);
	}

	private static JournalLine line(DayJournalDTO dto, String number) {
		return dto.getLines().stream().filter(l -> number.equals(l.getNumber())).findFirst()
				.orElseThrow(() -> new AssertionError("no line " + number));
	}

	private static DayRowDTO day(List<DayRowDTO> rows, LocalDate date) {
		return rows.stream().filter(r -> date.equals(r.getDate())).findFirst()
				.orElseThrow(() -> new AssertionError("no day " + date));
	}

	private static void inject(Object target, Class<?> declaringClass, String name, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
