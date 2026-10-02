package com.digithink.zsretail.model.enumeration;

/**
 * Installation type (head office design 2.1). Set by {@code node.type}; STORE when absent.
 * Not used by the application yet.
 */
public enum NodeType {
	STORE,       // sells: sessions, tickets, returns, printing
	HEAD_OFFICE  // manages stores: owns shared data, receives their documents, never sells
}
