package com.digithink.zsretail.config;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.core.env.PropertyResolver;

import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.DataOwner;
import com.digithink.zsretail.model.enumeration.NodeType;
import com.digithink.zsretail.model.enumeration.SalesUpstream;

/**
 * Installation type, owner of each data domain and sales upstreams (head office design 2.1, 2.2).
 *
 * Each value comes from its optional property when present ({@code node.type}, {@code ownership.<domain>},
 * {@code sales.upstream}); a preset states every one of them (task 9.3, docs/deployment-modes.md). Absent: the owner is
 * LOCAL, the node type STORE and the sales go nowhere. The property application.standalone was removed (task 9.3): a
 * configuration that still sets it stops the startup with a message naming the presets.
 *
 * An explicit value that is unknown, or an owner the domain does not allow, throws {@link IllegalStateException}
 * naming the key, so the application does not start. The franchise profiles were removed (step 9, task 9.4a):
 * franchise.admin or franchise.customer set to true stops the startup, on a store and on a head office.
 *
 * Head office ({@code node.type=HEAD_OFFICE}, docs/modules/head-office.md): it never sells, so sales go nowhere
 * when {@code sales.upstream} is absent. The startup also fails on a head office with an explicit owner HEAD_OFFICE,
 * or with a non-empty sales.upstream.
 *
 * Head office link (task 1.4): when {@code headoffice.url} is set, the startup fails on a head office, with a blank
 * {@code headoffice.api-key}, or with a {@code headoffice.heartbeat-interval-seconds} below 1. Stores page (task 1.5):
 * on a head office, the startup fails with a {@code headoffice.offline-after-seconds} below 1. Sales copies (tasks 2.1,
 * 2.4): when {@code headoffice.url} is set, the startup fails with a {@code headoffice.sales-push.from-date} that is
 * not a date, a {@code headoffice.sales-push.batch-size} outside 1..1000 or a
 * {@code headoffice.sales-push.interval-seconds} below 1 or (task 2.6) a {@code headoffice.log-retention-days} below 1;
 * on a store, an explicit {@code sales.upstream} that includes
 * HEAD_OFFICE without {@code headoffice.url} fails too.
 * <p>
 * Copies down (step 3): a store with an explicit {@code ownership.promotions=HEAD_OFFICE} without {@code headoffice.url}
 * fails; with the URL set, a {@code headoffice.pull.interval-seconds} below 1 fails. Shared loyalty (step 4): the same
 * for {@code ownership.loyalty=HEAD_OFFICE}, and with the URL set a {@code headoffice.loyalty-push.interval-seconds}
 * below 1 fails. Catalogue (step 6): the same for {@code ownership.catalogue=HEAD_OFFICE}. Supply (step 7A): the same for
 * {@code ownership.supply=HEAD_OFFICE}, which also fails without {@code ownership.catalogue=HEAD_OFFICE} (a BL names head office items by code); with the URL set a
 * {@code headoffice.supply-push.interval-seconds} below 1 fails.
 * <p>
 * The ERP owns all or nothing (step 9, tasks 9.1a and 9.3): the catalogue, the customers and the supply (the domains an
 * ERP can own) are the ERP's together, or none of them is. Any other mix stops the startup (an ERP store, its items
 * from a head office, for example), so "an owner is the ERP" answers every step 9 question (ModeQuestionTruthTableTest).
 */
public final class NodeOwnership {

	static final String NODE_TYPE_KEY = "node.type";
	static final String SALES_UPSTREAM_KEY = "sales.upstream";
	static final String HEADOFFICE_URL_KEY = "headoffice.url";
	static final String HEADOFFICE_API_KEY_KEY = "headoffice.api-key";
	static final String HEARTBEAT_INTERVAL_KEY = "headoffice.heartbeat-interval-seconds";
	static final String OFFLINE_AFTER_KEY = "headoffice.offline-after-seconds";
	static final String SALES_PUSH_FROM_DATE_KEY = "headoffice.sales-push.from-date";
	static final String SALES_PUSH_BATCH_SIZE_KEY = "headoffice.sales-push.batch-size";
	static final String SALES_PUSH_INTERVAL_KEY = "headoffice.sales-push.interval-seconds";
	static final String LOG_RETENTION_KEY = "headoffice.log-retention-days";
	static final String PULL_INTERVAL_KEY = "headoffice.pull.interval-seconds";
	static final String LOYALTY_PUSH_INTERVAL_KEY = "headoffice.loyalty-push.interval-seconds";
	static final String SUPPLY_PUSH_INTERVAL_KEY = "headoffice.supply-push.interval-seconds";

	/**
	 * Domains a store receives as copies down from its head office today (step 3: promotions; step 4: loyalty; step 6:
	 * catalogue; step 7A: supply, the BLs). An explicit owner HEAD_OFFICE for one of them needs headoffice.url.
	 */
	static final Set<DataDomain> COPIES_DOWN_DOMAINS = Collections.unmodifiableSet(
			EnumSet.of(DataDomain.CATALOGUE, DataDomain.PROMOTIONS, DataDomain.LOYALTY, DataDomain.SUPPLY));

	/** Largest batch a store may send in one request. */
	public static final int SALES_PUSH_MAX_BATCH_SIZE = 1000;

	// Mode flags, read like ApplicationModeService (@Value with a false default)
	/** Removed at task 9.3: refused at startup (checkNoStandaloneProperty). */
	static final String STANDALONE_KEY = "application.standalone";

	/** The presets of task 9.3 (src/main/resources/application-<name>.properties), named by the machine file. */
	public static final java.util.List<String> PRESETS = java.util.Collections.unmodifiableList(java.util.Arrays.asList("store",
			"store-erp", "headoffice", "headoffice-erp", "network-store", "network-store-erp"));
	static final String FRANCHISE_ADMIN_KEY = "franchise.admin";
	static final String FRANCHISE_CUSTOMER_KEY = "franchise.customer";

	private final NodeType nodeType;
	private final Map<DataDomain, DataOwner> owners;
	private final Set<SalesUpstream> salesUpstreams;

	private NodeOwnership(NodeType nodeType, Map<DataDomain, DataOwner> owners, Set<SalesUpstream> salesUpstreams) {
		this.nodeType = nodeType;
		this.owners = Collections.unmodifiableMap(owners);
		this.salesUpstreams = Collections.unmodifiableSet(salesUpstreams);
	}

	/**
	 * node.type, trimmed and case-insensitive; STORE when absent. Throws on an unknown value. Also used by
	 * {@link OnHeadOfficeCondition}, so the head office beans and the startup read the key the same way.
	 */
	public static NodeType nodeTypeOf(PropertyResolver env) {
		return env.containsProperty(NODE_TYPE_KEY)
				? parse(NodeType.class, NODE_TYPE_KEY, env.getProperty(NODE_TYPE_KEY))
				: NodeType.STORE;
	}

	/**
	 * True when headoffice.url is present and not blank: this store calls a head office (task 1.4). Also used by
	 * {@link OnHeadOfficeLinkCondition}, so the head office link beans and the startup read the key the same way.
	 */
	public static boolean isHeadOfficeLinkSet(PropertyResolver env) {
		String url = env.getProperty(HEADOFFICE_URL_KEY);
		return url != null && !url.trim().isEmpty();
	}

	/**
	 * True when this store pushes copies of its tickets, returns and session closings to the head office (task 2.1,
	 * decision 4): headoffice.url is set and the sales upstreams (sales.upstream, or derived from the mode flags) include
	 * HEAD_OFFICE. The mode flags are read from the environment like {@link ApplicationModeService}. Also used by
	 * {@link OnHeadOfficeSalesPushCondition}. Throws like the startup on an invalid configuration.
	 */
	public static boolean isHeadOfficeSalesPushSet(PropertyResolver env) {
		if (!isHeadOfficeLinkSet(env)) {
			return false;
		}
		return resolve(env).getSalesUpstreams().contains(SalesUpstream.HEAD_OFFICE);
	}

	/**
	 * True when this store pulls copies down from its head office (step 3): headoffice.url is set and at least one domain
	 * is owned by the head office. Also used by {@link OnHeadOfficePullCondition}. Throws like the startup on an invalid
	 * configuration.
	 */
	public static boolean isHeadOfficePullSet(PropertyResolver env) {
		if (!isHeadOfficeLinkSet(env)) {
			return false;
		}
		NodeOwnership ownership = resolve(env);
		for (DataDomain domain : DataDomain.values()) {
			if (ownership.ownerOf(domain) == DataOwner.HEAD_OFFICE) {
				return true;
			}
		}
		return false;
	}

	/**
	 * True when headoffice.url is set and the domain is owned by the head office (step 3): the domain's copies down
	 * handler exists. Also used by {@link OnHeadOfficeOwnedCondition}.
	 */
	public static boolean isOwnedByHeadOffice(PropertyResolver env, DataDomain domain) {
		return isHeadOfficeLinkSet(env) && resolve(env).ownerOf(domain) == DataOwner.HEAD_OFFICE;
	}

	/**
	 * True on a store whose catalogue is the head office's (step 6): headoffice.url set, ownership.catalogue=HEAD_OFFICE
	 * (only accepted without an ERP: with one the startup stops). Also used by {@link OnHeadOfficeCatalogueCondition}.
	 */
	public static boolean isCatalogueFromHeadOffice(PropertyResolver env) {
		return isOwnedByHeadOffice(env, DataDomain.CATALOGUE);
	}

	/**
	 * True on a store whose goods come from the head office by BL (step 7A): headoffice.url set, an explicit
	 * ownership.supply=HEAD_OFFICE (only accepted without an ERP and with ownership.catalogue=HEAD_OFFICE: otherwise the
	 * startup stops). Also used by {@link OnHeadOfficeSupplyCondition}.
	 */
	public static boolean isSupplyFromHeadOffice(PropertyResolver env) {
		return env.containsProperty(DataDomain.SUPPLY.getPropertyKey()) && isOwnedByHeadOffice(env, DataDomain.SUPPLY);
	}

	/**
	 * True on a head office with an ERP (task 3.4): node.type=HEAD_OFFICE and an owner ERP (preset headoffice-erp). Also used by {@link OnHeadOfficeErpCondition}.
	 */
	public static boolean isHeadOfficeErpSet(PropertyResolver env) {
		return nodeTypeOf(env) == NodeType.HEAD_OFFICE && resolve(env).hasErp();
	}

	/**
	 * True on a head office without an ERP (step 6): node.type=HEAD_OFFICE and no owner ERP. It sends its catalogue to its
	 * stores and keeps the price lists. Also used by {@link OnHeadOfficeWithoutErpCondition}.
	 */
	public static boolean isHeadOfficeWithoutErpSet(PropertyResolver env) {
		return nodeTypeOf(env) == NodeType.HEAD_OFFICE && !resolve(env).hasErp();
	}

	/**
	 * headoffice.sales-push.from-date (task 2.1): a date as yyyy-MM-dd, trimmed; null when absent or blank (the whole
	 * history is sent). Throws {@link IllegalStateException} naming the key on any other value.
	 */
	public static LocalDate parseSalesPushFromDate(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return null;
		}
		try {
			return LocalDate.parse(raw.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalStateException("Invalid value '" + raw + "' for property " + SALES_PUSH_FROM_DATE_KEY
					+ ": a date as yyyy-MM-dd, e.g. 2026-01-01");
		}
	}

	private static boolean flag(PropertyResolver env, String key) {
		return Boolean.TRUE.equals(env.getProperty(key, Boolean.class, Boolean.FALSE));
	}

	/**
	 * The installation of this environment, as ApplicationModeService resolves it at startup (and every head office
	 * condition). Throws {@link IllegalStateException} naming the key when the configuration cannot start (see the class
	 * comment).
	 */
	public static NodeOwnership resolve(PropertyResolver env) {
		checkNoStandaloneProperty(env);
		checkNoFranchiseFlag(env);
		NodeType nodeType = nodeTypeOf(env);
		boolean headOffice = nodeType == NodeType.HEAD_OFFICE;
		checkHeadOfficeLink(env, headOffice);
		if (headOffice) {
			checkWholeSeconds(env, OFFLINE_AFTER_KEY);
		}

		// Absent keys: everything LOCAL, sales nowhere (a preset states them all)
		Map<DataDomain, DataOwner> derivedOwners = owners(DataOwner.LOCAL, DataOwner.LOCAL, DataOwner.LOCAL,
				DataOwner.LOCAL, DataOwner.LOCAL);
		Set<SalesUpstream> derivedUpstreams = EnumSet.noneOf(SalesUpstream.class);

		Map<DataDomain, DataOwner> owners = new EnumMap<>(DataDomain.class);
		for (DataDomain domain : DataDomain.values()) {
			String key = domain.getPropertyKey();
			if (env.containsProperty(key)) {
				String raw = env.getProperty(key);
				DataOwner owner = parse(DataOwner.class, key, raw);
				if (!domain.allows(owner)) {
					throw invalid(key, raw, domain.getAllowedOwners());
				}
				if (headOffice && owner == DataOwner.HEAD_OFFICE) {
					throw new IllegalStateException("Invalid value '" + raw + "' for property " + key + ": on a head office ("
							+ NODE_TYPE_KEY + "=HEAD_OFFICE) the owner cannot be HEAD_OFFICE");
				}
				if (!headOffice && owner == DataOwner.HEAD_OFFICE && COPIES_DOWN_DOMAINS.contains(domain)
						&& !isHeadOfficeLinkSet(env)) {
					throw new IllegalStateException("Missing value for property " + HEADOFFICE_URL_KEY + ": required when "
							+ key + " is HEAD_OFFICE ('" + raw + "'). Set the head office URL and key, or set " + key
							+ " to LOCAL.");
				}
				owners.put(domain, owner);
			} else {
				owners.put(domain, derivedOwners.get(domain));
			}
		}

		checkErpOwnsAllOrNothing(owners);

		String catalogueKey = DataDomain.CATALOGUE.getPropertyKey();
		String supplyKey = DataDomain.SUPPLY.getPropertyKey();
		if (!headOffice && env.containsProperty(supplyKey) && owners.get(DataDomain.SUPPLY) == DataOwner.HEAD_OFFICE) {
			if (owners.get(DataDomain.CATALOGUE) != DataOwner.HEAD_OFFICE) {
				throw new IllegalStateException("Invalid combination: " + supplyKey + "=HEAD_OFFICE without "
						+ catalogueKey + "=HEAD_OFFICE. A BL names head office items by their code: the store's items must"
						+ " come from the head office; set " + catalogueKey + " to HEAD_OFFICE, or remove " + supplyKey
						+ ".");
			}
		}

		Set<SalesUpstream> upstreams = env.containsProperty(SALES_UPSTREAM_KEY)
				? parseUpstreams(env.getProperty(SALES_UPSTREAM_KEY))
				: derivedUpstreams;
		if (headOffice && !upstreams.isEmpty()) {
			throw new IllegalStateException("Invalid value '" + env.getProperty(SALES_UPSTREAM_KEY) + "' for property "
					+ SALES_UPSTREAM_KEY + ": a head office (" + NODE_TYPE_KEY
					+ "=HEAD_OFFICE) never sells; leave it empty or absent");
		}
		if (env.containsProperty(SALES_UPSTREAM_KEY) && upstreams.contains(SalesUpstream.HEAD_OFFICE)
				&& !isHeadOfficeLinkSet(env)) {
			throw new IllegalStateException("Missing value for property " + HEADOFFICE_URL_KEY + ": required when "
					+ SALES_UPSTREAM_KEY + " includes HEAD_OFFICE ('" + env.getProperty(SALES_UPSTREAM_KEY)
					+ "'). Set the head office URL and key, or remove HEAD_OFFICE from " + SALES_UPSTREAM_KEY + ".");
		}

		return new NodeOwnership(nodeType, owners, upstreams);
	}

	public NodeType getNodeType() {
		return nodeType;
	}

	public DataOwner ownerOf(DataDomain domain) {
		return owners.get(domain);
	}

	/** Unmodifiable; empty means sales go nowhere. */
	public Set<SalesUpstream> getSalesUpstreams() {
		return salesUpstreams;
	}

	/**
	 * Step 9 questions (task 9.1b), which replaced the old standalone checks one group at a time: the items and
	 * families come from the ERP (they cannot be written here).
	 */
	public boolean isCatalogueFromErp() {
		return ownerOf(DataDomain.CATALOGUE) == DataOwner.ERP;
	}

	/** Step 9 question: the customers come from the ERP (they are not created here, POS tickets are not invoiced here). */
	public boolean isCustomersFromErp() {
		return ownerOf(DataDomain.CUSTOMERS) == DataOwner.ERP;
	}

	/** Step 9 question: the stock, purchases, vendors and locations are the ERP's (no stock kept here). */
	public boolean isSupplyFromErp() {
		return ownerOf(DataDomain.SUPPLY) == DataOwner.ERP;
	}

	/**
	 * Step 9 question: this installation works with an ERP (some domain is owned by the ERP). The ERP owns the catalogue,
	 * the customers and the supply together or none of them (checked at startup), so the four questions answer alike for
	 * every configuration that starts ({@code ModeQuestionTruthTableTest}).
	 */
	public boolean hasErp() {
		return owners.containsValue(DataOwner.ERP);
	}

	/**
	 * Step 9, task 9.4a: the franchise profiles were removed. A leftover franchise.admin or franchise.customer set to true
	 * (an old profile file) stops the startup instead of silently running as a plain store.
	 */
	private static void checkNoFranchiseFlag(PropertyResolver env) {
		for (String key : new String[] { FRANCHISE_ADMIN_KEY, FRANCHISE_CUSTOMER_KEY }) {
			if (flag(env, key)) {
				throw new IllegalStateException("Invalid value '" + env.getProperty(key) + "' for property " + key
						+ ": the franchise profiles were removed (head office plan, step 9). A franchise network runs as"
						+ " a head office and stores, from the presets headoffice and network-store"
						+ " (docs/modules/franchise.md); remove " + key + ".");
			}
		}
	}

	/**
	 * Task 9.3: application.standalone was removed. A configuration that still sets it (an old profile or machine file)
	 * stops the startup: a preset states who owns what.
	 */
	private static void checkNoStandaloneProperty(PropertyResolver env) {
		if (env.containsProperty(STANDALONE_KEY)) {
			throw new IllegalStateException("The property " + STANDALONE_KEY + " was removed (head office plan, step 9, task"
					+ " 9.3): name a preset in the machine file instead, spring.profiles.active=" + String.join(" | ", PRESETS)
					+ " (docs/deployment-modes.md); remove " + STANDALONE_KEY + ".");
		}
	}

	/**
	 * Steps 9.1a and 9.3: the ERP owns the catalogue, the customers and the supply together, or none of them.
	 */
	private static void checkErpOwnsAllOrNothing(Map<DataDomain, DataOwner> owners) {
		DataDomain erpDomain = null;
		DataDomain otherDomain = null;
		for (DataDomain domain : DataDomain.values()) {
			if (!domain.allows(DataOwner.ERP)) {
				continue;
			}
			if (owners.get(domain) == DataOwner.ERP) {
				erpDomain = erpDomain == null ? domain : erpDomain;
			} else if (otherDomain == null) {
				otherDomain = domain;
			}
		}
		if (erpDomain != null && otherDomain != null) {
			throw new IllegalStateException("Invalid combination: " + erpDomain.getPropertyKey() + "=ERP with "
					+ otherDomain.getPropertyKey() + "=" + owners.get(otherDomain) + ". The ERP owns the catalogue, the"
					+ " customers and the supply together: set all three to ERP (presets store-erp, headoffice-erp,"
					+ " network-store-erp) or none of them.");
		}
	}

	/** Checked only when headoffice.url is set; otherwise the other headoffice.* keys are ignored. */
	private static void checkHeadOfficeLink(PropertyResolver env, boolean headOffice) {
		if (!isHeadOfficeLinkSet(env)) {
			return;
		}
		if (headOffice) {
			throw new IllegalStateException("Invalid combination: " + NODE_TYPE_KEY + "=HEAD_OFFICE with "
					+ HEADOFFICE_URL_KEY + " set. A head office does not call a head office; remove " + HEADOFFICE_URL_KEY
					+ ".");
		}
		String key = env.getProperty(HEADOFFICE_API_KEY_KEY);
		if (key == null || key.trim().isEmpty()) {
			throw new IllegalStateException("Missing value for property " + HEADOFFICE_API_KEY_KEY + ": required when "
					+ HEADOFFICE_URL_KEY + " is set. Paste the key shown once on the head office Stores page.");
		}
		checkWholeSeconds(env, HEARTBEAT_INTERVAL_KEY);
		parseSalesPushFromDate(env.getProperty(SALES_PUSH_FROM_DATE_KEY));
		checkWholeSeconds(env, SALES_PUSH_INTERVAL_KEY);
		checkBatchSize(env);
		checkWholeDays(env, LOG_RETENTION_KEY);
		checkWholeSeconds(env, PULL_INTERVAL_KEY);
		checkWholeSeconds(env, LOYALTY_PUSH_INTERVAL_KEY);
		checkWholeSeconds(env, SUPPLY_PUSH_INTERVAL_KEY);
	}

	/** When present, a whole number from 1 to {@value #SALES_PUSH_MAX_BATCH_SIZE}. */
	private static void checkBatchSize(PropertyResolver env) {
		if (!env.containsProperty(SALES_PUSH_BATCH_SIZE_KEY)) {
			return;
		}
		String raw = env.getProperty(SALES_PUSH_BATCH_SIZE_KEY);
		long size;
		try {
			size = Long.parseLong(raw == null ? "" : raw.trim());
		} catch (NumberFormatException e) {
			size = 0;
		}
		if (size < 1 || size > SALES_PUSH_MAX_BATCH_SIZE) {
			throw new IllegalStateException("Invalid value '" + raw + "' for property " + SALES_PUSH_BATCH_SIZE_KEY
					+ ": a whole number from 1 to " + SALES_PUSH_MAX_BATCH_SIZE);
		}
	}

	/** When present, the key must hold a whole number of days, at least 1 (task 2.6). */
	private static void checkWholeDays(PropertyResolver env, String key) {
		if (env.containsProperty(key) && wholeNumber(env.getProperty(key)) < 1) {
			throw new IllegalStateException("Invalid value '" + env.getProperty(key) + "' for property " + key
					+ ": a whole number of days, at least 1");
		}
	}

	/** The trimmed value as a whole number; 0 when it is not one. */
	private static long wholeNumber(String raw) {
		try {
			return Long.parseLong(raw == null ? "" : raw.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** When present, the key must hold a whole number of seconds, at least 1. */
	private static void checkWholeSeconds(PropertyResolver env, String key) {
		if (!env.containsProperty(key)) {
			return;
		}
		String raw = env.getProperty(key);
		long seconds;
		try {
			seconds = Long.parseLong(raw == null ? "" : raw.trim());
		} catch (NumberFormatException e) {
			seconds = 0;
		}
		if (seconds < 1) {
			throw new IllegalStateException(
					"Invalid value '" + raw + "' for property " + key + ": a whole number of seconds, at least 1");
		}
	}

	/** Owners in DataDomain order: catalogue, customers, promotions, loyalty, supply. */
	private static Map<DataDomain, DataOwner> owners(DataOwner catalogue, DataOwner customers, DataOwner promotions,
			DataOwner loyalty, DataOwner supply) {
		Map<DataDomain, DataOwner> map = new EnumMap<>(DataDomain.class);
		map.put(DataDomain.CATALOGUE, catalogue);
		map.put(DataDomain.CUSTOMERS, customers);
		map.put(DataDomain.PROMOTIONS, promotions);
		map.put(DataDomain.LOYALTY, loyalty);
		map.put(DataDomain.SUPPLY, supply);
		return map;
	}

	/** Empty or blank value: no upstream. Otherwise a comma list. */
	private static Set<SalesUpstream> parseUpstreams(String raw) {
		Set<SalesUpstream> upstreams = EnumSet.noneOf(SalesUpstream.class);
		if (raw == null || raw.trim().isEmpty()) {
			return upstreams;
		}
		for (String part : raw.split(",")) {
			upstreams.add(parse(SalesUpstream.class, SALES_UPSTREAM_KEY, part));
		}
		return upstreams;
	}

	/** Trimmed, case-insensitive enum name. */
	private static <E extends Enum<E>> E parse(Class<E> type, String key, String raw) {
		String value = raw == null ? "" : raw.trim().toUpperCase();
		for (E constant : type.getEnumConstants()) {
			if (constant.name().equals(value)) {
				return constant;
			}
		}
		throw invalid(key, raw, Arrays.asList(type.getEnumConstants()));
	}

	private static IllegalStateException invalid(String key, String raw, Iterable<?> allowed) {
		return new IllegalStateException(
				"Invalid value '" + raw + "' for property " + key + ": allowed values are " + allowed);
	}
}
