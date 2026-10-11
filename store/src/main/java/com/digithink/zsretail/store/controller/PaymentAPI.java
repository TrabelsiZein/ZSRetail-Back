package com.digithink.zsretail.store.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.store.service.PaymentService;

@RestController
@RequestMapping("payment")
public class PaymentAPI extends _BaseController<Payment, Long, PaymentService> {

}

