package com.digithink.zsretail.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.model.MemberFunction;
import com.digithink.zsretail.service.MemberFunctionService;

@RestController
@RequestMapping("member-function")
public class MemberFunctionAPI extends _BaseController<MemberFunction, Long, MemberFunctionService> {

}
