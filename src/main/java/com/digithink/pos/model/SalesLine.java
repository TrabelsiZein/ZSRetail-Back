package com.digithink.pos.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Sales line entity - line items for sales
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class SalesLine extends _BaseEntity {

	@ManyToOne
	@JoinColumn(name = "sales_header_id", nullable = false)
	@JsonIgnore
	private SalesHeader salesHeader;

	@ManyToOne
	@JoinColumn(name = "item_id", nullable = false)
	private Item item;

	@Column(nullable = false)
	private Integer quantity;

	@Column(nullable = false)
	private Double unitPrice;

	private Double discountPercentage;

	private Double discountAmount;

	@Column(nullable = false)
	private Double lineTotal;

	private Double vatAmount;

	private Integer vatPercent;

	@Column(nullable = false)
	private Double unitPriceIncludingVat;

	@Column(nullable = false)
	private Double lineTotalIncludingVat;

	// Transient fields for convenience
	private transient Long salesHeaderId;

	private transient Long itemId;

	@Column(nullable = false)
	private Boolean synched = false;

	/**
	 * Origin of the discount applied on this line. Values: MANUAL | SALES_PRICE |
	 * SALES_DISCOUNT | PROMOTION Null when no discount was applied.
	 */
	@Column(name = "discount_source", length = 20)
	private String discountSource;

	/**
	 * Promotion that produced the discount on this line. Set only when
	 * discountSource = PROMOTION.
	 * groupItems/getItem are excluded from serialization here so ticket responses
	 * keep their pre-1.10 shape and never lazy-load the group member list.
	 */
	@ManyToOne
	@JoinColumn(name = "promotion_id")
	@JsonIgnoreProperties({"groupItems", "getItem"})
	private Promotion promotion;
}
