package com.digithink.pos.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.pos.model.Item;
import com.digithink.pos.model.ItemComposition;
import com.digithink.pos.model.enumeration.ItemType;
import com.digithink.pos.repository.ItemCompositionRepository;
import com.digithink.pos.repository.ItemRepository;
import com.digithink.pos.repository._BaseRepository;

@Service
public class ItemCompositionService extends _BaseService<ItemComposition, Long> {

	@Autowired
	private ItemCompositionRepository itemCompositionRepository;

	@Autowired
	private ItemRepository itemRepository;

	@Override
	protected _BaseRepository<ItemComposition, Long> getRepository() {
		return itemCompositionRepository;
	}

	/**
	 * Get all active components of a kit (parent PACKAGE item)
	 */
	public List<ItemComposition> getComponentsByParentItemId(Long parentItemId) {
		return itemCompositionRepository.findByParentItemIdAndActiveTrue(parentItemId);
	}

	public List<ItemComposition> getCompositionsByComponentItemId(Long componentItemId) {
		return itemCompositionRepository.findByComponentItemId(componentItemId);
	}

	public List<ItemComposition> getCompositionsByParentItemId(Long parentItemId) {
		return itemCompositionRepository.findByParentItemId(parentItemId);
	}

	@Override
	public ItemComposition save(ItemComposition entity) throws Exception {
		if (entity.getParentItem() == null || entity.getParentItem().getId() == null) {
			throw new IllegalArgumentException("L'article parent est obligatoire");
		}
		if (entity.getComponentItem() == null || entity.getComponentItem().getId() == null) {
			throw new IllegalArgumentException("L'article composant est obligatoire");
		}
		if (entity.getQuantity() == null || entity.getQuantity() < 1) {
			throw new IllegalArgumentException("La quantité doit être supérieure ou égale à 1");
		}

		Long parentId = entity.getParentItem().getId();
		Long componentId = entity.getComponentItem().getId();

		if (parentId.equals(componentId)) {
			throw new IllegalArgumentException("Un pack ne peut pas être son propre composant");
		}

		Item parent = itemRepository.findById(parentId)
				.orElseThrow(() -> new IllegalArgumentException("Article parent introuvable"));
		if (parent.getType() != ItemType.PACKAGE) {
			throw new IllegalArgumentException("L'article parent doit être de type Pack");
		}

		Item component = itemRepository.findById(componentId)
				.orElseThrow(() -> new IllegalArgumentException("Article composant introuvable"));
		if (component.getType() == ItemType.PACKAGE) {
			throw new IllegalArgumentException("Un composant ne peut pas être un Pack (imbrication interdite)");
		}

		itemCompositionRepository.findByParentItemIdAndComponentItemId(parentId, componentId).ifPresent(existing -> {
			if (!existing.getId().equals(entity.getId())) {
				throw new IllegalArgumentException("Cet article est déjà un composant de ce pack");
			}
		});

		entity.setParentItem(parent);
		entity.setComponentItem(component);
		return super.save(entity);
	}
}
