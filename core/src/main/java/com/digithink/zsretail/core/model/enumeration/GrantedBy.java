package com.digithink.zsretail.core.model.enumeration;

/**
 * Step C (3.1.0): how a till action was authorised, one per row of badge_scan_log: ROLE (the logged user's role holds
 * the right, no badge) or BADGE (a badge was scanned: success or refusal; the rows written before step C are scans).
 */
public enum GrantedBy {
	ROLE, BADGE
}
