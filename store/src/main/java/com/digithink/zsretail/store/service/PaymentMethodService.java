package com.digithink.zsretail.store.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.service._BaseService;

@Service
public class PaymentMethodService extends _BaseService<PaymentMethod, Long> {

	@Autowired
	private PaymentMethodRepository paymentMethodRepository;

	@Override
	protected _BaseRepository<PaymentMethod, Long> getRepository() {
		return paymentMethodRepository;
	}
}

