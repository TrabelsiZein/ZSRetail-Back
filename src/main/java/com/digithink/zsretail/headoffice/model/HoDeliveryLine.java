package com.digithink.zsretail.headoffice.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One line of a {@link HoDelivery} (step 7A): an item of the head office and the quantity sent; the quantity the store
 * confirmed once received. The item's code and name are kept as they were when the line was written.
 */
@Entity
@Table(name = "ho_delivery_line",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_delivery_line", columnNames = { "delivery_id", "line_no" }))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoDeliveryLine extends _BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "delivery_id", nullable = false)
	@JsonIgnore
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private HoDelivery delivery;

	@Column(name = "line_no", nullable = false)
	private Integer lineNo;

	/** item.id of the head office. */
	@Column(name = "item_id", nullable = false)
	private Long itemId;

	@Column(name = "item_code", nullable = false, length = 100)
	private String itemCode;

	@Column(name = "item_name")
	private String itemName;

	@Column(name = "quantity_sent", nullable = false)
	private Integer quantitySent;

	/** Null until the store's confirmation arrives. */
	@Column(name = "quantity_received")
	private Integer quantityReceived;
}
