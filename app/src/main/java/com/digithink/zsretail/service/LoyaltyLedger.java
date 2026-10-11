package com.digithink.zsretail.service;

import com.digithink.zsretail.model.enumeration.LoyaltyTransactionType;

import lombok.Getter;
import lombok.ToString;

/**
 * Head office plan, step 4: how one loyalty movement changes a member's balance and totals, the same at the head
 * office (applying the movements the stores send) and at a store (adding its movements the head office has not applied
 * yet to the balance it received). The rules are those of LoyaltyService: a movement adds or removes points; the
 * balance never goes below zero, and what could not be removed is the overspend.
 */
public final class LoyaltyLedger {

	private LoyaltyLedger() {
	}

	/**
	 * The signed effect of a transaction of a store's loyalty_transaction: EARNED adds its points, REDEEMED and REVERSED
	 * remove them, ADJUSTED adds or removes as its balance before and after show (points given back after a return, or
	 * a manual adjustment).
	 */
	public static int deltaOf(LoyaltyTransactionType type, int points, Integer balanceBefore, Integer balanceAfter) {
		int magnitude = Math.abs(points);
		switch (type) {
			case EARNED:
				return magnitude;
			case REDEEMED:
			case REVERSED:
				return -magnitude;
			default:
				boolean removed = balanceBefore != null && balanceAfter != null && balanceAfter < balanceBefore;
				return removed ? -magnitude : magnitude;
		}
	}

	/**
	 * Applies a movement to the state. linkedToSale: an ADJUSTED movement of a sale is points given back after a return
	 * (the redeemed total goes down), otherwise it is a manual adjustment (the earned total goes up, or the redeemed
	 * total up for a removal), as in LoyaltyService.
	 */
	public static Applied apply(State state, LoyaltyTransactionType type, int delta, boolean linkedToSale) {
		int before = state.balance;
		int overspend = 0;
		if (delta >= 0) {
			state.balance += delta;
			if (type == LoyaltyTransactionType.EARNED) {
				state.earned += delta;
			} else if (type == LoyaltyTransactionType.ADJUSTED) {
				if (linkedToSale) {
					state.redeemed = Math.max(0, state.redeemed - delta);
				} else {
					state.earned += delta;
				}
			}
		} else {
			int wanted = -delta;
			int removed = Math.min(state.balance, wanted);
			state.balance -= removed;
			overspend = wanted - removed;
			if (type == LoyaltyTransactionType.REVERSED) {
				state.earned = Math.max(0, state.earned - wanted);
			} else {
				state.redeemed += removed;
			}
		}
		return new Applied(before, state.balance, overspend);
	}

	/** A member's balance and totals. */
	@Getter
	@ToString
	public static final class State {

		private int balance;
		private int earned;
		private int redeemed;

		public State(Integer balance, Integer earned, Integer redeemed) {
			this.balance = balance == null ? 0 : Math.max(0, balance);
			this.earned = earned == null ? 0 : earned;
			this.redeemed = redeemed == null ? 0 : redeemed;
		}
	}

	/** What one movement did: the balance before and after, and the points that could not be removed. */
	@Getter
	@ToString
	public static final class Applied {

		private final int balanceBefore;
		private final int balanceAfter;
		private final int overspend;

		Applied(int balanceBefore, int balanceAfter, int overspend) {
			this.balanceBefore = balanceBefore;
			this.balanceAfter = balanceAfter;
			this.overspend = overspend;
		}
	}
}
