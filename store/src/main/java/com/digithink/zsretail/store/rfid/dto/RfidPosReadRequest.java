package com.digithink.zsretail.store.rfid.dto;

import java.util.List;

import lombok.Data;

/** RFID POS step: POST /rfid/pos-read, what the cart holds already. */
@Data
public class RfidPosReadRequest {

	/** The pending ticket open in the cart (null: a new ticket): its own tags are not "on a pending ticket". */
	private Long pendingTicketId;

	/** The tags already on the cart's lines: ignored by the read (counted as in the cart). */
	private List<String> cartEpcs;
}
