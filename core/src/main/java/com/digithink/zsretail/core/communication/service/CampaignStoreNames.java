package com.digithink.zsretail.core.communication.service;

import java.util.function.BiFunction;

/**
 * 3.3.0, communication step 7: the {storeName} of a campaign message. A store: its own name (the location of
 * DEFAULT_LOCATION, JpaStoreEventSource); a head office: the store where the member enrolled (its author STORE:&lt;code&gt;,
 * else its card LYL-&lt;code&gt;-...), none for its own members (HoCommunicationStores). Absent: no store name (the
 * company's name stands in, CommunicationTemplates.withFallbacks).
 */
public interface CampaignStoreNames {

	/** Read once for a batch: (member's createdBy, card number) -> the store's name, null when unknown. */
	BiFunction<String, String, String> resolver();
}
