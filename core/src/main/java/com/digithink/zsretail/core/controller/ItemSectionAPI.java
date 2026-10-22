package com.digithink.zsretail.core.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.dto.ItemTreeDTO;
import com.digithink.zsretail.core.service.ItemTreeService;
import com.digithink.zsretail.core.service.PosCatalogueService;

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

	private final PosCatalogueService posCatalogue;

	/** GET /item-section: every section with its path (sous-famille, famille, catégorie). */
	@GetMapping
	public ResponseEntity<List<ItemTreeDTO.Section>> sections() {
		return ResponseEntity.ok(itemTreeService.sections());
	}

	/**
	 * 3.1.0 (ZEN), GET /item-section/pos?subFamily={id}: the sections of one sub-family on the till (active, without the
	 * empty ones when POS_HIDE_EMPTY_SUB_FAMILIES is on; PosCatalogueService); [] without sections, 400 with the message
	 * for an unknown sub-family (as GET /item-sub-family/pos/by-family/{id}).
	 */
	@GetMapping("pos")
	public ResponseEntity<?> pos(@RequestParam(name = "subFamily") Long subFamilyId) {
		try {
			return ResponseEntity.ok(posCatalogue.sections(subFamilyId));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}
}
