package com.digithink.zsretail.store.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.CounterType;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.core.repository.PaymentRepository;
import com.digithink.zsretail.core.repository.ReturnHeaderRepository;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.ControlLine;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.PaymentLine;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.VatLine;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.Verification;
import com.digithink.zsretail.store.model.SessionCashCount;
import com.digithink.zsretail.store.repository.PaymentChangeLogRepository;
import com.digithink.zsretail.store.repository.SessionCashCountRepository;

/**
 * 3.1.0: the session ticket X (session OPENED) and Z (CLOSED or TERMINATED), read-only: nothing is written, and the
 * session methods of CashierSessionService are only called, never changed (docs/modules/cash-sessions.md).
 * <ul>
 * <li>Sales: the tickets of the session, PENDING and CANCELLED left out, as in the session pages.</li>
 * <li>VAT per rate: the lines' TTC per rate times (ticket total - fiscal stamp) / goods TTC of the ticket, so the
 * ticket discount and the loyalty points (never spread on the lines) are spread over the rates; HT = TTC / (1 + rate),
 * VAT = TTC - HT, rounded to the millime so that HT + VAT + stamp = total TTC exactly.</li>
 * <li>Payments: per method; the cash net of the change given on the tickets that had a cash payment.</li>
 * <li>Control: the expected cash is the stored realCash of a closed or terminated session (recomputed when an admin
 * changes a payment method), else calculateRealCash through getExpectedCash; the count is the cashier's (POS_USER
 * rows, the float's rows without method count as cash).</li>
 * </ul>
 */
@Service
public class SessionReportService {

	static final List<TransactionStatus> EXCLUDED = Arrays.asList(TransactionStatus.PENDING,
			TransactionStatus.CANCELLED);

	/** The key of the cash rows when no CLIENT_ESPECES method exists (never on an initialised store). */
	static final String CASH_KEY = "CASH";

	@Autowired
	private CashierSessionRepository cashierSessionRepository;

	@Autowired
	private CashierSessionService cashierSessionService;

	@Autowired
	private SalesHeaderRepository salesHeaderRepository;

	@Autowired
	private SalesLineRepository salesLineRepository;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private ReturnHeaderRepository returnHeaderRepository;

	@Autowired
	private SessionCashCountRepository sessionCashCountRepository;

	@Autowired
	private PaymentMethodRepository paymentMethodRepository;

	@Autowired
	private PaymentChangeLogRepository paymentChangeLogRepository;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	/** The ticket X or Z of a session; IllegalArgumentException when the session does not exist. */
	@Transactional(readOnly = true)
	public SessionReportTicketDTO build(Long sessionId) {
		CashierSession session = cashierSessionRepository.findById(sessionId)
				.orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
		boolean open = session.getStatus() == SessionStatus.OPENED;

		List<SalesHeader> allSales = salesHeaderRepository.findByCashierSession(session);
		List<SalesHeader> sales = allSales.stream().filter(s -> !EXCLUDED.contains(s.getStatus()))
				.collect(Collectors.toList());
		List<SalesLine> lines = salesLineRepository.findByCashierSession(session, EXCLUDED);
		List<Payment> payments = paymentRepository.findByCashierSession(session, EXCLUDED);
		List<ReturnHeader> returns = returnHeaderRepository.findByCashierSession(session);
		List<SessionCashCount> counts = open ? Collections.emptyList()
				: sessionCashCountRepository.findByCashierSession(session);
		PaymentMethod cashMethod = paymentMethodRepository.findByType(PaymentMethodType.CLIENT_ESPECES).orElse(null);

		SessionReportTicketDTO dto = new SessionReportTicketDTO();
		dto.setType(open ? "X" : "Z");
		dto.setSessionId(session.getId());
		dto.setSessionNumber(session.getSessionNumber());
		dto.setStatus(session.getStatus());
		dto.setCashierName(displayName(session.getCashier()));
		dto.setOpenedAt(session.getOpenedAt());
		dto.setClosedAt(open ? null : session.getClosedAt());
		dto.setOpeningCash(millimes(session.getOpeningCash()));

		dto.setSales(sales(sales, lines, allSales, open));
		dto.setReturns(returns(returns));
		dto.setNetSales(millimes(dto.getSales().getTotalTTC() - dto.getReturns().getTotalAmount()));

		Methods methods = new Methods(cashMethod);
		Map<String, Double> paid = paymentsByMethod(payments, methods, dto);

		Double expectedCash = !open && session.getRealCash() != null ? session.getRealCash()
				: cashierSessionService.getExpectedCash(session.getId());
		Map<String, Double> expected = new HashMap<>();
		for (Map.Entry<String, Double> e : paid.entrySet()) {
			if (!methods.isCash(e.getKey())) {
				expected.put(e.getKey(), e.getValue());
			}
		}
		expected.put(methods.cashKey(), expectedCash != null ? expectedCash : 0.0);

		Map<String, Double> posCounted = countsByMethod(counts, CounterType.POS_USER, methods);
		Map<String, Double> respCounted = countsByMethod(counts, CounterType.RESPONSIBLE, methods);
		List<String> keys = methods.ordered(expected.keySet(), posCounted.keySet(), respCounted.keySet());

		boolean declared = !posCounted.isEmpty();
		for (String key : keys) {
			Double counted = null;
			if (!open) {
				if (declared) {
					counted = posCounted.getOrDefault(key, 0.0);
				} else if (methods.isCash(key)) {
					// a session closed with a total only (no count row): that total is the cash counted
					counted = session.getPosUserClosureCash();
				}
			}
			dto.getControl().add(controlLine(key, methods, expected.getOrDefault(key, 0.0), counted));
		}

		if (session.getStatus() == SessionStatus.TERMINATED) {
			dto.setVerification(verification(session, keys, methods, expected, respCounted));
		}

		if (!open && session.getClosedAt() != null) {
			long changes = paymentChangeLogRepository.countBySalesHeaderCashierSessionAndCreatedAtAfter(session,
					session.getClosedAt());
			dto.setPaymentChangesAfterClosing(changes);
			dto.setModifiedAfterClosing(changes > 0);
		}

		dto.setPrintedAt(LocalDateTime.now());
		dto.setPrintedBy(printedBy());
		return dto;
	}

	// ─── VENTES ──────────────────────────────────────────────────────

	SessionReportTicketDTO.Sales sales(List<SalesHeader> sales, List<SalesLine> lines, List<SalesHeader> allSales,
			boolean open) {
		Map<Long, List<SalesLine>> linesByTicket = lines.stream()
				.collect(Collectors.groupingBy(l -> l.getSalesHeader().getId()));

		Map<Integer, Double> ttcByRate = new TreeMap<>();
		double total = 0.0;
		double stamp = 0.0;
		double lineDiscounts = 0.0;
		double ticketDiscounts = 0.0;
		double loyalty = 0.0;
		for (SalesHeader sale : sales) {
			List<SalesLine> ticketLines = linesByTicket.getOrDefault(sale.getId(), Collections.emptyList());
			double[] goodsAndStamp = ReturnHeaderService.goodsAndStamp(ticketLines);
			double ticketTotal = sale.getTotalAmount() != null ? sale.getTotalAmount()
					: goodsAndStamp[0] + goodsAndStamp[1];
			double goodsPaid = ticketTotal - goodsAndStamp[1];
			total += ticketTotal;
			stamp += goodsAndStamp[1];
			if (goodsAndStamp[0] > 0) {
				double factor = goodsPaid / goodsAndStamp[0];
				for (SalesLine line : ticketLines) {
					if (!ReturnHeaderService.isTaxStamp(line.getItem())) {
						ttcByRate.merge(rate(line), ttc(line) * factor, Double::sum);
					}
				}
			} else if (goodsPaid != 0.0) {
				// a ticket without goods lines but with an amount (old data): kept at 0 % so that the totals still add up
				ttcByRate.merge(0, goodsPaid, Double::sum);
			}
			for (SalesLine line : ticketLines) {
				if (!ReturnHeaderService.isTaxStamp(line.getItem())) {
					lineDiscounts += lineDiscount(line);
				}
			}
			ticketDiscounts += sale.getDiscountAmount() != null ? sale.getDiscountAmount() : 0.0;
			loyalty += sale.getLoyaltyDeductionAmount() != null ? sale.getLoyaltyDeductionAmount() : 0.0;
		}

		SessionReportTicketDTO.Sales block = new SessionReportTicketDTO.Sales();
		block.setTicketsCount(sales.size());
		BigDecimal totalTtc = bd(total);
		BigDecimal stampTtc = bd(stamp);
		List<VatLine> vatLines = vatLines(ttcByRate, totalTtc.subtract(stampTtc));
		block.setVatLines(vatLines);
		block.setTotalHT(sum(vatLines, VatLine::getBaseHT));
		block.setTotalVat(sum(vatLines, VatLine::getVatAmount));
		block.setTaxStamp(stampTtc.doubleValue());
		block.setTotalTTC(totalTtc.doubleValue());
		block.setLineDiscounts(millimes(lineDiscounts));
		block.setTicketDiscounts(millimes(ticketDiscounts));
		block.setLoyaltyDiscounts(millimes(loyalty));
		block.setCancelledCount(allSales.stream().filter(s -> s.getStatus() == TransactionStatus.CANCELLED).count());
		block.setPendingCount(
				open ? allSales.stream().filter(s -> s.getStatus() == TransactionStatus.PENDING).count() : null);
		return block;
	}

	/**
	 * The VAT lines, their TTC rounded to the millime and adding up to goodsTtc exactly (the rounding remainder goes to
	 * the largest rate); HT = TTC / (1 + rate) to the millime, VAT = TTC - HT.
	 */
	static List<VatLine> vatLines(Map<Integer, Double> ttcByRate, BigDecimal goodsTtc) {
		Map<Integer, BigDecimal> rounded = new TreeMap<>();
		ttcByRate.forEach((rate, ttc) -> rounded.put(rate, bd(ttc)));
		BigDecimal remainder = goodsTtc.subtract(rounded.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
		if (remainder.signum() != 0) {
			Integer largest = rounded.entrySet().stream().max(Comparator.comparing(e -> e.getValue().abs()))
					.map(Map.Entry::getKey).orElse(0);
			rounded.merge(largest, remainder, BigDecimal::add);
		}
		List<VatLine> result = new ArrayList<>();
		rounded.forEach((rate, ttc) -> {
			BigDecimal ht = ttc.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(100L + rate), 3,
					RoundingMode.HALF_UP);
			result.add(new VatLine(rate, ht.doubleValue(), ttc.subtract(ht).doubleValue(), ttc.doubleValue()));
		});
		return result;
	}

	/**
	 * A line's discount, TTC, for the whole line (as the till sends it, and as applyDecimalLineAmounts stores it). The
	 * free-item line the server adds (FREE_QUANTITY promotion: total 0, 100 %) stores unitPrice x quantity, an HT
	 * amount: its TTC value is taken instead.
	 */
	static double lineDiscount(SalesLine line) {
		boolean free = line.getLineTotalIncludingVat() != null && line.getLineTotalIncludingVat() == 0.0
				&& line.getDiscountPercentage() != null && line.getDiscountPercentage() == 100.0
				&& line.getUnitPriceIncludingVat() != null && line.getQuantity() != null;
		if (free) {
			return line.getUnitPriceIncludingVat() * line.getQuantity().doubleValue();
		}
		return line.getDiscountAmount() != null ? line.getDiscountAmount() : 0.0;
	}

	private static int rate(SalesLine line) {
		return line.getVatPercent() != null ? line.getVatPercent() : 0;
	}

	private static double ttc(SalesLine line) {
		return line.getLineTotalIncludingVat() != null ? line.getLineTotalIncludingVat() : 0.0;
	}

	// ─── RETOURS ─────────────────────────────────────────────────────

	static SessionReportTicketDTO.Returns returns(List<ReturnHeader> returns) {
		SessionReportTicketDTO.Returns block = new SessionReportTicketDTO.Returns();
		double simple = 0.0;
		double voucher = 0.0;
		for (ReturnHeader ret : returns) {
			double amount = ret.getTotalReturnAmount() != null ? ret.getTotalReturnAmount() : 0.0;
			if (ret.getReturnType() == ReturnType.SIMPLE_RETURN) {
				block.setSimpleCount(block.getSimpleCount() + 1);
				simple += amount;
			} else if (ret.getReturnType() == ReturnType.RETURN_VOUCHER) {
				block.setVoucherCount(block.getVoucherCount() + 1);
				voucher += amount;
			}
		}
		block.setCount(returns.size());
		block.setSimpleAmount(millimes(simple));
		block.setVoucherAmount(millimes(voucher));
		block.setTotalAmount(millimes(simple + voucher));
		return block;
	}

	// ─── PAIEMENTS ───────────────────────────────────────────────────

	/** Fills the payment lines; returns the amount per method key (cash net of the change), not rounded. */
	private Map<String, Double> paymentsByMethod(List<Payment> payments, Methods methods,
			SessionReportTicketDTO dto) {
		Map<String, Double> amounts = new HashMap<>();
		Map<String, Set<Long>> tickets = new HashMap<>();
		Map<Long, SalesHeader> withCash = new HashMap<>();
		for (Payment payment : payments) {
			PaymentMethod method = payment.getPaymentMethod();
			if (method == null) {
				continue;
			}
			String key = methods.add(method);
			amounts.merge(key, payment.getTotalAmount() != null ? payment.getTotalAmount() : 0.0, Double::sum);
			tickets.computeIfAbsent(key, k -> new HashSet<>()).add(payment.getSalesHeader().getId());
			if (method.getType() == PaymentMethodType.CLIENT_ESPECES) {
				withCash.put(payment.getSalesHeader().getId(), payment.getSalesHeader());
			}
		}
		// the change is given back in cash, once per ticket that had a cash payment (as calculateRealCash)
		double change = withCash.values().stream()
				.mapToDouble(s -> s.getChangeAmount() != null && s.getChangeAmount() > 0 ? s.getChangeAmount() : 0.0)
				.sum();
		if (change > 0) {
			amounts.merge(methods.cashKey(), -change, Double::sum);
		}
		for (String key : methods.ordered(amounts.keySet())) {
			dto.getPayments().add(new PaymentLine(key, methods.name(key), methods.isCash(key),
					tickets.getOrDefault(key, Collections.emptySet()).size(), millimes(amounts.get(key))));
		}
		return amounts;
	}

	// ─── CONTRÔLE ────────────────────────────────────────────────────

	/** The count rows of one counter per method key; the rows without method (the float, the cash) are cash. */
	private static Map<String, Double> countsByMethod(List<SessionCashCount> counts, CounterType counter,
			Methods methods) {
		Map<String, Double> result = new HashMap<>();
		for (SessionCashCount count : counts) {
			if (count.getCounterType() != counter) {
				continue;
			}
			String key = count.getPaymentMethod() != null ? methods.add(count.getPaymentMethod()) : methods.cashKey();
			result.merge(key, count.getLineTotal() != null ? count.getLineTotal() : 0.0, Double::sum);
		}
		return result;
	}

	private static ControlLine controlLine(String key, Methods methods, double expected, Double counted) {
		Double expectedRounded = millimes(expected);
		Double countedRounded = counted != null ? millimes(counted) : null;
		Double difference = countedRounded != null ? millimes(countedRounded - expectedRounded) : null;
		return new ControlLine(key, methods.name(key), methods.isCash(key), expectedRounded, countedRounded,
				difference);
	}

	/**
	 * The responsible's verification: per method when RESPONSIBLE rows exist, else the session total
	 * (responsibleClosureCash, the sum of the verification form over every method) against every method's expected
	 * amount added up, as the session history page compares them.
	 */
	private Verification verification(CashierSession session, List<String> keys, Methods methods,
			Map<String, Double> expected, Map<String, Double> respCounted) {
		Verification verification = new Verification();
		verification.setVerifiedBy(displayName(session.getVerifiedBy()));
		verification.setVerifiedAt(session.getVerifiedAt());
		if (!respCounted.isEmpty()) {
			verification.setPerMethod(true);
			for (String key : keys) {
				verification.getLines().add(controlLine(key, methods, expected.getOrDefault(key, 0.0),
						respCounted.getOrDefault(key, 0.0)));
			}
		} else {
			double totalExpected = keys.stream().mapToDouble(k -> millimes(expected.getOrDefault(k, 0.0))).sum();
			verification.setTotalExpected(millimes(totalExpected));
			Double counted = session.getResponsibleClosureCash();
			verification.setTotalCounted(counted != null ? millimes(counted) : null);
			verification.setTotalDifference(counted != null ? millimes(millimes(counted) - millimes(totalExpected)) : null);
		}
		return verification;
	}

	// ─── Helpers ─────────────────────────────────────────────────────

	private String printedBy() {
		try {
			return displayName(currentUserProvider.getCurrentUser());
		} catch (RuntimeException e) {
			return currentUserProvider.getCurrentUserName();
		}
	}

	static String displayName(UserAccount user) {
		if (user == null) {
			return null;
		}
		return user.getFullName() != null && !user.getFullName().trim().isEmpty() ? user.getFullName()
				: user.getUsername();
	}

	private static BigDecimal bd(double value) {
		return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
	}

	static Double millimes(Double value) {
		return value == null ? null : bd(value).doubleValue();
	}

	private static Double sum(List<VatLine> lines, java.util.function.Function<VatLine, Double> field) {
		return lines.stream().map(l -> BigDecimal.valueOf(field.apply(l))).reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(3, RoundingMode.HALF_UP).doubleValue();
	}

	/** The payment methods met on the ticket, keyed by code, in the till's order (displayOrder, then code). */
	static final class Methods {
		private final Map<String, PaymentMethod> byKey = new LinkedHashMap<>();
		private final String cashKey;

		Methods(PaymentMethod cashMethod) {
			this.cashKey = cashMethod != null ? cashMethod.getCode() : CASH_KEY;
			if (cashMethod != null) {
				byKey.put(cashKey, cashMethod);
			}
		}

		String add(PaymentMethod method) {
			String key = method.getType() == PaymentMethodType.CLIENT_ESPECES ? cashKey : method.getCode();
			byKey.putIfAbsent(key, method);
			return key;
		}

		String cashKey() {
			return cashKey;
		}

		boolean isCash(String key) {
			return cashKey.equals(key);
		}

		String name(String key) {
			PaymentMethod method = byKey.get(key);
			return method != null && method.getName() != null ? method.getName() : key;
		}

		@SafeVarargs
		final List<String> ordered(java.util.Collection<String>... keySets) {
			Set<String> keys = new HashSet<>();
			for (java.util.Collection<String> set : keySets) {
				keys.addAll(set);
			}
			return keys.stream().sorted(Comparator.comparing((String k) -> order(k)).thenComparing(k -> k))
					.collect(Collectors.toList());
		}

		private int order(String key) {
			PaymentMethod method = byKey.get(key);
			if (method == null || method.getDisplayOrder() == null) {
				return isCash(key) ? 0 : Integer.MAX_VALUE;
			}
			return method.getDisplayOrder();
		}
	}
}
