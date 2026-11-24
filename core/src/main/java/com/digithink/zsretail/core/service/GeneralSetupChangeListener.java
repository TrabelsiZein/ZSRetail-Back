package com.digithink.zsretail.core.service;

/**
 * 3.4.0, event log step 8: told when an administrator changed a General setup row (GeneralSetupService.updateFromAdmin,
 * after the save, the value different), inside the save's transaction. Used by EventLogSettingsChange (the event and the
 * settings read again). A listener that throws refuses the save.
 */
public interface GeneralSetupChangeListener {

	void changed(String code, String oldValue, String newValue);
}
