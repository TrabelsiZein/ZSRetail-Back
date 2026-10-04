package com.digithink.zsretail.headoffice.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Head office plan, task 7A.5: the stock of one item at one store, as the store last sent it (POST /ho/supply/stock).
 * One row per store and item code; replaced by each new copy, deleted when the store no longer has the item. Head office
 * only data (prefix ho_).
 */
@Entity
@Table(name = "ho_store_stock",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_store_stock", columnNames = { "store_id", "item_code" }),
		indexes = @Index(name = "ix_ho_store_stock_item", columnList = "item_code"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoStoreStock extends _BaseEntity {

	/** ho_store.id. */
	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(name = "item_code", nullable = false, length = 100)
	private String itemCode;

	@Column(name = "item_name")
	private String itemName;

	@Column(nullable = false)
	private int quantity;

	/** True for an item of the store's own (not from the head office). */
	@Column(name = "own_item")
	private Boolean ownItem;

	/** Store clock when the store read it. */
	@Column(name = "store_time")
	private LocalDateTime storeTime;

	/** Head office clock when it arrived. */
	@Column(name = "received_at")
	private LocalDateTime receivedAt;
}
