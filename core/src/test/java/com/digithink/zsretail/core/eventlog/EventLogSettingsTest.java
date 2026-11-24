package com.digithink.zsretail.core.eventlog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.eventlog.service.EventLogSettings;
import com.digithink.zsretail.core.eventlog.service.EventLogSettingsChange;
import com.digithink.zsretail.core.eventlog.service.NewEvent;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.support.InMemoryEventLog;

/**
 * 3.4.0, event log step 8: the level (Notable: no Step written, no number taken, so no hole), the settings read with
 * their defaults, the values refused, and the change of a setting written with before and after (Alert).
 */
class EventLogSettingsTest {

	final InMemoryEventLog log = new InMemoryEventLog();

	@Test
	@DisplayName("Level Notable: the Steps are not written and take no number (no hole); Notable and Alert numbered on")
	void levelNotable() {
		log.eventLog().writeNow(NewEvent.of(EventCode.LOGIN_OK).details("a"));
		log.eventLog().writeNow(NewEvent.of(EventCode.CART_LINE_DELETED).details("b"));
		log.minImportance = EventImportance.NOTABLE;
		assertFalse(log.eventLog().stores(EventCode.SALE_VALIDATED));
		assertTrue(log.eventLog().stores(EventCode.RETURN_DONE));
		log.eventLog().writeNow(NewEvent.of(EventCode.LOGIN_OK).details("c"));
		log.eventLog().write(NewEvent.of(EventCode.SALE_VALIDATED).details("d"));
		log.eventLog().writeNow(NewEvent.of(EventCode.CART_CLEARED).details("e"));
		log.eventLog().writeNow(NewEvent.of(EventCode.RECEIPT_REPRINTED).details("f"));
		assertEquals(Arrays.asList("LOGIN_OK", "CART_LINE_DELETED", "CART_CLEARED", "RECEIPT_REPRINTED"), log.codes());
		assertEquals(Long.valueOf(1), log.numbers.get(EventImportance.STEP), "the Step counter did not move: no hole");
		assertEquals(Long.valueOf(2), log.only("CART_CLEARED").getSequenceNo());
		log.minImportance = EventImportance.STEP;
		log.eventLog().writeNow(NewEvent.of(EventCode.LOGIN_OK).details("g"));
		assertEquals(Long.valueOf(2), log.rows.get(log.rows.size() - 1).getSequenceNo(), "the next Step is number 2");
	}

	private static GeneralSetupRepository rows(Map<String, String> values) {
		return (GeneralSetupRepository) java.lang.reflect.Proxy.newProxyInstance(EventLogSettingsTest.class.getClassLoader(),
				new Class<?>[] { GeneralSetupRepository.class }, (proxy, method, args) -> {
					if ("findByCode".equals(method.getName())) {
						String value = values.get(args[0]);
						if (value == null) {
							return Optional.empty();
						}
						GeneralSetup row = new GeneralSetup();
						row.setCode((String) args[0]);
						row.setValeur(value);
						return Optional.of(row);
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}

	@Test
	@DisplayName("The settings: the defaults without rows (Step, 3 / 24 / 24 months, no night cleanup); the rows read;"
			+ " an empty duration is forever, an unreadable one too (never deletes by mistake)")
	void settingsRead() {
		EventLogSettings none = new EventLogSettings(rows(new HashMap<>()));
		none.refresh();
		assertEquals(EventImportance.STEP, none.minImportance());
		assertEquals(Integer.valueOf(3), none.keepMonths(EventImportance.STEP));
		assertEquals(Integer.valueOf(24), none.keepMonths(EventImportance.ALERT));
		assertFalse(none.autoCleanup());

		Map<String, String> values = new HashMap<>();
		values.put(EventLogSettings.STORE_FROM, "NOTABLE");
		values.put(EventLogSettings.KEEP_STEP_MONTHS, "6");
		values.put(EventLogSettings.KEEP_NOTABLE_MONTHS, "");
		values.put(EventLogSettings.KEEP_ALERT_MONTHS, "beaucoup");
		values.put(EventLogSettings.AUTO_CLEANUP, "true");
		EventLogSettings set = new EventLogSettings(rows(values));
		set.refresh();
		assertEquals(EventImportance.NOTABLE, set.minImportance());
		assertFalse(set.stores(EventCode.CART_LINE_ADDED));
		assertEquals(Integer.valueOf(6), set.keepMonths(EventImportance.STEP));
		assertNull(set.keepMonths(EventImportance.NOTABLE));
		assertNull(set.keepMonths(EventImportance.ALERT));
		assertTrue(set.autoCleanup());
	}

	@Test
	@DisplayName("The values refused (400): a level other than STEP / NOTABLE, a duration not 1 to 600 months, a switch"
			+ " not true / false; another setting untouched")
	void checked() {
		EventLogSettingsChange change = new EventLogSettingsChange(log.eventLog(), new EventLogSettings(10, new HashMap<>(), false));
		assertDoesNotThrow(() -> change.check(EventLogSettings.STORE_FROM, "NOTABLE"));
		assertThrows(IllegalStateException.class, () -> change.check(EventLogSettings.STORE_FROM, "ALERT"));
		assertDoesNotThrow(() -> change.check(EventLogSettings.KEEP_STEP_MONTHS, ""));
		assertDoesNotThrow(() -> change.check(EventLogSettings.KEEP_STEP_MONTHS, "12"));
		IllegalStateException zero = assertThrows(IllegalStateException.class,
				() -> change.check(EventLogSettings.KEEP_STEP_MONTHS, "0"));
		assertEquals("EVENTLOG_DURATION_INVALID", ((com.digithink.zsretail.core.exception.CodedRefusal) zero).getCode(),
				"a coded refusal: the screen says it in its language");
		assertEquals("0", ((com.digithink.zsretail.core.exception.CodedRefusal) zero).getParams().get("value"));
		assertThrows(IllegalStateException.class, () -> change.check(EventLogSettings.KEEP_ALERT_MONTHS, "601"));
		assertThrows(IllegalStateException.class, () -> change.check(EventLogSettings.KEEP_ALERT_MONTHS, "-3"));
		assertThrows(IllegalStateException.class, () -> change.check(EventLogSettings.AUTO_CLEANUP, "oui"));
		assertDoesNotThrow(() -> change.check("MAX_DAYS_FOR_RETURN", "whatever"));
	}

	@Test
	@DisplayName("A setting changed: EVENTLOG_SETTINGS_CHANGED (Alert), before and after in words; another setting writes"
			+ " nothing")
	void changeWritten() {
		EventLogSettingsChange change = new EventLogSettingsChange(log.eventLog(), new EventLogSettings(10, new HashMap<>(), false));
		change.changed(EventLogSettings.STORE_FROM, "STEP", "NOTABLE");
		change.changed(EventLogSettings.KEEP_STEP_MONTHS, "3", "");
		change.changed("MAX_DAYS_FOR_RETURN", "10", "30");
		assertEquals(Arrays.asList("EVENTLOG_SETTINGS_CHANGED", "EVENTLOG_SETTINGS_CHANGED"), log.codes());
		EventLogEntry level = log.rows.get(0);
		assertEquals(EventImportance.ALERT, level.getImportance());
		assertEquals("Étape", level.getOldValue());
		assertEquals("Notable", level.getNewValue());
		assertEquals("Réglage du journal changé : Enregistrer à partir de, Étape → Notable.", level.getDetails());
		assertEquals("Réglage du journal changé : Durée de conservation des Étapes, 3 mois → sans limite.",
				log.rows.get(1).getDetails());
	}
}
