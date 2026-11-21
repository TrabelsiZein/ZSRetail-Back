package com.digithink.zsretail.core.communication.service;

import java.util.Optional;

import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.repository.LoyaltyMemberRepository;

/**
 * 3.3.0, communication step 6: the member an event's card speaks for, where the messages are made. A store: the card's
 * own member, and an unknown card is skipped at once ({@link #local}). A head office (HoCommunicationMembers): also the
 * surviving member of a card merged by phone (ho_loyalty_alias), and a card it does not know yet may still arrive (the
 * stores' member push comes after their events).
 */
public interface CommunicationMembers {

	/** The member the card speaks for now; empty when this installation does not know the card. */
	Found find(String cardNumber);

	/** True where a member may arrive after its event (a head office). */
	boolean arriveLater();

	/**
	 * Step 7: the cards merged into another member (a head office's ho_loyalty_alias): never a campaign's recipient, even
	 * if a member row holds one. None on a store.
	 */
	default java.util.Set<String> aliasCards() {
		return java.util.Collections.emptySet();
	}

	/** A member found, and the card it was found through when that card was merged into it. */
	final class Found {
		private final LoyaltyMember member;
		private final boolean merged;

		private Found(LoyaltyMember member, boolean merged) {
			this.member = member;
			this.merged = merged;
		}

		public static Found of(LoyaltyMember member) {
			return new Found(member, false);
		}

		/** The surviving member of a merged card. */
		public static Found mergedInto(LoyaltyMember member) {
			return new Found(member, true);
		}

		public static Found none() {
			return new Found(null, false);
		}

		public Optional<LoyaltyMember> getMember() {
			return Optional.ofNullable(member);
		}

		/** True when the card was merged into this member (an alias card at a head office). */
		public boolean isMerged() {
			return merged;
		}
	}

	/** A store's: the card's own member, nothing arrives later. */
	static CommunicationMembers local(LoyaltyMemberRepository members) {
		return new CommunicationMembers() {
			@Override
			public Found find(String cardNumber) {
				return cardNumber == null ? Found.none()
						: members.findByCardNumber(cardNumber).map(Found::of).orElse(Found.none());
			}

			@Override
			public boolean arriveLater() {
				return false;
			}
		};
	}
}
