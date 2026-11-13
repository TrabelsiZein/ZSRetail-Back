package com.digithink.zsretail.store.posmachine.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.store.posmachine.PosMachineException;
import com.digithink.zsretail.store.posmachine.dto.MyPosMachine;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.service.PosMachineAddresses;
import com.digithink.zsretail.store.posmachine.service.PosMachineService;

/**
 * The POS machines page (RFID step 7; every store since 3.2.0, POS machines step 1). The generic reads of
 * {@link _BaseController} (GET /pos-machine, /{id}, /findByField, /{id}/exists, /count); create, update and delete
 * answer a broken rule with {"error", "reason"} (INVALID 400, NOT_FOUND 404) instead of the generic 500. The address
 * sent is kept normalised; the reader is sent as {"reader": {"id": n}} (or null), taken only with rfid.enabled=true.
 * GET /pos-machine/my-machine: the address I see for you and its machine. Logged users only (not in SecurityConfig's
 * permitAll). docs/modules/pos-machines.md.
 */
@RestController
@RequestMapping("pos-machine")
public class PosMachineAPI extends _BaseController<PosMachine, Long, PosMachineService> {

	/**
	 * GET /pos-machine/my-machine: the request's address as the back end sees it (normalised: 127.0.0.1 for the browser
	 * on the PC of the back end) and the POS machine recorded at that address (null when none). Any logged user.
	 */
	@GetMapping("/my-machine")
	public MyPosMachine myMachine(HttpServletRequest request) {
		String address = PosMachineAddresses.normalise(request.getRemoteAddr());
		return service.findByRequestAddress(address)
				.map(machine -> new MyPosMachine(address, machine.getId(), machine.getName(),
						Boolean.TRUE.equals(machine.getActive())))
				.orElseGet(() -> new MyPosMachine(address, null, null, null));
	}

	/** POST /pos-machine: 201 and the machine saved. */
	@Override
	@PostMapping
	public ResponseEntity<?> create(@RequestBody PosMachine machine) {
		machine.setId(null);
		return ResponseEntity.status(HttpStatus.CREATED).body(save(machine));
	}

	/** PUT /pos-machine/{id}: 200 and the machine saved; 404 when there is none. */
	@Override
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody PosMachine machine) {
		service.get(id);
		machine.setId(id);
		return ResponseEntity.ok(save(machine));
	}

	/** DELETE /pos-machine/{id}: 204; 404 when there is none. */
	@Override
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		service.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	/**
	 * {"error": its message, "reason": its reason, "code", "params"}: INVALID 400, NOT_FOUND 404 (as the RFID endpoints
	 * answer). Step 2: the code and the values, which the front shows in the language of the screen.
	 */
	@ExceptionHandler(PosMachineException.class)
	public ResponseEntity<Map<String, Object>> refused(PosMachineException e) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", e.getMessage());
		body.put("reason", e.getReason().name());
		body.put("code", e.getCode());
		body.put("params", e.getParams());
		HttpStatus status = e.getReason() == PosMachineException.Reason.NOT_FOUND ? HttpStatus.NOT_FOUND
				: HttpStatus.BAD_REQUEST;
		return ResponseEntity.status(status).body(body);
	}

	private PosMachine save(PosMachine machine) {
		try {
			return service.save(machine);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
