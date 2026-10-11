package com.digithink.zsretail.core.service;

import com.digithink.zsretail.core.dto.CreateLoyaltyMemberRequestDTO;
import com.digithink.zsretail.core.dto.LoyaltyMemberDTO;

/**
 * The changes of a loyalty member on a store whose loyalty is the head office's (the bean exists only there:
 * holink.service.StoreLoyaltyNetwork): they go through the head office. Without the bean the loyalty screen changes the
 * members here, as before. Code cleanup step 5 (knot b): the screen is core, the network is the store's.
 */
public interface LoyaltyMemberNetwork {

	String PROGRAM_AT_HEAD_OFFICE = "The loyalty program is managed by the head office.";

	LoyaltyMemberDTO enrol(CreateLoyaltyMemberRequestDTO request);

	LoyaltyMemberDTO edit(Long id, CreateLoyaltyMemberRequestDTO request);

	LoyaltyMemberDTO toggleActive(Long id);

	LoyaltyMemberDTO linkCustomer(Long id, Long customerId);

	LoyaltyMemberDTO adjust(Long id, int delta, String reason, String adjustedBy);

	/**
	 * Enrol refused: the phone already has a card in the network (head office answer, or a member of the register held
	 * here). Today's 409 message, plus the card for the caller: LoyaltyAPI adds existingCardNumber and
	 * existingCardActive to the body. Thrown only when loyalty is owned by the head office.
	 */
	class PhoneTakenException extends IllegalStateException {

		private static final long serialVersionUID = 1L;

		private final String cardNumber;
		private final boolean cardActive;

		public PhoneTakenException(String cardNumber, String firstName, String lastName, Boolean active) {
			super(LoyaltyService.phoneTakenMessage(cardNumber, firstName, lastName, active));
			this.cardNumber = cardNumber;
			this.cardActive = Boolean.TRUE.equals(active);
		}

		public String getCardNumber() {
			return cardNumber;
		}

		public boolean isCardActive() {
			return cardActive;
		}
	}

	/** A refusal of a member change, with the HTTP status LoyaltyAPI answers. */
	class NetworkException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final int status;

		public NetworkException(int status, String message) {
			super(message);
			this.status = status;
		}

		public int getStatus() {
			return status;
		}
	}
}
