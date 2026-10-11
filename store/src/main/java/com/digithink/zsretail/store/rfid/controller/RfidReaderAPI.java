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
import com.digithink.zsretail.store.rfid.dto.RfidConnectionResult;
import com.digithink.zsretail.store.rfid.dto.RfidReadResult;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.rfid.service.RfidReadService;
import com.digithink.zsretail.store.rfid.service.RfidReaderService;

/**
 * RFID step 7: the RFID readers page. The generic reads of {@link _BaseController} (GET /rfid-reader, /{id},
 * /findByField, /{id}/exists, /count); create, update and delete answer a broken rule with its message
 * ({@link RfidErrors}: 400, 404) instead of the generic 500; and the two actions on a reader, Test connection and Test
 * read. Exists only with rfid.enabled=true. docs/modules/rfid.md.
 */
@RestController
@RequestMapping("rfid-reader")
@ConditionalOnInstallation(Installation.RFID)
public class RfidReaderAPI extends _BaseController<RfidReader, Long, RfidReaderService> {

	private final RfidReadService reads;

	public RfidReaderAPI(RfidReadService reads) {
		this.reads = reads;
	}

	/** POST /rfid-reader: 201 and the reader saved (defaults filled in). */
	@Override
	@PostMapping
	public ResponseEntity<?> create(@RequestBody RfidReader reader) {
		reader.setId(null);
		return ResponseEntity.status(HttpStatus.CREATED).body(save(reader));
	}

	/** PUT /rfid-reader/{id}: 200 and the reader saved; 404 when there is none. */
	@Override
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody RfidReader reader) {
		service.get(id);
		reader.setId(id);
		return ResponseEntity.ok(save(reader));
	}

	/** DELETE /rfid-reader/{id}: 204; 404 when there is none; 400 while a POS machine reads with it. */
	@Override
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		service.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	/** POST /rfid-reader/{id}/test-connection: the reader answers, with its information. */
	@PostMapping("/{id}/test-connection")
	public RfidConnectionResult testConnection(@PathVariable Long id) {
		return reads.testConnection(id);
	}

	/** POST /rfid-reader/{id}/test-read: the tags the reader sees now, for its reading time. */
	@PostMapping("/{id}/test-read")
	public RfidReadResult testRead(@PathVariable Long id) {
		return reads.testRead(id);
	}

	@ExceptionHandler(RfidException.class)
	public ResponseEntity<Map<String, String>> refused(RfidException e) {
		return RfidErrors.answer(e);
	}

	private RfidReader save(RfidReader reader) {
		try {
			return service.save(reader);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
