package com.digithink.zsretail.headoffice.model;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.utils.Quantities;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** One line of a {@link HoReturn} (task 2.3). Replaced with its return. */
@Entity
@Table(name = "ho_return_line")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoReturnLine extends _BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "return_id", nullable = false)
	@JsonIgnore
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private HoReturn returnCopy;

	@Column(nullable = false)
	private Integer lineNo;

	private String itemCode;

	private String itemName;

	/** 2.2.2: up to 3 decimals, stored as the store sent it; read without trailing zeros (2, 0.2). */
	@Column(precision = Quantities.PRECISION, scale = Quantities.SCALE)
	private BigDecimal quantity;

	private Double unitPrice;

	private Double unitPriceIncludingVat;

	private Double lineTotal;

	private Double lineTotalIncludingVat;

	@Column(length = 1000)
	private String notes;

	public BigDecimal getQuantity() {
		return Quantities.normalize(quantity);
	}
}
