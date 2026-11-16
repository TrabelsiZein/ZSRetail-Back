package com.digithink.zsretail.store.support;

import java.util.LinkedHashMap;
import java.util.Map;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.PaymentRepository;
import com.digithink.zsretail.core.repository.ReturnHeaderRepository;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.store.service.ExpectedCashSums;

/**
 * 3.2.0: ExpectedCashSums over a test's in-memory repositories, by the rules the closing applied ticket by ticket until
 * 3.2.0 (the reference the SQL of ExpectedCashRepository follows): a ticket neither PENDING nor CANCELLED, its payments of
 * a CLIENT_ESPECES method, its change when it has one of them and the change is above 0, the simple returns.
 */
public final class InMemoryExpectedCash {

	private InMemoryExpectedCash() {
	}

	public static ExpectedCashSums over(SalesHeaderRepository sales, PaymentRepository payments,
			ReturnHeaderRepository returns) {
		return sessions -> {
			Map<Long, ExpectedCashSums.Sums> out = new LinkedHashMap<>();
			for (CashierSession session : sessions) {
				ExpectedCashSums.Sums sums = new ExpectedCashSums.Sums();
				for (SalesHeader sale : sales.findByCashierSession(session)) {
					if (sale.getStatus() == TransactionStatus.PENDING || sale.getStatus() == TransactionStatus.CANCELLED) {
						continue;
					}
					boolean cash = false;
					for (Payment payment : payments.findBySalesHeader(sale)) {
						if (payment.getPaymentMethod() != null
								&& payment.getPaymentMethod().getType() == PaymentMethodType.CLIENT_ESPECES) {
							cash = true;
							if (payment.getTotalAmount() != null) {
								sums.cashPaid += payment.getTotalAmount();
							}
						}
					}
					if (cash && sale.getChangeAmount() != null && sale.getChangeAmount() > 0) {
						sums.changeGiven += sale.getChangeAmount();
					}
				}
				if (returns != null) {
					for (ReturnHeader ret : returns.findByCashierSession(session)) {
						if (ret.getReturnType() == ReturnType.SIMPLE_RETURN && ret.getTotalReturnAmount() != null) {
							sums.simpleReturns += ret.getTotalReturnAmount();
						}
					}
				}
				out.put(session.getId(), sums);
			}
			return out;
		};
	}
}
