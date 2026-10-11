package com.digithink.zsretail.store.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.store.service.PaymentMethodService;

@RestController
@RequestMapping("payment-method")
public class PaymentMethodAPI extends _BaseController<PaymentMethod, Long, PaymentMethodService> {

}

