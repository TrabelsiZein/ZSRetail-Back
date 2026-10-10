package com.digithink.zsretail.service;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.repository.ItemRepository;

/**
 * 2.2.1, step 5: the one rule of the tax stamp (timbre fiscal) for both sides. The stamp is active when the General
 * Setup ENABLE_TAX_STAMP reads as on (true in any case, or 1) AND the TAX_STAMP item exists. The sale adds its line
 * by this rule, and GET /config sends it as taxStampActive: the till adds the amount only on that boolean. Until
 * 2.2.0 the till read 'true' or '1' and the server "true" (any case) plus the item, so a store with 1, or without the
 * item, showed a total the server did not record. For the value the General Setup screen writes (true / false) and an
 * existing item, nothing changes.
 */
@Service
public class TaxStampRule {

	public static final String SETTING = "ENABLE_TAX_STAMP";

	public static final String ITEM_CODE = "TAX_STAMP";

	@Autowired
	private GeneralSetupRepository generalSetupRepository;

	@Autowired
	private ItemRepository itemRepository;

	/** The setting reads as on: true, TRUE (any case) or 1, spaces around ignored. */
	public static boolean settingOn(String value) {
		if (value == null) {
			return false;
		}
		String trimmed = value.trim();
		return "true".equalsIgnoreCase(trimmed) || "1".equals(trimmed);
	}

	/** The TAX_STAMP item when the stamp is active, otherwise empty. */
	public Optional<Item> activeItem() {
		String value = generalSetupRepository.findByCode(SETTING).map(GeneralSetup::getValeur).orElse(null);
		if (!settingOn(value)) {
			return Optional.empty();
		}
		return itemRepository.findByItemCode(ITEM_CODE);
	}

	/** The boolean of GET /config. */
	public boolean active() {
		return activeItem().isPresent();
	}
}
