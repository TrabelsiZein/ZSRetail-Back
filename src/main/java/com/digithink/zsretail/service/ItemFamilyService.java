package com.digithink.zsretail.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository._BaseRepository;

@Service
public class ItemFamilyService extends _BaseService<ItemFamily, Long> {

	@Autowired
	private ItemFamilyRepository itemFamilyRepository;

	@Override
	protected _BaseRepository<ItemFamily, Long> getRepository() {
		return itemFamilyRepository;
	}
}


