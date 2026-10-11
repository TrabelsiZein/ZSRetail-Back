package com.digithink.zsretail.store.rfid.controller;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.dto.RfidMachineStatus;
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

	@ExceptionHandler(RfidException.class)
	public ResponseEntity<Map<String, String>> refused(RfidException e) {
		return RfidErrors.answer(e);
	}
}
