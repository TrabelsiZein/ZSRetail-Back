package com.digithink.zsretail.holink.enumeration;

/** Where the copy of one document stands (task 2.1). See docs/modules/head-office.md, "Sales copies". */
public enum SalesCopyStatus {

	/** Found (new, or changed since its last push) and waiting to be sent. */
	PENDING,

	/** The head office accepted the document as it is now. */
	SENT,

	/** The head office rejected the document, or the store could not build it: retried on later cycles. */
	ERROR
}
