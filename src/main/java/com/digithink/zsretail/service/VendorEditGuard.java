package com.digithink.zsretail.service;

import com.digithink.zsretail.model.Vendor;

/**
 * The refusals of the vendor and purchase screens on a store whose goods come from the head office by BL (the bean
 * exists only there: holink.service.SupplyVendorGuard): the head office vendor is consult-only. Each check answers the
 * refusal message, or null when allowed. Code cleanup step 5 (knot b): the screens are core, the guard is the store's.
 */
public interface VendorEditGuard {

	String create(Vendor input);

	String update(Long id, Vendor input);

	String delete(Long id);

	String purchase(Long vendorId);
}
