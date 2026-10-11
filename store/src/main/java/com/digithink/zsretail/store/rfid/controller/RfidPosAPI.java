package com.digithink.zsretail.store.rfid.controller;

import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.dto.RfidPosReadRequest;
import com.digithink.zsretail.store.rfid.dto.RfidPosReadResult;
import com.digithink.zsretail.store.rfid.dto.RfidTicketTag;
import com.digithink.zsretail.store.rfid.service.RfidPosService;

/** RFID POS step: the cart's RFID button and the tags of a ticket (docs/modules/rfid.md, "POS"). */
@RestController
@RequestMapping("rfid")
@ConditionalOnInstallation(Installation.RFID)
public class RfidPosAPI {

	private final RfidPosService pos;

	public RfidPosAPI(RfidPosService pos) {
		this.pos = pos;
	}

	/**
	 * POST /rfid/pos-read: a read with the reader of my POS machine, sorted for the cart (the items to add, one piece per
	 * tag, and the counts). 409 "This POS machine (address) has no reader." when no machine has my address or it has no
	 * reader; the other refusals as POST /rfid/read.
	 */
	@PostMapping("/pos-read")
	public RfidPosReadResult posRead(HttpServletRequest request, @RequestBody(required = false) RfidPosReadRequest body) {
		return pos.read(request.getRemoteAddr(), body);
	}

	/** GET /rfid/ticket-tags?salesHeaderId= or ?ticketNumber=: the tags of a ticket, each with its item and line. */
	@GetMapping("/ticket-tags")
	public List<RfidTicketTag> ticketTags(@RequestParam(required = false) Long salesHeaderId,
			@RequestParam(required = false) String ticketNumber) {
		return pos.ticketTags(salesHeaderId, ticketNumber);
	}

	@ExceptionHandler(RfidException.class)
	public ResponseEntity<Map<String, String>> refused(RfidException e) {
		return RfidErrors.answer(e);
	}
}
