package com.digithink.zsretail.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.model.Payment;
import com.digithink.zsretail.service.PaymentService;

@RestController
@RequestMapping("payment")
public class PaymentAPI extends _BaseController<Payment, Long, PaymentService> {

}

