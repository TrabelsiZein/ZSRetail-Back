package com.digithink.zsretail.model;

import java.time.LocalDate;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model.enumeration.SalesPriceType;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * SalesPrice entity - represents sales prices from Dynamics NAV
 */
@Entity
@Table(name = "sales_price", uniqueConstraints = { @UniqueConstraint(columnNames = { "item_no", "sales_type",
		"sales_code", "responsibility_center", "starting_date", "currency_code", "variant_code", "unit_of_measure_code",
		"minimum_quantity" }) }, indexes = {
				// Optimized index for best-value selection query
				// Covers: WHERE item_no=? AND (OR conditions on sales_type+sales_code) AND date conditions
				// ORDER BY unit_price ASC, starting_date DESC
				// Index order: item_no (filter) -> unit_price (sort) -> starting_date (sort/tie-breaker)
				@Index(name = "idx_sales_price_best_value", columnList = "item_no,unit_price,starting_date"),
				// Supporting index for filtering by sales_type + sales_code (helps with OR conditions)
				@Index(name = "idx_sales_price_type_code", columnList = "item_no,sales_type,sales_code"),
				// Index for date range filtering
				@Index(name = "idx_sales_price_dates", columnList = "starting_date,ending_date") })
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class SalesPrice extends _BaseEntity {

	@Column(name = "erp_external_id")
	private String erpExternalId;

	@Column(name = "item_no", nullable = false)
	private String itemNo;

	@Enumerated(EnumType.STRING)
	@Column(name = "sales_type", nullable = false)
	private SalesPriceType salesType;

	@Column(name = "sales_code", nullable = false)
	private String salesCode;

	@Column(name = "unit_price")
	private Double unitPrice;

	@Column(name = "price_includes_vat")
	private Boolean priceIncludesVat;

	@Column(name = "responsibility_center", nullable = false)
	private String responsibilityCenter;

	@Column(name = "responsibility_center_type")
	private String responsibilityCenterType;

	@Column(name = "starting_date", nullable = false)
	private LocalDate startingDate;

	@Column(name = "ending_date")
	private LocalDate endingDate;

	@Column(name = "currency_code", nullable = false)
	private String currencyCode;

	@Column(name = "variant_code", nullable = false)
	private String variantCode;

	@Column(name = "unit_of_measure_code", nullable = false)
	private String unitOfMeasureCode;

	@Column(name = "minimum_quantity", nullable = false)
	private Double minimumQuantity;
}
