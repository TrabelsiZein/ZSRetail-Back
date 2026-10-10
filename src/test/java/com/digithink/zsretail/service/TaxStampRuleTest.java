package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.repository.ItemRepository;

/**
 * 2.2.1, step 5: one tax stamp rule for both sides (TaxStampRule): ENABLE_TAX_STAMP reads as on with true in any case
 * or 1, AND the TAX_STAMP item exists. GET /config sends it as taxStampActive (AppConfigAPITest), the sale adds its line
 * by the same reading.
 */
public class TaxStampRuleTest {

	/** The rule over a database holding ENABLE_TAX_STAMP = value (null: no row) and, or not, the TAX_STAMP item. */
	public static TaxStampRule rule(String value, boolean itemExists) throws Exception {
		GeneralSetupRepository setup = mock(GeneralSetupRepository.class);
		GeneralSetup row = null;
		if (value != null) {
			row = new GeneralSetup();
			row.setCode(TaxStampRule.SETTING);
			row.setValeur(value);
		}
		when(setup.findByCode(TaxStampRule.SETTING)).thenReturn(Optional.ofNullable(row));
		ItemRepository items = mock(ItemRepository.class);
		Item stamp = new Item();
		stamp.setItemCode(TaxStampRule.ITEM_CODE);
		when(items.findByItemCode(TaxStampRule.ITEM_CODE)).thenReturn(itemExists ? Optional.of(stamp) : Optional.empty());
		TaxStampRule rule = new TaxStampRule();
		inject(rule, "generalSetupRepository", setup);
		inject(rule, "itemRepository", items);
		return rule;
	}

	@Test
	@DisplayName("A store with the normal value: true (what the General Setup screen writes) and the item is active, false is not; as in 2.2.0")
	void normalStoreUnchanged() throws Exception {
		assertTrue(rule("true", true).active());
		assertEquals(TaxStampRule.ITEM_CODE, rule("true", true).activeItem().get().getItemCode());
		assertFalse(rule("false", true).active());
		assertFalse(rule(null, true).active(), "no row: off, as in 2.2.0");
	}

	@Test
	@DisplayName("true, TRUE, True and 1 (spaces around ignored) read as on; anything else as off")
	void readings() throws Exception {
		for (String on : Arrays.asList("true", "TRUE", "True", "1", " true ", " 1")) {
			assertTrue(TaxStampRule.settingOn(on), on);
			assertTrue(rule(on, true).active(), on);
		}
		for (String off : Arrays.asList("false", "FALSE", "0", "", "yes", "on", "2", "01")) {
			assertFalse(TaxStampRule.settingOn(off), off);
			assertFalse(rule(off, true).active(), off);
		}
		assertFalse(TaxStampRule.settingOn(null));
	}

	@Test
	@DisplayName("Setting on but no TAX_STAMP item: not active (the server never adds the line, so the till must not either)")
	void itemMissing() throws Exception {
		assertFalse(rule("true", false).active());
		assertFalse(rule("1", false).active());
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception {
		Field field = TaxStampRule.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
