package com.digithink.zsretail.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.dto.AppConfigDTO;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.LicenseService;
import com.digithink.zsretail.service.LoyaltyService;

import lombok.RequiredArgsConstructor;

/**
 * Exposes application configuration for the frontend (e.g. ERP vs Standalone mode).
 * Used to control UI visibility (ERP menu, sync columns) without changing backend behaviour.
 */
@RestController
@RequestMapping("config")
@RequiredArgsConstructor
public class AppConfigAPI {

	private final ApplicationModeService applicationModeService;
	private final LicenseService licenseService;
	private final GeneralSetupService generalSetupService;

	@Value("${pos.pricing.enable-sales-price-group:false}")
	private boolean enableSalesPriceGroup;

	@Value("${app.version:unknown}")
	private String appVersion;

	@Autowired
	private LoyaltyService loyaltyService;

	/**
	 * GET /config - returns public app config (standalone, enableSalesPriceGroup, loyaltyEnabled, etc.).
	 * Allowed without authentication so the frontend can load it on app init.
	 */
	@GetMapping
	public ResponseEntity<AppConfigDTO> getConfig() {
		boolean loyaltyEnabled = loyaltyService.isLoyaltyEnabled();
		String posShowImagesVal = generalSetupService.findValueByCode("POS_SHOW_IMAGES");
		boolean posShowImages = posShowImagesVal == null || !"false".equalsIgnoreCase(posShowImagesVal);
		String posShowStockVal = generalSetupService.findValueByCode("POS_SHOW_STOCK");
		boolean posShowStock = "true".equalsIgnoreCase(posShowStockVal);
		String tableEnabledVal = generalSetupService.findValueByCode("TABLE_MANAGEMENT_ENABLED");
		boolean tableManagementEnabled = "true".equalsIgnoreCase(tableEnabledVal);
		String tableCountVal = generalSetupService.findValueByCode("TABLE_MANAGEMENT_TABLE_COUNT");
		int tableManagementTableCount = 10;
		if (tableCountVal != null) {
			try { tableManagementTableCount = Integer.parseInt(tableCountVal); } catch (NumberFormatException ignored) {}
		}
		boolean tombolaEnabled = "true".equalsIgnoreCase(generalSetupService.findValueByCode("TOMBOLA_ENABLED"));
		return ResponseEntity.ok(new AppConfigDTO(
				applicationModeService.isStandalone(),
				enableSalesPriceGroup,
				loyaltyEnabled,
				applicationModeService.isFranchiseAdmin(),
				applicationModeService.isFranchiseClient(),
				applicationModeService.isLocalItemsAllowed(),
				licenseService.getStatus().name(),
				licenseService.getDaysUntilExpiry(),
				posShowImages,
				posShowStock,
				tableManagementEnabled,
				tableManagementTableCount,
				appVersion,
				tombolaEnabled
		));
	}
}
