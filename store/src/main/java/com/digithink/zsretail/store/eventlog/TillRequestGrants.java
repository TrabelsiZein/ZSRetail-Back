package com.digithink.zsretail.store.eventlog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.store.model.TillGrant;

/**
 * 3.4.0, event log step 2: the badge grants a request used (TillRightsGuard), kept in the request's attributes until it
 * ends, so that the event of its gesture names the badge's owner by itself (StoreEventContext). A pass by the user's own
 * role keeps nothing: nobody authorised it. Outside a request (a job, a test) nothing is kept. Never throws.
 */
public final class TillRequestGrants {

	static final String ATTRIBUTE = TillRequestGrants.class.getName();

	/** A badge's owner as the grant named him, and the action the badge allowed. */
	public static final class Owner {
		public final Long id;
		public final String login;
		public final String name;
		public final String action;

		public Owner(Long id, String login, String name, String action) {
			this.id = id;
			this.login = login;
			this.name = name;
			this.action = action;
		}
	}

	private TillRequestGrants() {
	}

	/** Keeps the owner of a grant just used by the request. */
	public static void keep(TillGrant grant) {
		try {
			RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
			if (attributes == null || grant == null || grant.getBadgeOwner() == null) {
				return;
			}
			UserAccount owner = grant.getBadgeOwner();
			owners(attributes).add(new Owner(owner.getId(), owner.getUsername(), owner.getFullName(),
					grant.getAction() == null ? null : grant.getAction().name()));
		} catch (RuntimeException e) {
			// the grant was used; only its event will not name the badge's owner
		}
	}

	/** The owners of the grants the current request used, in order; empty outside a request. */
	public static List<Owner> used() {
		try {
			RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
			if (attributes == null) {
				return Collections.emptyList();
			}
			Object kept = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
			return kept == null ? Collections.emptyList() : new ArrayList<>(cast(kept));
		} catch (RuntimeException e) {
			return Collections.emptyList();
		}
	}

	private static List<Owner> owners(RequestAttributes attributes) {
		Object kept = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		if (kept != null) {
			return cast(kept);
		}
		List<Owner> owners = new ArrayList<>();
		attributes.setAttribute(ATTRIBUTE, owners, RequestAttributes.SCOPE_REQUEST);
		return owners;
	}

	@SuppressWarnings("unchecked")
	private static List<Owner> cast(Object kept) {
		return (List<Owner>) kept;
	}
}
