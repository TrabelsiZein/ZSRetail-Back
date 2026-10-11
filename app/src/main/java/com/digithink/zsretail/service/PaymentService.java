package com.digithink.zsretail.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.model.Payment;
import com.digithink.zsretail.repository.PaymentRepository;
import com.digithink.zsretail.repository._BaseRepository;

@Service
public class PaymentService extends _BaseService<Payment, Long> {

	@Autowired
	private PaymentRepository paymentRepository;

	@Override
	protected _BaseRepository<Payment, Long> getRepository() {
		return paymentRepository;
	}
}

