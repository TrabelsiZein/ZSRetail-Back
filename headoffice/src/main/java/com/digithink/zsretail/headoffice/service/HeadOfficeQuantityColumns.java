package com.digithink.zsretail.headoffice.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.service.QuantityColumns;

/**
 * Code cleanup step 6.5: the quantity columns of the head office's own tables in db/2.2.1/update.sql and
 * db/2.2.2/update.sql (the copies of the stores' tickets, stock and returns, the ERP invoices, the BLs), checked by
 * DecimalColumnsCheck after core's.
 */
@Component
public class HeadOfficeQuantityColumns implements QuantityColumns {

	@Override
	public List<String> columns() {
		return Arrays.asList("ho_ticket_line.quantity", "ho_store_stock.quantity", "ho_erp_invoice_line.quantity",
				"ho_erp_invoice_line.quantity_received", "ho_delivery_line.quantity_sent",
				"ho_delivery_line.quantity_received", "ho_return_line.quantity");
	}
}
