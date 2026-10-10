package com.digithink.zsretail.model;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.digithink.zsretail.utils.Quantities;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Return line entity - line items for returns
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ReturnLine extends _BaseEntity {

	@ManyToOne
	@JoinColumn(name = "return_header_id", nullable = false)
	@JsonIgnore
	private ReturnHeader returnHeader;

	@ManyToOne
	@JoinColumn(name = "original_sales_line_id", nullable = false)
	@JsonIgnore
	private SalesLine originalSalesLine;

	@ManyToOne
	@JoinColumn(name = "item_id", nullable = false)
	private Item item;

	/** 2.2.2: up to 3 decimals; read through {@link #getQuantity()}, so 2.000 travels as 2. */
	@Column(nullable = false, precision = Quantities.PRECISION, scale = Quantities.SCALE)
	private BigDecimal quantity;

	@Column(nullable = false)
	private Double unitPrice; // HT (excluding VAT)

	@Column(nullable = false)
	private Double unitPriceIncludingVat; // TTC (including VAT)

	@Column(nullable = false)
	private Double lineTotal; // HT (excluding VAT)

	@Column(nullable = false)
	private Double lineTotalIncludingVat; // TTC (including VAT)

	private String notes;

	// ERP synchronization field
	private Boolean synched = false;

	/** The quantity without trailing zeros (the column has 3 decimals): 2.000 is 2, 0.200 is 0.2. */
	public BigDecimal getQuantity() {
		return Quantities.normalize(quantity);
	}
}

