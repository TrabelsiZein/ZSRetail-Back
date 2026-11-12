package com.digithink.zsretail.core.model.enumeration;

/**
 * 3.2.0: what a payment method asks at the till. AMOUNT: an amount, with the details its switches ask (N° titre and its
 * length, due date, drawer name, bank); every method of before. SCANNED_TICKETS: the tickets scanned one by one (from
 * step 3 of the topic). A row without a value is AMOUNT.
 */
public enum PaymentMethodBehaviour {
	AMOUNT, SCANNED_TICKETS
}
