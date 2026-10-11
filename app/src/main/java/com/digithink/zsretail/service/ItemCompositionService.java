package com.digithink.zsretail.service;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemComposition;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.repository.ItemCompositionRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository._BaseRepository;
import com.digithink.zsretail.utils.Quantities;

@Service
public class ItemCompositionService extends _BaseService<ItemComposition, Long> {

	@Autowired
	private ItemCompositionRepository itemCompositionRepository;

	@Autowired
	private ItemRepository itemRepository;

	/** Step 6: a head office that sends its catalogue sends a pack again when its components change; none on a store. */
	@Autowired(required = false)
	private ObjectProvider<CatalogueHeadOfficeHooks> catalogueHooks;

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

	/**
	 * 2.2.1: a pack's components stay whole for now. The refusal of a quantity sent with decimals, naming the component;
	 * null for a whole quantity.
	 */
	public String decimalRefusal(ItemComposition entity) {
		if (entity == null || entity.getDecimalQuantity() == null) {
			return null;
		}
		Item component = entity.getComponentItem() == null || entity.getComponentItem().getId() == null ? null
				: itemRepository.findById(entity.getComponentItem().getId()).orElse(null);
		return Quantities.notSupportedYet(component == null ? null : component.getItemCode(),
				component == null ? null : component.getName(), entity.getDecimalQuantity(), "compositions");
	}

	@Override
	@Transactional
	public ItemComposition save(ItemComposition entity) throws Exception {
		if (entity.getParentItem() == null || entity.getParentItem().getId() == null) {
			throw new IllegalArgumentException("L'article parent est obligatoire");
		}
		if (entity.getComponentItem() == null || entity.getComponentItem().getId() == null) {
			throw new IllegalArgumentException("L'article composant est obligatoire");
		}
		String decimal = decimalRefusal(entity);
		if (decimal != null) {
			throw new IllegalArgumentException(decimal);
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
		CatalogueHeadOfficeHooks hooks = CatalogueHookCalls.hooks(catalogueHooks);
		Long previousParentId = hooks == null || entity.getId() == null ? null
				: itemCompositionRepository.findById(entity.getId()).map(c -> c.getParentItem().getId()).orElse(null);
		ItemComposition saved = super.save(entity);
		if (hooks != null) {
			if (previousParentId != null && !previousParentId.equals(parentId)) {
				hooks.afterPackChanged(previousParentId);
			}
			hooks.afterPackChanged(parentId);
		}
		return saved;
	}

	@Override
	@Transactional
	public void deleteById(Long id) {
		CatalogueHeadOfficeHooks hooks = CatalogueHookCalls.hooks(catalogueHooks);
		Long parentId = hooks == null ? null
				: itemCompositionRepository.findById(id).map(c -> c.getParentItem().getId()).orElse(null);
		super.deleteById(id);
		if (parentId != null) {
			hooks.afterPackChanged(parentId);
		}
	}
}
