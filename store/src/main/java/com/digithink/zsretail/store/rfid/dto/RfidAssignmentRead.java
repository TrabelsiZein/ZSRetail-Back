package com.digithink.zsretail.store.rfid.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RFID tags step: the answer of an assignment read. The tags the reader saw now, in four groups for this item: NEW
 * (unknown, would be linked), SAME_ITEM (already on this item, ignored), OTHER_ITEM (on another item, with it; never
 * changed here), SOLD (never changed here). Each tag with its signal, times seen and "weak": a signal more than
 * {@link #weakMarginDb} dB below the best of this read (likely a piece further away, not on the table).
 */
@Data
@NoArgsConstructor
public class RfidAssignmentRead {

	/** The groups of a tag. */
	public enum Group {
		NEW, SAME_ITEM, OTHER_ITEM, SOLD
	}

	/** One tag seen. */
	@Data
	@NoArgsConstructor
	public static class Tag {
		private String epc;
		private double bestRssi;
		private int seen;
		private boolean weak;
		private Group group;
		/** OTHER_ITEM, and SOLD: the item the tag is on. */
		private Long otherItemId;
		private String otherItemCode;
		private String otherItemName;
	}

	private String readerName;
	private Long itemId;
	private String itemCode;
	private String itemName;
	/** As given, null when none. */
	private Integer expectedQuantity;
	private int readTimeMs;
	private long durationMs;
	/** The best signal of this read (dB), null without any tag. */
	private Double bestRssi;
	private int weakMarginDb;

	private List<Tag> newTags = new ArrayList<>();
	private List<Tag> sameItem = new ArrayList<>();
	private List<Tag> otherItem = new ArrayList<>();
	private List<Tag> sold = new ArrayList<>();

	public int getNewCount() {
		return newTags.size();
	}

	public int getSameItemCount() {
		return sameItem.size();
	}

	public int getOtherItemCount() {
		return otherItem.size();
	}

	public int getSoldCount() {
		return sold.size();
	}
}
