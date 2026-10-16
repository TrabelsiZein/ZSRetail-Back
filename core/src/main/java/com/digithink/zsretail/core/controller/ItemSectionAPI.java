package com.digithink.zsretail.core.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.dto.ItemTreeDTO;
import com.digithink.zsretail.core.service.ItemTreeService;

import lombok.RequiredArgsConstructor;

/**
 * Item variants (ZEN), step 11b: the read-only page "Sections" (store and head office). GET only, as
 * {@link ItemCategoryAPI}.
 */
@RestController
@RequestMapping("item-section")
@RequiredArgsConstructor
public class ItemSectionAPI {

	private final ItemTreeService itemTreeService;

	/** GET /item-section: every section with its path (sous-famille, famille, catégorie). */
	@GetMapping
	public ResponseEntity<List<ItemTreeDTO.Section>> sections() {
		return ResponseEntity.ok(itemTreeService.sections());
	}
}
