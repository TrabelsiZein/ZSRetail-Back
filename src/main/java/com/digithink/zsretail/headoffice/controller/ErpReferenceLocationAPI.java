package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeErp;
import com.digithink.zsretail.headoffice.service.ErpReferenceLocationService;

/**
 * Head office plan, task 3.4: the ERP reference location of a head office with an ERP (JWT, like the other admin
 * APIs). On a store, or a head office without ERP, these URLs answer 404. Contract: docs/modules/head-office.md,
 * "Head office with an ERP".
 */
@RestController
@RequestMapping("admin/headoffice/erp/reference-location")
@ConditionalOnHeadOfficeErp
public class ErpReferenceLocationAPI {

	private final ErpReferenceLocationService service;

	public ErpReferenceLocationAPI(ErpReferenceLocationService service) {
		this.service = service;
	}

	@GetMapping
	public Map<String, Object> get() {
		return service.view();
	}

	/** Body {"locationCode": "MAG01"}; 400 {"error"} for a blank or unknown code. */
	@PutMapping
	public ResponseEntity<?> choose(@RequestBody(required = false) Map<String, Object> body) {
		Object code = body == null ? null : body.get("locationCode");
		try {
			return ResponseEntity.ok(service.choose(code == null ? null : code.toString()));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
		}
	}
}
