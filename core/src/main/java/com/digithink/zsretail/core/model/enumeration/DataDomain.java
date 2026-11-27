package com.digithink.zsretail.core.model.enumeration;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Kinds of data that have exactly one owner (head office design 2.2), with the property key that
 * sets the owner and the owners allowed for the domain.
 * <p>
 * 3.5.0, ticket templates step 5: {@link #TICKET_TEMPLATES} is a domain of the copies down only: it has no property
 * key (no ownership.&lt;x&gt; line); whether a store takes the head office's templates is a switch of its row on the head
 * office's Stores page ("Le siège gère les modèles de tickets"), sent with the heartbeat. The ownership code (reading the
 * lines, the summary, GET /config, the heartbeat's report) loops over {@link #ownershipDomains()} only.
 */
public enum DataDomain {
	CATALOGUE("ownership.catalogue", DataOwner.LOCAL, DataOwner.HEAD_OFFICE, DataOwner.ERP),
	CUSTOMERS("ownership.customers", DataOwner.LOCAL, DataOwner.HEAD_OFFICE, DataOwner.ERP),
	PROMOTIONS("ownership.promotions", DataOwner.LOCAL, DataOwner.HEAD_OFFICE),
	LOYALTY("ownership.loyalty", DataOwner.LOCAL, DataOwner.HEAD_OFFICE),
	SUPPLY("ownership.supply", DataOwner.LOCAL, DataOwner.HEAD_OFFICE, DataOwner.ERP),
	/** 3.5.0, step 5: the ticket templates, copied down to the stores whose switch is on; no ownership line. */
	TICKET_TEMPLATES(null, DataOwner.LOCAL, DataOwner.HEAD_OFFICE);

	/** The domains with an ownership.&lt;x&gt; line, in order. */
	private static final List<DataDomain> OWNERSHIP = Collections.unmodifiableList(
			Arrays.stream(values()).filter(DataDomain::isOwnership).collect(Collectors.toList()));

	private final String propertyKey;
	private final Set<DataOwner> allowedOwners;

	DataDomain(String propertyKey, DataOwner first, DataOwner... rest) {
		this.propertyKey = propertyKey;
		this.allowedOwners = Collections.unmodifiableSet(EnumSet.of(first, rest));
	}

	/** The ownership.&lt;x&gt; key; null for a domain of the copies down only (TICKET_TEMPLATES). */
	public String getPropertyKey() {
		return propertyKey;
	}

	/** 3.5.0: whether an ownership.&lt;x&gt; line sets its owner (false: TICKET_TEMPLATES). */
	public boolean isOwnership() {
		return propertyKey != null;
	}

	/** 3.5.0: the domains with an ownership line (every one but TICKET_TEMPLATES), in order. */
	public static List<DataDomain> ownershipDomains() {
		return OWNERSHIP;
	}

	public Set<DataOwner> getAllowedOwners() {
		return allowedOwners;
	}

	public boolean allows(DataOwner owner) {
		return allowedOwners.contains(owner);
	}
}
