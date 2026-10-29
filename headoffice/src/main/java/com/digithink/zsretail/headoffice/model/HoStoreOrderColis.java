package com.digithink.zsretail.headoffice.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OrderBy;
import javax.persistence.Table;

import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.utils.Quantities;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 3.1.1: colis of an ERP item in a store's order at the head office, one row per item, as the store sent them: a colis is
 * the whole item (one série of every colour, ZEN), posted to the ERP as a line of colis of the item. Its composition and
 * content as the store had them ({@link HoStoreOrderColisDetail}), and NAV's answer once posted. Apart from the lines.
 * The columns variant_code, color_description, contenance and colis_possibles of the first colis builds stay, unused.
 * See docs/modules/store-orders.md.
 */
@Entity
@Table(name = "ho_store_order_colis", indexes = @Index(name = "ix_ho_store_order_colis_order", columnList = "order_id"))
@Data
@EqualsAndHashCode(callSuper = false, exclude = { "order", "details" })
@ToString(exclude = { "order", "details" })
@NoArgsConstructor
public class HoStoreOrderColis extends _BaseEntity {

	public static final int COLIS_NUMBER_LENGTH = 50;

	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false)
	private HoStoreOrder order;

	@Column(name = "nav_item_no", nullable = false, length = 255)
	private String navItemNo;

	@Column(length = 255)
	private String description;

	@Column(name = "nb_colis", nullable = false)
	private int nbColis;

	/** The pieces of one colis when the store added them (the sum of the composition); null when unknown. */
	@Column(name = "colis_content")
	private Integer colisContent;

	/** The colis available when the store added them; null: none given. */
	@Column(name = "colis_disponibles")
	private Integer colisAvailable;

	/** The colis NAV accepted (its colisAcceptes) when the order was posted; null: none given. */
	@Column(name = "nav_accepted_colis")
	private Integer navAcceptedColis;

	/** The pieces NAV accepted on the colis line (its quantite); null: none given. */
	@Column(name = "nav_accepted_qty", precision = Quantities.PRECISION, scale = Quantities.SCALE)
	private BigDecimal navAcceptedQty;

	/** NAV's colis number (its numeroColis); null: none given. */
	@Column(name = "nav_numero_colis", length = COLIS_NUMBER_LENGTH)
	private String navColisNumber;

	/** NAV's message on the colis line when posted; null when it gave none. */
	@Column(name = "nav_message", length = HoStoreOrder.NAV_MESSAGE_LENGTH)
	private String navMessage;

	/** One colis: the pieces of each variant, as the store sent them. */
	@OneToMany(mappedBy = "colis", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id")
	private List<HoStoreOrderColisDetail> details = new ArrayList<>();

	public BigDecimal getNavAcceptedQty() {
		return navAcceptedQty == null ? null : Quantities.normalize(navAcceptedQty);
	}
}
