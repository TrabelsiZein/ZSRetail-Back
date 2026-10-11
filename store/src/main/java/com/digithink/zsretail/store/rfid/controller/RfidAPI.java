package com.digithink.zsretail.store.rfid.controller;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.dto.RfidMachineStatus;
import com.digithink.zsretail.store.rfid.dto.RfidReadResult;
import com.digithink.zsretail.store.rfid.service.RfidReadService;

/**
 * RFID: the endpoints of the machine a request comes from, known by the request's address (request.getRemoteAddr():
 * the frontend calls the back end on the same Tomcat, nothing in between; docs/modules/rfid.md). Exists only with
 * rfid.enabled=true (otherwise 404). Logged users only, like every endpoint not in SecurityConfig's permitAll. Refusals:
 * {@link RfidErrors}.
 */
@RestController
@RequestMapping("rfid")
@ConditionalOnInstallation(Installation.RFID)
public class RfidAPI {

	private final RfidReadService reads;

	public RfidAPI(RfidReadService reads) {
		this.reads = reads;
	}

	/**
	 * GET /rfid/my-machine (RFID step 6): the address I see for you, the POS machine recorded at that address and its
	 * reader, whether it can read now and, when not, why. Never calls the reader.
	 */
	@GetMapping("/my-machine")
	public RfidMachineStatus myMachine(HttpServletRequest request) {
		return reads.machineStatus(request.getRemoteAddr());
	}

	/**
	 * POST /rfid/read (RFID step 7): a read with the reader of my POS machine, for its reading time. The result names the
	 * POS machine and the reader. Refused with the address seen when no machine has it (404), 409 when the machine or its
	 * reader cannot read or the reader is busy, 503 / 502 when the reader does not answer / answers with an error.
	 */
	@PostMapping("/read")
	public RfidReadResult read(HttpServletRequest request) {
		return reads.readForRequest(request.getRemoteAddr());
	}

	@ExceptionHandler(RfidException.class)
	public ResponseEntity<Map<String, String>> refused(RfidException e) {
		return RfidErrors.answer(e);
	}
}
