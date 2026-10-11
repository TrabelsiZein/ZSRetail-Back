package com.digithink.zsretail.headoffice.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;

/**
 * Stock point copies, step 5: on a head office whose catalogue comes from the ERP, the every-store change rows of the ERP
 * items and their barcodes, written before step 4, are split into one row per store whose point has a row for the item
 * (active or not), with the same change number, then deleted. A store that has already pulled past that number reads
 * nothing again; a store behind it (a store created with a point before step 4 that has not pulled since) still reads
 * the code. A store with no point, or whose point does not have the item, is no longer told about it. A store that
 * already has its own row for the code keeps the higher of the two numbers. Hand-made items, TAX_STAMP, families and
 * sub-families are not touched; the sequence is not touched.
 * <p>
 * One transaction, set-based (SQL Server; also run on H2 by the tests). It runs only while such every-store rows exist:
 * once deleted, a second start finds none and changes nothing.
 */
@Component
@ConditionalOnInstallation(Installation.HEAD_OFFICE_ERP_CATALOGUE_ONLY)
public class ErpCatalogueRowsUpgrade implements CopiesDownUpgrade {

	/** The item came from the ERP. */
	static final String FROM_ERP = " AND i.erp_external_id IS NOT NULL AND LTRIM(RTRIM(i.erp_external_id)) <> ''";

	/** [record_code, store_id, change_version]: each every-store row of an ERP item or barcode, per store of its points. */
	static final String PAIRS = "SELECT c.record_code, s.id AS store_id, c.change_version FROM ho_down_change c"
			+ " JOIN item i ON c.record_code = CONCAT('ITEM:', i.item_code)"
			+ " JOIN ho_stock_point_item r ON r.item_id = i.id JOIN ho_store s ON s.stock_point_id = r.stock_point_id"
			+ " WHERE c.domain = 'CATALOGUE' AND c.store_id IS NULL" + FROM_ERP
			+ " UNION SELECT c.record_code, s.id AS store_id, c.change_version FROM ho_down_change c"
			+ " JOIN item_barcode b ON c.record_code = CONCAT('BARCODE:', b.barcode) JOIN item i ON i.id = b.item_id"
			+ " JOIN ho_stock_point_item r ON r.item_id = i.id JOIN ho_store s ON s.stock_point_id = r.stock_point_id"
			+ " WHERE c.domain = 'CATALOGUE' AND c.store_id IS NULL" + FROM_ERP;

	/** The every-store rows of the ERP items and their barcodes. */
	static final String EVERY_STORE_ERP_ROWS = " domain = 'CATALOGUE' AND store_id IS NULL AND ("
			+ "EXISTS (SELECT 1 FROM item i WHERE ho_down_change.record_code = CONCAT('ITEM:', i.item_code)" + FROM_ERP
			+ ") OR EXISTS (SELECT 1 FROM item_barcode b JOIN item i ON i.id = b.item_id"
			+ " WHERE ho_down_change.record_code = CONCAT('BARCODE:', b.barcode)" + FROM_ERP + "))";

	static final String COUNT = "SELECT COUNT(*) FROM ho_down_change WHERE" + EVERY_STORE_ERP_ROWS;

	/** A store's own row of the code with a lower number takes the every-store row's number. */
	static final String RAISE = "UPDATE ho_down_change SET change_version = (SELECT p.change_version FROM (" + PAIRS
			+ ") p WHERE p.record_code = ho_down_change.record_code AND p.store_id = ho_down_change.store_id),"
			+ " updated_at = ? WHERE domain = 'CATALOGUE' AND store_id IS NOT NULL AND EXISTS (SELECT 1 FROM (" + PAIRS
			+ ") p WHERE p.record_code = ho_down_change.record_code AND p.store_id = ho_down_change.store_id"
			+ " AND p.change_version > ho_down_change.change_version)";

	/** A store without its own row of the code gets one with the every-store row's number (as _BaseEntity). */
	static final String SPLIT = "INSERT INTO ho_down_change (active, created_at, created_by, updated_at, change_version,"
			+ " domain, record_code, store_id) SELECT 1, ?, 'System', ?, p.change_version, 'CATALOGUE', p.record_code,"
			+ " p.store_id FROM (" + PAIRS + ") p WHERE NOT EXISTS (SELECT 1 FROM ho_down_change d"
			+ " WHERE d.domain = 'CATALOGUE' AND d.record_code = p.record_code AND d.store_id = p.store_id)";

	static final String DELETE = "DELETE FROM ho_down_change WHERE" + EVERY_STORE_ERP_ROWS;

	private final JdbcTemplate jdbc;

	public ErpCatalogueRowsUpgrade(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public String run() {
		Long found = jdbc.queryForObject(COUNT, Long.class);
		if (found == null || found == 0) {
			return null;
		}
		Timestamp now = Timestamp.valueOf(LocalDateTime.now());
		int raised = jdbc.update(RAISE, now);
		int split = jdbc.update(SPLIT, now, now);
		int deleted = jdbc.update(DELETE);
		return "every-store rows of ERP items and barcodes: " + deleted + " deleted; store rows: " + split
				+ " written with their number, " + raised + " raised to it";
	}
}
