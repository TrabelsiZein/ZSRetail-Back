package com.digithink.zsretail.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.repository.PaymentRepository;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.service._BaseService;

@Service
public class PaymentService extends _BaseService<Payment, Long> {

	@Autowired
	private PaymentRepository paymentRepository;

	@Override
	protected _BaseRepository<Payment, Long> getRepository() {
		return paymentRepository;
	}
}

