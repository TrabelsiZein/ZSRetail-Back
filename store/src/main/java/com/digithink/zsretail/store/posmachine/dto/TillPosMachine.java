package com.digithink.zsretail.store.posmachine.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.2.0, POS machines step 2, part B: GET /cashier-session/current/pos-machine, what the till reads when its screens open
 * (the POS screen, payment, returns): who the drawer belongs to, the address the back end sees for this PC, the POS
 * machine of the user's open session (null without one) and whether this PC is refused selling, holding and returning for
 * it (rule 3: the drawer of the POS machine and another address).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TillPosMachine {

	/** "CASHIER" or "POS_MACHINE". */
	private String drawerOwner;
	/** The request's address, normalised; null when it cannot be read. */
	private String address;
	/** The POS machine of the user's open session; null without a session or a machine. */
	private String posMachineName;
	/** True when this PC may not sell, hold or return for the open session (the till shows why and greys them). */
	private boolean blocked;
}
