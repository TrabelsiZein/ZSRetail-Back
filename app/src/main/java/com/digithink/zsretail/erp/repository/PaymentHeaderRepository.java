package com.digithink.zsretail.erp.repository;

import java.util.List;

import com.digithink.zsretail.erp.model.PaymentHeader;
import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.repository._BaseRepository;

public interface PaymentHeaderRepository extends _BaseRepository<PaymentHeader, Long> {

	List<PaymentHeader> findByCashierSession(CashierSession cashierSession);
}

