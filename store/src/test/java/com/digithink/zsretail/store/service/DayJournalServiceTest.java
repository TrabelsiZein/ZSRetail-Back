package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.inject;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.ReturnLine;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.core.repository.PaymentRepository;
import com.digithink.zsretail.core.repository.ReturnHeaderRepository;
import com.digithink.zsretail.core.repository.ReturnLineRepository;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.service.CompanyInformationService;
import com.digithink.zsretail.store.dto.DayJournalDTO;
import com.digithink.zsretail.store.dto.DayJournalDTO.JournalLine;
import com.digithink.zsretail.store.dto.DayJournalDTO.MethodAmount;

/**
 * 3.1.0: the day journal (DayJournalService) over a day in memory; the repository stubs apply the rules of their
 * queries (a finished ticket by completedDate or else salesDate, a cancelled one by salesDate, a return by returnDate).
 */
class DayJournalServiceTest {

	private static final double EPS = 1e-9;
	private static final LocalDate DAY = LocalDate.of(2026, 10, 13);

	private final PaymentMethod cash = method(1L, "ESP", "Espèces", PaymentMethodType.CLIENT_ESPECES, 1);
	private final PaymentMethod card = method(2L, "TPE", "Carte bancaire", PaymentMethodType.CLIENT_TPE, 2);
	private final PaymentMethod voucher = method(8L, "BON", "Bon d'achat", PaymentMethodType.RETURN_VOUCHER, 8);
	private final Item goods = item("ART001");
	private final Item stampItem = item("TAX_STAMP");
	private final Customer passenger = customer("PASSENGER", "Passenger Customer");
	private final Customer sami = customer("C001", "Sami Trabelsi");
	private final CashierSession session = new CashierSession();

	private final List<SalesHeader> sales = new ArrayList<>();
	private final List<SalesLine> lines = new ArrayList<>();
	private final List<Payment> payments = new ArrayList<>();
	private final List<ReturnHeader> returns = new ArrayList<>();
	private final List<ReturnLine> returnLines = new ArrayList<>();
	private long nextId = 1;

	private DayJournalService service;

	@BeforeEach
	void setUp() {
		session.setId(7L);
		session.setSessionNumber("SESSION261013001");
		UserAccount cashier = new UserAccount();
		cashier.setUsername("cashier1");
		cashier.setFullName("Amira Ben Salem");
		session.setCashier(cashier);
		session.setStatus(SessionStatus.OPENED);

		service = new DayJournalService();
		inject(service, "salesHeaderRepository", proxy(SalesHeaderRepository.class, (m, a) -> {
			switch (m) {
				case "findFinishedBetween": {
					LocalDateTime from = (LocalDateTime) a[0];
					LocalDateTime to = (LocalDateTime) a[1];
					List<?> excluded = (List<?>) a[2];
					return sales.stream().filter(s -> !excluded.contains(s.getStatus()) && in(salesDateOf(s), from, to))
							.sorted(Comparator.comparing(DayJournalServiceTest::salesDateOf)).collect(Collectors.toList());
				}
				case "findByStatusAndSalesDateGreaterThanEqualAndSalesDateLessThanOrderBySalesDateAsc":
					return sales.stream().filter(s -> s.getStatus() == a[0]
							&& in(s.getSalesDate(), (LocalDateTime) a[1], (LocalDateTime) a[2]))
							.sorted(Comparator.comparing(SalesHeader::getSalesDate)).collect(Collectors.toList());
				default:
					return UNHANDLED;
			}
		}));
		inject(service, "salesLineRepository", proxy(SalesLineRepository.class, (m, a) -> "findBySalesHeaderIn".equals(m)
				? lines.stream().filter(l -> ((List<?>) a[0]).contains(l.getSalesHeader())).collect(Collectors.toList())
				: UNHANDLED));
		inject(service, "paymentRepository", proxy(PaymentRepository.class, (m, a) -> "findBySalesHeaderIn".equals(m)
				? payments.stream().filter(p -> ((List<?>) a[0]).contains(p.getSalesHeader())).collect(Collectors.toList())
				: UNHANDLED));
		inject(service, "returnHeaderRepository", proxy(ReturnHeaderRepository.class,
				(m, a) -> "findByReturnDateGreaterThanEqualAndReturnDateLessThanOrderByReturnDateAsc".equals(m)
						? returns.stream().filter(r -> in(r.getReturnDate(), (LocalDateTime) a[0], (LocalDateTime) a[1]))
								.sorted(Comparator.comparing(ReturnHeader::getReturnDate)).collect(Collectors.toList())
						: UNHANDLED));
		inject(service, "returnLineRepository", proxy(ReturnLineRepository.class, (m, a) -> "findByReturnHeaderIn".equals(m)
				? returnLines.stream().filter(l -> ((List<?>) a[0]).contains(l.getReturnHeader())).collect(Collectors.toList())
				: UNHANDLED));
		inject(service, "paymentMethodRepository", proxy(PaymentMethodRepository.class,
				(m, a) -> "findAll".equals(m) ? Arrays.asList(voucher, card, cash) : UNHANDLED));
		inject(service, "generalSetupRepository", proxy(GeneralSetupRepository.class, (m, a) -> {
			if (!"findByCode".equals(m)) return UNHANDLED;
			if (!DayJournalService.PASSENGER_CUSTOMER.equals(a[0])) return Optional.empty();
			GeneralSetup setup = new GeneralSetup();
			setup.setCode(DayJournalService.PASSENGER_CUSTOMER);
			setup.setValeur("PASSENGER");
			return Optional.of(setup);
		}));
		inject(service, "companyInformationService", new CompanyInformationService(null) {
			@Override
			public CompanyInformationDTO get() {
				CompanyInformationDTO info = new CompanyInformationDTO();
				info.setCompanyName("PARA FENDRI");
				return info;
			}
		});
	}

	private static boolean in(LocalDateTime time, LocalDateTime from, LocalDateTime to) {
		return time != null && !time.isBefore(from) && time.isBefore(to);
	}

	private static LocalDateTime salesDateOf(SalesHeader sale) {
		return sale.getSalesDate(); // the queries select every ticket by its salesDate, as the sales reports
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private static PaymentMethod method(Long id, String code, String name, PaymentMethodType type, int order) {
		PaymentMethod method = new PaymentMethod();
		method.setId(id);
		method.setCode(code);
		method.setName(name);
		method.setType(type);
		method.setDisplayOrder(order);
		return method;
	}

	private static Item item(String code) {
		Item item = new Item();
		item.setItemCode(code);
		return item;
	}

	private static Customer customer(String code, String name) {
		Customer customer = new Customer();
		customer.setCustomerCode(code);
		customer.setName(name);
		return customer;
	}

	private SalesHeader sale(String number, TransactionStatus status, LocalDateTime salesDate, LocalDateTime completed,
			double total, Customer customer) {
		SalesHeader sale = new SalesHeader();
		sale.setId(nextId++);
		sale.setSalesNumber(number);
		sale.setCashierSession(session);
		sale.setStatus(status);
		sale.setSalesDate(salesDate);
		sale.setCompletedDate(completed);
		sale.setTotalAmount(total);
		sale.setDiscountAmount(0.0);
		sale.setChangeAmount(0.0);
		sale.setCustomer(customer);
		sales.add(sale);
		return sale;
	}

	private void line(SalesHeader sale, Item item, int rate, double ttc) {
		SalesLine line = new SalesLine();
		line.setId(nextId++);
		line.setSalesHeader(sale);
		line.setItem(item);
		line.setQuantity(BigDecimal.ONE);
		line.setVatPercent(rate);
		line.setLineTotalIncludingVat(ttc);
		line.setUnitPriceIncludingVat(ttc);
		line.setLineTotal(ttc / (1 + rate / 100.0));
		line.setVatAmount(ttc - line.getLineTotal());
		lines.add(line);
	}

	private void pay(SalesHeader sale, PaymentMethod method, double amount) {
		Payment payment = new Payment();
		payment.setId(nextId++);
		payment.setSalesHeader(sale);
		payment.setPaymentMethod(method);
		payment.setTotalAmount(amount);
		payments.add(payment);
	}

	private ReturnHeader ret(String number, ReturnType type, LocalDateTime date, double refund, double linesHt,
			double linesTtc) {
		ReturnHeader ret = new ReturnHeader();
		ret.setId(nextId++);
		ret.setReturnNumber(number);
		ret.setReturnType(type);
		ret.setReturnDate(date);
		ret.setCashierSession(session);
		ret.setTotalReturnAmount(refund);
		returns.add(ret);
		ReturnLine line = new ReturnLine();
		line.setId(nextId++);
		line.setReturnHeader(ret);
		line.setItem(goods);
		line.setLineTotal(linesHt);
		line.setLineTotalIncludingVat(linesTtc);
		returnLines.add(line);
		return ret;
	}

	/**
	 * DAY: T1 09:00 by the passenger, 119 at 19 % paid 120 in cash with 1 of change; T2 10:00 by Sami, 107 at 7 % and
	 * a stamp of 1, paid 108 by card; C1 11:00 cancelled (50); R1 12:00 a simple return of 23.8 (20 HT). A ticket of the
	 * day before and a pending one are not in it.
	 */
	private void standardDay() {
		SalesHeader t1 = sale("T1", TransactionStatus.COMPLETED, DAY.atTime(8, 55), DAY.atTime(9, 0), 119.0, passenger);
		t1.setChangeAmount(1.0);
		line(t1, goods, 19, 119.0);
		pay(t1, cash, 120.0);
		SalesHeader t2 = sale("T2", TransactionStatus.COMPLETED, DAY.atTime(10, 0), DAY.atTime(10, 0), 108.0, sami);
		line(t2, goods, 7, 107.0);
		line(t2, stampItem, 0, 1.0);
		pay(t2, card, 108.0);
		sale("C1", TransactionStatus.CANCELLED, DAY.atTime(11, 0), null, 50.0, passenger);
		ret("R1", ReturnType.SIMPLE_RETURN, DAY.atTime(12, 0), 23.8, 20.0, 23.8);
		SalesHeader before = sale("T0", TransactionStatus.COMPLETED, DAY.minusDays(1).atTime(18, 0),
				DAY.minusDays(1).atTime(18, 0), 500.0, passenger);
		line(before, goods, 19, 500.0);
		sale("P1", TransactionStatus.PENDING, DAY.atTime(13, 0), null, 30.0, passenger);
	}

	private static JournalLine line(DayJournalDTO dto, String number) {
		return dto.getLines().stream().filter(l -> number.equals(l.getNumber())).findFirst().orElse(null);
	}

	private static double method(List<MethodAmount> amounts, String name) {
		return amounts.stream().filter(p -> name.equals(p.getMethodName())).mapToDouble(MethodAmount::getAmount).sum();
	}

	private static BigDecimal exact(Double value) {
		return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
	}

	// ─── Tests ───────────────────────────────────────────────────────

	@Test
	@DisplayName("A day with two tickets, a cancelled ticket and a return: the lines in time order, each with its amounts")
	void dayLines() {
		standardDay();
		DayJournalDTO dto = service.build(DAY);

		assertEquals(DAY, dto.getDate());
		assertEquals("PARA FENDRI", dto.getStoreName());
		assertEquals(Arrays.asList("T1", "T2", "C1", "R1"),
				dto.getLines().stream().map(JournalLine::getNumber).collect(Collectors.toList()),
				"time order; not the ticket of the day before nor the pending one");
		JournalLine t1 = line(dto, "T1");
		assertEquals(DayJournalDTO.TICKET, t1.getType());
		assertEquals(DAY.atTime(8, 55), t1.getTime(), "its salesDate (finished at 09:00)");
		assertEquals("SESSION261013001", t1.getSessionNumber());
		assertEquals("Amira Ben Salem", t1.getCashierName());
		assertEquals(100.0, t1.getTotalHT(), EPS);
		assertEquals(19.0, t1.getTotalVat(), EPS);
		assertEquals(119.0, t1.getTotalTTC(), EPS);
		assertEquals(119.0, method(t1.getPayments(), "Espèces"), EPS, "120 handed over, 1 of change");
		JournalLine t2 = line(dto, "T2");
		assertEquals(100.0, t2.getTotalHT(), EPS);
		assertEquals(7.0, t2.getTotalVat(), EPS);
		assertEquals(1.0, t2.getTaxStamp(), EPS);
		assertEquals(exact(t2.getTotalTTC()), exact(t2.getTotalHT()).add(exact(t2.getTotalVat())).add(exact(t2.getTaxStamp())));
		JournalLine c1 = line(dto, "C1");
		assertEquals(DayJournalDTO.CANCELLED, c1.getType());
		assertEquals(0.0, c1.getTotalTTC(), EPS);
		assertTrue(c1.getPayments().isEmpty());
		JournalLine r1 = line(dto, "R1");
		assertEquals(DayJournalDTO.RETURN, r1.getType());
		assertEquals(-23.8, r1.getTotalTTC(), EPS);
		assertEquals(-20.0, r1.getTotalHT(), EPS);
		assertEquals(-3.8, r1.getTotalVat(), EPS);
		assertEquals(-23.8, method(r1.getPayments(), "Espèces"), EPS, "a simple return: cash given back");
	}

	@Test
	@DisplayName("Totals: counts, the tickets' HT / VAT / stamp / TTC, the returns, net, and per method (adding up to net)")
	void totalsAndPerMethod() {
		standardDay();
		DayJournalDTO.Totals totals = service.build(DAY).getTotals();

		assertEquals(2, totals.getTicketsCount());
		assertEquals(1, totals.getReturnsCount());
		assertEquals(1, totals.getCancelledCount());
		assertEquals(200.0, totals.getTotalHT(), EPS);
		assertEquals(26.0, totals.getTotalVat(), EPS);
		assertEquals(1.0, totals.getTaxStamp(), EPS);
		assertEquals(227.0, totals.getTotalTTC(), EPS);
		assertEquals(23.8, totals.getReturnsAmount(), EPS);
		assertEquals(203.2, totals.getNet(), EPS, "a return reduces the net");
		// cash 119 - 23.8 refunded, card 108; in the till's order
		assertEquals(Arrays.asList("Espèces", "Carte bancaire"),
				totals.getPayments().stream().map(MethodAmount::getMethodName).collect(Collectors.toList()));
		assertEquals(95.2, method(totals.getPayments(), "Espèces"), EPS);
		assertEquals(108.0, method(totals.getPayments(), "Carte bancaire"), EPS);
		assertEquals(totals.getNet(), totals.getPayments().stream().mapToDouble(MethodAmount::getAmount).sum(), 1e-6);
	}

	@Test
	@DisplayName("A return voucher is given back under the voucher method; the net goes down by it")
	void voucherReturn() {
		standardDay();
		ret("R2", ReturnType.RETURN_VOUCHER, DAY.atTime(15, 0), 10.7, 10.0, 10.7);
		DayJournalDTO dto = service.build(DAY);

		assertEquals(-10.7, method(line(dto, "R2").getPayments(), "Bon d'achat"), EPS);
		assertEquals(192.5, dto.getTotals().getNet(), EPS);
		assertEquals(-10.7, method(dto.getTotals().getPayments(), "Bon d'achat"), EPS);
	}

	@Test
	@DisplayName("The passenger customer gives no name; another customer gives his")
	void passengerCustomer() {
		standardDay();
		DayJournalDTO dto = service.build(DAY);
		assertNull(line(dto, "T1").getCustomerName());
		assertEquals("Sami Trabelsi", line(dto, "T2").getCustomerName());
		assertNull(line(dto, "C1").getCustomerName());
	}

	@Test
	@DisplayName("A ticket parked before midnight and finished after it is in the journal of its salesDate, the day before")
	void parkedTicketBySalesDate() {
		SalesHeader parked = sale("T9", TransactionStatus.COMPLETED, DAY.minusDays(1).atTime(23, 50), DAY.atTime(0, 10),
				11.9, passenger);
		line(parked, goods, 19, 11.9);
		pay(parked, cash, 11.9);
		DayJournalDTO dayBefore = service.build(DAY.minusDays(1));
		assertEquals("T9", dayBefore.getLines().get(0).getNumber());
		assertEquals(DAY.minusDays(1).atTime(23, 50), dayBefore.getLines().get(0).getTime());
		assertThrows(IllegalArgumentException.class, () -> service.build(DAY), "nothing on the day it was finished");
	}

	@Test
	@DisplayName("A day with nothing: refused (404 at the API)")
	void emptyDay() {
		standardDay();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.build(DAY.plusDays(1)));
		assertTrue(e.getMessage().contains("2026-10-14"), e.getMessage());
	}

	@Test
	@DisplayName("Excel: one sheet, the header row, one row per line, the totals row (net under Total TTC), 3 decimals")
	void excel() throws Exception {
		standardDay();
		DayJournalDTO dto = service.build(DAY);
		byte[] file = JournalExcel.journal(dto);
		assertEquals("journal-2026-10-13.xlsx", JournalExcel.journalFileName(dto));
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
			assertEquals(1, workbook.getNumberOfSheets());
			Sheet sheet = workbook.getSheetAt(0);
			assertEquals("Heure", sheet.getRow(0).getCell(0).getStringCellValue());
			assertEquals("Total TTC", sheet.getRow(0).getCell(9).getStringCellValue());
			assertEquals(1 + 4 + 2, sheet.getPhysicalNumberOfRows(), "header, 4 lines, two totals rows");
			assertEquals("T1", sheet.getRow(1).getCell(1).getStringCellValue());
			assertEquals(-23.8, sheet.getRow(4).getCell(9).getNumericCellValue(), EPS);
			Row sales = sheet.getRow(5);
			assertEquals("Total ventes TTC", sales.getCell(0).getStringCellValue());
			assertEquals(227.0, sales.getCell(9).getNumericCellValue(), EPS, "the finished tickets");
			assertEquals(200.0, sales.getCell(6).getNumericCellValue(), EPS);
			Row net = sheet.getRow(6);
			assertEquals("Net", net.getCell(0).getStringCellValue());
			assertEquals(203.2, net.getCell(9).getNumericCellValue(), EPS, "the column sum: tickets - returns");
			assertEquals(180.0, net.getCell(6).getNumericCellValue(), EPS, "HT 200 - 20 of the return");
			assertEquals("#,##0.000", net.getCell(9).getCellStyle().getDataFormatString());
		}
	}
}
