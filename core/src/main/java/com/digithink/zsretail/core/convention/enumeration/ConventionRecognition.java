package com.digithink.zsretail.core.convention.enumeration;

/**
 * 3.6.0, conventions (docs/roadmap/conventions.md, decision 4): how a member is recognised at the till, a setting per
 * convention (convention.recognition, as text).
 */
public enum ConventionRecognition {
	/** The default: cards generated, imported or added by hand; the card gives the benefit. */
	CARDS,
	/** No card: the cashier picks the convention and types the member's number; the limits count per member number. */
	NO_CARD
}
