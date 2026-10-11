package com.digithink.zsretail.repository;

import java.util.List;

import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.model.PaymentChangeLog;

public interface PaymentChangeLogRepository extends _BaseRepository<PaymentChangeLog, Long> {

	List<PaymentChangeLog> findBySalesHeaderOrderByCreatedAtDesc(SalesHeader salesHeader);
}
