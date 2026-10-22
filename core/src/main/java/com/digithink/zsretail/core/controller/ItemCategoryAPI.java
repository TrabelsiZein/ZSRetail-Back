package com.digithink.zsretail.core.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.dto.ItemTreeDTO;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.service.ItemTreeService;
import com.digithink.zsretail.core.service.PosCatalogueService;

import lombok.RequiredArgsConstructor;

/**
 * Item variants (ZEN), step 11b: the read-only page "Catégories" (store and head office). GET only: the catégories are
 * the ERP's (head office) or the head office's copies (store), never written here. The code travels as a query
 * parameter (an ERP code may hold a '/').
 */
@RestController
@RequestMapping("item-category")
@RequiredArgsConstructor
public class ItemCategoryAPI {

	private final ItemTreeService itemTreeService;

	private final PosCatalogueService posCatalogue;

	/** GET /item-category: every catégorie with its number of families. */
	@GetMapping
	public ResponseEntity<List<ItemTreeDTO.Category>> categories() {
		return ResponseEntity.ok(itemTreeService.categories());
	}

	/** GET /item-category/families?code: the families under one catégorie. */
	@GetMapping("families")
	public ResponseEntity<List<ItemTreeDTO.Family>> families(@RequestParam(name = "code") String code) {
		return ResponseEntity.ok(itemTreeService.familiesOf(code));
	}

	/**
	 * 3.1.0 (ZEN), GET /item-category/pos: the catégories of the till (active, without the empty ones when
	 * POS_HIDE_EMPTY_FAMILIES is on; PosCatalogueService); [] without catégories.
	 */
	@GetMapping("pos")
	public ResponseEntity<List<ItemCategory>> pos() {
		return ResponseEntity.ok(posCatalogue.categories());
	}
}
