package com.digithink.zsretail.store.rfid.model;

/**
 * RFID tags step: how a tag was linked to its item: on the Tag assignment page (MANUAL), or from a line of a delivery
 * reception (RECEPTION, with the delivery number and the line).
 */
public enum RfidTagSource {
	MANUAL, RECEPTION
}
