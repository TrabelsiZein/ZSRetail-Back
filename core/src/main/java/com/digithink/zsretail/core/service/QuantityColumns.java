package com.digithink.zsretail.core.service;

import java.util.List;

/**
 * Code cleanup step 6.5: the quantity columns of an application's own tables, as "table.column", that
 * {@link DecimalColumnsCheck} checks after core's: each application looks at its own tables only (a new store database
 * has no head office table, and the reverse).
 */
public interface QuantityColumns {

	List<String> columns();
}
