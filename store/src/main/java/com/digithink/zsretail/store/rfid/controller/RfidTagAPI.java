package com.digithink.zsretail.store.rfid.controller;

import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.dto.RfidTagDTO;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;
import com.digithink.zsretail.store.rfid.service.RfidTagService;

import lombok.Data;

/**
 * RFID tags step: the Tags page (/rfid-tag). Not the generic CRUD: a tag is created only by an assignment, moved only
 * by the explicit action, deleted only in stock. Exists only with rfid.enabled=true. Refusals: {@link RfidErrors}.
 * docs/modules/rfid.md.
 */
@RestController
@RequestMapping("rfid-tag")
@ConditionalOnInstallation(Installation.RFID)
public class RfidTagAPI {

	private final RfidTagService tags;

	public RfidTagAPI(RfidTagService tags) {
		this.tags = tags;
	}

	/** GET /rfid-tag?itemId=&status=&epc=&page=&size=: a page of tags, newest first, with each tag's item. */
	@GetMapping
	public Page<RfidTagDTO> list(@RequestParam(required = false) Long itemId,
			@RequestParam(required = false) RfidTagStatus status, @RequestParam(required = false) String epc,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return tags.list(itemId, status, epc, page, size);
	}

	/** PUT /rfid-tag/{id}/item {"itemId": n}: moves an IN_STOCK tag to another item. */
	@PutMapping("/{id}/item")
	public RfidTagDTO move(@PathVariable Long id, @RequestBody MoveRequest request) {
		return tags.move(id, request.getItemId());
	}

	/** DELETE /rfid-tag/{id}: 204; an IN_STOCK tag only (409 for a SOLD tag). */
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		tags.delete(id);
		return ResponseEntity.noContent().build();
	}

	@ExceptionHandler(RfidException.class)
	public ResponseEntity<Map<String, String>> refused(RfidException e) {
		return RfidErrors.answer(e);
	}

	/** The body of a move. */
	@Data
	public static class MoveRequest {
		private Long itemId;
	}
}
