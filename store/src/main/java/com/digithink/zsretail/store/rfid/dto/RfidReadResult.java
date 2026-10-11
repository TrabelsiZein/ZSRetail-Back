package com.digithink.zsretail.store.rfid.dto;

import java.util.List;

import com.digithink.zsretail.store.rfid.reader.RfidTag;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RFID step 4: the answer of a read. It always names the reader, and the POS machine when the read was made for one
 * (null for a test read of the RFID readers page), so a cashier sees a wrong link at once.
 */
@Data
@NoArgsConstructor
public class RfidReadResult {

	/** The POS machine the read was made for; null for a test read. */
	private String posMachineName;

	private String readerName;

	/** How long the reader read, in ms (the reader's reading time). */
	private int readTimeMs;

	/** How long the whole request took, in ms (connect, read, stop, disconnect). */
	private long durationMs;

	/** Every tag seen, once, by EPC; weak when its best signal is below minSignal. */
	private List<RfidTag> tags;

	/** RFID weak tags: the reader's minimum signal (dB), the lowest and highest best signal of the read (null: no tag). */
	private Integer minSignal;
	private Double lowestRssi;
	private Double highestRssi;
}
