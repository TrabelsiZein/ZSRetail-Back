package com.digithink.zsretail.model;

import java.time.LocalDate;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model.enumeration.SalesDiscountSalesType;
import com.digithink.zsretail.model.enumeration.SalesDiscountType;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * SalesDiscount entity - represents sales discounts from Dynamics NAV
 */
@Entity
@Table(name = "sales_discount", uniqueConstraints = { @UniqueConstraint(columnNames = { "type", "code", "sales_type",
		"sales_code", "responsibility_center", "starting_date", "auxiliary_index1", "auxiliary_index2",
		"auxiliary_index3", "auxiliary_index4" }) }, indexes = {
				// Optimized index for best-value selection query
				// Covers: WHERE type=? AND code=? AND (OR conditions on sales_type+sales_code) AND date conditions
				// ORDER BY line_discount DESC, starting_date DESC
				// Index order: type+code (filter) -> line_discount (sort) -> starting_date (sort/tie-breaker)
				@Index(name = "idx_sales_discount_best_value", columnList = "type,code,line_discount,starting_date"),
				// Supporting index for filtering by sales_type + sales_code (helps with OR conditions)
				@Index(name = "idx_sales_discount_type_code", columnList = "type,code,sales_type,sales_code"),
				// Index for date range filtering
				@Index(name = "idx_sales_discount_dates", columnList = "starting_date,ending_date") })
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class SalesDiscount extends _BaseEntity {

	@Column(name = "erp_external_id")
	private String erpExternalId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false)
	private SalesDiscountType type;

	@Column(name = "code", nullable = false)
	private String code;

	@Enumerated(EnumType.STRING)
	@Column(name = "sales_type", nullable = false)
	private SalesDiscountSalesType salesType;

	@Column(name = "sales_code", nullable = false)
	private String salesCode;

	@Column(name = "responsibility_center_type")
	private String responsibilityCenterType;

	@Column(name = "responsibility_center", nullable = false)
	private String responsibilityCenter;

	@Column(name = "starting_date", nullable = false)
	private LocalDate startingDate;

	@Column(name = "ending_date")
	private LocalDate endingDate;

	@Column(name = "line_discount")
	private Double lineDiscount;

	@Column(name = "auxiliary_index1", nullable = false)
	private String auxiliaryIndex1;

	@Column(name = "auxiliary_index2", nullable = false)
	private String auxiliaryIndex2;

	@Column(name = "auxiliary_index3", nullable = false)
	private String auxiliaryIndex3;

	@Column(name = "auxiliary_index4", nullable = false)
	private Integer auxiliaryIndex4;
}
