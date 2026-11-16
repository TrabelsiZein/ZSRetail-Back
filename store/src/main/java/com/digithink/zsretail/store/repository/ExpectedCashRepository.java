package com.digithink.zsretail.store.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;

/**
 * 3.2.0: the sums of the cash expected in a drawer, grouped by session, in SQL (CashierSessionService.expectedCash:
 * the closing, the ticket X, the session details, "Caisse en direct"). The rules of before, read in one query each
 * instead of one per ticket: a ticket that counts is neither PENDING nor CANCELLED; its cash payments (method of type
 * CLIENT_ESPECES, a payment without method left out); its change only when it has a cash payment and the change is
 * above 0; the simple returns (cash refunds). Rows {session id, sum}; a session without any is absent.
 */
public interface ExpectedCashRepository extends Repository<SalesHeader, Long> {

	@Query("SELECT s.cashierSession.id, SUM(p.totalAmount) FROM Payment p JOIN p.salesHeader s JOIN p.paymentMethod m"
			+ " WHERE s.cashierSession.id IN :ids AND s.status NOT IN :notCounted AND m.type = :cash"
			+ " GROUP BY s.cashierSession.id")
	List<Object[]> cashPaid(@Param("ids") Collection<Long> ids,
			@Param("notCounted") Collection<TransactionStatus> notCounted, @Param("cash") PaymentMethodType cash);

	@Query("SELECT s.cashierSession.id, SUM(s.changeAmount) FROM SalesHeader s"
			+ " WHERE s.cashierSession.id IN :ids AND s.status NOT IN :notCounted AND s.changeAmount > 0"
			+ " AND EXISTS (SELECT p.id FROM Payment p JOIN p.paymentMethod m WHERE p.salesHeader = s AND m.type = :cash)"
			+ " GROUP BY s.cashierSession.id")
	List<Object[]> changeGiven(@Param("ids") Collection<Long> ids,
			@Param("notCounted") Collection<TransactionStatus> notCounted, @Param("cash") PaymentMethodType cash);

	@Query("SELECT r.cashierSession.id, SUM(r.totalReturnAmount) FROM ReturnHeader r"
			+ " WHERE r.cashierSession.id IN :ids AND r.returnType = :simple GROUP BY r.cashierSession.id")
	List<Object[]> simpleReturns(@Param("ids") Collection<Long> ids, @Param("simple") ReturnType simple);
}
