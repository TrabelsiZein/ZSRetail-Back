package com.digithink.zsretail.repository;

import java.util.List;

import com.digithink.zsretail.model.PaymentChangeLog;
import com.digithink.zsretail.model.SalesHeader;

public interface PaymentChangeLogRepository extends _BaseRepository<PaymentChangeLog, Long> {

	List<PaymentChangeLog> findBySalesHeaderOrderByCreatedAtDesc(SalesHeader salesHeader);
}
