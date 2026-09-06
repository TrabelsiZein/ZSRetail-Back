package com.digithink.zsretail.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.model.Vendor;
import com.digithink.zsretail.service.VendorService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("vendor")
@Log4j2
public class VendorAPI extends _BaseController<Vendor, Long, VendorService> {

	@Autowired
	private VendorService vendorService;

	@Autowired
	private ApplicationModeService applicationModeService;

	@PostMapping
	public ResponseEntity<?> create(@RequestBody Vendor entity) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Vendor creation is only available in standalone mode. In ERP mode vendors are managed by the ERP."));
		}
		try {
			log.info("VendorAPI::create");
			Vendor created = vendorService.save(entity);
			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("VendorAPI::create:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Vendor entity) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Vendor update is only available in standalone mode."));
		}
		try {
			log.info("VendorAPI::update::" + id);
			return super.update(id, entity);
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("VendorAPI::update:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Vendor deletion is only available in standalone mode."));
		}
		try {
			log.info("VendorAPI::deleteById::" + id);
			vendorService.deleteById(id);
			return ResponseEntity.noContent().build();
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("VendorAPI::deleteById:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	@GetMapping("/admin/paginated")
	public ResponseEntity<?> getVendorsPaginatedForAdmin(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String searchTerm,
			@RequestParam(required = false, defaultValue = "all") String statusFilter) {
		try {
			log.info("VendorAPI::getVendorsPaginatedForAdmin: page={}, size={}, searchTerm={}, statusFilter={}",
					page, size, searchTerm, statusFilter);

			Page<Vendor> vendorPage = vendorService.findVendorsPaginated(page, size, searchTerm, statusFilter);

			Map<String, Object> response = new HashMap<>();
			response.put("content", vendorPage.getContent());
			response.put("totalElements", vendorPage.getTotalElements());
			response.put("totalPages", vendorPage.getTotalPages());
			response.put("number", vendorPage.getNumber());
			response.put("size", vendorPage.getSize());
			response.put("numberOfElements", vendorPage.getNumberOfElements());
			response.put("first", vendorPage.isFirst());
			response.put("last", vendorPage.isLast());

			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("VendorAPI::getVendorsPaginatedForAdmin:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}
}
