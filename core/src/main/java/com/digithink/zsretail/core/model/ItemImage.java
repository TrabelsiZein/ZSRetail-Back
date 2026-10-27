package com.digithink.zsretail.core.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Index;
import javax.persistence.Table;

import com.digithink.zsretail.core.model.enumeration.ImageSource;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 3.1.1 (store orders, step 1): the images of an item, any number of them, in sort order. Generic: store and head
 * office. Item.imageUrl is untouched and keeps meaning "the main image" for the till, the admin pages and the copies
 * down; nothing writes this table yet (no upload, no sync). See docs/modules/store-orders.md.
 */
@Entity
@Table(name = "item_image", indexes = @Index(name = "ix_item_image_item", columnList = "item_id, sort_order"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ItemImage extends _BaseEntity {

	public static final int PATH_LENGTH = 1000;

	@Column(name = "item_id", nullable = false)
	private Long itemId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private ImageSource source;

	/** A file name of the uploads (FILE) or a full URL (URL). */
	@Column(nullable = false, length = PATH_LENGTH)
	private String path;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(name = "is_main", nullable = false)
	private boolean main;
}
