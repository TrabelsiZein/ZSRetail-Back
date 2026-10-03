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
 * {@code sales.upstream}); otherwise it is derived from the existing mode flags (design 5.1):
 *
 *   mode                 type   catalogue    customers  promotions  loyalty  supply       sales go to
 *   franchise customer   STORE  HEAD_OFFICE  LOCAL      LOCAL       LOCAL    HEAD_OFFICE  HEAD_OFFICE
 *   franchise admin      STORE  LOCAL        LOCAL      LOCAL       LOCAL    LOCAL        nowhere   (legacy, until step 8)
 *   standalone           STORE  LOCAL        LOCAL      LOCAL       LOCAL    LOCAL        nowhere
 *   ERP                  STORE  ERP          ERP        LOCAL       LOCAL    ERP          ERP
 *
 * The first matching row wins, in this order. An explicit value that is unknown, or an owner the domain
 * does not allow, throws {@link IllegalStateException} naming the key, so the application does not start.
 *
 * Head office ({@code node.type=HEAD_OFFICE}, docs/modules/head-office.md): it never sells, so sales go nowhere
 * when {@code sales.upstream} is absent. The startup also fails on a head office with franchise.admin or
 * franchise.customer set to true, with an explicit owner HEAD_OFFICE, or with a non-empty sales.upstream.
 *
 * Head office link (task 1.4): when {@code headoffice.url} is set, the startup fails on a head office, with a blank
 * {@code headoffice.api-key}, or with a {@code headoffice.heartbeat-interval-seconds} below 1. Stores page (task 1.5):
 * on a head office, the startup fails with a {@code headoffice.offline-after-seconds} below 1. Sales copies (tasks 2.1,
 * 2.4): when {@code headoffice.url} is set, the startup fails with a {@code headoffice.sales-push.from-date} that is
 * not a date, a {@code headoffice.sales-push.batch-size} outside 1..1000 or a
 * {@code headoffice.sales-push.interval-seconds} below 1 or (task 2.6) a {@code headoffice.log-retention-days} below 1;
 * on a store, an explicit {@code sales.upstream} that includes
 * HEAD_OFFICE without {@code headoffice.url} fails too (a derived upstream is not checked: the franchise customer
 * profile derives HEAD_OFFICE for its legacy push and has no headoffice.url).
 * <p>
 * Copies down (step 3): a store with an explicit {@code ownership.promotions=HEAD_OFFICE} without {@code headoffice.url}
 * fails; with the URL set, a {@code headoffice.pull.interval-seconds} below 1 fails.
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

	/**
	 * Domains a store receives as copies down from its head office today (step 3: promotions). An explicit owner
	 * HEAD_OFFICE for one of them needs headoffice.url. Later steps add theirs.
	 */
	static final Set<DataDomain> COPIES_DOWN_DOMAINS = Collections.unmodifiableSet(EnumSet.of(DataDomain.PROMOTIONS));

	/** Largest batch a store may send in one request. */
	public static final int SALES_PUSH_MAX_BATCH_SIZE = 1000;

	// Mode flags, read like ApplicationModeService (@Value with a false default)
	static final String STANDALONE_KEY = "application.standalone";
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
		return resolveFromEnvironment(env).getSalesUpstreams().contains(SalesUpstream.HEAD_OFFICE);
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
		NodeOwnership ownership = resolveFromEnvironment(env);
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
		return isHeadOfficeLinkSet(env) && resolveFromEnvironment(env).ownerOf(domain) == DataOwner.HEAD_OFFICE;
	}

	/** The mode flags read from the environment like {@link ApplicationModeService}. */
	private static NodeOwnership resolveFromEnvironment(PropertyResolver env) {
		return resolve(env, flag(env, STANDALONE_KEY), flag(env, FRANCHISE_ADMIN_KEY), flag(env, FRANCHISE_CUSTOMER_KEY));
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

	public static NodeOwnership resolve(PropertyResolver env, boolean standalone, boolean franchiseAdmin,
			boolean franchiseCustomer) {
		NodeType nodeType = nodeTypeOf(env);
		boolean headOffice = nodeType == NodeType.HEAD_OFFICE;
		if (headOffice && (franchiseAdmin || franchiseCustomer)) {
			throw new IllegalStateException("Invalid combination: " + NODE_TYPE_KEY + "=HEAD_OFFICE with "
					+ (franchiseAdmin ? "franchise.admin=true" : "franchise.customer=true")
					+ ". A head office uses neither franchise profile.");
		}
		checkHeadOfficeLink(env, headOffice);
		if (headOffice) {
			checkWholeSeconds(env, OFFLINE_AFTER_KEY);
		}

		Map<DataDomain, DataOwner> derivedOwners;
		Set<SalesUpstream> derivedUpstreams;
		if (franchiseCustomer) {
			derivedOwners = owners(DataOwner.HEAD_OFFICE, DataOwner.LOCAL, DataOwner.LOCAL, DataOwner.LOCAL,
					DataOwner.HEAD_OFFICE);
			derivedUpstreams = EnumSet.of(SalesUpstream.HEAD_OFFICE);
		} else if (franchiseAdmin || standalone) {
			derivedOwners = owners(DataOwner.LOCAL, DataOwner.LOCAL, DataOwner.LOCAL, DataOwner.LOCAL,
					DataOwner.LOCAL);
			derivedUpstreams = EnumSet.noneOf(SalesUpstream.class);
		} else {
			derivedOwners = owners(DataOwner.ERP, DataOwner.ERP, DataOwner.LOCAL, DataOwner.LOCAL, DataOwner.ERP);
			derivedUpstreams = EnumSet.of(SalesUpstream.ERP);
		}
		if (headOffice) {
			derivedUpstreams = EnumSet.noneOf(SalesUpstream.class); // a head office never sells
		}

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
