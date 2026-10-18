package com.digithink.zsretail.store.support;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.digithink.zsretail.core.model.BadgeScanLog;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.repository.BadgeScanLogRepository;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.service.BadgeService;
import com.digithink.zsretail.store.dto.ProcessReturnRequestDTO;
import com.digithink.zsretail.store.dto.ProcessSaleRequestDTO;
import com.digithink.zsretail.store.dto.TillGrantDTO;
import com.digithink.zsretail.store.model.TillGrant;
import com.digithink.zsretail.store.repository.TillGrantRepository;
import com.digithink.zsretail.store.service.TillGrantService;
import com.digithink.zsretail.store.service.TillRightsGuard;

/**
 * Test support (till authorisation, step D): the till rights of the requests in memory. The real TillRightsGuard,
 * TillGrantService and BadgeService over in-memory grants (till_grant), scan log (badge_scan_log), General Setup and
 * the stored lines of a pending ticket; and {@link #allowAll()}, a guard letting every request pass, for the tests of
 * something else.
 */
public final class InMemoryTillRights {

	/** The grants by id, as till_grant holds them (the token's hash, never the token). */
	public final Map<Long, TillGrant> grants = new LinkedHashMap<>();
	/** The rows written to badge_scan_log. */
	public final List<BadgeScanLog> log = new ArrayList<>();
	/** The lines salesLineRepository.findBySalesHeader gives: the stored lines of the pending ticket. */
	public final List<SalesLine> storedLines = new ArrayList<>();
	/** General Setup by code (TILL_GRANT_MAX_MINUTES). */
	public final Map<String, String> settings = new HashMap<>();

	public final BadgeService badges = new BadgeService();
	public final TillGrantService grantService = new TillGrantService();
	public final TillRightsGuard guard = new TillRightsGuard();

	/** How many times the next consume calls find the grant used by another request first (a race). */
	public int racesLost;

	public InMemoryTillRights() {
		try {
			set(badges, BadgeService.class, "badgeScanLogRepository", proxy(BadgeScanLogRepository.class, (m, a) -> {
				if (!"save".equals(m)) return UNHANDLED;
				log.add((BadgeScanLog) a[0]);
				return a[0];
			}));
			set(grantService, TillGrantService.class, "tillGrantRepository", grantRepository());
			set(grantService, TillGrantService.class, "generalSetupRepository",
					proxy(GeneralSetupRepository.class, (m, a) -> {
						if (!"findByCode".equals(m)) return UNHANDLED;
						if (!settings.containsKey(a[0])) return Optional.empty();
						GeneralSetup row = new GeneralSetup();
						row.setCode((String) a[0]);
						row.setValeur(settings.get(a[0]));
						return Optional.of(row);
					}));
			set(guard, TillRightsGuard.class, "badgeService", badges);
			set(guard, TillRightsGuard.class, "tillGrantService", grantService);
			set(guard, TillRightsGuard.class, "salesLineRepository", proxy(SalesLineRepository.class,
					(m, a) -> "findBySalesHeader".equals(m) ? new ArrayList<>(storedLines) : UNHANDLED));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private TillGrantRepository grantRepository() {
		return proxy(TillGrantRepository.class, (m, a) -> {
			switch (m) {
				case "save": {
					TillGrant grant = (TillGrant) a[0];
					if (grant.getId() == null) {
						grant.setId((long) grants.size() + 1);
					}
					grants.put(grant.getId(), grant);
					return grant;
				}
				case "findByTokenHash":
					return grants.values().stream().filter(g -> g.getTokenHash().equals(a[0])).findFirst();
				case "consume": {
					TillGrant grant = grants.get(a[0]);
					if (racesLost > 0) {
						racesLost--;
						return 0;
					}
					if (grant == null || grant.getConsumedAt() != null) {
						return 0;
					}
					grant.setConsumedAt((LocalDateTime) a[1]);
					grant.setConsumedByRef((String) a[2]);
					return 1;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	/** A successful scan's grant (as POST /badge/scan gives it): the token the till holds. */
	public String grant(BadgePermission action, Long sessionId, UserAccount scannedBy, UserAccount owner) {
		TillGrantDTO issued = grantService.issue(action, scannedBy, owner, "B-" + owner.getUsername(), sessionId);
		return issued == null ? null : issued.getToken();
	}

	/** The grant of a token's issue (the last one issued). */
	public TillGrant last() {
		TillGrant last = null;
		for (TillGrant grant : grants.values()) {
			last = grant;
		}
		return last;
	}

	/** A guard that lets every request pass (a close with a difference passes on a confirmation or a grant). */
	public static TillRightsGuard allowAll() {
		return new TillRightsGuard() {
			@Override
			public void checkSale(ProcessSaleRequestDTO request, UserAccount user, CashierSession session,
					SalesHeader pending, String ref) {
			}

			@Override
			public void checkReturn(ProcessReturnRequestDTO request, UserAccount user, CashierSession session,
					String ref) {
			}

			@Override
			public boolean closeWithDifference(UserAccount actor, CashierSession session, boolean confirmed,
					String token) {
				return confirmed || (token != null && !token.trim().isEmpty());
			}
		};
	}

	private static void set(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
