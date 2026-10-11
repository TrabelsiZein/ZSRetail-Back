package com.digithink.zsretail.core.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.model.MemberFunction;
import com.digithink.zsretail.core.service.MemberFunctionService;

@RestController
@RequestMapping("member-function")
public class MemberFunctionAPI extends _BaseController<MemberFunction, Long, MemberFunctionService> {

}
