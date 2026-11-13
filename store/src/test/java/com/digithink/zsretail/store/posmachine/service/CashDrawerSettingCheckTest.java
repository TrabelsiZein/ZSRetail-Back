package com.digithink.zsretail.store.posmachine.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.controller.AppConfigAPI;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.store.posmachine.DrawerOwner;
import com.digithink.zsretail.store.posmachine.DrawerRuleException;
import com.digithink.zsretail.store.support.InMemoryRfid;

/**
 * 3.2.0, POS machines step 2, part B, rule 5: the choice "Le tiroir-caisse appartient" (CASH_DRAWER_OWNER) is CASHIER or
 * POS_MACHINE and does not change while a session is open (either way); saving the value it has passes. And how the
 * setting is read (CashDrawerSetting): no row, a blank or unknown value is CASHIER.
 */
class CashDrawerSettingCheckTest {

	private final InMemoryRfid rfid = new InMemoryRfid();
	private final CashDrawerSettingCheck check = new CashDrawerSettingCheck(rfid.drawer, rfid.cashierSessionRepository);

	@Test
	@DisplayName("Another setting: never looked at")
	void otherSetting() {
		rfid.session(1L, "amira", SessionStatus.OPENED, null);
		assertDoesNotThrow(() -> check.check("TILL_GRANT_MAX_MINUTES", "anything"));
	}

	@Test
	@DisplayName("No session open: CASHIER -> POS_MACHINE and back, any case")
	void changeWithoutOpenSession() {
		rfid.session(1L, "amira", SessionStatus.TERMINATED, null);
		rfid.session(2L, "sami", SessionStatus.CLOSED, null);
		assertDoesNotThrow(() -> check.check(AppConfigAPI.CASH_DRAWER_OWNER, "POS_MACHINE"));
		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		assertDoesNotThrow(() -> check.check(AppConfigAPI.CASH_DRAWER_OWNER, " cashier "));
	}

	@Test
	@DisplayName("A session open: the change refused either way, with the number of open sessions; the same value passes")
	void changeWithOpenSession() {
		rfid.session(1L, "amira", SessionStatus.OPENED, null);
		rfid.session(2L, "sami", SessionStatus.OPENED, null);
		DrawerRuleException toMachine = assertThrows(DrawerRuleException.class,
				() -> check.check(AppConfigAPI.CASH_DRAWER_OWNER, "POS_MACHINE"));
		assertEquals("CASH_DRAWER_OWNER_SESSIONS_OPEN", toMachine.getCode());
		assertEquals(2L, toMachine.getParams().get("count"));
		assertEquals("Who the drawer belongs to cannot change while a session is open (2 open): close every session"
				+ " first.", toMachine.getMessage());
		assertDoesNotThrow(() -> check.check(AppConfigAPI.CASH_DRAWER_OWNER, "CASHIER"), "the value it has");

		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		assertEquals("CASH_DRAWER_OWNER_SESSIONS_OPEN", assertThrows(DrawerRuleException.class,
				() -> check.check(AppConfigAPI.CASH_DRAWER_OWNER, "CASHIER")).getCode());
		assertDoesNotThrow(() -> check.check(AppConfigAPI.CASH_DRAWER_OWNER, "pos_machine"), "the value it has");
	}

	@Test
	@DisplayName("Not a choice: refused (CASH_DRAWER_OWNER_INVALID)")
	void invalid() {
		DrawerRuleException e = assertThrows(DrawerRuleException.class,
				() -> check.check(AppConfigAPI.CASH_DRAWER_OWNER, "BOTH"));
		assertEquals("CASH_DRAWER_OWNER_INVALID", e.getCode());
		assertEquals("BOTH", e.getParams().get("value"));
		assertEquals("CASH_DRAWER_OWNER_INVALID", assertThrows(DrawerRuleException.class,
				() -> check.check(AppConfigAPI.CASH_DRAWER_OWNER, null)).getCode());
	}

	@Test
	@DisplayName("The setting read: POS_MACHINE in any case; no row, blank or unknown: CASHIER (the behaviour of before)")
	void settingRead() {
		String[] value = { null };
		boolean[] row = { false };
		GeneralSetupRepository setups = (GeneralSetupRepository) java.lang.reflect.Proxy.newProxyInstance(
				GeneralSetupRepository.class.getClassLoader(), new Class<?>[] { GeneralSetupRepository.class },
				(proxy, method, args) -> {
					if (!"findByCode".equals(method.getName()) || !AppConfigAPI.CASH_DRAWER_OWNER.equals(args[0])) {
						throw new UnsupportedOperationException(method.getName());
					}
					if (!row[0]) {
						return Optional.empty();
					}
					GeneralSetup setup = new GeneralSetup();
					setup.setCode(AppConfigAPI.CASH_DRAWER_OWNER);
					setup.setValeur(value[0]);
					return Optional.of(setup);
				});
		CashDrawerSetting setting = new CashDrawerSetting(setups);
		assertEquals(DrawerOwner.CASHIER, setting.owner(), "no row");
		row[0] = true;
		for (String text : new String[] { null, "", "  ", "CASHIER", "machine", "POS MACHINE" }) {
			value[0] = text;
			assertEquals(DrawerOwner.CASHIER, setting.owner(), String.valueOf(text));
		}
		value[0] = " pos_machine ";
		assertEquals(DrawerOwner.POS_MACHINE, setting.owner());
	}
}
