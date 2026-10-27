package com.digithink.zsretail.core.controller;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.dto.ItemImageDTO;
import com.digithink.zsretail.core.repository.ItemImageRepository;

import lombok.RequiredArgsConstructor;

/**
 * 3.1.1 (store orders, step 1): the images of an item, read only, store and head office. Nothing writes them yet; the
 * main image of the till and the admin pages stays item.imageUrl. See docs/modules/store-orders.md.
 */
@RestController
@RequestMapping("item")
@RequiredArgsConstructor
public class ItemImageAPI {

	private final ItemImageRepository images;

	/** GET /item/{id}/images: [{id, source, path, sortOrder, main}], the main one first; empty for an unknown item. */
	@GetMapping("/{id}/images")
	public ResponseEntity<List<ItemImageDTO>> images(@PathVariable Long id) {
		return ResponseEntity.ok(images.findOfItem(id).stream().map(ItemImageDTO::of).collect(Collectors.toList()));
	}
}
