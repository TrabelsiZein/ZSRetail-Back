package com.digithink.zsretail.store.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.CompanyInformationService;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.ControlLine;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.PaymentLine;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.VatLine;
import com.digithink.zsretail.store.dto.StoreReportTicketDTO;
import com.digithink.zsretail.store.dto.StoreReportTicketDTO.SessionRow;

/**
 * 3.1.0: the store ticket X (today) and Z (one day): the session ticket of each session of the day
 * (SessionReportService.build) added up, read-only (docs/modules/cash-sessions.md, "Store ticket X / Z").
 * <ul>
 * <li>Z of a date: the sessions CLOSED or TERMINATED whose closedAt is on that date, whatever their opening; the open
 * sessions are left out.</li>
 * <li>X: today, the sessions closed today (as the Z of today) and the sessions open now (opened any day): after the
 * last closing of the day, the X and the Z of today hold the same sessions.</li>
 * </ul>
 * No session: IllegalArgumentException (404 at the API). The amounts are added up in BigDecimal from the session
 * tickets' millimes, so each VAT line keeps HT + VAT = TTC and the total keeps HT + VAT + stamp = TTC.
 */
@Service
public class StoreReportService {

	static final List<SessionStatus> CLOSED = Arrays.asList(SessionStatus.CLOSED, SessionStatus.TERMINATED);

	@Autowired
	private CashierSessionRepository cashierSessionRepository;

	@Autowired
	private SessionReportService sessionReportService;

	@Autowired
	private PaymentMethodRepository paymentMethodRepository;

	@Autowired
	private CompanyInformationService companyInformationService;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	/** The day of a ticket X; a test sets its own. */
	Clock clock = Clock.systemDefaultZone();

	/** The store ticket X of today. */
	public StoreReportTicketDTO buildX() {
		LocalDate today = LocalDate.now(clock);
		List<CashierSession> sessions = cashierSessionRepository
				.findByStatusInAndClosedAtGreaterThanEqualAndClosedAtLessThanOrStatusOrderByOpenedAtAsc(CLOSED,
						today.atStartOfDay(), today.plusDays(1).atStartOfDay(), SessionStatus.OPENED);
		if (sessions.isEmpty()) {
			throw new IllegalArgumentException("No cashier session closed today (" + today + ") or open now: no store ticket X.");
		}
		return sum("X", today, sessions);
	}

	/** The store ticket Z of a date. */
	public StoreReportTicketDTO buildZ(LocalDate date) {
		List<CashierSession> sessions = cashierSessionRepository
				.findByStatusInAndClosedAtGreaterThanEqualAndClosedAtLessThanOrderByOpenedAtAsc(CLOSED,
						date.atStartOfDay(), date.plusDays(1).atStartOfDay());
		if (sessions.isEmpty()) {
			throw new IllegalArgumentException("No cashier session closed on " + date + ": no store ticket Z.");
		}
		return sum("Z", date, sessions);
	}

	private StoreReportTicketDTO sum(String type, LocalDate date, List<CashierSession> sessions) {
		boolean z = "Z".equals(type);
		List<SessionReportTicketDTO> reports = sessions.stream().map(s -> sessionReportService.build(s.getId()))
				.collect(Collectors.toList());

		StoreReportTicketDTO dto = new StoreReportTicketDTO();
		dto.setType(type);
		dto.setDate(date);
		dto.setStoreName(companyInformationService.get().getCompanyName());
		for (SessionReportTicketDTO r : reports) {
			dto.getSessions().add(new SessionRow(r.getSessionId(), r.getSessionNumber(), r.getCashierName(),
					r.getOpenedAt(), r.getClosedAt(), r.getStatus()));
		}
		dto.setSales(sales(reports, z));
		dto.setReturns(returns(reports));
		dto.setNetSales(total(reports, SessionReportTicketDTO::getNetSales));
		dto.setOpeningCash(total(reports, SessionReportTicketDTO::getOpeningCash));

		Map<String, Integer> order = order();
		dto.setPayments(payments(reports, order));
		dto.setControl(control(reports, order, z));
		if (z) {
			dto.setModifiedAfterClosingCount(reports.stream().filter(SessionReportTicketDTO::isModifiedAfterClosing).count());
		}

		dto.setPrintedAt(LocalDateTime.now(clock));
		dto.setPrintedBy(printedBy());
		return dto;
	}

	// ─── VENTES ──────────────────────────────────────────────────────

	private static SessionReportTicketDTO.Sales sales(List<SessionReportTicketDTO> reports, boolean z) {
		SessionReportTicketDTO.Sales block = new SessionReportTicketDTO.Sales();
		Map<Integer, BigDecimal[]> byRate = new TreeMap<>();
		long pending = 0;
		for (SessionReportTicketDTO r : reports) {
			SessionReportTicketDTO.Sales s = r.getSales();
			block.setTicketsCount(block.getTicketsCount() + s.getTicketsCount());
			block.setCancelledCount(block.getCancelledCount() + s.getCancelledCount());
			pending += s.getPendingCount() != null ? s.getPendingCount() : 0;
			for (VatLine v : s.getVatLines()) {
				BigDecimal[] sums = byRate.computeIfAbsent(v.getRate(),
						k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
				sums[0] = sums[0].add(bd(v.getBaseHT()));
				sums[1] = sums[1].add(bd(v.getVatAmount()));
				sums[2] = sums[2].add(bd(v.getTotalTTC()));
			}
		}
		byRate.forEach((rate, sums) -> block.getVatLines()
				.add(new VatLine(rate, sums[0].doubleValue(), sums[1].doubleValue(), sums[2].doubleValue())));
		block.setTotalHT(total(reports, r -> r.getSales().getTotalHT()));
		block.setTotalVat(total(reports, r -> r.getSales().getTotalVat()));
		block.setTaxStamp(total(reports, r -> r.getSales().getTaxStamp()));
		block.setTotalTTC(total(reports, r -> r.getSales().getTotalTTC()));
		block.setLineDiscounts(total(reports, r -> r.getSales().getLineDiscounts()));
		block.setTicketDiscounts(total(reports, r -> r.getSales().getTicketDiscounts()));
		block.setLoyaltyDiscounts(total(reports, r -> r.getSales().getLoyaltyDiscounts()));
		block.setPendingCount(z ? null : pending);
		return block;
	}

	// ─── RETOURS ─────────────────────────────────────────────────────

	private static SessionReportTicketDTO.Returns returns(List<SessionReportTicketDTO> reports) {
		SessionReportTicketDTO.Returns block = new SessionReportTicketDTO.Returns();
		for (SessionReportTicketDTO r : reports) {
			block.setCount(block.getCount() + r.getReturns().getCount());
			block.setSimpleCount(block.getSimpleCount() + r.getReturns().getSimpleCount());
			block.setVoucherCount(block.getVoucherCount() + r.getReturns().getVoucherCount());
		}
		block.setSimpleAmount(total(reports, r -> r.getReturns().getSimpleAmount()));
		block.setVoucherAmount(total(reports, r -> r.getReturns().getVoucherAmount()));
		block.setTotalAmount(total(reports, r -> r.getReturns().getTotalAmount()));
		return block;
	}

	// ─── PAIEMENTS ───────────────────────────────────────────────────

	private static List<PaymentLine> payments(List<SessionReportTicketDTO> reports, Map<String, Integer> order) {
		Map<String, PaymentLine> byCode = new LinkedHashMap<>();
		Map<String, BigDecimal> amounts = new HashMap<>();
		for (SessionReportTicketDTO r : reports) {
			for (PaymentLine p : r.getPayments()) {
				PaymentLine line = byCode.computeIfAbsent(p.getCode(),
						k -> new PaymentLine(p.getCode(), p.getName(), p.isCash(), 0, null));
				line.setTicketsCount(line.getTicketsCount() + p.getTicketsCount());
				amounts.merge(p.getCode(), bd(p.getAmount()), BigDecimal::add);
			}
		}
		byCode.forEach((code, line) -> line.setAmount(amounts.get(code).doubleValue()));
		return sorted(byCode, order, PaymentLine::isCash);
	}

	// ─── CONTRÔLE ────────────────────────────────────────────────────

	/**
	 * Per method: the expected amounts added up; on a ticket Z the counts added up too, null when one session counted
	 * nothing for the method (a session closed with a total only declares the cash alone). A session without the method
	 * adds 0 to both.
	 */
	private static List<ControlLine> control(List<SessionReportTicketDTO> reports, Map<String, Integer> order,
			boolean z) {
		Map<String, ControlLine> byCode = new LinkedHashMap<>();
		Map<String, BigDecimal> expected = new HashMap<>();
		Map<String, BigDecimal> counted = new HashMap<>();
		Map<String, Boolean> undeclared = new HashMap<>();
		for (SessionReportTicketDTO r : reports) {
			boolean open = r.getStatus() == SessionStatus.OPENED;
			for (ControlLine c : r.getControl()) {
				byCode.computeIfAbsent(c.getCode(), k -> new ControlLine(c.getCode(), c.getName(), c.isCash(), null,
						null, null));
				expected.merge(c.getCode(), bd(c.getExpected()), BigDecimal::add);
				if (open || c.getCounted() == null) {
					undeclared.put(c.getCode(), true);
				} else {
					counted.merge(c.getCode(), bd(c.getCounted()), BigDecimal::add);
				}
			}
		}
		byCode.forEach((code, line) -> {
			BigDecimal exp = expected.get(code);
			line.setExpected(exp.doubleValue());
			if (z && !undeclared.containsKey(code)) {
				BigDecimal cnt = counted.getOrDefault(code, BigDecimal.ZERO.setScale(3));
				line.setCounted(cnt.doubleValue());
				line.setDifference(cnt.subtract(exp).doubleValue());
			}
		});
		return sorted(byCode, order, ControlLine::isCash);
	}

	// ─── Helpers ─────────────────────────────────────────────────────

	/** The payment methods' order of the till (displayOrder), by code. */
	private Map<String, Integer> order() {
		Map<String, Integer> order = new HashMap<>();
		for (PaymentMethod method : paymentMethodRepository.findAll()) {
			order.put(method.getCode(), method.getDisplayOrder() != null ? method.getDisplayOrder() : Integer.MAX_VALUE);
		}
		return order;
	}

	/** The lines in the till's order: the cash first when it has no order, then displayOrder, then code. */
	private static <T> List<T> sorted(Map<String, T> byCode, Map<String, Integer> order, Function<T, Boolean> cash) {
		return byCode.entrySet().stream()
				.sorted(Comparator.comparing((Map.Entry<String, T> e) -> order.getOrDefault(e.getKey(),
						Boolean.TRUE.equals(cash.apply(e.getValue())) ? 0 : Integer.MAX_VALUE))
						.thenComparing(Map.Entry::getKey))
				.map(Map.Entry::getValue).collect(Collectors.toCollection(ArrayList::new));
	}

	private static Double total(List<SessionReportTicketDTO> reports, Function<SessionReportTicketDTO, Double> field) {
		return reports.stream().map(r -> bd(field.apply(r))).reduce(BigDecimal.ZERO.setScale(3), BigDecimal::add)
				.doubleValue();
	}

	private static BigDecimal bd(Double value) {
		return value == null ? BigDecimal.ZERO.setScale(3)
				: BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
	}

	private String printedBy() {
		try {
			return SessionReportService.displayName(currentUserProvider.getCurrentUser());
		} catch (RuntimeException e) {
			return currentUserProvider.getCurrentUserName();
		}
	}
}
