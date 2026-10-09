package com.digithink.zsretail.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.model.enumeration.StockMovementType;

/**
 * Stock changes of many items at once, in JDBC batches (an inventory count of 30,000 lines). Called only by
 * {@link com.digithink.zsretail.service.StockService} and {@link com.digithink.zsretail.service.StockMovementService},
 * in the caller's transaction. Each stock update is the same atomic relative update as
 * {@link ItemRepository#addToStockQuantity}.
 */
@Repository
public class StockBatchRepository {

	static final int BATCH = 1000;

	/** One stock_movement row without price nor session. */
	public static final class MovementRow {
		public final long itemId;
		public final StockMovementType type;
		public final StockMovementDirection direction;
		/** Positive; 2.2.1: up to 3 decimals. */
		public final BigDecimal quantity;
		public final Long referenceId;
		public final String referenceType;
		public final String notes;

		public MovementRow(long itemId, StockMovementType type, StockMovementDirection direction, BigDecimal quantity,
				Long referenceId, String referenceType, String notes) {
			this.itemId = itemId;
			this.type = type;
			this.direction = direction;
			this.quantity = quantity;
			this.referenceId = referenceId;
			this.referenceType = referenceType;
			this.notes = notes;
		}
	}

	private final JdbcTemplate jdbc;

	public StockBatchRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Adds each delta (item id to delta) to the item's stock, null counting as 0. */
	public void addToStockQuantities(Map<Long, BigDecimal> deltas) {
		List<Map.Entry<Long, BigDecimal>> entries = new ArrayList<>(deltas.entrySet());
		jdbc.batchUpdate("UPDATE item SET stock_quantity = COALESCE(stock_quantity, 0) + ? WHERE id = ?", entries,
				BATCH, (ps, entry) -> {
					ps.setBigDecimal(1, entry.getValue());
					ps.setLong(2, entry.getKey());
				});
	}

	/** Inserts the movement rows. */
	public void insertMovements(List<MovementRow> rows, String user) {
		Timestamp now = Timestamp.valueOf(LocalDateTime.now());
		jdbc.batchUpdate("INSERT INTO stock_movement (movement_type, direction, item_id, quantity, reference_id,"
				+ " reference_type, notes, created_at, updated_at, created_by, updated_by, active)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)", rows, BATCH, (ps, row) -> {
					ps.setString(1, row.type.name());
					ps.setString(2, row.direction.name());
					ps.setLong(3, row.itemId);
					ps.setBigDecimal(4, row.quantity);
					ps.setObject(5, row.referenceId, java.sql.Types.BIGINT);
					ps.setString(6, row.referenceType);
					ps.setString(7, row.notes);
					ps.setTimestamp(8, now);
					ps.setTimestamp(9, now);
					ps.setString(10, user);
					ps.setString(11, user);
				});
	}
}
