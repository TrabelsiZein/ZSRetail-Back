package com.digithink.zsretail.holink.model;

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
 * One line of a {@link ReceivedDelivery} (step 7A): the head office item by code, the store item when it exists here
 * (a head office item, origin HEAD_OFFICE), the quantity sent and, once confirmed, the quantity received and whether it
 * went into the stock (false while the item is missing here: applied once by the next cycle that finds it).
 */
@Entity
@Table(name = "hol_delivery_line",
		uniqueConstraints = @UniqueConstraint(name = "uk_hol_delivery_line", columnNames = { "delivery_id", "line_no" }))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ReceivedDeliveryLine extends _BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "delivery_id", nullable = false)
	@JsonIgnore
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private ReceivedDelivery delivery;

	@Column(name = "line_no", nullable = false)
	private Integer lineNo;

	@Column(name = "item_code", nullable = false, length = 100)
	private String itemCode;

	@Column(name = "item_name")
	private String itemName;

	/** item.id of this store; null while the item is not here. */
	@Column(name = "item_id")
	private Long itemId;

	@Column(name = "quantity_sent", nullable = false)
	private Integer quantitySent;

	/** Null until confirmed. */
	@Column(name = "quantity_received")
	private Integer quantityReceived;

	/** Null until confirmed; then true once the quantity is in the stock (or nothing to add), false while it waits. */
	@Column(name = "stock_applied")
	private Boolean stockApplied;
}
