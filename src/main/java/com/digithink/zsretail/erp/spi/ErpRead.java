package com.digithink.zsretail.erp.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One read of the ERP translated to the Erp*DTO classes, with what happened to the rows read: kept, left out by reason,
 * and kept but noted (e.g. a zero price). For the barcodes, the highest position read (the number the ERP gives each
 * barcode row and that only grows; null when the read was empty). Code cleanup step 3: was NavPosResult of the
 * navpospages connector.
 */
public class ErpRead<T> {

	private final List<T> rows;
	private final int read;
	private final Map<String, Integer> leftOut;
	private final Map<String, Integer> notes;
	private final Long highestPosition;

	public ErpRead(List<T> rows, int read, Map<String, Integer> leftOut, Map<String, Integer> notes,
			Long highestPosition) {
		this.rows = Collections.unmodifiableList(rows);
		this.read = read;
		this.leftOut = Collections.unmodifiableMap(new LinkedHashMap<>(leftOut));
		this.notes = Collections.unmodifiableMap(new LinkedHashMap<>(notes));
		this.highestPosition = highestPosition;
	}

	public List<T> getRows() {
		return rows;
	}

	/** Rows read from the page. */
	public int getRead() {
		return read;
	}

	/** Rows kept (translated). */
	public int getKept() {
		return rows.size();
	}

	/** Rows left out, by reason. */
	public Map<String, Integer> getLeftOut() {
		return leftOut;
	}

	public int getLeftOut(String reason) {
		return leftOut.getOrDefault(reason, 0);
	}

	/** Rows kept but noted, by reason. */
	public Map<String, Integer> getNotes() {
		return notes;
	}

	public int getNote(String reason) {
		return notes.getOrDefault(reason, 0);
	}

	public Long getHighestPosition() {
		return highestPosition;
	}

	/** read N, kept N, left out {...}, notes {...}[, highest position N] */
	@Override
	public String toString() {
		return "read " + read + ", kept " + getKept() + ", left out " + leftOut + (notes.isEmpty() ? "" : ", notes " + notes)
				+ (highestPosition == null ? "" : ", highest position " + highestPosition);
	}
}
