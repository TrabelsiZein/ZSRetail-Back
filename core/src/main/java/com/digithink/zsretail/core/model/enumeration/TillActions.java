package com.digithink.zsretail.core.model.enumeration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.digithink.zsretail.core.dto.TillActionDTO;

/**
 * Step B (3.1.0): the registry of the till actions, defined once here (the constants of {@link BadgePermission} and
 * their role keys) and served by GET /config as tillActions, so the frontend keeps no list of its own (badge service,
 * Users page, badge popup, scan history filter; the Roles page at step C).
 */
public final class TillActions {

	private TillActions() {
	}

	/** Every action, in the enum's order: {code: the constant name, key: the role permission key, checkedOnRequest}. */
	public static List<TillActionDTO> all() {
		List<TillActionDTO> actions = new ArrayList<>();
		for (BadgePermission action : BadgePermission.values()) {
			actions.add(new TillActionDTO(action.name(), action.key(), action.checkedOnRequest()));
		}
		return Collections.unmodifiableList(actions);
	}
}
