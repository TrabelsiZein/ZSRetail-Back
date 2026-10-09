package com.digithink.zsretail.erp.navpospages.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.erp.navpospages.sync.NavPosPagesHeadOffice;
import com.digithink.zsretail.service.GeneralSetupValueCheck;

/**
 * Release 2.2: the General Setup "Read ERP invoices after number" (ERP_INVOICES_READ_AFTER) is empty (every invoice of
 * the page) or an invoice number of the ERP: the prefix of the invoices (invoices.number-prefix, FVV), the year in 2
 * digits, then digits. Anything else is refused with a clear message. Exists only with erp.navpospages.enabled=true.
 */
@Component
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
public class NavPosPagesInvoiceSettingCheck implements GeneralSetupValueCheck {

	private final NavPosPagesProperties properties;

	public NavPosPagesInvoiceSettingCheck(NavPosPagesProperties properties) {
		this.properties = properties;
	}

	@Override
	public void check(String code, String value) {
		if (!NavPosPagesHeadOffice.INVOICES_READ_AFTER.equals(code) || value == null || value.trim().isEmpty()) {
			return;
		}
		if (!properties.getInvoices().looksLikeNumber(value)) {
			String prefix = properties.getInvoices().getNumberPrefix().trim();
			throw new IllegalStateException("'" + value.trim() + "' is not an invoice number of the ERP: write the number"
					+ " after which the invoices are read, e.g. " + prefix + "26000000123 (" + prefix
					+ ", the year in 2 digits, then digits), or leave it empty to read every invoice of the page.");
		}
	}
}
