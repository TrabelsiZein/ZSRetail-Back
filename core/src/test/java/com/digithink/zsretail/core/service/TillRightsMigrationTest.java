package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.repository.AppRoleRepository;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.service.TillRightsMigration.Card;
import com.digithink.zsretail.core.support.TestModes;

/**
 * Step C (3.1.0): the till rights of the users' badge cards given to their roles, once. The rule alone (who passed what
 * without a badge before step C), then the whole run on H2 (MODE=MSSQLServer, COL_LENGTH as an alias): the cards read
 * by JDBC, the rights only added, the matrix in the marker, the retired setting row deleted, nothing on a head office or
 * a second time.
 */
public class TillRightsMigrationTest {

	private static final Set<BadgePermission> ALL = EnumSet.allOf(BadgePermission.class);
	private static final Set<BadgePermission> NONE = EnumSet.noneOf(BadgePermission.class);

	private static Set<BadgePermission> of(BadgePermission... actions) {
		Set<BadgePermission> set = EnumSet.noneOf(BadgePermission.class);
		set.addAll(Arrays.asList(actions));
		return set;
	}

	private static Card card(Long roleId, boolean active, boolean admin, boolean validBadge, BadgePermission... listed) {
		return new Card(roleId, active, admin, validBadge, of(listed));
	}

	private static AppRole role(long id, String name, String... permissions) {
		AppRole role = new AppRole(name, name, "POS_USER".equals(name));
		role.setId(id);
		role.setPermissions(new HashSet<>(Arrays.asList(permissions)));
		return role;
	}

	// ─── The rule ────────────────────────────────────────────────────

	@Test
	@DisplayName("An admin passed every action, also with the setting on and without a badge; anyone else nothing with the setting on")
	void adminAndSetting() {
		assertEquals(ALL, TillRightsMigration.passedToday(card(1L, true, true, false), false));
		assertEquals(ALL, TillRightsMigration.passedToday(card(1L, true, true, false), true));
		assertEquals(NONE, TillRightsMigration.passedToday(card(2L, true, false, true, BadgePermission.values()), true));
	}

	@Test
	@DisplayName("Setting off: the actions of the card when the badge is valid, never the close with a difference (badge or admin only); no valid badge: nothing")
	void cardListWithAValidBadge() {
		assertEquals(of(BadgePermission.CONSULT_CUSTOMER_LIST, BadgePermission.MAKE_RETURN,
				BadgePermission.APPLY_LINE_DISCOUNT, BadgePermission.APPLY_TOTAL_DISCOUNT),
				TillRightsMigration.passedToday(card(2L, true, false, true, BadgePermission.values()), false));
		assertEquals(of(BadgePermission.MAKE_RETURN), TillRightsMigration.passedToday(
				card(2L, true, false, true, BadgePermission.MAKE_RETURN), false));
		assertEquals(NONE, TillRightsMigration.passedToday(card(2L, true, false, false, BadgePermission.values()), false));
		assertEquals(NONE, TillRightsMigration.passedToday(card(2L, true, false, true), false));
	}

	@Test
	@DisplayName("The card's list as the till read it: split on commas, trimmed, the exact names only")
	void cardListParsing() {
		assertEquals(of(BadgePermission.MAKE_RETURN, BadgePermission.APPLY_LINE_DISCOUNT), TillRightsMigration
				.parseList(" MAKE_RETURN,APPLY_LINE_DISCOUNT ,make_return, ,UNKNOWN,APPLY_LINE_DISCOUNT"));
		assertEquals(NONE, TillRightsMigration.parseList(null));
		assertEquals(NONE, TillRightsMigration.parseList(""));
	}

	@Test
	@DisplayName("A role: what every active user passes (the intersection); inactive users and users of another role ignored; no active user: nothing; ADMIN: the five")
	void rightsByRole() {
		AppRole admin = role(1, "ADMIN");
		AppRole responsible = role(2, "RESPONSIBLE");
		AppRole cashier = role(3, "POS_USER");
		AppRole empty = role(4, "VIDE");
		List<Card> cards = Arrays.asList(
				card(2L, true, false, true, BadgePermission.values()),
				card(2L, true, false, true, BadgePermission.MAKE_RETURN, BadgePermission.APPLY_TOTAL_DISCOUNT,
						BadgePermission.CONSULT_CUSTOMER_LIST),
				card(2L, false, false, false), // inactive, no badge: ignored
				card(3L, true, false, false, BadgePermission.MAKE_RETURN), // a cashier without a badge
				card(3L, true, false, true, BadgePermission.MAKE_RETURN),
				card(4L, false, true, true, BadgePermission.values()), // the only user of VIDE is inactive
				card(null, true, true, true)); // no role: ignored

		Map<Long, Set<BadgePermission>> rights = TillRightsMigration.rightsByRole(
				Arrays.asList(admin, responsible, cashier, empty), cards, false);

		assertEquals(ALL, rights.get(1L), "ADMIN without any card");
		assertEquals(of(BadgePermission.CONSULT_CUSTOMER_LIST, BadgePermission.MAKE_RETURN,
				BadgePermission.APPLY_TOTAL_DISCOUNT), rights.get(2L));
		assertEquals(NONE, rights.get(3L), "one cashier is asked for a badge today");
		assertEquals(NONE, rights.get(4L), "no active user");

		Map<Long, Set<BadgePermission>> settingOn = TillRightsMigration.rightsByRole(
				Arrays.asList(admin, responsible, cashier), cards, true);
		assertEquals(ALL, settingOn.get(1L));
		assertEquals(NONE, settingOn.get(2L));
		assertEquals(NONE, settingOn.get(3L));

		// an admin of the 2.2.2 rule in a custom role: his role gets what the others of the role pass
		Map<Long, Set<BadgePermission>> mixed = TillRightsMigration.rightsByRole(Collections.singletonList(responsible),
				Arrays.asList(card(2L, true, true, false), card(2L, true, false, true, BadgePermission.MAKE_RETURN)),
				false);
		assertEquals(of(BadgePermission.MAKE_RETURN), mixed.get(2L));
	}

	@Test
	@DisplayName("The matrix, short: the kebab names in the enum's order, '-' for none")
	void matrixText() {
		assertEquals("-", TillRightsMigration.tillRightsOf(Collections.singleton("read:home")));
		assertEquals("consult-customer-list make-return apply-line-discount apply-total-discount"
				+ " close-session-with-discrepancy", TillRightsMigration.tillRightsOf(
						Arrays.stream(BadgePermission.values()).map(BadgePermission::key).collect(Collectors.toList())));
		assertEquals("make-return apply-total-discount", TillRightsMigration.tillRightsOf(Arrays.asList(
				"till:apply-total-discount", "read:home", "till:make-return")));
	}

	// ─── The run, on H2 ──────────────────────────────────────────────

	/** COL_LENGTH of SQL Server for H2: a length when the column exists (what the migration reads), else null. */
	public static Integer colLength(Connection connection, String table, String column) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS"
				+ " WHERE UPPER(TABLE_NAME) = UPPER(?) AND UPPER(COLUMN_NAME) = UPPER(?)")) {
			statement.setString(1, table);
			statement.setString(2, column);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() && rows.getInt(1) > 0 ? 50 : null;
			}
		}
	}

	/** A store database of before: app_role and user_account with the columns of 2.3.0 (or without the two old ones). */
	private static JdbcTemplate database(boolean oldColumns) {
		JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
				"jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MSSQLServer"));
		jdbc.execute("CREATE ALIAS COL_LENGTH FOR \"" + TillRightsMigrationTest.class.getName() + ".colLength\"");
		jdbc.execute("CREATE TABLE app_role (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL)");
		jdbc.execute("CREATE TABLE user_account (id BIGINT PRIMARY KEY, username VARCHAR(255), app_role_id BIGINT NULL,"
				+ " active BIT NULL, badge_code VARCHAR(50) NULL, badge_revoked BIT NULL, badge_expiration_date DATETIME2 NULL"
				+ (oldColumns ? ", badge_permissions VARCHAR(255) NULL, role VARCHAR(255) NULL" : "") + ")");
		return jdbc;
	}

	private static void roleRow(JdbcTemplate jdbc, AppRole role) {
		jdbc.update("INSERT INTO app_role (id, name) VALUES (?, ?)", role.getId(), role.getName());
	}

	private static int users;

	/** A user of 2.3.0: badge expiring in a year unless {@code expired}; {@code roleEnum} the old role column. */
	private static void user(JdbcTemplate jdbc, Long roleId, Boolean active, String badgeCode, Boolean revoked,
			boolean expired, String list, String roleEnum) {
		Timestamp expiration = badgeCode == null ? null
				: Timestamp.valueOf(LocalDateTime.now().plusDays(expired ? -1 : 365));
		jdbc.update("INSERT INTO user_account (id, username, app_role_id, active, badge_code, badge_revoked,"
				+ " badge_expiration_date, badge_permissions, role) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", ++users,
				"u" + users, roleId, active, badgeCode, revoked, expiration, list, roleEnum);
	}

	/** What the run did to General Setup and the roles. */
	private static final class Run {
		final Map<String, GeneralSetup> settings = new LinkedHashMap<>();
		final List<String> deleted = new ArrayList<>();
		final List<String> savedRoles = new ArrayList<>();
		final List<AppRole> roles = new ArrayList<>();
		boolean rolesRead;

		void setting(String code, String value) {
			GeneralSetup row = new GeneralSetup();
			row.setCode(code);
			row.setValeur(value);
			settings.put(code, row);
		}

		TillRightsMigration migration(JdbcTemplate jdbc, ApplicationModeService mode) {
			GeneralSetupRepository setup = stub(GeneralSetupRepository.class, (method, args) -> {
				switch (method) {
					case "findByCode": return Optional.ofNullable(settings.get(args[0]));
					case "delete":
						GeneralSetup row = (GeneralSetup) args[0];
						deleted.add(row.getCode());
						settings.remove(row.getCode());
						return null;
					case "save":
						GeneralSetup saved = (GeneralSetup) args[0];
						settings.put(saved.getCode(), saved);
						return saved;
					default: return UNHANDLED;
				}
			});
			AppRoleRepository appRoles = stub(AppRoleRepository.class, (method, args) -> {
				switch (method) {
					case "findAll":
						rolesRead = true;
						return roles;
					case "save":
						savedRoles.add(((AppRole) args[0]).getName());
						return args[0];
					default: return UNHANDLED;
				}
			});
			return new TillRightsMigration(jdbc, appRoles, setup, mode);
		}

		Set<String> tillRights(String roleName) {
			return roles.stream().filter(role -> role.getName().equals(roleName)).findFirst().get().getPermissions()
					.stream().filter(key -> key.startsWith("till:")).collect(Collectors.toSet());
		}
	}

	private static Set<String> keys(BadgePermission... actions) {
		return Arrays.stream(actions).map(BadgePermission::key).collect(Collectors.toSet());
	}

	private static final String ALL_FIVE = "CONSULT_CUSTOMER_LIST,MAKE_RETURN,APPLY_LINE_DISCOUNT,APPLY_TOTAL_DISCOUNT,"
			+ "CLOSE_SESSION_WITH_DISCREPANCY";

	/** A store of 2.3.0 as the local copies: ADMIN, RESPONSIBLE with two badges, a cashier, CONTROLE, a role unused. */
	private static Run storeOfBefore(JdbcTemplate jdbc) {
		Run run = new Run();
		run.roles.add(role(1, "ADMIN", "read:home", "read:cashier-interface"));
		run.roles.add(role(2, "RESPONSIBLE", "read:home"));
		run.roles.add(role(3, "POS_USER", "read:cashier-interface"));
		run.roles.add(role(4, "CONTROLE", "read:home"));
		run.roles.add(role(5, "VIDE", "read:home"));
		run.roles.forEach(role -> roleRow(jdbc, role));
		user(jdbc, 1L, true, null, null, false, null, "ADMIN");
		user(jdbc, 2L, true, "R-1", false, false, ALL_FIVE, "RESPONSIBLE");
		user(jdbc, 2L, null, "R-2", null, false, "MAKE_RETURN, APPLY_TOTAL_DISCOUNT,CONSULT_CUSTOMER_LIST",
				"RESPONSIBLE"); // active unset: active
		user(jdbc, 2L, false, null, null, false, null, "RESPONSIBLE"); // inactive: ignored
		user(jdbc, 3L, true, null, null, false, "MAKE_RETURN", "POS_USER");
		user(jdbc, 4L, true, "C-1", false, true, ALL_FIVE, "RESPONSIBLE"); // expired badge
		user(jdbc, null, true, "N-1", false, false, ALL_FIVE, "RESPONSIBLE"); // no role
		return run;
	}

	@Test
	@DisplayName("Store of before, setting absent: ADMIN the five, RESPONSIBLE what both badges pass, the others nothing; only the changed roles saved; the marker written with the matrix")
	void storeOfBeforeSettingOff() {
		JdbcTemplate jdbc = database(true);
		Run run = storeOfBefore(jdbc);

		run.migration(jdbc, TestModes.standalone()).run();

		assertEquals(keys(BadgePermission.values()), run.tillRights("ADMIN"));
		assertEquals(keys(BadgePermission.CONSULT_CUSTOMER_LIST, BadgePermission.MAKE_RETURN,
				BadgePermission.APPLY_TOTAL_DISCOUNT), run.tillRights("RESPONSIBLE"));
		assertEquals(Collections.emptySet(), run.tillRights("POS_USER"));
		assertEquals(Collections.emptySet(), run.tillRights("CONTROLE"));
		assertEquals(Collections.emptySet(), run.tillRights("VIDE"));
		assertTrue(run.roles.get(0).getPermissions().containsAll(Arrays.asList("read:home", "read:cashier-interface")),
				"nothing removed");
		assertEquals(Arrays.asList("ADMIN", "RESPONSIBLE"), run.savedRoles);
		assertTrue(run.deleted.isEmpty());

		GeneralSetup marker = run.settings.get(TillRightsMigration.MARKER);
		assertTrue(marker.getReadOnly());
		assertTrue(marker.getValeur().contains("{ADMIN=consult-customer-list make-return apply-line-discount"
				+ " apply-total-discount close-session-with-discrepancy, RESPONSIBLE=consult-customer-list make-return"
				+ " apply-total-discount, POS_USER=-, CONTROLE=-, VIDE=-}"), marker.getValeur());
		assertTrue(marker.getValeur().length() <= 255);
	}

	@Test
	@DisplayName("Setting on: nobody but the admin passed: ADMIN the five, every other role nothing; the setting row deleted")
	void storeOfBeforeSettingOn() {
		JdbcTemplate jdbc = database(true);
		Run run = storeOfBefore(jdbc);
		run.setting(TillRightsMigration.ALWAYS_SHOW_BADGE_SCAN_POPUP, " TRUE ");

		run.migration(jdbc, TestModes.standalone()).run();

		assertEquals(keys(BadgePermission.values()), run.tillRights("ADMIN"));
		for (String other : new String[] { "RESPONSIBLE", "POS_USER", "CONTROLE", "VIDE" }) {
			assertEquals(Collections.emptySet(), run.tillRights(other), other);
		}
		assertEquals(Collections.singletonList("ADMIN"), run.savedRoles);
		assertEquals(Collections.singletonList(TillRightsMigration.ALWAYS_SHOW_BADGE_SCAN_POPUP), run.deleted);
		assertFalse(run.settings.containsKey(TillRightsMigration.ALWAYS_SHOW_BADGE_SCAN_POPUP));
		assertTrue(run.settings.containsKey(TillRightsMigration.MARKER));
	}

	@Test
	@DisplayName("Setting off by its row ('false'): read as off, the row deleted too")
	void settingRowOff() {
		JdbcTemplate jdbc = database(true);
		Run run = storeOfBefore(jdbc);
		run.setting(TillRightsMigration.ALWAYS_SHOW_BADGE_SCAN_POPUP, "false");

		run.migration(jdbc, TestModes.standalone()).run();

		assertEquals(3, run.tillRights("RESPONSIBLE").size());
		assertEquals(Collections.singletonList(TillRightsMigration.ALWAYS_SHOW_BADGE_SCAN_POPUP), run.deleted);
	}

	@Test
	@DisplayName("Rights only added: a role created at this start (the five of its seed) keeps them; a role already holding its rights is not saved")
	void onlyAdded() {
		JdbcTemplate jdbc = database(true);
		Run run = new Run();
		run.roles.add(role(1, "ADMIN", keys(BadgePermission.values()).toArray(new String[0])));
		run.roles.add(role(3, "POS_USER", keys(BadgePermission.values()).toArray(new String[0])));
		run.roles.forEach(role -> roleRow(jdbc, role));
		user(jdbc, 3L, true, null, null, false, null, "POS_USER"); // asked for a badge today

		run.migration(jdbc, TestModes.standalone()).run();

		assertEquals(keys(BadgePermission.values()), run.tillRights("POS_USER"), "nothing removed");
		assertTrue(run.savedRoles.isEmpty(), "saved: " + run.savedRoles);
		assertTrue(run.settings.containsKey(TillRightsMigration.MARKER));
	}

	@Test
	@DisplayName("A database without badge_permissions and role (created after them): the admin is the user of the role named ADMIN, the others pass nothing")
	void withoutTheOldColumns() {
		JdbcTemplate jdbc = database(false);
		Run run = new Run();
		run.roles.add(role(1, "ADMIN"));
		run.roles.add(role(2, "RESPONSIBLE"));
		run.roles.forEach(role -> roleRow(jdbc, role));
		jdbc.update("INSERT INTO user_account (id, username, app_role_id, active, badge_code) VALUES (1, 'a', 1, 1, NULL)");
		jdbc.update("INSERT INTO user_account (id, username, app_role_id, active, badge_code) VALUES (2, 'r', 2, 1, 'R-1')");

		TillRightsMigration migration = run.migration(jdbc, TestModes.standalone());
		List<Card> cards = migration.readCards();
		assertEquals(2, cards.size());
		assertTrue(cards.get(0).admin);
		assertFalse(cards.get(1).admin);
		assertTrue(cards.get(1).validBadge);
		assertTrue(cards.get(1).listed.isEmpty());

		migration.run();
		assertEquals(keys(BadgePermission.values()), run.tillRights("ADMIN"));
		assertEquals(Collections.emptySet(), run.tillRights("RESPONSIBLE"));
	}

	@Test
	@DisplayName("The cards read by JDBC: active unset is active, a revoked or expired badge or no code is not valid, the old role column decides the admin")
	void cardsRead() {
		JdbcTemplate jdbc = database(true);
		roleRow(jdbc, role(1, "ADMIN"));
		roleRow(jdbc, role(2, "RESPONSIBLE"));
		user(jdbc, 2L, null, "OK", null, false, "MAKE_RETURN", "RESPONSIBLE");
		user(jdbc, 2L, true, "REV", true, false, "MAKE_RETURN", "RESPONSIBLE");
		user(jdbc, 2L, true, "EXP", false, true, "MAKE_RETURN", "RESPONSIBLE");
		user(jdbc, 2L, false, " ", false, false, "MAKE_RETURN", "RESPONSIBLE");
		user(jdbc, 2L, true, null, null, false, null, "ADMIN"); // the enum decides: an admin in the RESPONSIBLE role
		user(jdbc, 1L, true, null, null, false, null, "POS_USER"); // in the ADMIN role, not an admin of 2.2.2

		List<Card> cards = new Run().migration(jdbc, TestModes.standalone()).readCards();

		assertEquals(Arrays.asList(true, true, true, false, true, true),
				cards.stream().map(card -> card.active).collect(Collectors.toList()));
		assertEquals(Arrays.asList(true, false, false, false, false, false),
				cards.stream().map(card -> card.validBadge).collect(Collectors.toList()));
		assertEquals(Arrays.asList(false, false, false, false, true, false),
				cards.stream().map(card -> card.admin).collect(Collectors.toList()));
		assertEquals(of(BadgePermission.MAKE_RETURN), cards.get(0).listed);
		assertEquals(Long.valueOf(2L), cards.get(0).roleId);
	}

	@Test
	@DisplayName("Once: the marker present, nothing is read or written; a head office: nothing at all")
	void onceAndNeverOnAHeadOffice() {
		JdbcTemplate jdbc = database(true);
		Run done = storeOfBefore(jdbc);
		done.setting(TillRightsMigration.MARKER, "2026-10-18T10:00 {...}");
		done.setting(TillRightsMigration.ALWAYS_SHOW_BADGE_SCAN_POPUP, "true");
		done.migration(jdbc, TestModes.standalone()).run();
		assertFalse(done.rolesRead);
		assertTrue(done.savedRoles.isEmpty());
		assertTrue(done.deleted.isEmpty());
		assertEquals("2026-10-18T10:00 {...}", done.settings.get(TillRightsMigration.MARKER).getValeur());

		Run headOffice = storeOfBefore(database(true));
		headOffice.migration(null, TestModes.of(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"))).run();
		assertFalse(headOffice.rolesRead);
		assertTrue(headOffice.settings.isEmpty(), "no marker on a head office");
	}

	// ─── Stub plumbing (as ZZDataInitializerRolesTest) ───────────────

	private interface Handler {
		Object handle(String method, Object[] args);
	}

	private static final Object UNHANDLED = new Object();

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				case "toString": return type.getSimpleName() + "Stub";
				default: break;
			}
			Object result = handler.handle(method.getName(), args == null ? new Object[0] : args);
			if (result == UNHANDLED) {
				throw new UnsupportedOperationException("Unexpected call: " + type.getSimpleName() + "." + method.getName());
			}
			return result;
		});
	}
}
