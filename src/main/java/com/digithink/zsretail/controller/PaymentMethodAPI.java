package com.digithink.zsretail.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.model.PaymentMethod;
import com.digithink.zsretail.service.PaymentMethodService;

@RestController
@RequestMapping("payment-method")
public class PaymentMethodAPI extends _BaseController<PaymentMethod, Long, PaymentMethodService> {

}

