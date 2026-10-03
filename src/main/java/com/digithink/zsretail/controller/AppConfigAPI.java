package com.digithink.zsretail.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.dto.AppConfigDTO;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.SalesUpstream;
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
				tombolaEnabled,
				applicationModeService.getNodeType().name(),
				ownership(),
				salesUpstreams(),
				applicationModeService.isHeadOfficeLinked()
		));
	}

	/** Every DataDomain name to its DataOwner name, in DataDomain order. */
	private Map<String, String> ownership() {
		Map<String, String> ownership = new LinkedHashMap<>();
		for (DataDomain domain : DataDomain.values()) {
			ownership.put(domain.name(), applicationModeService.ownerOf(domain).name());
		}
		return ownership;
	}

	/** SalesUpstream names in enum order; empty when sales go nowhere. */
	private List<String> salesUpstreams() {
		List<String> upstreams = new ArrayList<>();
		for (SalesUpstream upstream : applicationModeService.salesUpstreams()) {
			upstreams.add(upstream.name());
		}
		return upstreams;
	}
}
