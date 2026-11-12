package com.digithink.zsretail.core.service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.BarcodeLayout;
import com.digithink.zsretail.core.model.PaymentMethod;

/**
 * 3.2.0, step 3: the common issuers of meal and gift tickets in Tunisia, the one place they are written. The third digit
 * of the code: 1 = repas (meal, 10 % taken off by default), 2 = cadeau (gift, 0 %). Used by the seed of a new store
 * (ZZDataInitializer: two inactive methods "Ticket Restaurant" and "Chèque Cadeau") and by the admin's action "add the
 * common issuers" (AcceptedTicketService), which add the lines inactive with the default layout and a validity of 1 month
 * after the printed year.
 */
public final class CommonTicketIssuers {

	public enum Kind {
		MEAL, GIFT
	}

	public static final class Issuer {
		private final String code;
		private final String name;
		private final Kind kind;
		private final BigDecimal deductionPercent;

		Issuer(String code, String name, Kind kind, int deductionPercent) {
			this.code = code;
			this.name = name;
			this.kind = kind;
			this.deductionPercent = BigDecimal.valueOf(deductionPercent).setScale(2);
		}

		public String getCode() {
			return code;
		}

		public String getName() {
			return name;
		}

		public Kind getKind() {
			return kind;
		}

		public BigDecimal getDeductionPercent() {
			return deductionPercent;
		}
	}

	static final List<Issuer> ALL = Collections.unmodifiableList(Arrays.asList(
			new Issuer("011", "Bonus Repas", Kind.MEAL, 10), new Issuer("012", "Bonus Cadeau", Kind.GIFT, 0),
			new Issuer("021", "Joker Repas", Kind.MEAL, 10), new Issuer("022", "Joker Cadeau", Kind.GIFT, 0),
			new Issuer("031", "Servimax Repas", Kind.MEAL, 10), new Issuer("032", "Servimax Cadeau", Kind.GIFT, 0),
			new Issuer("041", "Sodexo Repas", Kind.MEAL, 10), new Issuer("042", "Sodexo Cadeau", Kind.GIFT, 0),
			new Issuer("051", "Top Chek Repas", Kind.MEAL, 10), new Issuer("052", "Top Chek Cadeau", Kind.GIFT, 0),
			new Issuer("081", "Sud Repas", Kind.MEAL, 10), new Issuer("082", "Sud Cadeau", Kind.GIFT, 0)));

	private CommonTicketIssuers() {
	}

	public static List<Issuer> of(Kind kind) {
		return ALL.stream().filter(issuer -> issuer.getKind() == kind).collect(Collectors.toList());
	}

	/** The line of an issuer under a method: inactive, the default layout, valid 1 month after the printed year. */
	public static AcceptedTicket line(Issuer issuer, PaymentMethod method) {
		AcceptedTicket line = new AcceptedTicket();
		line.setPaymentMethod(method);
		line.setIssuerName(issuer.getName());
		line.setTicketCode(issuer.getCode());
		line.setDeductionPercent(issuer.getDeductionPercent());
		line.setValidityMonths(1);
		line.setLayout(BarcodeLayout.standard());
		line.setActive(false);
		line.setCreatedBy("System");
		line.setUpdatedBy("System");
		return line;
	}
}
