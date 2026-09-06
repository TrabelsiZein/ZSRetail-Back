package com.digithink.zsretail.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public application configuration for the frontend (e.g. dual mode: ERP vs Standalone, franchise flags).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppConfigDTO {

	/**
	 * True when the POS runs in standalone mode (no ERP). False when integrated with ERP.
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
	 * True when this instance is the central franchise admin (HQ).
	 * Enables franchise sync APIs and franchise-specific admin UI.
	 */
	private boolean franchiseAdmin;

	/**
	 * True when this instance is a franchise client.
	 * Disables item CRUD and manual purchases; enables sync UI from admin.
	 */
	private boolean franchiseCustomer;

	/**
	 * True when the franchise client is allowed to add/manage its own local items.
	 * Items synced from the franchise admin remain read-only regardless.
	 * Always false when not in franchise client mode.
	 */
	private boolean allowLocalItems;

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
	 * Only meaningful in standalone/franchise mode where stock is tracked locally.
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
}
