package com.digithink.zsretail.dto;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public application configuration for the frontend (e.g. with or without an ERP). The three franchise fields
 * (franchiseAdmin, franchiseCustomer, allowLocalItems) left with step 9, task 9.4a: the frontend reads a missing one as false.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppConfigDTO {

	/**
	 * True when the POS runs without an ERP. False when integrated with ERP.
	 */
	private boolean standalone;

	/**
	 * True when sales price group and sales discount features are enabled (admin views and pricing logic).
	 */
	private boolean enableSalesPriceGroup;

	/**
	 * True when the loyalty (fidélité) program is enabled. Controlled by GeneralSetup LOYALTY_ENABLED.
	 */
	private boolean loyaltyEnabled;

	/**
	 * Current license status: VALID, WARNING, EXPIRED, or MISSING.
	 * Frontend uses this to show warning banners and block access when needed.
	 */
	private String licenseStatus;

	/**
	 * Number of days until the current license expires.
	 * Negative when license is expired or missing. Used for warning countdown.
	 */
	private long licenseDaysUntilExpiry;

	/**
	 * True when POS should display images for families, subfamilies and items.
	 * Controlled by GeneralSetup POS_SHOW_IMAGES. Set to false to suppress all
	 * image requests when the network or server is slow.
	 */
	private boolean posShowImages;

	/**
	 * True when POS should display stock quantity on item cards.
	 * Controlled by GeneralSetup POS_SHOW_STOCK. Defaults to false.
	 * Only meaningful where stock is tracked locally (no ERP).
	 */
	private boolean posShowStock;

	/**
	 * True when table management mode is enabled in POS.
	 * Controlled by GeneralSetup TABLE_MANAGEMENT_ENABLED.
	 */
	private boolean tableManagementEnabled;

	/**
	 * Number of tables shown in the table selection grid.
	 * Controlled by GeneralSetup TABLE_MANAGEMENT_TABLE_COUNT.
	 */
	private int tableManagementTableCount;

	/**
	 * Application version string (e.g. "1.2.5"), injected from pom.xml at build time.
	 * Displayed in the frontend footer.
	 */
	private String appVersion;

	/**
	 * True when tombola printing is enabled. When true, a small tombola slip
	 * (ticket number + barcode + customer name + phone) is automatically printed
	 * alongside the main receipt for tickets attached to a loyalty member.
	 * Controlled by GeneralSetup TOMBOLA_ENABLED.
	 */
	private boolean tombolaEnabled;

	/**
	 * Installation type (head office design 2.1): "STORE" or "HEAD_OFFICE".
	 * From ApplicationModeService.getNodeType(). Not used by the frontend yet.
	 */
	private String nodeType;

	/**
	 * Owner of every data domain (head office design 2.2), DataDomain name to DataOwner name,
	 * e.g. {"CATALOGUE": "ERP", ...}. Every domain is present, in DataDomain order.
	 * From ApplicationModeService.ownerOf(domain). Not used by the frontend yet.
	 */
	private Map<String, String> ownership;

	/**
	 * Where copies of tickets, returns and session closings go: SalesUpstream names, empty = nowhere.
	 * From ApplicationModeService.salesUpstreams(). Not used by the frontend yet.
	 */
	private List<String> salesUpstreams;

	/**
	 * True on a store that calls a head office (headoffice.url set, task 1.4). The frontend shows the
	 * "Head office link" page only then. From ApplicationModeService.isHeadOfficeLinked().
	 */
	private boolean headOfficeLinked;

	/**
	 * Step 6: true on a store whose catalogue is the head office's (headoffice.url, ownership.catalogue=HEAD_OFFICE):
	 * head office records are consult-only. Since step 9 the same as ownership.CATALOGUE == HEAD_OFFICE (no franchise
	 * profile derives it any more). From ApplicationModeService.isCatalogueFromHeadOffice().
	 */
	private boolean catalogueFromHeadOffice;

	/**
	 * Step 7A: true on a store whose goods come from the head office by BL (headoffice.url, ownership.supply=HEAD_OFFICE):
	 * the BL reception page exists. Since step 9 the same as ownership.SUPPLY == HEAD_OFFICE (no franchise profile
	 * derives it any more). From ApplicationModeService.isSupplyFromHeadOffice().
	 */
	private boolean supplyFromHeadOffice;
}
