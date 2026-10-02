package com.digithink.zsretail.model.enumeration;

/**
 * Where copies of tickets, returns and session closings go (head office design 2.2, {@code sales.upstream}).
 * An empty set means nowhere. Not used by the application yet.
 */
public enum SalesUpstream {
	ERP,
	HEAD_OFFICE
}
