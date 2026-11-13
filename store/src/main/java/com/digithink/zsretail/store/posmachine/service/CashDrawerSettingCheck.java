package com.digithink.zsretail.store.posmachine.service;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.controller.AppConfigAPI;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.erp.api.GeneralSetupValueCheck;
import com.digithink.zsretail.store.posmachine.DrawerOwner;
import com.digithink.zsretail.store.posmachine.DrawerRuleException;

/**
 * 3.2.0, POS machines step 2, part B, rule 5: the choice "Le tiroir-caisse appartient" (CASH_DRAWER_OWNER) is CASHIER or
 * POS_MACHINE, and it does not change while any session is open (whatever the way: to POS_MACHINE, a session opened
 * before would have no machine; to CASHIER, a session opened under the rules would lose them in the middle). Saving the
 * value it has is never refused. A refusal is a DrawerRuleException, answered 400 by General setup with its code.
 */
@Component
public class CashDrawerSettingCheck implements GeneralSetupValueCheck {

	private final CashDrawerSetting drawer;
	private final CashierSessionRepository sessions;

	public CashDrawerSettingCheck(CashDrawerSetting drawer, CashierSessionRepository sessions) {
		this.drawer = drawer;
		this.sessions = sessions;
	}

	@Override
	public void check(String code, String value) {
		if (!AppConfigAPI.CASH_DRAWER_OWNER.equals(code)) {
			return;
		}
		String wanted = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
		if (!DrawerOwner.CASHIER.name().equals(wanted) && !DrawerOwner.POS_MACHINE.name().equals(wanted)) {
			throw new DrawerRuleException("CASH_DRAWER_OWNER_INVALID",
					"'" + (value == null ? "" : value.trim()) + "' is not a choice: CASHIER or POS_MACHINE.", "value",
					value == null ? "" : value.trim());
		}
		if (drawer.owner().name().equals(wanted)) {
			return;
		}
		long open = sessions.countByStatus(SessionStatus.OPENED);
		if (open > 0) {
			throw new DrawerRuleException("CASH_DRAWER_OWNER_SESSIONS_OPEN", "Who the drawer belongs to cannot change while"
					+ " a session is open (" + open + " open): close every session first.", "count", open);
		}
	}
}
