package com.digithink.zsretail.erp.navpospages.mapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rows of one page translated to the Erp*DTO classes, with what happened to the rows read: kept, left out by reason,
 * and kept but noted (e.g. a zero price). For the barcodes, the highest Entry_No read (null when the page was empty).
 */
public class NavPosResult<T> {

	private final List<T> rows;
	private final int read;
	private final Map<String, Integer> leftOut;
	private final Map<String, Integer> notes;
	private final Long highestEntryNo;

	public NavPosResult(List<T> rows, int read, Map<String, Integer> leftOut, Map<String, Integer> notes,
			Long highestEntryNo) {
		this.rows = Collections.unmodifiableList(rows);
		this.read = read;
		this.leftOut = Collections.unmodifiableMap(new LinkedHashMap<>(leftOut));
		this.notes = Collections.unmodifiableMap(new LinkedHashMap<>(notes));
		this.highestEntryNo = highestEntryNo;
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

	public Long getHighestEntryNo() {
		return highestEntryNo;
	}

	/** read N, kept N, left out {...}, notes {...}[, highest Entry_No N] */
	@Override
	public String toString() {
		return "read " + read + ", kept " + getKept() + ", left out " + leftOut + (notes.isEmpty() ? "" : ", notes " + notes)
				+ (highestEntryNo == null ? "" : ", highest Entry_No " + highestEntryNo);
	}
}
