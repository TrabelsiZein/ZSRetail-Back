package com.digithink.zsretail.erp.repository;

import java.util.List;

import com.digithink.zsretail.erp.model.PaymentHeader;
import com.digithink.zsretail.erp.model.PaymentLine;
import com.digithink.zsretail.repository._BaseRepository;

public interface PaymentLineRepository extends _BaseRepository<PaymentLine, Long> {

	List<PaymentLine> findByPaymentHeader(PaymentHeader paymentHeader);

	List<PaymentLine> findByPaymentHeaderAndSynched(PaymentHeader paymentHeader, Boolean synched);
}

