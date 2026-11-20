package com.digithink.zsretail.headoffice.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.service.CommunicationStoreNames;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.StoreRepository;

/** 3.3.0, communication step 6: the head office's stores (ho_store) for the Communication page. */
@Component
@ConditionalOnInstallation(Installation.HEAD_OFFICE)
public class HoCommunicationStores implements CommunicationStoreNames {

	private final StoreRepository stores;

	public HoCommunicationStores(StoreRepository stores) {
		this.stores = stores;
	}

	@Override
	public Map<String, String> names() {
		Map<String, String> names = new LinkedHashMap<>();
		stores.findAll().stream().sorted(Comparator.comparing(Store::getCode))
				.forEach(store -> names.put(store.getCode(), store.getName()));
		return names;
	}
}
