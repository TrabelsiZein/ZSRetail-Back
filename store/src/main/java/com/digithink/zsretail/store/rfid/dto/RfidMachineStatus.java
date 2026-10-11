package com.digithink.zsretail.store.rfid.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RFID step 6: what the back end sees of the machine a request comes from, for the "Add this machine" button and the
 * POS: its address (normalised), the POS machine recorded at that address and its reader, whether it can read now and,
 * when not, why.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RfidMachineStatus {

	/** The request's address, normalised (127.0.0.1 for the PC of the back end). */
	private String address;

	/** The POS machine recorded at this address; null when none. */
	private Long posMachineId;
	private String posMachineName;

	/** Its reader; null when none. */
	private String readerName;

	/** True when a read with this machine's reader would be tried now. */
	private boolean canRead;

	/** Why it cannot read (the message the read would answer); null when it can. */
	private String message;
}
