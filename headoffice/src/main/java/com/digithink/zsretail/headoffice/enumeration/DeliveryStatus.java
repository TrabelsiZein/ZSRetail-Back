package com.digithink.zsretail.headoffice.enumeration;

/**
 * Status of a BL (delivery note) at the head office (step 7A, design 3.5). Stored by name in ho_delivery.status. See
 * docs/modules/head-office.md, "BLs".
 */
public enum DeliveryStatus {

	/** Being prepared by the head office: editable and deletable, no number, no stock moved, not visible to the store. */
	DRAFT,

	/** Validated: numbered, the goods left the head office stock, the store receives it by the copies down. */
	SENT,

	/** The store confirmed the quantities it received (its confirmation travelled up). */
	RECEIVED,

	/** In an invoice (step 7B; never set in step 7A). */
	INVOICED
}
