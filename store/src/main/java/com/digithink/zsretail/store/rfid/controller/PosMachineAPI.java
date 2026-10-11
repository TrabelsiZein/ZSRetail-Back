package com.digithink.zsretail.store.rfid.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.model.PosMachine;
import com.digithink.zsretail.store.rfid.service.PosMachineService;

/**
 * RFID step 7: the POS machines page. The generic reads of {@link _BaseController} (GET /pos-machine, /{id},
 * /findByField, /{id}/exists, /count); create, update and delete answer a broken rule with its message
 * ({@link RfidErrors}: 400, 404) instead of the generic 500. The address sent is kept normalised; the reader is sent
 * as {"reader": {"id": n}} (or null). Exists only with rfid.enabled=true. docs/modules/rfid.md.
 */
@RestController
@RequestMapping("pos-machine")
@ConditionalOnInstallation(Installation.RFID)
public class PosMachineAPI extends _BaseController<PosMachine, Long, PosMachineService> {

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

	@ExceptionHandler(RfidException.class)
	public ResponseEntity<Map<String, String>> refused(RfidException e) {
		return RfidErrors.answer(e);
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
