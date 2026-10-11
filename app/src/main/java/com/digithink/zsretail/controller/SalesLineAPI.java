package com.digithink.zsretail.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.service.SalesLineService;

@RestController
@RequestMapping("sales-line")
public class SalesLineAPI extends _BaseController<SalesLine, Long, SalesLineService> {

}

