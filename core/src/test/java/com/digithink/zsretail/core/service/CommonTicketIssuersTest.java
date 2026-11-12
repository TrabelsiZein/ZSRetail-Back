package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.BarcodeLayout;
import com.digithink.zsretail.core.model.PaymentMethod;

/** 3.2.0, step 3: the common issuers, written once: the third digit says the kind; repas 10 %, cadeau 0 %. */
class CommonTicketIssuersTest {

	@Test
	@DisplayName("Six repas (third digit 1, 10 %) and six cadeau (third digit 2, 0 %), with their names")
	void theTwelveIssuers() {
		assertEquals(Arrays.asList("011 Bonus Repas", "021 Joker Repas", "031 Servimax Repas", "041 Sodexo Repas",
				"051 Top Chek Repas", "081 Sud Repas"), CommonTicketIssuers.of(CommonTicketIssuers.Kind.MEAL).stream()
						.map(i -> i.getCode() + " " + i.getName()).collect(Collectors.toList()));
		assertEquals(Arrays.asList("012 Bonus Cadeau", "022 Joker Cadeau", "032 Servimax Cadeau", "042 Sodexo Cadeau",
				"052 Top Chek Cadeau", "082 Sud Cadeau"), CommonTicketIssuers.of(CommonTicketIssuers.Kind.GIFT).stream()
						.map(i -> i.getCode() + " " + i.getName()).collect(Collectors.toList()));
		for (CommonTicketIssuers.Issuer issuer : CommonTicketIssuers.ALL) {
			boolean meal = issuer.getKind() == CommonTicketIssuers.Kind.MEAL;
			assertEquals(meal ? '1' : '2', issuer.getCode().charAt(2), issuer.getCode());
			assertEquals(new BigDecimal(meal ? "10.00" : "0.00"), issuer.getDeductionPercent(), issuer.getCode());
		}
	}

	@Test
	@DisplayName("A line: under its method, inactive, the default layout, valid 1 month after the printed year")
	void line() {
		PaymentMethod method = new PaymentMethod();
		AcceptedTicket line = CommonTicketIssuers.line(CommonTicketIssuers.of(CommonTicketIssuers.Kind.MEAL).get(2), method);
		assertEquals(method, line.getPaymentMethod());
		assertEquals("031", line.getTicketCode());
		assertEquals("Servimax Repas", line.getIssuerName());
		assertFalse(line.getActive());
		assertEquals(BarcodeLayout.standard(), line.getLayout());
		assertEquals(1, line.getValidityMonths());
	}
}
