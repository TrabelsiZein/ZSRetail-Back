package com.digithink.zsretail.store.rfid.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * RFID POS step: a read at the POS. The items to add (one piece per tag, one entry per item) and how many tags of each
 * kind the read saw: added, already sold, unknown, not read clearly (weak), already in the cart, on a pending ticket.
 */
@Data
public class RfidPosReadResult {

	private String posMachineName;
	private String readerName;
	private long durationMs;

	private List<RfidPosItem> items = new ArrayList<>();

	private int added;
	private int alreadySold;
	private int unknown;
	private int notReadClearly;
	private int inCart;
	private int onPendingTicket;

	/** An item to add to the cart: one piece per tag. */
	@Data
	public static class RfidPosItem {
		private Long itemId;
		private String itemCode;
		private String itemName;
		private List<String> epcs = new ArrayList<>();
	}
}
