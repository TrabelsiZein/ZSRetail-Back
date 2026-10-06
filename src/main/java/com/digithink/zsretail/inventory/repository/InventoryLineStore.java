package com.digithink.zsretail.inventory.repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.inventory.enumeration.InventoryLineStatus;
import com.digithink.zsretail.inventory.model.InventoryCountLine;

/**
 * The bulk writes of inventory_count_line in JDBC batches (a file may hold 30,000 rows; the IDENTITY ids would make
 * Hibernate insert them one by one). Runs in the caller's transaction (JpaTransactionManager shares its connection).
 */
@Repository
public class InventoryLineStore {

	static final int BATCH = 1000;

	/** An OK line to apply at the validation; the service fills in the stock read and the difference. */
	public static final class OkLine {
		public final long lineId;
		public final long itemId;
		public final int counted;
		public Integer systemQuantity;
		public Integer difference;
		public String message;

		public OkLine(long lineId, long itemId, int counted) {
			this.lineId = lineId;
			this.itemId = itemId;
			this.counted = counted;
		}
	}

	private final JdbcTemplate jdbc;

	public InventoryLineStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Inserts the lines of one count (count, item id, code, quantities, status, message). */
	public void insert(long countId, List<InventoryCountLine> lines, String user) {
		Timestamp now = Timestamp.valueOf(LocalDateTime.now());
		jdbc.batchUpdate("INSERT INTO inventory_count_line (count_id, item_id, code, counted_quantity, merged_rows,"
				+ " system_quantity_at_import, status, message, created_at, updated_at, created_by, updated_by, active)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)", lines, BATCH, (ps, line) -> {
					ps.setLong(1, countId);
					ps.setObject(2, line.getItemId(), Types.BIGINT);
					ps.setString(3, line.getCode());
					ps.setObject(4, line.getCountedQuantity(), Types.INTEGER);
					ps.setObject(5, line.getMergedRows(), Types.INTEGER);
					ps.setObject(6, line.getSystemQuantityAtImport(), Types.INTEGER);
					ps.setString(7, line.getStatus().name());
					ps.setString(8, line.getMessage());
					ps.setTimestamp(9, now);
					ps.setTimestamp(10, now);
					ps.setString(11, user);
					ps.setString(12, user);
				});
	}

	/** Deletes the lines of one count (a new import of a draft, or its deletion). */
	public int deleteByCount(long countId) {
		return jdbc.update("DELETE FROM inventory_count_line WHERE count_id = ?", countId);
	}

	/** The OK lines of one count, by id. */
	public List<OkLine> okLines(long countId) {
		return jdbc.query("SELECT id, item_id, counted_quantity FROM inventory_count_line"
				+ " WHERE count_id = ? AND status = ? ORDER BY id",
				(rs, n) -> new OkLine(rs.getLong(1), rs.getLong(2), rs.getInt(3)), countId,
				InventoryLineStatus.OK.name());
	}

	/** Stores the stock read at the validation, the difference applied and the message of each OK line. */
	public void saveValidation(List<OkLine> lines, String user) {
		Timestamp now = Timestamp.valueOf(LocalDateTime.now());
		jdbc.batchUpdate("UPDATE inventory_count_line SET system_quantity_at_validation = ?, difference_applied = ?,"
				+ " message = ?, updated_at = ?, updated_by = ? WHERE id = ?", lines, BATCH, (ps, line) -> {
					ps.setObject(1, line.systemQuantity, Types.INTEGER);
					ps.setObject(2, line.difference, Types.INTEGER);
					ps.setString(3, line.message);
					ps.setTimestamp(4, now);
					ps.setString(5, user);
					ps.setLong(6, line.lineId);
				});
	}
}
