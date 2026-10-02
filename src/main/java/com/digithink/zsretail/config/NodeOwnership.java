package com.digithink.zsretail.config;

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
 */
public final class NodeOwnership {

	static final String NODE_TYPE_KEY = "node.type";
	static final String SALES_UPSTREAM_KEY = "sales.upstream";

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

	public static NodeOwnership resolve(PropertyResolver env, boolean standalone, boolean franchiseAdmin,
			boolean franchiseCustomer) {
		NodeType nodeType = nodeTypeOf(env);
		boolean headOffice = nodeType == NodeType.HEAD_OFFICE;
		if (headOffice && (franchiseAdmin || franchiseCustomer)) {
			throw new IllegalStateException("Invalid combination: " + NODE_TYPE_KEY + "=HEAD_OFFICE with "
					+ (franchiseAdmin ? "franchise.admin=true" : "franchise.customer=true")
					+ ". A head office uses neither franchise profile.");
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
