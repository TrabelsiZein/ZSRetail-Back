package com.digithink.zsretail.core.model.enumeration;

/**
 * The fixed payment methods the code knows, one row each at most (index ux_payment_method_type). 3.2.0: a method the
 * admin creates has none; TICKET_RESTAURANT and CHEQUE_CADEAU (no logic behind them) are no longer types, their rows
 * are converted by PaymentMethodsUpgrade and db/3.2.0/update.sql.
 */
public enum PaymentMethodType {
	CLIENT_ESPECES, CLIENT_CHEQUE, CLIENT_TPE, CLIENT_TRAITE, DEPOT_BANQUE, RETURN_VOUCHER, VIREMENT_BANCAIRE,
	RETENUE_SOURCE
}
