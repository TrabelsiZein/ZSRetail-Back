package com.digithink.zsretail.store.rfid.dto;

import java.util.List;

import com.digithink.zsretail.store.rfid.reader.RfidTag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RFID step 4: the answer of a read. It always names the reader, and the POS machine when the read was made for one
 * (null for a test read of the RFID readers page), so a cashier sees a wrong link at once.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RfidReadResult {

	/** The POS machine the read was made for; null for a test read. */
	private String posMachineName;

	private String readerName;

	/** How long the reader read, in ms (the reader's reading time). */
	private int readTimeMs;

	/** How long the whole request took, in ms (connect, read, stop, disconnect). */
	private long durationMs;

	/** Every tag seen, once, by EPC. */
	private List<RfidTag> tags;
}
