package com.digithink.zsretail.store.rfid.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RFID tags step: the answer of an assignment save. linked: the tags now linked to the item (they were unknown);
 * alreadyOnItem: already on this item, unchanged (saving twice changes nothing); refused: the others, each with why,
 * never changed.
 */
@Data
@NoArgsConstructor
public class RfidAssignmentResult {

	/** Why a tag was not linked. */
	public enum Refusal {
		/** Now on another item: never moved here (the Tags page moves a tag). */
		OTHER_ITEM,
		/** Sold: never changed here. */
		SOLD,
		/** Not an EPC. */
		INVALID
	}

	/** A tag not linked. */
	@Data
	@NoArgsConstructor
	public static class Refused {
		private String epc;
		private Refusal reason;
		/** OTHER_ITEM and SOLD: the item the tag is on. */
		private String itemCode;
		private String itemName;
		private String message;
	}

	private Long itemId;
	private String itemCode;
	private String itemName;
	private List<String> linked = new ArrayList<>();
	private List<String> alreadyOnItem = new ArrayList<>();
	private List<Refused> refused = new ArrayList<>();
}
