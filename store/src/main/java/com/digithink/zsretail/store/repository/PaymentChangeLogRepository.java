package com.digithink.zsretail.store.repository;

import java.time.LocalDateTime;
import java.util.List;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.model.PaymentChangeLog;

public interface PaymentChangeLogRepository extends _BaseRepository<PaymentChangeLog, Long> {

	List<PaymentChangeLog> findBySalesHeaderOrderByCreatedAtDesc(SalesHeader salesHeader);

	/** 3.1.0: the payment method changes made on a session's tickets after the given time (the ticket Z: after closing). */
	long countBySalesHeaderCashierSessionAndCreatedAtAfter(CashierSession cashierSession, LocalDateTime after);
}
