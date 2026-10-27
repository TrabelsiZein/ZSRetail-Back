package com.digithink.zsretail.core.dto;

import com.digithink.zsretail.core.model.ItemImage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 3.1.1: one image of an item (GET /item/{id}/images, the variants of a store order). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItemImageDTO {

	private Long id;

	/** FILE or URL. */
	private String source;

	private String path;

	private int sortOrder;

	private boolean main;

	public static ItemImageDTO of(ItemImage image) {
		return new ItemImageDTO(image.getId(), image.getSource() == null ? null : image.getSource().name(),
				image.getPath(), image.getSortOrder(), image.isMain());
	}
}
