package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.inject;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.ReturnHeaderRepository;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.store.dto.DayRowDTO;

/**
 * 3.1.0: the days of a month (StoreDaysService) over tickets, returns and sessions in memory; the repository stubs
 * apply the rules of their queries (tickets by their finish, cancelled ones by salesDate, returns by returnDate,
 * sessions closed by closedAt, open ones by openedAt).
 */
class StoreDaysServiceTest {

	private static final double EPS = 1e-9;
	private static final YearMonth MONTH = YearMonth.of(2026, 10);
	private static final LocalDate D12 = LocalDate.of(2026, 10, 12);
	private static final LocalDate D13 = LocalDate.of(2026, 10, 13);

	private final List<SalesHeader> sales = new ArrayList<>();
	private final List<ReturnHeader> returns = new ArrayList<>();
	private final List<CashierSession> sessions = new ArrayList<>();
	private long nextId = 1;

	private StoreDaysService service;

	@BeforeEach
	void setUp() {
		service = new StoreDaysService();
		inject(service, "salesHeaderRepository", proxy(SalesHeaderRepository.class, (m, a) -> {
			switch (m) {
				case "findFinishedTimesAndTotals": {
					List<?> excluded = (List<?>) a[2];
					return sales.stream().filter(s -> !excluded.contains(s.getStatus())
							&& in(salesDateOf(s), (LocalDateTime) a[0], (LocalDateTime) a[1]))
							.map(s -> new Object[] { salesDateOf(s), s.getTotalAmount() }).collect(Collectors.toList());
				}
				case "findSalesDatesByStatusBetween":
					return sales.stream().filter(s -> s.getStatus() == a[0]
							&& in(s.getSalesDate(), (LocalDateTime) a[1], (LocalDateTime) a[2]))
							.map(SalesHeader::getSalesDate).collect(Collectors.toList());
				default:
					return UNHANDLED;
			}
		}));
		inject(service, "returnHeaderRepository", proxy(ReturnHeaderRepository.class,
				(m, a) -> "findReturnTimesAndAmounts".equals(m)
						? returns.stream().filter(r -> in(r.getReturnDate(), (LocalDateTime) a[0], (LocalDateTime) a[1]))
								.map(r -> new Object[] { r.getReturnDate(), r.getTotalReturnAmount() })
								.collect(Collectors.toList())
						: UNHANDLED));
		inject(service, "cashierSessionRepository", proxy(CashierSessionRepository.class, (m, a) -> {
			switch (m) {
				case "findByStatusInAndClosedAtGreaterThanEqualAndClosedAtLessThanOrderByOpenedAtAsc":
					return sessions.stream().filter(s -> ((List<?>) a[0]).contains(s.getStatus())
							&& in(s.getClosedAt(), (LocalDateTime) a[1], (LocalDateTime) a[2])).collect(Collectors.toList());
				case "findByStatusAndOpenedAtGreaterThanEqualAndOpenedAtLessThan":
					return sessions.stream().filter(s -> s.getStatus() == a[0]
							&& in(s.getOpenedAt(), (LocalDateTime) a[1], (LocalDateTime) a[2])).collect(Collectors.toList());
				case "countByStatus":
					return sessions.stream().filter(s -> s.getStatus() == a[0]).count();
				default:
					return UNHANDLED;
			}
		}));
		// today is D13
		service.clock = java.time.Clock.fixed(D13.atTime(15, 0).atZone(java.time.ZoneId.systemDefault()).toInstant(),
				java.time.ZoneId.systemDefault());
	}

	private static boolean in(LocalDateTime time, LocalDateTime from, LocalDateTime to) {
		return time != null && !time.isBefore(from) && time.isBefore(to);
	}

	private static LocalDateTime salesDateOf(SalesHeader sale) {
		return sale.getSalesDate(); // the queries select every ticket by its salesDate, as the sales reports
	}

	private void sale(TransactionStatus status, LocalDateTime when, double total) {
		SalesHeader sale = new SalesHeader();
		sale.setId(nextId++);
		sale.setStatus(status);
		sale.setSalesDate(when);
		sale.setCompletedDate(status == TransactionStatus.COMPLETED ? when : null);
		sale.setTotalAmount(total);
		sales.add(sale);
	}

	private void ret(LocalDateTime when, double amount) {
		ReturnHeader ret = new ReturnHeader();
		ret.setId(nextId++);
		ret.setReturnType(ReturnType.SIMPLE_RETURN);
		ret.setReturnDate(when);
		ret.setTotalReturnAmount(amount);
		returns.add(ret);
	}

	private CashierSession session(LocalDateTime opened, LocalDateTime closed) {
		CashierSession session = new CashierSession();
		session.setId(nextId++);
		session.setSessionNumber("S" + session.getId());
		session.setOpenedAt(opened);
		session.setClosedAt(closed);
		session.setStatus(closed == null ? SessionStatus.OPENED : SessionStatus.CLOSED);
		sessions.add(session);
		return session;
	}

	/**
	 * D12: a session closed, two tickets (100 + 50). D13: a session closed, a session still open, a ticket 200, a
	 * return of 30, a cancelled ticket, a pending one (not counted). A ticket of September is not in October.
	 */
	private void twoDays() {
		session(D12.atTime(8, 0), D12.atTime(20, 0));
		sale(TransactionStatus.COMPLETED, D12.atTime(9, 0), 100.0);
		sale(TransactionStatus.COMPLETED, D12.atTime(10, 0), 50.0);
		session(D13.atTime(8, 0), D13.atTime(14, 0));
		session(D13.atTime(14, 30), null);
		sale(TransactionStatus.COMPLETED, D13.atTime(9, 0), 200.0);
		ret(D13.atTime(11, 0), 30.0);
		sale(TransactionStatus.CANCELLED, D13.atTime(12, 0), 40.0);
		sale(TransactionStatus.PENDING, D13.atTime(15, 0), 25.0);
		sale(TransactionStatus.COMPLETED, LocalDate.of(2026, 9, 30).atTime(18, 0), 999.0);
	}

	@Test
	@DisplayName("A month with two days gives two rows, newest first, each with its sessions, tickets, returns and net")
	void twoDaysNewestFirst() {
		twoDays();
		List<DayRowDTO> rows = service.buildMonth(MONTH);

		assertEquals(Arrays.asList(D13, D12), rows.stream().map(DayRowDTO::getDate).collect(Collectors.toList()));
		DayRowDTO d13 = rows.get(0);
		assertEquals(1, d13.getSessionsClosed());
		assertEquals(1, d13.getSessionsOpen());
		assertEquals(1, d13.getTicketsCount(), "the pending ticket is not finished");
		assertEquals(1, d13.getReturnsCount());
		assertEquals(1, d13.getCancelledCount());
		assertEquals(200.0, d13.getTotalTTC(), EPS);
		assertEquals(30.0, d13.getReturnsAmount(), EPS);
		assertEquals(170.0, d13.getNet(), EPS, "a return reduces the net");
		DayRowDTO d12 = rows.get(1);
		assertEquals(1, d12.getSessionsClosed());
		assertEquals(0, d12.getSessionsOpen());
		assertEquals(2, d12.getTicketsCount());
		assertEquals(150.0, d12.getTotalTTC(), EPS);
		assertEquals(150.0, d12.getNet(), EPS);
	}

	@Test
	@DisplayName("inProgress only on today's row, while a session is open, whatever its opening day")
	void inProgress() {
		twoDays();
		List<DayRowDTO> rows = service.buildMonth(MONTH);
		assertTrue(rows.get(0).isInProgress(), "D13, today: a session still open");
		assertFalse(rows.get(1).isInProgress(), "D12: not today");

		// only a session opened the day before is still open: today is still in progress, D12 is not
		sessions.stream().filter(s -> s.getStatus() == SessionStatus.OPENED).forEach(s -> {
			s.setStatus(SessionStatus.CLOSED);
			s.setClosedAt(D13.atTime(15, 0));
		});
		session(D12.atTime(21, 0), null);
		rows = service.buildMonth(MONTH);
		assertTrue(rows.get(0).isInProgress(), "D13: the session opened on D12 sells into today");
		assertFalse(rows.get(1).isInProgress(), "D12: its session is open, but D12 is not today");
		assertEquals(1, rows.get(1).getSessionsOpen(), "D12 still counts it as opened that day");

		// no session open: today is not in progress
		sessions.forEach(s -> {
			s.setStatus(SessionStatus.CLOSED);
			if (s.getClosedAt() == null) s.setClosedAt(D13.atTime(15, 0));
		});
		assertFalse(service.buildMonth(MONTH).get(0).isInProgress());
	}

	@Test
	@DisplayName("A session opened one day and closed the next counts as closed on its closing day; a day with only that")
	void sessionClosedNextDay() {
		session(LocalDate.of(2026, 10, 20).atTime(22, 0), LocalDate.of(2026, 10, 21).atTime(1, 0));
		List<DayRowDTO> rows = service.buildMonth(MONTH);
		assertEquals(1, rows.size());
		assertEquals(LocalDate.of(2026, 10, 21), rows.get(0).getDate());
		assertEquals(1, rows.get(0).getSessionsClosed());
		assertEquals(0.0, rows.get(0).getNet(), EPS);
	}

	@Test
	@DisplayName("A month with nothing gives no row")
	void emptyMonth() {
		twoDays();
		assertTrue(service.buildMonth(YearMonth.of(2026, 11)).isEmpty());
	}

	@Test
	@DisplayName("Excel: the header row, one row per day, the totals row, 3 decimals")
	void excel() throws Exception {
		twoDays();
		byte[] file = JournalExcel.days(MONTH, service.buildMonth(MONTH));
		assertEquals("jours-2026-10.xlsx", JournalExcel.daysFileName(MONTH));
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
			Sheet sheet = workbook.getSheetAt(0);
			assertEquals("Date", sheet.getRow(0).getCell(0).getStringCellValue());
			assertEquals(1 + 2 + 1, sheet.getPhysicalNumberOfRows());
			assertEquals(170.0, sheet.getRow(1).getCell(8).getNumericCellValue(), EPS);
			assertEquals("Oui", sheet.getRow(1).getCell(9).getStringCellValue());
			assertEquals("TOTAL", sheet.getRow(3).getCell(0).getStringCellValue());
			assertEquals(3, (long) sheet.getRow(3).getCell(3).getNumericCellValue(), "3 tickets");
			assertEquals(320.0, sheet.getRow(3).getCell(8).getNumericCellValue(), EPS);
			assertEquals("#,##0.000", sheet.getRow(3).getCell(8).getCellStyle().getDataFormatString());
		}
	}
}
