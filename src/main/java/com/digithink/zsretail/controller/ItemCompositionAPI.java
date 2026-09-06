package com.digithink.zsretail.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.model.ItemComposition;
import com.digithink.zsretail.service.ItemCompositionService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("item-composition")
@Log4j2
public class ItemCompositionAPI extends _BaseController<ItemComposition, Long, ItemCompositionService> {

	@Autowired
	private ItemCompositionService itemCompositionService;

	/**
	 * Get all active components of a kit (parent PACKAGE item)
	 */
	@GetMapping("/item/{itemId}")
	public ResponseEntity<?> getComponentsByItemId(@PathVariable Long itemId) {
		try {
			log.info("ItemCompositionAPI::getComponentsByItemId: " + itemId);
			List<ItemComposition> components = itemCompositionService.getComponentsByParentItemId(itemId);
			return ResponseEntity.ok(components);
		} catch (Exception e) {
			log.error("ItemCompositionAPI::getComponentsByItemId:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}
}
