package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.store.dto.ProcessSaleRequestDTO;

/**
 * 3.2.0: the N° titre's exact length is the method's (PaymentMethod.titleNumberLength), no longer the setting
 * PAYMENT_METHOD_&lt;type&gt;_TITLE_NUMBER_LENGTH; a method without type (Ticket Restaurant, Chèque Cadeau after the
 * conversion, or one the admin creates) keeps its length. Calls the check of a sale and of a payment change directly.
 */
class SalesTitleNumberLengthTest {

	private final SalesHeaderService sales = new SalesHeaderService();

	private static PaymentMethod method(PaymentMethodType type, Integer length) {
		PaymentMethod method = new PaymentMethod();
		method.setName("Méthode");
		method.setType(type);
		method.setRequireTitleNumber(true);
		method.setTitleNumberLength(length);
		return method;
	}

	private static ProcessSaleRequestDTO.PaymentDTO title(String titleNumber) {
		ProcessSaleRequestDTO.PaymentDTO payment = new ProcessSaleRequestDTO.PaymentDTO();
		payment.setAmount(10.0);
		payment.setTitleNumber(titleNumber);
		return payment;
	}

	@Test
	@DisplayName("A method without type and a length of 7: 7 characters pass, 6 are refused with the length")
	void lengthOfAMethodWithoutType() {
		PaymentMethod converted = method(null, 7);
		assertDoesNotThrow(() -> sales.validateAdditionalPaymentFields(converted, title("1234567")));
		assertDoesNotThrow(() -> sales.validateAdditionalPaymentFields(converted, title(" 1234567 ")), "trimmed");
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> sales.validateAdditionalPaymentFields(converted, title("123456")));
		assertEquals("Title number must be exactly 7 characters for payment method: Méthode. Provided: 6 characters.",
				refused.getMessage());
	}

	@Test
	@DisplayName("A fixed type (cheque) takes its row's length too")
	void lengthOfAFixedType() {
		PaymentMethod cheque = method(PaymentMethodType.CLIENT_CHEQUE, 8);
		assertDoesNotThrow(() -> sales.validateAdditionalPaymentFields(cheque, title("12345678")));
		assertThrows(IllegalArgumentException.class, () -> sales.validateAdditionalPaymentFields(cheque, title("1234567")));
	}

	@Test
	@DisplayName("No length (null or 0): any length; the N° titre is still required")
	void noLength() {
		assertDoesNotThrow(() -> sales.validateAdditionalPaymentFields(method(null, null), title("1")));
		assertDoesNotThrow(() -> sales.validateAdditionalPaymentFields(method(null, 0), title("123")));
		assertThrows(IllegalArgumentException.class,
				() -> sales.validateAdditionalPaymentFields(method(null, null), title(" ")));
	}

	@Test
	@DisplayName("A length without the N° titre required checks nothing")
	void lengthWithoutRequiredTitle() {
		PaymentMethod method = method(null, 7);
		method.setRequireTitleNumber(false);
		assertDoesNotThrow(() -> sales.validateAdditionalPaymentFields(method, title("12")));
	}
}
