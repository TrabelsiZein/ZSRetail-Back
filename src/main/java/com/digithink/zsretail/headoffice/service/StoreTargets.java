package com.digithink.zsretail.headoffice.service;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * The stores a record of the head office is addressed to (step 3): every store (including stores created later), or a
 * list of ho_store ids. Immutable.
 */
@EqualsAndHashCode
@ToString
public final class StoreTargets {

	private static final StoreTargets ALL = new StoreTargets(true, Collections.emptySet());

	private final boolean allStores;
	private final Set<Long> storeIds;

	private StoreTargets(boolean allStores, Set<Long> storeIds) {
		this.allStores = allStores;
		this.storeIds = Collections.unmodifiableSet(storeIds);
	}

	public static StoreTargets all() {
		return ALL;
	}

	/** A list of stores; nulls are dropped. */
	public static StoreTargets of(Collection<Long> storeIds) {
		Set<Long> ids = new TreeSet<>();
		if (storeIds != null) {
			for (Long id : storeIds) {
				if (id != null) {
					ids.add(id);
				}
			}
		}
		return new StoreTargets(false, ids);
	}

	public boolean isAllStores() {
		return allStores;
	}

	/** Empty when every store. */
	public Set<Long> getStoreIds() {
		return storeIds;
	}

	public boolean includes(Long storeId) {
		return allStores || storeIds.contains(storeId);
	}

	/** The stores concerned by a change from these targets to the other ones: both, every store when one is. */
	public StoreTargets union(StoreTargets other) {
		Set<Long> ids = new TreeSet<>(storeIds);
		ids.addAll(other.storeIds);
		return new StoreTargets(allStores || other.allStores, ids);
	}
}
