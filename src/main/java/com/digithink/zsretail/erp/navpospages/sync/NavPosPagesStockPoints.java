package com.digithink.zsretail.erp.navpospages.sync;

import java.util.List;
import java.util.Map;

/**
 * Stock points, step 2: the points de stock of the head office and their rows, as the items run reads and writes them.
 * The head office tables ho_stock_point and ho_stock_point_item (headoffice.service.HoStockPointRows), or maps in the
 * tests. Keys are item codes.
 */
public interface NavPosPagesStockPoints {

	/** The active points, in list order. */
	List<Point> activePoints();

	/** Every row of the point (active or not), by item code. */
	Map<String, Row> rows(long pointId);

	/**
	 * Writes these rows of the point (new ones created, the others replaced), in one transaction. A row whose item is not
	 * at the head office is left out (the next run hands it again). Returns how many rows were written.
	 */
	int write(long pointId, List<Row> packet);

	/** Step 4: the last read of the point, shown on the points page: OK, NO_ANSWER or FAILED, with its summary. */
	void recordRead(long pointId, String status, String summary);

	String READ_OK = "OK";
	String READ_NO_ANSWER = "NO_ANSWER";
	String READ_FAILED = "FAILED";

	/** A point de stock: its id, its code (the ERP's Location_Code) and its name. */
	class Point {
		public final long id;
		public final String code;
		public final String name;

		public Point(long id, String code, String name) {
			this.id = id;
			this.code = code;
			this.name = name;
		}
	}

	/** An item as the ERP gives it for one point. unitPrice has the meaning of item.unitPrice (before VAT). */
	class Row {
		public final String itemCode;
		public final String name;
		public final String description;
		public final String familyCode;
		public final String subFamilyCode;
		public final Double unitPrice;
		public final boolean active;

		public Row(String itemCode, String name, String description, String familyCode, String subFamilyCode,
				Double unitPrice, boolean active) {
			this.itemCode = itemCode;
			this.name = name;
			this.description = description;
			this.familyCode = familyCode;
			this.subFamilyCode = subFamilyCode;
			this.unitPrice = unitPrice;
			this.active = active;
		}

		/** The same row, inactive. */
		public Row inactive() {
			return new Row(itemCode, name, description, familyCode, subFamilyCode, unitPrice, false);
		}
	}
}
