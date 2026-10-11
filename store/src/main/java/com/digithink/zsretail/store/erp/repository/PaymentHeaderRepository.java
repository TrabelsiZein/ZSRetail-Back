package com.digithink.zsretail.store.erp.repository;

import java.util.List;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.erp.model.PaymentHeader;

public interface PaymentHeaderRepository extends _BaseRepository<PaymentHeader, Long> {

	List<PaymentHeader> findByCashierSession(CashierSession cashierSession);
}

