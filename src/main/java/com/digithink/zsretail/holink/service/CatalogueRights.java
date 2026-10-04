package com.digithink.zsretail.holink.service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeCatalogue;
import com.digithink.zsretail.holink.model.LinkRight;
import com.digithink.zsretail.holink.repository.LinkRightRepository;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, step 6 (decision 9): the catalogue rights of this store, set on the head office Stores page and
 * received with each heartbeat answer. The last values received are saved (hol_link_right) and used after a restart and
 * while the head office is unreachable; a right never received is off. Store whose catalogue is the head office's only.
 */
@Component
@ConditionalOnHeadOfficeCatalogue
@Log4j2
public class CatalogueRights {

	public static final String MAY_CHANGE_PRICES = "MAY_CHANGE_PRICES";
	public static final String CAN_PURCHASE = "CAN_PURCHASE";

	private final LinkRightRepository repository;

	/** The saved values, read once from the table. */
	private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

	public CatalogueRights(LinkRightRepository repository) {
		this.repository = repository;
	}

	/** May change the selling price of a head office item (task 6.5). False when never received. */
	public boolean mayChangePrices() {
		return value(MAY_CHANGE_PRICES);
	}

	/** May purchase from its own suppliers and create its own items (task 6.6). False when never received. */
	public boolean canPurchase() {
		return value(CAN_PURCHASE);
	}

	/** Null when never received (the link page shows it as unknown, the rules as off). */
	public Boolean saved(String code) {
		return repository.findByCode(code).map(LinkRight::getGranted).orElse(null);
	}

	/**
	 * A heartbeat answer (heartbeat thread): each value sent is saved when it differs from the saved one, with one INFO
	 * line. A value not sent (older head office) leaves the saved one.
	 */
	public void received(Boolean mayChangePrices, Boolean canPurchase, LocalDateTime at) {
		save(MAY_CHANGE_PRICES, mayChangePrices, at);
		save(CAN_PURCHASE, canPurchase, at);
	}

	private boolean value(String code) {
		return cache.computeIfAbsent(code, key -> Boolean.TRUE.equals(saved(key)));
	}

	private void save(String code, Boolean granted, LocalDateTime at) {
		if (granted == null) {
			return;
		}
		Optional<LinkRight> found = repository.findByCode(code);
		if (found.isPresent() && granted.equals(found.get().getGranted())) {
			cache.put(code, granted);
			return;
		}
		LinkRight right = found.orElseGet(LinkRight::new);
		right.setCode(code);
		right.setGranted(granted);
		right.setReceivedAt(at);
		repository.save(right);
		cache.put(code, granted);
		log.info("Head office link: right {} is now {}", code, granted ? "on" : "off");
	}
}
