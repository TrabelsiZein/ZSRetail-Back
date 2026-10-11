package com.digithink.zsretail.store.rfid.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RFID POS step: a tag of a ticket (GET /rfid/ticket-tags). A pending ticket's tags have no line (its lines are made
 * again at each save): the cart puts them on the line of their item. A paid ticket's tags have their paid line;
 * returned: a return put the tag back in stock.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RfidTicketTag {
	private String epc;
	private Long itemId;
	private Long salesLineId;
	private boolean returned;
}
