package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.UserAccount;
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
import com.digithink.zsretail.store.cashdifference.CashDifferencesDTO;
import com.digithink.zsretail.store.cashdifference.CashDifferencesDTO.WatchRow;
import com.digithink.zsretail.store.cashdifference.CashDifferencesService;
import com.digithink.zsretail.store.cashmovement.dto.CashMovementDTO;
import com.digithink.zsretail.store.cashmovement.model.CashMovement;
import com.digithink.zsretail.store.cashmovement.model.CashMovementType;
import com.digithink.zsretail.store.dto.DayJournalDTO;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO;
import com.digithink.zsretail.store.support.InMemorySessionFigures;

/**
 * 3.2.0, the differences, step C: the block "À surveiller" of one day equals the Journal of that day (tickets,
 * cancelled tickets, returns and their amount, the dépenses and prélèvements of its cash movements) and the tickets
 * X / Z's REMISES (line + ticket discounts) on the same documents: two sessions opened and closed that day (the Journal
 * reads each document by its own date, the block by its session's opening: the same when no session crosses midnight).
 */
class CashWatchJournalTest {

	private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

	private final InMemorySessionFigures db = new InMemorySessionFigures();
	private CashDifferencesService page;
	private DayJournalService journal;
	private long nextId = 500;

	@BeforeEach
	void setUp() throws Exception {
		CashierSession amira = session(1L, "Amira", 8);
		CashierSession bilel = session(2L, "Bilel", 14);
		SalesHeader a1 = sale(amira, TransactionStatus.COMPLETED, 40.0, 9, 5.0, "MANUAL");
		line(a1, 2.0, "PROMOTION", "ART");
		line(a1, 1.0, "MANUAL", "TAX_STAMP");
		sale(amira, TransactionStatus.CANCELLED, 30.0, 10, null, null);
		SalesHeader b1 = sale(bilel, TransactionStatus.COMPLETED, 25.0, 15, null, null);
		line(b1, 3.5, "MANUAL", "ART");
		sale(bilel, TransactionStatus.COMPLETED, 12.0, 16, null, null);
		sale(bilel, TransactionStatus.PENDING, 9.0, 17, null, null);
		returnOf(amira, ReturnType.SIMPLE_RETURN, 10.0, 11);
		returnOf(bilel, ReturnType.RETURN_VOUCHER, 6.5, 17);
		movement(amira, CashMovementType.DEPENSE, 7.5);
		movement(bilel, CashMovementType.PRELEVEMENT, 100.0);
		movement(bilel, CashMovementType.APPORT, 20.0);

		page = new CashDifferencesService();
		InMemorySessionFigures.set(page, "figures", db.repository);
		InMemorySessionFigures.set(page, "sessionFigures", db.service);
		InMemorySessionFigures.set(page, "drawerHandoverService", db.handover);
		InMemorySessionFigures.set(page, "watchRepository", db.watchRepository);

		journal = new DayJournalService();
		inject(journal, "salesHeaderRepository", proxy(SalesHeaderRepository.class, (m, a) -> {
			switch (m) {
				case "findFinishedBetween": {
					List<?> excluded = (List<?>) a[2];
					return db.sales.stream().filter(s -> !excluded.contains(s.getStatus())
							&& in(s.getSalesDate(), (LocalDateTime) a[0], (LocalDateTime) a[1]))
							.sorted(Comparator.comparing(SalesHeader::getSalesDate)).collect(Collectors.toList());
				}
				case "findByStatusAndSalesDateGreaterThanEqualAndSalesDateLessThanOrderBySalesDateAsc":
					return db.sales.stream().filter(s -> s.getStatus() == a[0]
							&& in(s.getSalesDate(), (LocalDateTime) a[1], (LocalDateTime) a[2])).collect(Collectors.toList());
				default:
					return UNHANDLED;
			}
		}));
		inject(journal, "salesLineRepository", proxy(SalesLineRepository.class, (m, a) -> "findBySalesHeaderIn".equals(m)
				? db.lines.stream().filter(l -> ((List<?>) a[0]).contains(l.getSalesHeader())).collect(Collectors.toList())
				: UNHANDLED));
		inject(journal, "paymentRepository", proxy(PaymentRepository.class,
				(m, a) -> "findBySalesHeaderIn".equals(m) ? new ArrayList<>() : UNHANDLED));
		inject(journal, "returnHeaderRepository", proxy(ReturnHeaderRepository.class,
				(m, a) -> "findByReturnDateGreaterThanEqualAndReturnDateLessThanOrderByReturnDateAsc".equals(m)
						? db.returns.stream().filter(r -> in(r.getReturnDate(), (LocalDateTime) a[0], (LocalDateTime) a[1]))
								.collect(Collectors.toList())
						: UNHANDLED));
		inject(journal, "returnLineRepository", proxy(ReturnLineRepository.class,
				(m, a) -> "findByReturnHeaderIn".equals(m) ? new ArrayList<>() : UNHANDLED));
		inject(journal, "paymentMethodRepository", proxy(PaymentMethodRepository.class,
				(m, a) -> "findAll".equals(m) ? new ArrayList<>() : UNHANDLED));
		inject(journal, "generalSetupRepository", proxy(GeneralSetupRepository.class,
				(m, a) -> "findByCode".equals(m) ? Optional.empty() : UNHANDLED));
		inject(journal, "companyInformationService", new CompanyInformationService(null) {
			@Override
			public CompanyInformationDTO get() {
				return new CompanyInformationDTO();
			}
		});
	}

	@Test
	@DisplayName("The block's total of one day equals the Journal's tickets, cancelled, returns and movements, and the"
			+ " X / Z's discounts")
	void equalsTheJournal() {
		CashDifferencesDTO result = page.all(new CashDifferencesService.Filter(DAY, DAY, null, null, true), true);
		WatchRow total = result.getWatchTotal();
		DayJournalDTO day = journal.build(DAY);

		assertEquals(day.getTotals().getTicketsCount(), total.getTickets());
		assertEquals(day.getTotals().getCancelledCount(), total.getCancelledCount());
		assertEquals(day.getTotals().getReturnsCount(), total.getReturnsCount());
		assertEquals(day.getTotals().getReturnsAmount(), total.getReturnsAmount(), 1e-9);

		SessionReportTicketDTO.CashMovements movements = DayJournalService.cashMovementTotals(db.movements.stream()
				.map(CashWatchJournalTest::dto).collect(Collectors.toList()));
		assertEquals(movements.getDepenseCount(), total.getExpenseCount());
		assertEquals(movements.getDepenseTotal(), total.getExpenseAmount(), 1e-9);
		assertEquals(movements.getPrelevementCount(), total.getWithdrawalCount());
		assertEquals(movements.getPrelevementTotal(), total.getWithdrawalAmount(), 1e-9);

		List<SalesHeader> finished = db.sales.stream()
				.filter(s -> !SessionReportService.EXCLUDED.contains(s.getStatus())).collect(Collectors.toList());
		SessionReportTicketDTO.Sales xz = new SessionReportService().sales(finished, db.lines, db.sales, false);
		assertEquals(xz.getLineDiscounts() + xz.getTicketDiscounts(), total.getDiscountAmount(), 1e-9);
		assertEquals(10.5, total.getDiscountAmount(), 1e-9, "5 + 2 + 3.5, the stamp line left out");
		assertEquals(2, total.getDiscountTickets());
		assertEquals(8.5, total.getManualDiscountAmount(), 1e-9);
		assertEquals(3, total.getTickets(), "the pending ticket is not one");
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private CashierSession session(Long cashierId, String name, int hour) {
		UserAccount cashier = new UserAccount();
		cashier.setId(cashierId);
		cashier.setUsername(name.toLowerCase());
		cashier.setFullName(name);
		CashierSession session = new CashierSession();
		session.setId(nextId++);
		session.setSessionNumber("SESSION" + session.getId());
		session.setCashier(cashier);
		session.setOpeningCash(100.0);
		session.setOpenedAt(DAY.atTime(hour, 0));
		session.setClosedAt(DAY.atTime(hour + 5, 0));
		session.setStatus(SessionStatus.TERMINATED);
		db.sessions.add(session);
		return session;
	}

	private SalesHeader sale(CashierSession session, TransactionStatus status, double total, int hour, Double discount,
			String source) {
		SalesHeader sale = new SalesHeader();
		sale.setId(nextId++);
		sale.setSalesNumber("T" + sale.getId());
		sale.setCashierSession(session);
		sale.setStatus(status);
		sale.setTotalAmount(total);
		sale.setDiscountAmount(discount);
		sale.setDiscountSource(source);
		sale.setChangeAmount(0.0);
		sale.setSalesDate(DAY.atTime(hour, 0));
		db.sales.add(sale);
		return sale;
	}

	private void line(SalesHeader sale, double discount, String source, String itemCode) {
		SalesLine line = new SalesLine();
		line.setId(nextId++);
		line.setSalesHeader(sale);
		line.setDiscountAmount(discount);
		line.setDiscountSource(source);
		line.setLineTotalIncludingVat(10.0);
		line.setQuantity(BigDecimal.ONE);
		Item item = new Item();
		item.setItemCode(itemCode);
		line.setItem(item);
		db.lines.add(line);
	}

	private void returnOf(CashierSession session, ReturnType type, double amount, int hour) {
		ReturnHeader r = new ReturnHeader();
		r.setId(nextId++);
		r.setReturnNumber("R" + r.getId());
		r.setCashierSession(session);
		r.setReturnType(type);
		r.setTotalReturnAmount(amount);
		r.setReturnDate(DAY.atTime(hour, 30));
		db.returns.add(r);
	}

	private void movement(CashierSession session, CashMovementType type, double amount) {
		CashMovement m = new CashMovement();
		m.setId(nextId++);
		m.setCashierSession(session);
		m.setType(type);
		m.setAmount(amount);
		m.setMovedAt(session.getOpenedAt().plusHours(1));
		db.movements.add(m);
	}

	private static CashMovementDTO dto(CashMovement m) {
		CashMovementDTO dto = new CashMovementDTO();
		dto.setType(m.getType().name());
		dto.setAmount(m.getAmount());
		dto.setMovedAt(m.getMovedAt());
		return dto;
	}

	private static boolean in(LocalDateTime time, LocalDateTime from, LocalDateTime to) {
		return time != null && !time.isBefore(from) && time.isBefore(to);
	}

	private static void inject(Object target, String field, Object value) throws Exception {
		Field f = target.getClass().getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}
}
