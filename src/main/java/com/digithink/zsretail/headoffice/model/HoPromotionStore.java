package com.digithink.zsretail.headoffice.model;

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
 * Head office plan, task 3.3: one store a promotion of the head office is addressed to. A promotion without any row is
 * for every store (the default, including stores created later); with rows, only for those stores. Head office only
 * data (prefix ho_); no column is added to promotion. Plain ids: a store can only be deleted before its first contact.
 */
@Entity
@Table(name = "ho_promotion_store",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_promotion_store", columnNames = { "promotion_id",
				"store_id" }),
		indexes = @Index(name = "ix_ho_promotion_store_promotion", columnList = "promotion_id"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoPromotionStore extends _BaseEntity {

	/** promotion.id of the head office. */
	@Column(name = "promotion_id", nullable = false)
	private Long promotionId;

	/** ho_store.id. */
	@Column(name = "store_id", nullable = false)
	private Long storeId;
}
