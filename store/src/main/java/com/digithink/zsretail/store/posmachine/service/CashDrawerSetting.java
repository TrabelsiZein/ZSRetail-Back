package com.digithink.zsretail.store.posmachine.service;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.controller.AppConfigAPI;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.store.posmachine.DrawerOwner;

/**
 * 3.2.0, POS machines step 2, part B: the General setup choice "Le tiroir-caisse appartient" (CASH_DRAWER_OWNER, a store
 * row seeded by ZZDataInitializer, CASHIER by default), read at each use: no row, a blank or unknown value is CASHIER, the
 * behaviour of before.
 */
@Component
public class CashDrawerSetting {

	private final GeneralSetupRepository setups;

	public CashDrawerSetting(GeneralSetupRepository setups) {
		this.setups = setups;
	}

	/** Who the drawer belongs to now. */
	public DrawerOwner owner() {
		return DrawerOwner.of(setups.findByCode(AppConfigAPI.CASH_DRAWER_OWNER).map(GeneralSetup::getValeur).orElse(null));
	}

	/** True when the drawer belongs to the POS machine (the rules apply). */
	public boolean toPosMachine() {
		return owner() == DrawerOwner.POS_MACHINE;
	}
}
