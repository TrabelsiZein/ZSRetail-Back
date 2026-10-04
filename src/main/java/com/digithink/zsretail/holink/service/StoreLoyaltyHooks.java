package com.digithink.zsretail.holink.service;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeOwned;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.model.LoyaltyMemberCopy;
import com.digithink.zsretail.holink.repository.LoyaltyMemberCopyRepository;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.LoyaltyProgram;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.service.LoyaltyCardNumbers;
import com.digithink.zsretail.service.LoyaltyNetworkHooks;

/**
 * Head office plan, step 4: what LoyaltyService does differently on a store whose loyalty is owned by its head office.
 * A member created here (till or admin page, after the phone check of StoreLoyaltyNetwork) is a member of the network
 * register: origin HEAD_OFFICE, card LYL-&lt;store code&gt;-000001 (DEFAULT_LOCATION, its own sequence), and a
 * hol_loyalty_member_copy row in the same transaction, so the LOYALTY_PUSH job sends it up. The phone is unique among
 * the members of the register held here; the local members switched off at the switch do not count. Program writes
 * never reach LoyaltyService here (LoyaltyAPI refuses them).
 */
@Component
@ConditionalOnHeadOfficeOwned(DataDomain.LOYALTY)
public class StoreLoyaltyHooks implements LoyaltyNetworkHooks {

	static final String NO_STORE_CODE = "DEFAULT_LOCATION is empty in the general setup: the card number of a network"
			+ " member carries the store code.";

	public static final String FRESH_BALANCE_REQUIRED = "Points cannot be spent: this store spends points only with a"
			+ " balance checked with the head office in the last 2 minutes, and it could not be checked. Select the card"
			+ " again when the head office answers, or complete the sale without spending points.";

	private final HeadOfficeClient client;
	private final LoyaltyMemberRepository members;
	private final LoyaltyMemberCopyRepository memberCopies;
	private final HeadOfficeLinkStatus linkStatus;
	private final LoyaltyFreshness freshness;

	public StoreLoyaltyHooks(HeadOfficeClient client, LoyaltyMemberRepository members,
			LoyaltyMemberCopyRepository memberCopies, HeadOfficeLinkStatus linkStatus, LoyaltyFreshness freshness) {
		this.client = client;
		this.members = members;
		this.memberCopies = memberCopies;
		this.linkStatus = linkStatus;
		this.freshness = freshness;
	}

	/**
	 * Step 5: with redeemRequiresOnline set for this store at the head office (last heartbeat answer), points are spent
	 * only when the member's balance was refreshed from the head office within the last 2 minutes; otherwise the
	 * spending is refused (409) with the reason. The sale without points, and earning, are not concerned. Unknown
	 * (before the first heartbeat answer) or false: never refused, as today.
	 */
	@Override
	public void beforeRedeem(LoyaltyMember member, int points) {
		if (points > 0 && Boolean.TRUE.equals(linkStatus.get().getRedeemRequiresOnline())
				&& !freshness.isFresh(member.getCardNumber())) {
			throw new IllegalStateException(FRESH_BALANCE_REQUIRED);
		}
	}

	/** LYL-&lt;store code&gt;-000001: this store's own sequence. 409 when DEFAULT_LOCATION is empty. */
	@Override
	public String nextCardNumber() {
		String code = client.readStoreCode();
		if (code == null) {
			throw new IllegalStateException(NO_STORE_CODE);
		}
		String prefix = LoyaltyCardNumbers.prefixOf(code);
		return LoyaltyCardNumbers.next(prefix, members.findCardNumbersLike(LoyaltyCardNumbers.likePattern(prefix)));
	}

	@Override
	public boolean holdsPhone(LoyaltyMember member) {
		return member.getOrigin() == RecordOrigin.HEAD_OFFICE;
	}

	@Override
	public void beforeMemberCreated(LoyaltyMember member) {
		member.setOrigin(RecordOrigin.HEAD_OFFICE);
	}

	/** A member created here is queued for the head office; a change goes through the head office (StoreLoyaltyNetwork). */
	@Override
	public void afterMemberSaved(LoyaltyMember member, boolean created) {
		if (!created) {
			return;
		}
		LoyaltyMemberCopy copy = new LoyaltyMemberCopy();
		copy.setMemberId(member.getId());
		copy.setCardNumber(member.getCardNumber());
		memberCopies.save(copy);
	}

	@Override
	public void afterProgramSaved(LoyaltyProgram program) {
		// refused before LoyaltyService: the program is the head office's
	}

	@Override
	public void beforeProgramDeleted(LoyaltyProgram program) {
		// refused before LoyaltyService: the program is the head office's
	}
}
