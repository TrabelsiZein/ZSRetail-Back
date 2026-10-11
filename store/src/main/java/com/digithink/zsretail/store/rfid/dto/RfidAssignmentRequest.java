package com.digithink.zsretail.store.rfid.dto;

import java.util.ArrayList;
import java.util.List;

import com.digithink.zsretail.store.rfid.model.RfidTagSource;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RFID tags step: the bodies of an assignment. Read: readerId, itemId, expectedQuantity (optional). Save: itemId, the
 * EPCs the user kept, the source (MANUAL by default; RECEPTION with the delivery number and line).
 */
public final class RfidAssignmentRequest {

	private RfidAssignmentRequest() {
	}

	/** POST /rfid-tag/assignment/read. */
	@Data
	@NoArgsConstructor
	public static class Read {
		private Long readerId;
		private Long itemId;
		private Integer expectedQuantity;
	}

	/** POST /rfid-tag/assignment/save. */
	@Data
	@NoArgsConstructor
	public static class Save {
		private Long itemId;
		private List<String> epcs = new ArrayList<>();
		private RfidTagSource sourceType;
		private String sourceDocument;
		private Integer sourceLine;
	}
}
