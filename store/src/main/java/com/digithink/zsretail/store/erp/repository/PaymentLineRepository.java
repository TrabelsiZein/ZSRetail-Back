package com.digithink.zsretail.store.erp.repository;

import java.util.List;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.erp.model.PaymentHeader;
import com.digithink.zsretail.store.erp.model.PaymentLine;

public interface PaymentLineRepository extends _BaseRepository<PaymentLine, Long> {

	List<PaymentLine> findByPaymentHeader(PaymentHeader paymentHeader);

	List<PaymentLine> findByPaymentHeaderAndSynched(PaymentHeader paymentHeader, Boolean synched);
}

