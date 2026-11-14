package com.digithink.zsretail.store.cashmovement.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.cashmovement.CashMovementException;
import com.digithink.zsretail.store.cashmovement.dto.CashMovementRequest;
import com.digithink.zsretail.store.cashmovement.service.CashMovementService;
import com.digithink.zsretail.store.exception.TillRightRequiredException;

import lombok.extern.log4j.Log4j2;

/**
 * 3.2.0, POS machines step 5: POST /cash-movement, the till's cash movement (docs/modules/cash-sessions.md, "Cash
 * movements"): 200 and its slip (CashMovementDTO); 400 {error, reason, code, params} for a request not valid or no own
 * open session; 403 TILL_RIGHT_REQUIRED without the right or a valid grant. No other endpoint: a movement is never
 * edited or deleted (not a _BaseController). Logged users only. With the drawer of the POS machine,
 * PosMachineSellingGuard refuses it from another PC (409 OTHER_POS_MACHINE), as a sale.
 */
@RestController
@RequestMapping("cash-movement")
@Log4j2
public class CashMovementAPI {

	@Autowired
	private CashMovementService service;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@PostMapping
	public ResponseEntity<?> record(@RequestBody CashMovementRequest request) {
		try {
			return ResponseEntity.ok(service.record(currentUserProvider.getCurrentUser(), request));
		} catch (TillRightRequiredException e) {
			log.warn("CashMovementAPI::record: till right required: {}", e.getMessage());
			return e.response();
		} catch (CashMovementException e) {
			log.info("CashMovementAPI::record refused: {}", e.getMessage());
			return ResponseEntity.badRequest().body(e.body());
		}
	}
}
