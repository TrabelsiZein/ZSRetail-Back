package com.digithink.zsretail.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository._BaseRepository;

@Service
public class ItemFamilyService extends _BaseService<ItemFamily, Long> {

	@Autowired
	private ItemFamilyRepository itemFamilyRepository;

	/** Step 6: a head office that sends its catalogue records each change; no bean on a store. */
	@Autowired(required = false)
	private ObjectProvider<CatalogueHeadOfficeHooks> catalogueHooks;

	@Override
	protected _BaseRepository<ItemFamily, Long> getRepository() {
		return itemFamilyRepository;
	}

	@Override
	@Transactional
	public ItemFamily save(ItemFamily family) throws Exception {
		return CatalogueHookCalls.save(CatalogueHookCalls.hooks(catalogueHooks), CatalogueKind.FAMILY, family,
				id -> itemFamilyRepository.findById(id).map(ItemFamily::getCode), super::save);
	}

	@Override
	@Transactional
	public void deleteById(Long id) {
		CatalogueHeadOfficeHooks hooks = CatalogueHookCalls.hooks(catalogueHooks);
		if (hooks != null) {
			itemFamilyRepository.findById(id).ifPresent(family -> hooks.beforeDelete(CatalogueKind.FAMILY, family));
		}
		super.deleteById(id);
	}
}
