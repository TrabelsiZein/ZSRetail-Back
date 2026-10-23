package com.digithink.zsretail.connector.zen.config;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.zen.ZenConnectorDescriptor;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpInvoiceStart;
import com.digithink.zsretail.erp.api.GeneralSetupValueCheck;

/**
 * Item variants (ZEN), step 9, as Happyness's: the General Setup "Read ERP invoices after number"
 * (ERP_INVOICES_READ_AFTER) is empty (every invoice of the stores) or an invoice number of ZEN's NAV: the prefix of the
 * invoices (invoices.number-prefix, FVE), the year in 2 digits, then digits. Anything else is refused with a clear
 * message. Exists only with erp.connector=zen.
 */
@Component
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
public class ZenInvoiceSettingCheck implements GeneralSetupValueCheck {

	private final ZenProperties properties;

	public ZenInvoiceSettingCheck(ZenProperties properties) {
		this.properties = properties;
	}

	@Override
	public void check(String code, String value) {
		if (!ErpInvoiceStart.INVOICES_READ_AFTER.equals(code) || value == null || value.trim().isEmpty()) {
			return;
		}
		if (!properties.getInvoices().looksLikeNumber(value)) {
			String prefix = properties.getInvoices().getNumberPrefix().trim();
			throw new IllegalStateException("'" + value.trim() + "' is not an invoice number of the ERP: write the number"
					+ " after which the invoices are read, e.g. " + prefix + "2600001 (" + prefix
					+ ", the year in 2 digits, then digits), or leave it empty to read every invoice of the stores.");
		}
	}
}
