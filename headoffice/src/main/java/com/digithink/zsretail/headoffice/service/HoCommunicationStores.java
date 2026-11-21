package com.digithink.zsretail.headoffice.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.service.CampaignStoreNames;
import com.digithink.zsretail.core.communication.service.CommunicationStoreNames;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.StoreRepository;

/**
 * 3.3.0, communication step 6: the head office's stores (ho_store) for the Communication page. Step 7: the {storeName}
 * of a campaign message, the store where the member enrolled: its author STORE:&lt;code&gt; (the store's member push),
 * else its card LYL-&lt;code&gt;-...; none for the head office's own members (the company's name stands in).
 */
@Component
@ConditionalOnInstallation(Installation.HEAD_OFFICE)
public class HoCommunicationStores implements CommunicationStoreNames, CampaignStoreNames {

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

	@Override
	public BiFunction<String, String, String> resolver() {
		Map<String, String> names = names();
		return (createdBy, card) -> {
			String code = storeCodeOf(createdBy, card, names);
			return code == null ? null : names.get(code);
		};
	}

	/** The store of a member: its author STORE:&lt;code&gt;, else its card LYL-&lt;code&gt;-NNNNNN when a store has that code. */
	static String storeCodeOf(String createdBy, String card, Map<String, String> names) {
		if (createdBy != null && createdBy.startsWith(HoLoyaltyReceiver.STORE_USER_PREFIX)) {
			return createdBy.substring(HoLoyaltyReceiver.STORE_USER_PREFIX.length());
		}
		if (card != null && card.startsWith("LYL-") && card.lastIndexOf('-') > 4) {
			String code = card.substring(4, card.lastIndexOf('-'));
			return names.containsKey(code) ? code : null;
		}
		return null;
	}
}
