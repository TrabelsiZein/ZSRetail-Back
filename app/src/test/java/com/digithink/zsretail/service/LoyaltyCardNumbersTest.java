package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.model.enumeration.LoyaltyTransactionType;

/**
 * Head office plan, step 4: card number formats of a shared register (LYL-HO-000001 at the head office,
 * LYL-&lt;store code&gt;-000001 at a store) and the movement arithmetic shared by the head office and the stores.
 */
class LoyaltyCardNumbersTest {

	@Test
	@DisplayName("Prefix: LYL-<code>-, the code trimmed and uppercase")
	void prefix() {
		assertEquals("LYL-HO-", LoyaltyCardNumbers.prefixOf("HO"));
		assertEquals("LYL-RS01-", LoyaltyCardNumbers.prefixOf(" rs01 "));
		assertEquals("LYL-SHOWROOM-S-", LoyaltyCardNumbers.prefixOf("showroom-s"));
	}

	@Test
	@DisplayName("Next: the highest sequence of the prefix plus one, six digits; other prefixes and today's LYL-000001 ignored")
	void next() {
		assertEquals("LYL-RS01-000001", LoyaltyCardNumbers.next("LYL-RS01-", Collections.emptyList()));
		assertEquals("LYL-RS01-000013", LoyaltyCardNumbers.next("LYL-RS01-", Arrays.asList("LYL-RS01-000012",
				"LYL-RS01-000003", "LYL-RS02-000099", "LYL-000250", "LYL-RS01-ABC", "LYL-RS01-", null)));
		assertEquals("LYL-RS01-1000000", LoyaltyCardNumbers.next("LYL-RS01-", Collections.singletonList("LYL-RS01-999999")));
		// A store whose code starts like another one's: LYL-RS01-X... is not a card of LYL-RS0
		assertEquals("LYL-RS0-000001", LoyaltyCardNumbers.next("LYL-RS0-", Collections.singletonList("LYL-RS01-000005")));
	}

	@Test
	@DisplayName("LIKE pattern: %, _, [ and ! escaped with !, then %")
	void likePattern() {
		assertEquals("LYL-RS01-%", LoyaltyCardNumbers.likePattern("LYL-RS01-"));
		assertEquals("LYL-A!_B!%C![D!!-%", LoyaltyCardNumbers.likePattern("LYL-A_B%C[D!-"));
	}

	@Test
	@DisplayName("Ledger: the sign of each type; never below zero, the rest is the overspend; totals as LoyaltyService")
	void ledger() {
		assertEquals(10, LoyaltyLedger.deltaOf(LoyaltyTransactionType.EARNED, 10, 0, 10));
		assertEquals(-10, LoyaltyLedger.deltaOf(LoyaltyTransactionType.REDEEMED, 10, 10, 0));
		assertEquals(-10, LoyaltyLedger.deltaOf(LoyaltyTransactionType.REVERSED, 10, 5, 0));
		assertEquals(10, LoyaltyLedger.deltaOf(LoyaltyTransactionType.ADJUSTED, 10, 0, 10));
		assertEquals(-10, LoyaltyLedger.deltaOf(LoyaltyTransactionType.ADJUSTED, 10, 10, 0));

		LoyaltyLedger.State state = new LoyaltyLedger.State(50, 50, 0);
		LoyaltyLedger.Applied redeemed = LoyaltyLedger.apply(state, LoyaltyTransactionType.REDEEMED, -80, true);
		assertEquals(50, redeemed.getBalanceBefore());
		assertEquals(0, redeemed.getBalanceAfter());
		assertEquals(30, redeemed.getOverspend());
		assertEquals(50, state.getRedeemed());
		LoyaltyLedger.apply(state, LoyaltyTransactionType.ADJUSTED, 20, true); // given back after a return
		assertEquals(20, state.getBalance());
		assertEquals(30, state.getRedeemed());
		LoyaltyLedger.apply(state, LoyaltyTransactionType.REVERSED, -100, true);
		assertEquals(0, state.getBalance());
		assertEquals(0, state.getEarned());
		LoyaltyLedger.apply(state, LoyaltyTransactionType.ADJUSTED, 15, false); // manual
		assertEquals(15, state.getEarned());
	}
}
