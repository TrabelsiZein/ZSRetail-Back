package com.digithink.zsretail.store.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.ReturnLine;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
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
import com.digithink.zsretail.store.dto.SessionReportTicketDTO;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.VatLine;

/**
 * 3.1.0: the journal of one day, read-only (docs/modules/cash-sessions.md, "Journal"): every finished ticket, return
 * and cancelled ticket of that date, by its own date, whatever its session.
 * <ul>
 * <li>A finished ticket (status neither PENDING nor CANCELLED) by its salesDate, as the sales reports (a ticket
 * parked before midnight and finished after it is on the day it was parked). HT and VAT as on the session ticket: the ticket discount and the loyalty points spread over the
 * rates (SessionReportService.addTicketTtcByRate, vatLines), so HT + VAT + stamp = TTC; its payments per method, the
 * cash net of the change.</li>
 * <li>A return by its returnDate: the amount given back, negative; HT in the proportion of its lines; the money out
 * under the cash (SIMPLE_RETURN) or the return voucher method (RETURN_VOUCHER).</li>
 * <li>A cancelled ticket by its salesDate (cancelling stores no date): amounts 0, no payment.</li>
 * </ul>
 * No line: IllegalArgumentException (404 at the API).
 */
@Service
public class DayJournalService {

	static final String PASSENGER_CUSTOMER = "PASSENGER_CUSTOMER";

	@Autowired
	private SalesHeaderRepository salesHeaderRepository;

	@Autowired
	private SalesLineRepository salesLineRepository;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private ReturnHeaderRepository returnHeaderRepository;

	@Autowired
	private ReturnLineRepository returnLineRepository;

	@Autowired
	private PaymentMethodRepository paymentMethodRepository;

	@Autowired
	private GeneralSetupRepository generalSetupRepository;

	@Autowired
	private CompanyInformationService companyInformationService;

	/**
	 * 3.2.0, POS machines step 5: the day's cash movements. A service built without it (a unit test of another rule) shows
	 * none.
	 */
	@Autowired
	private com.digithink.zsretail.store.cashmovement.service.CashMovementService cashMovementService;

	/** 3.2.0, the opening amount (a service built without it shows none). */
	@Autowired
	private com.digithink.zsretail.store.cashmovement.service.DrawerHandoverService drawerHandoverService;

	/** The totals per type of the day's movements; null without any. */
	static SessionReportTicketDTO.CashMovements cashMovementTotals(
			List<com.digithink.zsretail.store.cashmovement.dto.CashMovementDTO> movements) {
		// 3.2.0, the opening amount: a closing withdrawal (PRELEVEMENT_CLOTURE) is not a movement of the session's drawer
		List<com.digithink.zsretail.store.cashmovement.dto.CashMovementDTO> drawer = movements.stream()
				.filter(m -> !com.digithink.zsretail.store.cashmovement.service.DrawerHandoverService.CLOSING_TYPE
						.equals(m.getType()))
				.collect(Collectors.toList());
		if (drawer.isEmpty()) {
			return null;
		}
		SessionReportTicketDTO.CashMovements totals = new SessionReportTicketDTO.CashMovements();
		BigDecimal apport = BigDecimal.ZERO.setScale(3);
		BigDecimal prelevement = BigDecimal.ZERO.setScale(3);
		BigDecimal depense = BigDecimal.ZERO.setScale(3);
		for (com.digithink.zsretail.store.cashmovement.dto.CashMovementDTO m : drawer) {
			BigDecimal amount = bd(m.getAmount());
			if ("APPORT".equals(m.getType())) {
				totals.setApportCount(totals.getApportCount() + 1);
				apport = apport.add(amount);
			} else if ("PRELEVEMENT".equals(m.getType())) {
				totals.setPrelevementCount(totals.getPrelevementCount() + 1);
				prelevement = prelevement.add(amount);
			} else {
				totals.setDepenseCount(totals.getDepenseCount() + 1);
				depense = depense.add(amount);
			}
		}
		totals.setApportTotal(apport.doubleValue());
		totals.setPrelevementTotal(prelevement.doubleValue());
		totals.setDepenseTotal(depense.doubleValue());
		totals.setInTotal(apport.doubleValue());
		totals.setOutTotal(prelevement.add(depense).doubleValue());
		return totals;
	}

	/** The journal without the gains (as before 3.1.2). */
	@Transactional(readOnly = true)
	public DayJournalDTO build(LocalDate date) {
		return build(date, false);
	}

	/**
	 * The journal; with withMargins (the caller checks the installation and the right read:admin-margins) each ticket and
	 * return, and the totals, carry the gains (JournalCosts) from the lines read here: no other query.
	 */
	@Transactional(readOnly = true)
	public DayJournalDTO build(LocalDate date, boolean withMargins) {
		LocalDateTime from = date.atStartOfDay();
		LocalDateTime to = date.plusDays(1).atStartOfDay();
		List<SalesHeader> finished = salesHeaderRepository.findFinishedBetween(from, to, SessionReportService.EXCLUDED);
		List<SalesHeader> cancelled = salesHeaderRepository
				.findByStatusAndSalesDateGreaterThanEqualAndSalesDateLessThanOrderBySalesDateAsc(TransactionStatus.CANCELLED,
						from, to);
		List<ReturnHeader> returns = returnHeaderRepository
				.findByReturnDateGreaterThanEqualAndReturnDateLessThanOrderByReturnDateAsc(from, to);
		// 3.2.0, POS machines step 5: the day's cash movements, by their own time (a day with only movements has a journal)
		List<com.digithink.zsretail.store.cashmovement.dto.CashMovementDTO> movements = cashMovementService == null
				? new ArrayList<>() : new ArrayList<>(cashMovementService.linesOfDay(date));
		// 3.2.0, the opening amount: the closing withdrawals of the day (their own type) and the openings with a float
		List<com.digithink.zsretail.store.cashmovement.dto.OpeningFloatDTO> openingFloats = new ArrayList<>();
		if (drawerHandoverService != null) {
			movements.addAll(drawerHandoverService.closingLinesOfDay(date));
			movements.sort(java.util.Comparator.comparing(com.digithink.zsretail.store.cashmovement.dto.CashMovementDTO::getMovedAt,
					java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
			// an écart of 0 is not an écart: only the openings that differ from the float proposed
			drawerHandoverService.openingsOfDay(date).stream()
					.filter(o -> o.getOpeningGap() != null && Math.abs(o.getOpeningGap()) >= 0.0005).forEach(openingFloats::add);
		}
		if (finished.isEmpty() && cancelled.isEmpty() && returns.isEmpty() && movements.isEmpty()
				&& openingFloats.isEmpty()) {
			throw new IllegalArgumentException("No ticket, return or cancelled ticket on " + date + ": no journal.");
		}

		// one query per kind, never with an empty list (an empty IN is not valid SQL)
		Map<Long, List<SalesLine>> linesByTicket = finished.isEmpty() ? Collections.emptyMap()
				: salesLineRepository.findBySalesHeaderIn(finished).stream()
						.collect(Collectors.groupingBy(l -> l.getSalesHeader().getId()));
		Map<Long, List<Payment>> paymentsByTicket = finished.isEmpty() ? Collections.emptyMap()
				: paymentRepository.findBySalesHeaderIn(finished).stream()
						.collect(Collectors.groupingBy(p -> p.getSalesHeader().getId()));
		Map<Long, List<ReturnLine>> linesByReturn = returns.isEmpty() ? Collections.emptyMap()
				: returnLineRepository.findByReturnHeaderIn(returns).stream()
						.collect(Collectors.groupingBy(l -> l.getReturnHeader().getId()));
		Methods methods = new Methods(paymentMethodRepository.findAll());
		String passengerCode = generalSetupRepository.findByCode(PASSENGER_CUSTOMER).map(GeneralSetup::getValeur)
				.orElse(null);

		DayJournalDTO dto = new DayJournalDTO();
		dto.setDate(date);
		dto.setStoreName(companyInformationService.get().getCompanyName());
		dto.setCashMovements(movements);
		dto.setCashMovementTotals(cashMovementTotals(movements));
		dto.setOpeningFloats(openingFloats);
		dto.setOpeningGapTotal(openingFloats.isEmpty() ? null
				: openingFloats.stream().map(o -> bd(o.getOpeningGap())).reduce(BigDecimal.ZERO.setScale(3), BigDecimal::add)
						.doubleValue());
		List<JournalCosts.Result> gains = new ArrayList<>();
		for (SalesHeader sale : finished) {
			List<SalesLine> lines = linesByTicket.getOrDefault(sale.getId(), Collections.emptyList());
			JournalLine line = ticketLine(sale, lines, paymentsByTicket.getOrDefault(sale.getId(), Collections.emptyList()),
					methods, passengerCode);
			if (withMargins) {
				gains.add(gains(line, JournalCosts.ticket(costLinesOfSale(lines), sale.getTotalAmount())));
			}
			dto.getLines().add(line);
		}
		for (SalesHeader sale : cancelled) {
			dto.getLines().add(cancelledLine(sale, passengerCode));
		}
		for (ReturnHeader ret : returns) {
			List<ReturnLine> lines = linesByReturn.getOrDefault(ret.getId(), Collections.emptyList());
			JournalLine line = returnLine(ret, lines, methods);
			if (withMargins) {
				gains.add(gains(line, JournalCosts.returned(costLinesOfReturn(lines), ret.getTotalReturnAmount())));
			}
			dto.getLines().add(line);
		}
		dto.getLines().sort(Comparator.comparing(JournalLine::getTime, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(JournalLine::getNumber, Comparator.nullsLast(Comparator.naturalOrder())));
		dto.setTotals(totals(dto.getLines(), methods));
		if (withMargins) {
			JournalCosts.Result day = JournalCosts.day(gains);
			DayJournalDTO.Totals totals = dto.getTotals();
			totals.setCost(day.costValue());
			totals.setMargin(day.marginValue());
			totals.setMarginPercent(day.marginPercent);
			totals.setLinesWithoutCost(day.linesWithoutCost);
		}
		return dto;
	}

	// ─── Gains (3.1.2) ───────────────────────────────────────────────

	/** A document's gains on its journal line; the result for the day's sums. */
	private static JournalCosts.Result gains(JournalLine line, JournalCosts.Result result) {
		line.setCost(result.costValue());
		line.setMargin(result.marginValue());
		line.setMarginPercent(result.marginPercent);
		line.setLinesWithoutCost(result.linesWithoutCost);
		return result;
	}

	/** A ticket's lines as JournalCosts reads them (the same values as StoreDaysService's projection). */
	static List<JournalCosts.Line> costLinesOfSale(List<SalesLine> lines) {
		List<JournalCosts.Line> result = new ArrayList<>();
		for (SalesLine l : lines) {
			result.add(new JournalCosts.Line(l.getItem() != null ? l.getItem().getItemCode() : null,
					l.getItem() != null ? l.getItem().getType() : null, l.getLineTotalIncludingVat(), l.getLineTotal(),
					l.getVatPercent(), l.getQuantity(), l.getUnitCost()));
		}
		return result;
	}

	/** A return's lines as JournalCosts reads them. */
	static List<JournalCosts.Line> costLinesOfReturn(List<ReturnLine> lines) {
		List<JournalCosts.Line> result = new ArrayList<>();
		for (ReturnLine l : lines) {
			result.add(new JournalCosts.Line(l.getItem() != null ? l.getItem().getItemCode() : null,
					l.getItem() != null ? l.getItem().getType() : null, l.getLineTotalIncludingVat(), l.getLineTotal(),
					null, l.getQuantity(), l.getUnitCost()));
		}
		return result;
	}

	// ─── Lines ───────────────────────────────────────────────────────

	private static JournalLine ticketLine(SalesHeader sale, List<SalesLine> lines, List<Payment> payments,
			Methods methods, String passengerCode) {
		JournalLine line = header(sale, DayJournalDTO.TICKET, passengerCode);
		line.setTime(sale.getSalesDate());

		Map<Integer, Double> ttcByRate = new TreeMap<>();
		double[] totalAndStamp = SessionReportService.addTicketTtcByRate(sale, lines, ttcByRate);
		BigDecimal ttc = bd(totalAndStamp[0]);
		BigDecimal stamp = bd(totalAndStamp[1]);
		List<VatLine> vat = SessionReportService.vatLines(ttcByRate, ttc.subtract(stamp));
		line.setTotalHT(sum(vat, VatLine::getBaseHT));
		line.setTotalVat(sum(vat, VatLine::getVatAmount));
		line.setTaxStamp(stamp.doubleValue());
		line.setTotalTTC(ttc.doubleValue());

		// per method; the change is given back in cash, once per ticket with a cash payment (as calculateRealCash)
		Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
		boolean cash = false;
		for (Payment payment : payments) {
			if (payment.getPaymentMethod() == null) {
				continue;
			}
			String key = methods.key(payment.getPaymentMethod());
			byMethod.merge(key, bd(payment.getTotalAmount()), BigDecimal::add);
			cash |= payment.getPaymentMethod().getType() == PaymentMethodType.CLIENT_ESPECES;
		}
		if (cash && sale.getChangeAmount() != null && sale.getChangeAmount() > 0) {
			byMethod.merge(methods.cashKey(), bd(sale.getChangeAmount()).negate(), BigDecimal::add);
		}
		line.setPayments(methods.amounts(byMethod));
		return line;
	}

	private static JournalLine cancelledLine(SalesHeader sale, String passengerCode) {
		JournalLine line = header(sale, DayJournalDTO.CANCELLED, passengerCode);
		line.setTime(sale.getSalesDate());
		line.setTotalHT(0.0);
		line.setTotalVat(0.0);
		line.setTaxStamp(0.0);
		line.setTotalTTC(0.0);
		return line;
	}

	/** A return: the amount given back, negative; its HT in the proportion of its lines (HT over TTC). */
	private static JournalLine returnLine(ReturnHeader ret, List<ReturnLine> lines, Methods methods) {
		JournalLine line = new JournalLine();
		line.setType(DayJournalDTO.RETURN);
		line.setTime(ret.getReturnDate());
		line.setNumber(ret.getReturnNumber());
		session(line, ret.getCashierSession(), ret.getCreatedByUser());

		BigDecimal refund = bd(ret.getTotalReturnAmount());
		double linesTtc = lines.stream()
				.mapToDouble(l -> l.getLineTotalIncludingVat() != null ? l.getLineTotalIncludingVat() : 0.0).sum();
		double linesHt = lines.stream().mapToDouble(l -> l.getLineTotal() != null ? l.getLineTotal() : 0.0).sum();
		BigDecimal ht = linesTtc > 0 ? bd(linesHt * refund.doubleValue() / linesTtc) : refund;
		line.setTotalHT(ht.negate().doubleValue());
		line.setTotalVat(refund.subtract(ht).negate().doubleValue());
		line.setTaxStamp(0.0);
		line.setTotalTTC(refund.negate().doubleValue());

		String key = ret.getReturnType() == ReturnType.RETURN_VOUCHER ? methods.voucherKey() : methods.cashKey();
		Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
		byMethod.put(key, refund.negate());
		line.setPayments(methods.amounts(byMethod));
		return line;
	}

	private static JournalLine header(SalesHeader sale, String type, String passengerCode) {
		JournalLine line = new JournalLine();
		line.setType(type);
		line.setNumber(sale.getSalesNumber());
		session(line, sale.getCashierSession(), sale.getCreatedByUser());
		boolean passenger = sale.getCustomer() == null
				|| (passengerCode != null && passengerCode.equals(sale.getCustomer().getCustomerCode()));
		line.setCustomerName(passenger ? null : sale.getCustomer().getName());
		return line;
	}

	/** The session number and its cashier (the user who made the document when it has no session). */
	private static void session(JournalLine line, CashierSession session, com.digithink.zsretail.core.model.UserAccount user) {
		line.setSessionNumber(session != null ? session.getSessionNumber() : null);
		line.setCashierName(SessionReportService
				.displayName(session != null && session.getCashier() != null ? session.getCashier() : user));
	}

	// ─── Totals ──────────────────────────────────────────────────────

	private static DayJournalDTO.Totals totals(List<JournalLine> lines, Methods methods) {
		DayJournalDTO.Totals totals = new DayJournalDTO.Totals();
		BigDecimal ht = zero();
		BigDecimal vat = zero();
		BigDecimal stamp = zero();
		BigDecimal ttc = zero();
		BigDecimal returns = zero();
		Map<String, BigDecimal> byName = new LinkedHashMap<>();
		for (JournalLine line : lines) {
			switch (line.getType()) {
				case DayJournalDTO.TICKET:
					totals.setTicketsCount(totals.getTicketsCount() + 1);
					ht = ht.add(bd(line.getTotalHT()));
					vat = vat.add(bd(line.getTotalVat()));
					stamp = stamp.add(bd(line.getTaxStamp()));
					ttc = ttc.add(bd(line.getTotalTTC()));
					break;
				case DayJournalDTO.RETURN:
					totals.setReturnsCount(totals.getReturnsCount() + 1);
					returns = returns.add(bd(line.getTotalTTC()).negate());
					break;
				default:
					totals.setCancelledCount(totals.getCancelledCount() + 1);
			}
			for (MethodAmount p : line.getPayments()) {
				byName.merge(p.getMethodName(), bd(p.getAmount()), BigDecimal::add);
			}
		}
		totals.setTotalHT(ht.doubleValue());
		totals.setTotalVat(vat.doubleValue());
		totals.setTaxStamp(stamp.doubleValue());
		totals.setTotalTTC(ttc.doubleValue());
		totals.setReturnsAmount(returns.doubleValue());
		totals.setNet(ttc.subtract(returns).doubleValue());
		totals.setPayments(methods.sortedByName(byName));
		return totals;
	}

	// ─── Helpers ─────────────────────────────────────────────────────

	static BigDecimal bd(Double value) {
		return value == null ? zero() : BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
	}

	private static BigDecimal zero() {
		return BigDecimal.ZERO.setScale(3);
	}

	private static Double sum(List<VatLine> lines, java.util.function.Function<VatLine, Double> field) {
		return lines.stream().map(l -> bd(field.apply(l))).reduce(zero(), BigDecimal::add).doubleValue();
	}

	/** The payment methods by code: their names and the till's order; the cash and the return voucher methods. */
	static final class Methods {
		private final Map<String, PaymentMethod> byCode = new HashMap<>();
		private final Map<String, Integer> orderByName = new HashMap<>();
		private String cashKey = "CASH";
		private String voucherKey = "RETURN_VOUCHER";

		Methods(List<PaymentMethod> all) {
			for (PaymentMethod method : all) {
				byCode.put(method.getCode(), method);
				orderByName.put(name(method), method.getDisplayOrder() != null ? method.getDisplayOrder() : Integer.MAX_VALUE);
				if (method.getType() == PaymentMethodType.CLIENT_ESPECES) {
					cashKey = method.getCode();
				} else if (method.getType() == PaymentMethodType.RETURN_VOUCHER) {
					voucherKey = method.getCode();
				}
			}
		}

		private static String name(PaymentMethod method) {
			return method.getName() != null ? method.getName() : method.getCode();
		}

		String key(PaymentMethod method) {
			byCode.putIfAbsent(method.getCode(), method);
			return method.getCode();
		}

		String cashKey() {
			return cashKey;
		}

		String voucherKey() {
			return voucherKey;
		}

		String nameOf(String key) {
			PaymentMethod method = byCode.get(key);
			return method != null ? name(method) : key;
		}

		/** Amounts by method code to the named list, in the till's order. */
		List<MethodAmount> amounts(Map<String, BigDecimal> byCodeAmounts) {
			Map<String, BigDecimal> byName = new LinkedHashMap<>();
			byCodeAmounts.forEach((code, amount) -> byName.merge(nameOf(code), amount, BigDecimal::add));
			return sortedByName(byName);
		}

		List<MethodAmount> sortedByName(Map<String, BigDecimal> byName) {
			List<MethodAmount> result = new ArrayList<>();
			byName.entrySet().stream()
					.sorted(Comparator.comparing((Map.Entry<String, BigDecimal> e) -> orderByName.getOrDefault(e.getKey(),
							Integer.MAX_VALUE)).thenComparing(Map.Entry::getKey))
					.forEach(e -> result.add(new MethodAmount(e.getKey(), e.getValue().doubleValue())));
			return result;
		}
	}
}
