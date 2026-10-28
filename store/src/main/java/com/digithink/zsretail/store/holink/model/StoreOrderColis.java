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
 * 3.1.1: colis of a colour row of an ERP item in an order to the head office (ZEN's série, per variant: contenance pieces
 * per colis), one row per item, variant and order (adding again replaces the number); the variant is the first of its
 * colour row in the window. Apart from the lines. The head office's figures when it was added (colis possibles: the
 * limit; contenance: informative) are kept. See docs/modules/store-orders.md.
 */
@Entity
@Table(name = "hol_order_colis",
		uniqueConstraints = @UniqueConstraint(name = "uk_hol_order_colis_variant",
				columnNames = { "order_id", "nav_item_no", "variant_code" }))
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

	/**
	 * The variant the colis are of: the first of its colour row in the store's window (NAV wants an item and a variant
	 * on every line; the série is per colour).
	 */
	@Column(name = "variant_code", length = 50)
	private String variantCode;

	/** The colour of that row when added (its description). */
	@Column(name = "color_description", length = 255)
	private String colorDescription;

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
