package com.digithink.zsretail.store.holink.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OrderBy;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.utils.Quantities;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 3.1.1: colis of an ERP item in an order to the head office, one row per item and order (adding again replaces the
 * number). A colis is the whole item: one série of every colour (ZEN), so it has no variant; its composition (the pieces
 * of each variant in one colis) is kept in {@link StoreOrderColisDetail} as the head office gave it when added, with its
 * content (their sum) and the colis available then (the limit). Apart from the lines. NAV's answer once posted. The
 * columns variant_code, color_description, contenance and colis_possibles of the first colis builds stay in the table,
 * unused. See docs/modules/store-orders.md.
 */
@Entity
@Table(name = "hol_order_colis",
		uniqueConstraints = @UniqueConstraint(name = "uk_hol_order_colis_order_item", columnNames = { "order_id", "nav_item_no" }))
@Data
@EqualsAndHashCode(callSuper = false, exclude = { "order", "details" })
@ToString(exclude = { "order", "details" })
@NoArgsConstructor
public class StoreOrderColis extends _BaseEntity {

	public static final int COLIS_NUMBER_LENGTH = 50;

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

	/** The pieces of one colis when added (the sum of the composition); null when unknown (older rows). */
	@Column(name = "colis_content")
	private Integer colisContent;

	/** The colis the head office made available when added; nb_colis may not exceed it. Null: none given (no limit). */
	@Column(name = "colis_disponibles")
	private Integer colisAvailable;

	/** The colis NAV accepted when the head office posted the order; null: none given. */
	@Column(name = "nav_accepted_colis")
	private Integer navAcceptedColis;

	/** The pieces NAV accepted on the colis line (its quantite); null: none given. */
	@Column(name = "nav_accepted_qty", precision = Quantities.PRECISION, scale = Quantities.SCALE)
	private BigDecimal navAcceptedQty;

	/** NAV's colis number (its numeroColis); null: none given. */
	@Column(name = "nav_numero_colis", length = COLIS_NUMBER_LENGTH)
	private String navColisNumber;

	/** NAV's message on the colis line when posted; null when it gave none. */
	@Column(name = "nav_message", length = StoreOrder.NAV_MESSAGE_LENGTH)
	private String navMessage;

	/** One colis: the pieces of each variant, as the head office gave them when added. */
	@OneToMany(mappedBy = "colis", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id")
	private List<StoreOrderColisDetail> details = new ArrayList<>();

	public BigDecimal getNavAcceptedQty() {
		return navAcceptedQty == null ? null : Quantities.normalize(navAcceptedQty);
	}
}
