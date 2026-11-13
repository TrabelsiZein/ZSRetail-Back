package com.digithink.zsretail.store.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodBehaviour;
import com.digithink.zsretail.store.model.ScannedTicketPayment;

/**
 * 3.2.0, step 6: a session's scanned tickets added up per method (by its code) and, inside, per issuer, from the copies
 * taken at the sale (ScannedTicketPayment): the number of tickets, the face total (what the cashier reads on the papers
 * and declares at closing), the deduction (face - counted), the excess kept (counted - applied) and the net (the parts
 * applied, what entered the sales and the payments). face = deduction + excess + net, to the millime.
 */
public final class ScannedTicketsSummary {

	private ScannedTicketsSummary() {
	}

	/** True for a method whose tickets are scanned (its closing row is a number of tickets and a face total). */
	public static boolean isScanned(PaymentMethod method) {
		return method != null && method.getBehaviour() == PaymentMethodBehaviour.SCANNED_TICKETS;
	}

	/** One method's tickets. */
	public static final class Method {
		private final PaymentMethod method;
		private long count;
		private BigDecimal face = zero();
		private BigDecimal counted = zero();
		private BigDecimal applied = zero();
		private BigDecimal excess = zero();
		private final Map<String, Issuer> issuers = new LinkedHashMap<>();

		Method(PaymentMethod method) {
			this.method = method;
		}

		public PaymentMethod getMethod() {
			return method;
		}

		public long getCount() {
			return count;
		}

		public BigDecimal getFace() {
			return face;
		}

		/** Face - counted: what the issuers' deduction took. */
		public BigDecimal getDeduction() {
			return face.subtract(counted);
		}

		public BigDecimal getExcess() {
			return excess;
		}

		/** The parts applied: the payments' amounts. */
		public BigDecimal getNet() {
			return applied;
		}

		/** Per issuer, in the order first met (the sales' order). */
		public Map<String, Issuer> getIssuers() {
			return issuers;
		}
	}

	/** One issuer's tickets inside a method. */
	public static final class Issuer {
		private final String name;
		private long count;
		private BigDecimal face = zero();

		Issuer(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}

		public long getCount() {
			return count;
		}

		public BigDecimal getFace() {
			return face;
		}
	}

	/** The tickets per method code, in the order first met; the issuers likewise. */
	public static Map<String, Method> byMethodCode(List<ScannedTicketPayment> tickets) {
		Map<String, Method> result = new LinkedHashMap<>();
		for (ScannedTicketPayment ticket : tickets) {
			PaymentMethod paymentMethod = ticket.getPayment().getPaymentMethod();
			Method method = result.computeIfAbsent(paymentMethod.getCode(), k -> new Method(paymentMethod));
			method.count++;
			method.face = method.face.add(millimes(ticket.getFaceValue()));
			method.counted = method.counted.add(millimes(ticket.getCountedValue()));
			method.applied = method.applied.add(millimes(ticket.getAppliedAmount()));
			method.excess = method.excess.add(millimes(ticket.getExcessAmount()));
			String issuerName = ticket.getIssuerName() != null ? ticket.getIssuerName() : "?";
			Issuer issuer = method.issuers.computeIfAbsent(issuerName, Issuer::new);
			issuer.count++;
			issuer.face = issuer.face.add(millimes(ticket.getFaceValue()));
		}
		return result;
	}

	private static BigDecimal millimes(BigDecimal value) {
		return value == null ? zero() : value.setScale(3, RoundingMode.HALF_UP);
	}

	private static BigDecimal zero() {
		return BigDecimal.ZERO.setScale(3);
	}
}
