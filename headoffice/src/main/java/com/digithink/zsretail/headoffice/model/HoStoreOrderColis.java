package com.digithink.zsretail.headoffice.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.digithink.zsretail.core.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 3.1.1: colis of an ERP item in a store's order at the head office (ZEN's série: contenance pieces per colis), as the
 * store sent them; posted to the ERP as a line of colis (no variant). NAV's answer kept beside them. Apart from the
 * lines: a colis is of the item, never of one variant. See docs/modules/store-orders.md.
 */
@Entity
@Table(name = "ho_store_order_colis", indexes = @Index(name = "ix_ho_store_order_colis_order", columnList = "order_id"))
@Data
@EqualsAndHashCode(callSuper = false, exclude = "order")
@ToString(exclude = "order")
@NoArgsConstructor
public class HoStoreOrderColis extends _BaseEntity {

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

	/** The pieces in one colis when the store knew it (informative); null otherwise. */
	@Column(name = "contenance")
	private Integer contenance;

	/** The colis the head office had made available when the store added them; null: none given. */
	@Column(name = "colis_possibles")
	private Integer colisPossibles;

	/** The colis NAV accepted (its colisAcceptes) when the order was posted; null: none given. */
	@Column(name = "nav_accepted_colis")
	private Integer navAcceptedColis;

	/** NAV's message on the colis line when posted; null when it gave none. */
	@Column(name = "nav_message", length = HoStoreOrder.NAV_MESSAGE_LENGTH)
	private String navMessage;
}
