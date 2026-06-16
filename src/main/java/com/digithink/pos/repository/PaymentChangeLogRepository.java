package com.digithink.pos.repository;

import java.util.List;

import com.digithink.pos.model.PaymentChangeLog;
import com.digithink.pos.model.SalesHeader;

public interface PaymentChangeLogRepository extends _BaseRepository<PaymentChangeLog, Long> {

	List<PaymentChangeLog> findBySalesHeaderOrderByCreatedAtDesc(SalesHeader salesHeader);
}
