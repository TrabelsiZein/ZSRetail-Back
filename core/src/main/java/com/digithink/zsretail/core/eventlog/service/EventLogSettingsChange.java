package com.digithink.zsretail.core.eventlog.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.service.GeneralSetupChangeListener;
import com.digithink.zsretail.erp.api.GeneralSetupValueCheck;

/**
 * 3.4.0, event log step 8: the five settings of the event log (EventLogSettings) on the General setup page.
 * <ul>
 * <li>{@link #check}: a value that does not fit is refused (400 with its reason): the level STEP or NOTABLE, a duration
 * of 1 to 600 months or empty, the switch true or false.</li>
 * <li>{@link #changed}: EVENTLOG_SETTINGS_CHANGED (Alert) with before and after, written once the save has committed,
 * and the settings read again then. An Alert: lowering the level or shortening a duration is how traces would be made
 * to go; it must stand out, and it is never deleted by a cleanup (EventLogCleanup).</li>
 * </ul>
 */
@Component
public class EventLogSettingsChange implements GeneralSetupValueCheck, GeneralSetupChangeListener {

	private final EventLog eventLog;
	private final EventLogSettings settings;

	public EventLogSettingsChange(EventLog eventLog, EventLogSettings settings) {
		this.eventLog = eventLog;
		this.settings = settings;
	}

	@Override
	public void check(String code, String value) {
		if (!EventLogSettings.CODES.contains(code)) {
			return;
		}
		String v = value == null ? "" : value.trim();
		if (EventLogSettings.STORE_FROM.equals(code)) {
			if (!EventLogSettings.LEVEL_STEP.equals(v) && !EventLogSettings.LEVEL_NOTABLE.equals(v)) {
				throw new Refused("EVENTLOG_LEVEL_INVALID", "The event log's level is STEP or NOTABLE, not '" + v + "'.", v);
			}
		} else if (EventLogSettings.AUTO_CLEANUP.equals(code)) {
			if (!"true".equals(v) && !"false".equals(v)) {
				throw new Refused("EVENTLOG_SWITCH_INVALID", "The automatic cleanup is true or false, not '" + v + "'.", v);
			}
		} else if (!v.isEmpty() && EventLogSettings.months(v) == null) {
			throw new Refused("EVENTLOG_DURATION_INVALID", "A duration is 1 to " + EventLogSettings.MAX_MONTHS
					+ " months, or empty (kept forever), not '" + v + "'.", v);
		}
	}

	@Override
	public void changed(String code, String oldValue, String newValue) {
		if (!EventLogSettings.CODES.contains(code)) {
			return;
		}
		eventLog.write(NewEvent.of(EventCode.EVENTLOG_SETTINGS_CHANGED).subject(code, label(code))
				.change(text(code, oldValue), text(code, newValue))
				.details("Réglage du journal changé : " + label(code) + ", " + text(code, oldValue) + " → "
						+ text(code, newValue) + "."));
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
				@Override
				public void afterCommit() {
					settings.refresh();
				}
			});
		} else {
			settings.refresh();
		}
	}

	/**
	 * A value refused, with its code (the front shows serverErrors.<code> in the screen's language, the field named by its
	 * label): {value} the value typed, {max} the longest duration. 400 by General setup, as every refusal of a setting.
	 */
	static final class Refused extends IllegalStateException implements com.digithink.zsretail.core.exception.CodedRefusal {
		private static final long serialVersionUID = 1L;
		private final String code;
		private final java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();

		Refused(String code, String message, String value) {
			super(message);
			this.code = code;
			params.put("value", value);
			params.put("max", EventLogSettings.MAX_MONTHS);
		}

		@Override
		public String getCode() {
			return code;
		}

		@Override
		public java.util.Map<String, Object> getParams() {
			return params;
		}
	}

	/** The setting's name, as the tab shows it in French. */
	static String label(String code) {
		switch (code) {
			case EventLogSettings.STORE_FROM:
				return "Enregistrer à partir de";
			case EventLogSettings.KEEP_STEP_MONTHS:
				return "Durée de conservation des Étapes";
			case EventLogSettings.KEEP_NOTABLE_MONTHS:
				return "Durée de conservation des Notables";
			case EventLogSettings.KEEP_ALERT_MONTHS:
				return "Durée de conservation des Alertes";
			case EventLogSettings.AUTO_CLEANUP:
				return "Nettoyage automatique";
			default:
				return code;
		}
	}

	/** A value as a person reads it: "Étape", "24 mois", "sans limite", "activé". */
	static String text(String code, String value) {
		String v = value == null ? "" : value.trim();
		if (EventLogSettings.STORE_FROM.equals(code)) {
			return EventLogSettings.LEVEL_NOTABLE.equals(v) ? "Notable" : EventLogSettings.LEVEL_STEP.equals(v) ? "Étape" : v;
		}
		if (EventLogSettings.AUTO_CLEANUP.equals(code)) {
			return "true".equals(v) ? "activé" : "désactivé";
		}
		return v.isEmpty() ? "sans limite" : v + " mois";
	}
}
