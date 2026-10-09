package com.digithink.zsretail.service;

/**
 * Release 2.2: a check of a General Setup value before the admin saves it (GeneralSetupService.updateFromAdmin). A bean of
 * a mode that owns a setting checks it (e.g. "Read ERP invoices after number" on a head office whose supply comes from
 * the ERP); every other code passes.
 */
public interface GeneralSetupValueCheck {

	/** Throws IllegalStateException with the message shown to the admin (answered 400) when the value is refused. */
	void check(String code, String value);
}
