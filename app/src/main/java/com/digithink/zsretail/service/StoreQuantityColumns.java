package com.digithink.zsretail.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.service.QuantityColumns;

/**
 * Code cleanup step 6.5: the quantity columns of the store's own tables in db/2.2.1/update.sql (the copies received
 * from the head office, the inventory counts), checked by DecimalColumnsCheck after core's.
 */
@Component
public class StoreQuantityColumns implements QuantityColumns {

	@Override
	public List<String> columns() {
		return Arrays.asList("hol_stock_copy.quantity_sent", "inventory_count_line.counted_quantity",
				"inventory_count_line.system_quantity_at_import", "inventory_count_line.system_quantity_at_validation",
				"inventory_count_line.difference_applied", "hol_delivery_line.quantity_sent",
				"hol_delivery_line.quantity_received");
	}
}
