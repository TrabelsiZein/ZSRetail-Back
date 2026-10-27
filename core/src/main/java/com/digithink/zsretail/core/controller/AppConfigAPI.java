package com.digithink.zsretail.core.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.AppConfigDTO;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.model.enumeration.SalesUpstream;
import com.digithink.zsretail.core.model.enumeration.TillActions;
import com.digithink.zsretail.core.service.DecimalColumnsCheck;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.service.ItemTreeService;
import com.digithink.zsretail.core.service.LicenseService;
import com.digithink.zsretail.core.service.LoyaltyService;
import com.digithink.zsretail.core.service.NavItemService;
import com.digithink.zsretail.core.service.QuantityPolicy;
import com.digithink.zsretail.core.service.TaxStampRule;

import lombok.RequiredArgsConstructor;

/**
 * Exposes application configuration for the frontend (e.g. with or without an ERP).
 * Used to control UI visibility (ERP menu, sync columns) without changing backend behaviour.
 */
@RestController
@RequestMapping("config")
@RequiredArgsConstructor
public class AppConfigAPI {

	/** 2.2.2: a store setting, display only: amounts with 3 decimals on the POS, payment and return screens. */
	public static final String POS_AMOUNTS_THREE_DECIMALS = "POS_AMOUNTS_3_DECIMALS";

	/**
	 * 3.1.0: a store setting, the ticket the till prints when the cashier closes the session: "Z" (the ticket Z, also
	 * when the row is absent) or "DECLARED" (the amounts declared only, the closing ticket of before).
	 */
	public static final String CASHIER_CLOSING_TICKET = "CASHIER_CLOSING_TICKET";

	/** CASHIER_CLOSING_TICKET read: "DECLARED" in any case, else "Z"; null on a head office (no till). */
	String cashierClosingTicket() {
		if (applicationModeService.isHeadOffice()) {
			return null;
		}
		return "DECLARED".equalsIgnoreCase(generalSetupService.findValueByCode(CASHIER_CLOSING_TICKET)) ? "DECLARED"
				: "Z";
	}

	private final ApplicationModeService applicationModeService;
	private final LicenseService licenseService;
	private final GeneralSetupService generalSetupService;

	@Value("${pos.pricing.enable-sales-price-group:false}")
	private boolean enableSalesPriceGroup;

	@Value("${app.version:unknown}")
	private String appVersion;

	@Autowired
	private LoyaltyService loyaltyService;

	@Autowired
	private TaxStampRule taxStampRule;

	@Autowired(required = false)
	private DecimalColumnsCheck decimalColumns;

	/** Always there in the applications; absent in the tests that build this API by hand (navItems false). */
	@Autowired(required = false)
	private NavItemService navItemService;

	/** As navItemService: absent in the hand-built tests (itemCategories, itemSections false). */
	@Autowired(required = false)
	private ItemTreeService itemTreeService;

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
				!applicationModeService.hasErp(), // step 9: the field keeps its name and value (no ERP)
				enableSalesPriceGroup,
				loyaltyEnabled,
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
				applicationModeService.isHeadOfficeLinked(),
				applicationModeService.isCatalogueFromHeadOffice(),
				applicationModeService.isSupplyFromHeadOffice(),
				!applicationModeService.isHeadOfficeWithoutStock(),
				!applicationModeService.isHeadOffice() ? null
						: applicationModeService.isSupplyFromErpSource() ? "ERP" : "HEAD_OFFICE",
				"true".equalsIgnoreCase(generalSetupService.findValueByCode(QuantityPolicy.SETTING))
						&& (decimalColumns == null || decimalColumns.ready()), // 2.2.1: off while a column is still int
				taxStampRule.active(),
				"true".equalsIgnoreCase(generalSetupService.findValueByCode(POS_AMOUNTS_THREE_DECIMALS)),
				applicationModeService.isRfidEnabled(),
				cashierClosingTicket(),
				navItemService != null && navItemService.hasNavItems(),
				itemTreeService != null && itemTreeService.hasCategories(),
				itemTreeService != null && itemTreeService.hasSections(),
				// step B: the till actions registry; a head office has no till
				applicationModeService.isHeadOffice() ? Collections.emptyList() : TillActions.all(),
				applicationModeService.getStoreOrdersPost() // 3.1.1: ERP, MANUAL or null (a store: always null)
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
