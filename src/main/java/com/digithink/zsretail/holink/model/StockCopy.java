package com.digithink.zsretail.holink.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Head office plan, task 7A.5: the stock of one item of this store as last accepted by the head office. An item whose
 * stock differs from quantity_sent (or without a row) is sent at the next SUPPLY_PUSH; a row whose item no longer exists
 * is sent as removed, then deleted. Store table of the head office link (prefix hol_).
 */
@Entity
@Table(name = "hol_stock_copy",
		uniqueConstraints = @UniqueConstraint(name = "uk_hol_stock_copy_item", columnNames = "item_id"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class StockCopy extends _BaseEntity {

	/** item.id of this store. */
	@Column(name = "item_id", nullable = false)
	private Long itemId;

	@Column(name = "item_code", nullable = false, length = 100)
	private String itemCode;

	/** The quantity the head office accepted last (null stock is sent as 0). */
	@Column(name = "quantity_sent", nullable = false)
	private int quantitySent;

	/** Store clock of that push. */
	@Column(name = "sent_at")
	private LocalDateTime sentAt;
}
