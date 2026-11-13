package com.digithink.zsretail.store.posmachine.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.2.0, POS machines step 1: the machine a request comes from, as the back end sees it, on every store (with or
 * without RFID): its address (normalised) and the POS machine recorded at that address. GET /pos-machine/my-machine.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MyPosMachine {

	/** The request's address, normalised (127.0.0.1 for the PC of the back end). */
	private String address;

	/** The POS machine recorded at this address; null when none. */
	private Long posMachineId;
	private String posMachineName;

	/** Whether that machine is active; null when none. */
	private Boolean posMachineActive;
}
