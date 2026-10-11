package com.digithink.zsretail.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.model.PaymentMethod;
import com.digithink.zsretail.repository.PaymentMethodRepository;
import com.digithink.zsretail.repository._BaseRepository;

@Service
public class PaymentMethodService extends _BaseService<PaymentMethod, Long> {

	@Autowired
	private PaymentMethodRepository paymentMethodRepository;

	@Override
	protected _BaseRepository<PaymentMethod, Long> getRepository() {
		return paymentMethodRepository;
	}
}

