package com.digithink.zsretail.service;

import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.LoyaltyProgram;

/**
 * Head office plan, step 4: what shared loyalty adds to {@link LoyaltyService}. Implemented by one bean at most: on a
 * head office (HoLoyaltyService: card numbers LYL-HO-000001, every change recorded for the stores) or on a store whose
 * loyalty is owned by its head office (StoreLoyaltyHooks: card numbers LYL-&lt;store code&gt;-000001, the member sent up).
 * Without such a bean (loyalty LOCAL, and in the tests that build LoyaltyService by hand) LoyaltyService behaves as
 * before. Called inside the transaction of the LoyaltyService method.
 */
public interface LoyaltyNetworkHooks {

	/** The card number of a member created now. */
	String nextCardNumber();

	/** True when this member's phone counts for the uniqueness of the phone numbers. */
	boolean holdsPhone(LoyaltyMember member);

	/** Before a new member is saved for the first time (e.g. sets its origin). */
	void beforeMemberCreated(LoyaltyMember member);

	/** After a member is saved: created, edited, activated or deactivated, linked to a customer, points adjusted. */
	void afterMemberSaved(LoyaltyMember member, boolean created);

	/** After a program is saved: created, edited, closed or deactivated. */
	void afterProgramSaved(LoyaltyProgram program);

	/** Before a program is deleted. */
	void beforeProgramDeleted(LoyaltyProgram program);

	/**
	 * Step 5: before points of this member are spent in a sale; throws (with the reason the cashier sees) to refuse.
	 * Nothing by default (a head office never sells).
	 */
	default void beforeRedeem(LoyaltyMember member, int points) {
	}
}
