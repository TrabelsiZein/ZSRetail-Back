package com.digithink.zsretail.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.repository._BaseRepository;

@Service
public class ItemSubFamilyService extends _BaseService<ItemSubFamily, Long> {

	@Autowired
	private ItemSubFamilyRepository itemSubFamilyRepository;

	@Override
	protected _BaseRepository<ItemSubFamily, Long> getRepository() {
		return itemSubFamilyRepository;
	}

	public List<ItemSubFamily> findByFamily(ItemFamily family) {
		return itemSubFamilyRepository.findByItemFamily(family);
	}
}


