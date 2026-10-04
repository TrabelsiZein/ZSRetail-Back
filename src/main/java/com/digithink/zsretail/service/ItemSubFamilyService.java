package com.digithink.zsretail.service;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.repository._BaseRepository;

@Service
public class ItemSubFamilyService extends _BaseService<ItemSubFamily, Long> {

	@Autowired
	private ItemSubFamilyRepository itemSubFamilyRepository;

	/** Step 6: a head office that sends its catalogue records each change; no bean on a store. */
	@Autowired(required = false)
	private ObjectProvider<CatalogueHeadOfficeHooks> catalogueHooks;

	@Override
	protected _BaseRepository<ItemSubFamily, Long> getRepository() {
		return itemSubFamilyRepository;
	}

	public List<ItemSubFamily> findByFamily(ItemFamily family) {
		return itemSubFamilyRepository.findByItemFamily(family);
	}

	@Override
	@Transactional
	public ItemSubFamily save(ItemSubFamily subFamily) throws Exception {
		return CatalogueHookCalls.save(CatalogueHookCalls.hooks(catalogueHooks), CatalogueKind.SUBFAMILY, subFamily,
				id -> itemSubFamilyRepository.findById(id).map(ItemSubFamily::getCode), super::save);
	}

	@Override
	@Transactional
	public void deleteById(Long id) {
		CatalogueHeadOfficeHooks hooks = CatalogueHookCalls.hooks(catalogueHooks);
		if (hooks != null) {
			itemSubFamilyRepository.findById(id)
					.ifPresent(subFamily -> hooks.beforeDelete(CatalogueKind.SUBFAMILY, subFamily));
		}
		super.deleteById(id);
	}
}
