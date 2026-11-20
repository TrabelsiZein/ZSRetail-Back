package com.digithink.zsretail.core.model;

import java.time.LocalDateTime;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 3.3.0, communication step 5: the one place where a loyalty member's consent to receive messages changes
 * (LoyaltyMember.messageConsent, messageConsentAt, messageConsentBy). docs/modules/communication.md, "Step 5".
 * <ul>
 * <li>{@link #apply}: a change asked here (enrolment, members page, the head office's edit): null asks nothing; the
 * same value keeps its date; a new value gets now and the user.</li>
 * <li>{@link #copy}: the value of the owner of loyalty as it is (a copy down, the head office's answer): null (an older
 * head office sends none) changes nothing.</li>
 * <li>{@link #mergeYes}: an enrolment merged into a card that holds the phone: a yes is kept with its date when it is
 * newer than the card's last change; a no never removes a yes.</li>
 * </ul>
 */
public final class MessageConsent {

	static final int BY_LENGTH = 100;

	private MessageConsent() {
	}

	/** A change asked: true when the member changed. */
	public static boolean apply(LoyaltyMember member, Boolean asked, String by, LocalDateTime now) {
		if (asked == null || asked.equals(given(member))) {
			return false;
		}
		set(member, asked, now, by);
		return true;
	}

	/** The owner's value as it is (date and user included); null changes nothing. True when the member changed. */
	public static boolean copy(LoyaltyMember member, Boolean value, LocalDateTime at, String by) {
		if (value == null) {
			return false;
		}
		boolean changed = !value.equals(given(member)) || !same(at, member.getMessageConsentAt())
				|| !same(by, member.getMessageConsentBy());
		if (changed) {
			set(member, value, at, by);
		}
		return changed;
	}

	/**
	 * A merge: the yes of the new enrolment (given at {@code at}, null: now) kept on the surviving member when that
	 * member has none and its last change is older; a no, or an older yes, changes nothing. True when it changed.
	 */
	public static boolean mergeYes(LoyaltyMember member, Boolean value, LocalDateTime at, String by,
			LocalDateTime now) {
		if (!Boolean.TRUE.equals(value) || given(member)) {
			return false;
		}
		LocalDateTime when = at != null ? at : now;
		if (member.getMessageConsentAt() != null && !when.isAfter(member.getMessageConsentAt())) {
			return false; // withdrawn after that enrolment: the withdrawal stands
		}
		set(member, Boolean.TRUE, when, by);
		return true;
	}

	/** True when the member gave its consent. */
	public static boolean given(LoyaltyMember member) {
		return Boolean.TRUE.equals(member.getMessageConsent());
	}

	/** The logged user's name, "System" without one (a job, a test). */
	public static String currentUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		String name = authentication == null ? null : authentication.getName();
		return name == null || name.trim().isEmpty() || "anonymousUser".equals(name) ? "System" : name;
	}

	private static void set(LoyaltyMember member, Boolean value, LocalDateTime at, String by) {
		member.setMessageConsent(value);
		member.setMessageConsentAt(at);
		member.setMessageConsentBy(by == null || by.length() <= BY_LENGTH ? by : by.substring(0, BY_LENGTH));
	}

	private static boolean same(Object a, Object b) {
		return a == null ? b == null : a.equals(b);
	}
}
