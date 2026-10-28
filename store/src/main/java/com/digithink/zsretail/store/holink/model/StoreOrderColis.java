package com.digithink.zsretail.store.holink.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 3.1.1: colis of an ERP item in an order to the head office (ZEN's série: contenance pieces per colis), one row per item
 * and order (adding again replaces the number). Apart from the lines: a colis is of the item, never of one variant. The
 * head office's figures when it was added (colis possibles: the limit; contenance: informative) are kept. See
 * docs/modules/store-orders.md.
 */
@Entity
@Table(name = "hol_order_colis",
		uniqueConstraints = @UniqueConstraint(name = "uk_hol_order_colis_item", columnNames = { "order_id", "nav_item_no" }))
@Data
@EqualsAndHashCode(callSuper = false, exclude = "order")
@ToString(exclude = "order")
@NoArgsConstructor
public class StoreOrderColis extends _BaseEntity {

	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false)
	private StoreOrder order;

	@Column(name = "nav_item_no", nullable = false, length = 255)
	private String navItemNo;

	/** The item's name when added. */
	@Column(length = 255)
	private String description;

	@Column(name = "nb_colis", nullable = false)
	private int nbColis;

	/** The pieces in one colis, as the head office gave them; null when unknown (informative). */
	@Column(name = "contenance")
	private Integer contenance;

	/** The colis the head office made available when added; nb_colis may not exceed it. Null: none given (no limit). */
	@Column(name = "colis_possibles")
	private Integer colisPossibles;

	/** The colis NAV accepted when the head office posted the order; null: none given. */
	@Column(name = "nav_accepted_colis")
	private Integer navAcceptedColis;

	/** NAV's message on the colis line when posted; null when it gave none. */
	@Column(name = "nav_message", length = StoreOrder.NAV_MESSAGE_LENGTH)
	private String navMessage;
}
