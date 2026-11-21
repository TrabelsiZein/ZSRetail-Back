package com.digithink.zsretail.headoffice.service;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.service.CommunicationMembers;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.headoffice.model.HoLoyaltyAlias;
import com.digithink.zsretail.headoffice.repository.HoLoyaltyAliasRepository;

/**
 * 3.3.0, communication step 6: the members of the head office's register for the communication processor. A card of
 * the register is its member; a card a store enrolled and the head office merged into the card that holds the phone
 * (ho_loyalty_alias) is the surviving member (a ticket of it goes to him; its welcome is dropped); a card not known yet
 * may still arrive (the member push comes after the event): {@link #arriveLater()}.
 */
@Component
@ConditionalOnInstallation(Installation.HEAD_OFFICE)
public class HoCommunicationMembers implements CommunicationMembers {

	private final LoyaltyMemberRepository members;
	private final HoLoyaltyAliasRepository aliases;

	public HoCommunicationMembers(LoyaltyMemberRepository members, HoLoyaltyAliasRepository aliases) {
		this.members = members;
		this.aliases = aliases;
	}

	@Override
	public Found find(String cardNumber) {
		if (cardNumber == null) {
			return Found.none();
		}
		Optional<LoyaltyMember> own = members.findByCardNumber(cardNumber);
		if (own.isPresent()) {
			return Found.of(own.get());
		}
		Optional<HoLoyaltyAlias> alias = aliases.findByCardNumber(cardNumber);
		if (alias.isPresent()) {
			return members.findById(alias.get().getMemberId()).map(Found::mergedInto).orElse(Found.none());
		}
		return Found.none();
	}

	@Override
	public boolean arriveLater() {
		return true;
	}

	/** Step 7: the cards merged into another member, never a campaign's recipient (read once for a batch). */
	@Override
	public java.util.Set<String> aliasCards() {
		return aliases.findAll().stream().map(HoLoyaltyAlias::getCardNumber).collect(java.util.stream.Collectors.toSet());
	}
}
