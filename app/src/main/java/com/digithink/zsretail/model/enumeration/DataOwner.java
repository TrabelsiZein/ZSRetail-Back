package com.digithink.zsretail.model.enumeration;

/**
 * Who owns (edits) a {@link DataDomain}; everyone else receives a read-only copy (head office design 2.2).
 * Not used by the application yet.
 */
public enum DataOwner {
	LOCAL,
	HEAD_OFFICE,
	ERP
}
