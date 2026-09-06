package com.digithink.zsretail.controller.franchise;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.franchise.FranchiseSupplyReceptionService;
import com.digithink.zsretail.service.franchise.FranchiseSyncService;

import lombok.RequiredArgsConstructor;

/**
 * Franchise client: internal endpoints for the admin UI to trigger manual sync operations.
 * Requires normal JWT authentication (used by the admin panel, not by the admin instance).
 * Only active when franchise.customer=true.
 */
@RestController
@RequestMapping("franchise/client")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "franchise.customer", havingValue = "true")
public class FranchiseClientSyncController {

	private final FranchiseSyncService franchiseSyncService;
	private final FranchiseSupplyReceptionService franchiseSupplyReceptionService;
	private final GeneralSetupService generalSetupService;

	/**
	 * Returns the persisted timestamps for last item sync and last supply reception.
	 * Called by the frontend on dashboard load so dates are always accurate, even after a page refresh.
	 */
	@GetMapping("/sync/status")
	public ResponseEntity<Map<String, Object>> getSyncStatus() {
		String lastItemSync = generalSetupService.findValueByCode("FRANCHISE_LAST_ITEM_SYNC");
		String lastSupplySync = generalSetupService.findValueByCode("FRANCHISE_LAST_SUPPLY_RECEPTION_SYNC");
		Map<String, Object> response = new HashMap<>();
		response.put("lastItemSync", lastItemSync != null && !lastItemSync.isBlank() ? lastItemSync : null);
		response.put("lastSupplySync", lastSupplySync != null && !lastSupplySync.isBlank() ? lastSupplySync : null);
		return ResponseEntity.ok(response);
	}

	/**
	 * Triggers an immediate item sync from the franchise admin.
	 */
	@PostMapping("/sync/items")
	public ResponseEntity<Map<String, Object>> syncItems() {
		FranchiseSyncService.FranchiseSyncResult result = franchiseSyncService.syncItems();

		Map<String, Object> response = new HashMap<>();
		response.put("success", result.success);
		response.put("created", result.created);
		response.put("updated", result.updated);
		if (result.errorMessage != null) {
			response.put("error", result.errorMessage);
		}

		return result.success ? ResponseEntity.ok(response) : ResponseEntity.status(500).body(response);
	}

	/**
	 * Triggers an immediate supply reception from the franchise admin.
	 */
	@PostMapping("/sync/supplies")
	public ResponseEntity<Map<String, Object>> receiveSupplies() {
		FranchiseSupplyReceptionService.ReceptionResult result = franchiseSupplyReceptionService.receiveSupplies();

		Map<String, Object> response = new HashMap<>();
		response.put("success", result.success);
		response.put("receivedCount", result.receivedCount);
		if (result.errorMessage != null) {
			response.put("error", result.errorMessage);
		}
		if (!result.missingItemCodes.isEmpty()) {
			response.put("missingItems", result.missingItemCodes);
		}

		return result.success ? ResponseEntity.ok(response) : ResponseEntity.status(422).body(response);
	}
}
