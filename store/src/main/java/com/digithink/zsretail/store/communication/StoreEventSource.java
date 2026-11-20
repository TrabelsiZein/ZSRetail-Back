package com.digithink.zsretail.store.communication;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Value;

/**
 * 3.3.0, communication module, step 2: what the store's finders read. Read only: nothing in the selling services or in
 * LoyaltyService is called or changed. Implemented with JPQL by {@link JpaStoreEventSource}; replaced by in-memory
 * lists in the tests.
 */
public interface StoreEventSource {

	/**
	 * Tickets COMPLETED with a loyalty member, completed from {@code completedFrom} (the activation instant), whose
	 * updated_at is after the cursor ({@code afterChangedAt}, then {@code afterId}) and not after {@code until} (now
	 * minus the settle delay). In cursor order (updated_at, then id), at most {@code limit}.
	 */
	List<TicketRow> completedTicketsWithMember(LocalDateTime completedFrom, LocalDateTime afterChangedAt, long afterId,
			LocalDateTime until, int limit);

	/** The member's balance after this sale: balance_after of the sale's last loyalty row; null when it has none. */
	Integer balanceAfterSale(long salesHeaderId);

	/**
	 * Active members enrolled at this store from {@code createdFrom} and without a MEMBER_CREATED event yet, by id, at
	 * most {@code limit}. Loyalty LOCAL: the members made here (origin not HEAD_OFFICE). Loyalty owned by the head office:
	 * the members with a hol_loyalty_member_copy row (enrolled here) that is not a merge; never a copy received from
	 * the head office.
	 */
	List<MemberRow> membersEnrolledHere(LocalDateTime createdFrom, boolean loyaltyFromHeadOffice, int limit);

	/** The store code (DEFAULT_LOCATION, null when empty), its location's name and the company name. */
	StoreLabel storeLabel();

	@Value
	class TicketRow {
		long id;
		LocalDateTime changedAt;
		String salesNumber;
		LocalDateTime salesDate;
		LocalDateTime completedDate;
		Double totalAmount;
		Integer pointsEarned;
		Integer pointsRedeemed;
		Double deductionAmount;
		String cardNumber;
		String firstName;
		String lastName;
		Integer memberPoints;
	}

	@Value
	class MemberRow {
		long id;
		String cardNumber;
		String firstName;
		String lastName;
		Integer points;
		LocalDateTime createdAt;
	}

	@Value
	class StoreLabel {
		String code;
		String name;
		String companyName;
	}
}
