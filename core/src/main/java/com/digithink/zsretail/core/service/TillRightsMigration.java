package com.digithink.zsretail.core.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.model.enumeration.ConfigType;
import com.digithink.zsretail.core.repository.AppRoleRepository;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;

import lombok.extern.log4j.Log4j2;

/**
 * Step C (3.1.0): the till rights move from the users' badge cards to the roles, once, on a store of before, so nothing
 * changes for anyone on the day of the upgrade. A role gets a right when every active user holding it passes that
 * action today without being asked for a badge:
 * <ul>
 * <li>an admin (the 2.2.2 rule: UserAccount.role ADMIN) passes every action, whatever the setting or the card;</li>
 * <li>anyone else passes nothing while ALWAYS_SHOW_BADGE_SCAN_POPUP is on;</li>
 * <li>else he passes the actions of his card's list when his own badge is valid (a code, not revoked, not expired),
 * except closing with a cash difference, which the server took from an admin or a badge only.</li>
 * </ul>
 * The role named ADMIN gets the five rights always (the 2.2.2 admin is never asked); a role without an active user gets
 * none. Rights are only added (a role created at this start keeps the five of its seed). The matrix is logged, the
 * retired setting row deleted, and the read-only row {@link #MARKER} written: the migration never runs again (the
 * cards are never written any more, a second run would give back what an admin removed). A head office has no till: no
 * migration there.
 */
@Component
@Log4j2
public class TillRightsMigration {

	/** The read-only General Setup row written once the till rights were given to the roles (not shown on the page). */
	public static final String MARKER = "TILL_RIGHTS_MIGRATED";

	/** Retired by step C: its value is read once here (on: nobody but an admin passed), then its row is deleted. */
	public static final String ALWAYS_SHOW_BADGE_SCAN_POPUP = "ALWAYS_SHOW_BADGE_SCAN_POPUP";

	/** The name of the built-in administrator role. */
	static final String ADMIN_ROLE = "ADMIN";

	private final JdbcTemplate jdbc;
	private final AppRoleRepository appRoleRepository;
	private final GeneralSetupRepository generalSetupRepository;
	private final ApplicationModeService applicationModeService;

	public TillRightsMigration(JdbcTemplate jdbc, AppRoleRepository appRoleRepository,
			GeneralSetupRepository generalSetupRepository, ApplicationModeService applicationModeService) {
		this.jdbc = jdbc;
		this.appRoleRepository = appRoleRepository;
		this.generalSetupRepository = generalSetupRepository;
		this.applicationModeService = applicationModeService;
	}

	/** One user's card as the till of before read it: the parts that decided whether he was asked for a badge. */
	static final class Card {
		final Long roleId;
		final boolean active;
		final boolean admin;
		final boolean validBadge;
		final Set<BadgePermission> listed;

		Card(Long roleId, boolean active, boolean admin, boolean validBadge, Set<BadgePermission> listed) {
			this.roleId = roleId;
			this.active = active;
			this.admin = admin;
			this.validBadge = validBadge;
			this.listed = listed;
		}
	}

	/** Runs once on a store (ZZDataInitializer.init, after the roles exist); nothing on a head office or a second time. */
	public void run() {
		if (applicationModeService.isHeadOffice() || generalSetupRepository.findByCode(MARKER).isPresent()) {
			return;
		}
		boolean alwaysShow = generalSetupRepository.findByCode(ALWAYS_SHOW_BADGE_SCAN_POPUP)
				.map(row -> row.getValeur() != null && "true".equalsIgnoreCase(row.getValeur().trim())).orElse(false);
		List<AppRole> roles = appRoleRepository.findAll();
		Map<Long, Set<BadgePermission>> rights = rightsByRole(roles, readCards(), alwaysShow);

		Map<String, String> matrix = new LinkedHashMap<>();
		for (AppRole role : roles) {
			Set<String> keys = rights.getOrDefault(role.getId(), EnumSet.noneOf(BadgePermission.class)).stream()
					.map(BadgePermission::key).collect(Collectors.toSet());
			Set<String> permissions = new HashSet<>(role.getPermissions() != null ? role.getPermissions() : new HashSet<>());
			if (permissions.addAll(keys)) {
				role.setPermissions(permissions);
				role.setUpdatedBy("System");
				appRoleRepository.save(role);
			}
			matrix.put(role.getName(), tillRightsOf(permissions));
		}

		log.info("Till rights given to the roles from the users' badges (ALWAYS_SHOW_BADGE_SCAN_POPUP was {}): {}",
				alwaysShow ? "on" : "off", matrix);

		generalSetupRepository.findByCode(ALWAYS_SHOW_BADGE_SCAN_POPUP).ifPresent(generalSetupRepository::delete);
		writeMarker(matrix);
	}

	/** The till rights of a role's permissions, short: "CL MR LD TD CS" in the enum's order, "-" for none. */
	static String tillRightsOf(Collection<String> permissions) {
		List<String> held = new ArrayList<>();
		for (BadgePermission action : BadgePermission.values()) {
			if (permissions.contains(action.key())) {
				held.add(action.key().substring("till:".length()));
			}
		}
		return held.isEmpty() ? "-" : String.join(" ", held);
	}

	/**
	 * The rights of each role (by id): the actions every active user of the role passes today without a badge; the five
	 * for the role named ADMIN; none for a role without an active user.
	 */
	static Map<Long, Set<BadgePermission>> rightsByRole(Collection<AppRole> roles, Collection<Card> cards,
			boolean alwaysShow) {
		Map<Long, Set<BadgePermission>> rights = new LinkedHashMap<>();
		for (AppRole role : roles) {
			if (ADMIN_ROLE.equals(role.getName())) {
				rights.put(role.getId(), EnumSet.allOf(BadgePermission.class));
				continue;
			}
			Set<BadgePermission> common = null;
			for (Card card : cards) {
				if (!card.active || card.roleId == null || !card.roleId.equals(role.getId())) {
					continue;
				}
				Set<BadgePermission> passed = passedToday(card, alwaysShow);
				if (common == null) {
					common = EnumSet.noneOf(BadgePermission.class);
					common.addAll(passed);
				} else {
					common.retainAll(passed);
				}
			}
			rights.put(role.getId(), common == null ? EnumSet.noneOf(BadgePermission.class) : common);
		}
		return rights;
	}

	/** The actions this user passed before step C without being asked for a badge (the rule of the class comment). */
	static Set<BadgePermission> passedToday(Card card, boolean alwaysShow) {
		if (card.admin) {
			return EnumSet.allOf(BadgePermission.class);
		}
		if (alwaysShow || !card.validBadge) {
			return EnumSet.noneOf(BadgePermission.class);
		}
		Set<BadgePermission> passed = EnumSet.noneOf(BadgePermission.class);
		passed.addAll(card.listed);
		passed.remove(BadgePermission.CLOSE_SESSION_WITH_DISCREPANCY); // the server took a badge or an admin only
		return passed;
	}

	/** The list of a card as the till parsed it: split on commas, trimmed, the exact constant names (others ignored). */
	static Set<BadgePermission> parseList(String list) {
		Set<BadgePermission> parsed = EnumSet.noneOf(BadgePermission.class);
		if (list == null) {
			return parsed;
		}
		for (String token : list.split(",")) {
			String name = token.trim();
			for (BadgePermission action : BadgePermission.values()) {
				if (action.name().equals(name)) {
					parsed.add(action);
				}
			}
		}
		return parsed;
	}

	/**
	 * The cards of user_account, by JDBC: badge_permissions is no longer an entity field (step C), and the role column
	 * may be gone on a database created without it (step E): then the admin is the user of the role named ADMIN.
	 */
	List<Card> readCards() {
		boolean hasList = columnExists("badge_permissions");
		boolean hasRole = columnExists("role");
		String sql = "SELECT u.app_role_id, u.active, u.badge_code, u.badge_revoked, u.badge_expiration_date, "
				+ (hasList ? "u.badge_permissions" : "NULL") + " AS badge_permissions, "
				+ (hasRole ? "u.role" : "NULL") + " AS role, r.name AS role_name "
				+ "FROM user_account u LEFT JOIN app_role r ON r.id = u.app_role_id ORDER BY u.id";
		LocalDateTime now = LocalDateTime.now();
		return jdbc.query(sql, (rs, rowNum) -> {
			Object roleId = rs.getObject("app_role_id");
			Object active = rs.getObject("active");
			String code = rs.getString("badge_code");
			Object revoked = rs.getObject("badge_revoked");
			java.sql.Timestamp expiration = rs.getTimestamp("badge_expiration_date");
			String role = rs.getString("role");
			boolean admin = role != null ? ADMIN_ROLE.equals(role) : ADMIN_ROLE.equals(rs.getString("role_name"));
			boolean validBadge = code != null && !code.trim().isEmpty() && !Boolean.TRUE.equals(asBoolean(revoked))
					&& (expiration == null || !now.isAfter(expiration.toLocalDateTime()));
			return new Card(roleId == null ? null : ((Number) roleId).longValue(), !Boolean.FALSE.equals(asBoolean(active)),
					admin, validBadge, parseList(rs.getString("badge_permissions")));
		});
	}

	private static Boolean asBoolean(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		return ((Number) value).intValue() != 0;
	}

	private boolean columnExists(String column) {
		Integer length = jdbc.queryForObject("SELECT COL_LENGTH('user_account', '" + column + "')", Integer.class);
		return length != null;
	}

	private void writeMarker(Map<String, String> matrix) {
		String value = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substring(0, 16) + " " + matrix;
		GeneralSetup marker = new GeneralSetup();
		marker.setCode(MARKER);
		marker.setValeur(value.length() > 255 ? value.substring(0, 255) : value);
		marker.setDescription("Step C (3.1.0): the till rights were given to the roles from the users' badges, once (read only).");
		marker.setReadOnly(true);
		marker.setConfigType(ConfigType.STRING);
		marker.setActive(true);
		marker.setCreatedBy("System");
		marker.setUpdatedBy("System");
		generalSetupRepository.save(marker);
	}
}
