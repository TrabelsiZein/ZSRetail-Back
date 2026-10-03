package com.digithink.zsretail.headoffice.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 4: a card a store enrolled for a phone that already had a card in the network. The card is
 * not a member of the register: it is an inactive alias of the member that holds the phone, and the movements that
 * arrive for it are applied to that member. Head office only data (prefix ho_).
 */
@Entity
@Table(name = "ho_loyalty_alias", uniqueConstraints = @UniqueConstraint(name = "uk_ho_loyalty_alias_card",
		columnNames = "card_number"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoLoyaltyAlias extends _BaseEntity {

	@Column(name = "card_number", nullable = false, length = 50)
	private String cardNumber;

	/** loyalty_member id of the member that survives. */
	@Column(name = "member_id", nullable = false)
	private Long memberId;

	/** ho_store id of the store that enrolled the card. */
	@Column(name = "store_id")
	private Long storeId;
}
