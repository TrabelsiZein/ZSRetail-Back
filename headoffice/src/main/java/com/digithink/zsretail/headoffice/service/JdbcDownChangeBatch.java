package com.digithink.zsretail.headoffice.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.enumeration.DataDomain;

/**
 * Stock points, step 3a (speed): the change rows of one store for many codes, written with JDBC batches inside the
 * caller's transaction (JdbcTemplate joins the JPA transaction's connection). The same rows as the JPA path of
 * {@link CopiesDownFeed#recordChangesForStore}: per code, the store's row gets its new number (updated_at now); a code
 * without a row gets one (active, created and updated now, created by System, as _BaseEntity). Used only when a store's
 * stock point changes (thousands of codes for one store).
 */
@Component
@ConditionalOnInstallation(Installation.HEAD_OFFICE)
public class JdbcDownChangeBatch {

	/** Statements per JDBC batch. */
	static final int BATCH = 1000;

	/**
	 * The driver sends text as nvarchar; domain and record_code are varchar: without the casts SQL Server converts the
	 * column, cannot seek uk_ho_down_change and scans the table once per code (227 s for 16,038 codes on the rehearsal).
	 */
	static final String UPDATE = "UPDATE ho_down_change SET change_version = ?, updated_at = ?"
			+ " WHERE domain = CAST(? AS varchar(20)) AND record_code = CAST(? AS varchar(100)) AND store_id = ?";
	static final String INSERT = "INSERT INTO ho_down_change (active, created_at, created_by, updated_at, change_version,"
			+ " domain, record_code, store_id) VALUES (1, ?, 'System', ?, ?, ?, ?, ?)";

	/** 3.1.1: the every-store row of a code (store_id null), cast as above. */
	static final String UPDATE_EVERY_STORE = "UPDATE ho_down_change SET change_version = ?, updated_at = ?"
			+ " WHERE domain = CAST(? AS varchar(20)) AND record_code = CAST(? AS varchar(100)) AND store_id IS NULL";
	static final String INSERT_EVERY_STORE = "INSERT INTO ho_down_change (active, created_at, created_by, updated_at,"
			+ " change_version, domain, record_code) VALUES (1, ?, 'System', ?, ?, ?, ?)";

	private final JdbcTemplate jdbc;

	public JdbcDownChangeBatch(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** versions: code to its new number, in order. Returns how many rows were inserted (the others were updated). */
	public int write(DataDomain domain, long storeId, Map<String, Long> versions) {
		return write(domain, Long.valueOf(storeId), versions);
	}

	/**
	 * 3.1.1: the every-store rows (store_id null) of these codes, as {@link #write} for one store: a data import, the
	 * item images run (thousands of codes; one JPA find and save per code took about 100 ms each, holding the
	 * domain's sequence row meanwhile).
	 */
	public int writeEveryStore(DataDomain domain, Map<String, Long> versions) {
		return write(domain, null, versions);
	}

	private int write(DataDomain domain, Long storeId, Map<String, Long> versions) {
		Timestamp now = Timestamp.valueOf(LocalDateTime.now());
		List<Map.Entry<String, Long>> entries = new ArrayList<>(versions.entrySet());
		List<Object[]> inserts = new ArrayList<>();
		for (int from = 0; from < entries.size(); from += BATCH) {
			List<Map.Entry<String, Long>> chunk = entries.subList(from, Math.min(from + BATCH, entries.size()));
			List<Object[]> updates = new ArrayList<>();
			for (Map.Entry<String, Long> entry : chunk) {
				updates.add(storeId == null ? new Object[] { entry.getValue(), now, domain.name(), entry.getKey() }
						: new Object[] { entry.getValue(), now, domain.name(), entry.getKey(), storeId });
			}
			int[] counts = jdbc.batchUpdate(storeId == null ? UPDATE_EVERY_STORE : UPDATE, updates);
			for (int i = 0; i < chunk.size(); i++) {
				if (counts[i] < 0 || counts[i] > 1) { // no count from the driver, or the unique key broken: never guess
					throw new IllegalStateException("ho_down_change batch: update count " + counts[i] + " for "
							+ chunk.get(i).getKey() + " of " + (storeId == null ? "every store" : "store " + storeId));
				}
				if (counts[i] == 0) {
					Map.Entry<String, Long> entry = chunk.get(i);
					inserts.add(storeId == null ? new Object[] { now, now, entry.getValue(), domain.name(), entry.getKey() }
							: new Object[] { now, now, entry.getValue(), domain.name(), entry.getKey(), storeId });
				}
			}
		}
		for (int from = 0; from < inserts.size(); from += BATCH) {
			jdbc.batchUpdate(storeId == null ? INSERT_EVERY_STORE : INSERT,
					inserts.subList(from, Math.min(from + BATCH, inserts.size())));
		}
		return inserts.size();
	}
}
