package com.digithink.zsretail.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Exposes the application run mode: ERP, Standalone, Franchise Admin, or Franchise Customer.
 * All mode-dependent behaviour should use this service so each mode flag has a single source
 * of truth in application properties.
 *
 * Modes are not mutually exclusive at the property level but should be treated as such:
 *   - Normal standalone:       standalone=true,  franchise.admin=false, franchise.customer=false
 *   - Normal ERP:              standalone=false, franchise.admin=false, franchise.customer=false
 *   - Franchise Admin:         standalone=true,  franchise.admin=true,  franchise.customer=false
 *   - Franchise Customer:      standalone=true,  franchise.admin=false, franchise.customer=true
 */
@Service
public class ApplicationModeService {

	@Value("${application.standalone:false}")
	private boolean standalone;

	@Value("${franchise.admin:false}")
	private boolean franchiseAdmin;

	@Value("${franchise.customer:false}")
	private boolean franchiseCustomer;

	/**
	 * True when the franchise client is allowed to add and manage its own locally-created items
	 * (e.g. products sourced from other vendors). Items synced from the franchise admin always
	 * remain read-only, regardless of this flag.
	 * Only meaningful when franchise.customer=true; ignored in all other modes.
	 */
	@Value("${franchise.customer.allow-local-items:false}")
	private boolean allowLocalItems;

	/**
	 * True when the POS runs without an ERP (standalone mode).
	 * False when the POS is integrated with ERP (e.g. Dynamics NAV / Business Central).
	 */
	public boolean isStandalone() {
		return standalone;
	}

	/**
	 * True when the POS is integrated with an ERP (current mode for e.g. MTOP).
	 */
	public boolean isErpMode() {
		return !standalone;
	}

	/**
	 * True when this instance is the central franchise admin (HQ).
	 * Exposes sync APIs for franchise clients and applies franchise-specific validations.
	 */
	public boolean isFranchiseAdmin() {
		return franchiseAdmin;
	}

	/**
	 * True when this instance is a franchise client.
	 * Item CRUD and manual purchases are blocked; data is synced from the franchise admin.
	 */
	public boolean isFranchiseClient() {
		return franchiseCustomer;
	}

	/**
	 * True when the franchise client is allowed to create and manage its own local items.
	 * Items that were synced from the franchise admin ({@code fromFranchiseAdmin=true}) are always read-only.
	 * Returns false when not in franchise client mode.
	 */
	public boolean isLocalItemsAllowed() {
		return franchiseCustomer && allowLocalItems;
	}
}
