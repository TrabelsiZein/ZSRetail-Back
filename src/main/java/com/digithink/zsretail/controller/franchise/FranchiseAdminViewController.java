package com.digithink.zsretail.controller.franchise;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.model.FranchiseSalesHeader;
import com.digithink.zsretail.repository.FranchiseSalesHeaderRepository;

import lombok.RequiredArgsConstructor;

/**
 * Admin UI facing endpoints for viewing franchise data (sales tracking, etc.).
 * Secured by normal JWT authentication — NOT by the API key filter.
 * Path is /admin/franchise/** so it is outside /franchise/** and bypasses FranchiseApiKeyFilter.
 * Only active when franchise.admin=true.
 */
@RestController
@RequestMapping("admin/franchise")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "franchise.admin", havingValue = "true")
public class FranchiseAdminViewController {

	private final FranchiseSalesHeaderRepository franchiseSalesHeaderRepository;

	/**
	 * Lists received franchise sales, optionally filtered by location code.
	 * Used by the admin's Sales Tracking dashboard (JWT auth required).
	 */
	@GetMapping("/sales")
	public ResponseEntity<List<FranchiseSalesHeader>> getSales(
			@RequestParam(required = false) String locationCode) {
		List<FranchiseSalesHeader> results = (locationCode != null && !locationCode.isBlank())
				? franchiseSalesHeaderRepository.findByLocationCode(locationCode)
				: franchiseSalesHeaderRepository.findAllByOrderByCreatedAtDesc();
		return ResponseEntity.ok(results);
	}
}
