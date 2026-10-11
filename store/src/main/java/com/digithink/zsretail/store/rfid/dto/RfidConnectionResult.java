package com.digithink.zsretail.store.rfid.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** RFID step 4: the answer of "Test connection": the reader answered, with its information. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RfidConnectionResult {

	private String readerName;

	/** ip:port. */
	private String address;

	/** What the reader says of itself (model, firmware). */
	private String information;

	/** How long the request took, in ms. */
	private long durationMs;
}
