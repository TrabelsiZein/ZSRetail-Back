package com.digithink.zsretail.core.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;

import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.PaymentMethod;

/** 3.2.0, step 3: the accepted tickets of the scanned-tickets payment methods. */
public interface AcceptedTicketRepository extends _BaseRepository<AcceptedTicket, Long> {

	List<AcceptedTicket> findByPaymentMethodOrderByTicketCode(PaymentMethod paymentMethod);

	long countByPaymentMethod(PaymentMethod paymentMethod);

	/** Per payment method id: its number of lines and of active ones ({id, count, active}). */
	@Query("SELECT t.paymentMethod.id, COUNT(t), SUM(CASE WHEN t.active = true THEN 1 ELSE 0 END)"
			+ " FROM AcceptedTicket t GROUP BY t.paymentMethod.id")
	List<Object[]> countPerPaymentMethod();
}
