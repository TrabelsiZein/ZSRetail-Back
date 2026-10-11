package com.digithink.zsretail.headoffice.support;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.support.InMemoryLoyalty;
import com.digithink.zsretail.headoffice.model.HoLoyaltyAlias;
import com.digithink.zsretail.headoffice.model.HoLoyaltyMovement;
import com.digithink.zsretail.headoffice.repository.HoLoyaltyAliasRepository;
import com.digithink.zsretail.headoffice.repository.HoLoyaltyMovementRepository;

/**
 * Test support: the head office's loyalty alias and movement tables in memory, beside the loyalty tables of the same
 * installation ({@link InMemoryLoyalty}: its ids, its members). Code cleanup step 6.7: split from InMemoryLoyalty, so
 * that core's tests need no head office class.
 */
public final class InMemoryHoLoyalty {

	public final InMemoryLoyalty loyalty;
	public final List<HoLoyaltyAlias> aliases = new ArrayList<>();
	public final List<HoLoyaltyMovement> movements = new ArrayList<>();

	public InMemoryHoLoyalty(InMemoryLoyalty loyalty) {
		this.loyalty = loyalty;
	}

	public HoLoyaltyAliasRepository aliasRepository() {
		return proxy(HoLoyaltyAliasRepository.class, (method, a) -> {
			switch (method) {
				case "findByCardNumber":
					return aliases.stream().filter(x -> a[0].equals(x.getCardNumber())).findFirst();
				case "save": {
					HoLoyaltyAlias alias = (HoLoyaltyAlias) a[0];
					if (aliases.stream().anyMatch(x -> x != alias && x.getCardNumber().equals(alias.getCardNumber()))) {
						throw new IllegalStateException("uk_ho_loyalty_alias_card");
					}
					if (alias.getId() == null) {
						alias.setId(loyalty.nextId());
						aliases.add(alias);
					}
					return alias;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	public HoLoyaltyMovementRepository movementRepository() {
		return proxy(HoLoyaltyMovementRepository.class, (method, a) -> {
			switch (method) {
				case "findByStoreIdAndStoreKey":
					return movements.stream()
							.filter(x -> Objects.equals(x.getStoreId(), a[0]) && x.getStoreKey().equals(a[1]))
							.findFirst();
				case "findOverspends": {
					long storeId = (Long) a[0];
					LocalDateTime from = (LocalDateTime) a[1];
					LocalDateTime to = (LocalDateTime) a[2];
					Pattern search = InMemoryLoyalty.like(((String) a[3]).replace("!", "!!"));
					List<Object[]> rows = movements.stream()
							.filter(m -> m.getOverspendPoints() > 0 && (storeId == 0L || m.getStoreId() == storeId)
									&& !m.getCreatedAt().isBefore(from) && !m.getCreatedAt().isAfter(to))
							.map(m -> new Object[] { m, loyalty.members.get(m.getMemberId()) })
							.filter(r -> {
								HoLoyaltyMovement m = (HoLoyaltyMovement) r[0];
								LoyaltyMember l = (LoyaltyMember) r[1];
								return l != null && (search.matcher(m.getCardNumber().toLowerCase()).matches()
										|| search.matcher(l.getCardNumber().toLowerCase()).matches()
										|| search.matcher((l.getFirstName() + " " + l.getLastName()).toLowerCase()).matches()
										|| search.matcher(Objects.toString(m.getSalesNumber(), "").toLowerCase()).matches()
										|| search.matcher(Objects.toString(m.getReturnNumber(), "").toLowerCase()).matches());
							})
							.sorted(Comparator.comparing((Object[] r) -> ((HoLoyaltyMovement) r[0]).getCreatedAt())
									.thenComparing(r -> ((HoLoyaltyMovement) r[0]).getId()).reversed())
							.collect(Collectors.toList());
					Pageable pageable = (Pageable) a[4];
					int fromIndex = (int) Math.min(rows.size(), pageable.getOffset());
					int toIndex = Math.min(rows.size(), fromIndex + pageable.getPageSize());
					return new PageImpl<>(new ArrayList<>(rows.subList(fromIndex, toIndex)), pageable, rows.size());
				}
				case "overspendTotals": {
					LocalDateTime from = (LocalDateTime) a[0];
					LocalDateTime to = (LocalDateTime) a[1];
					List<HoLoyaltyMovement> found = movements.stream().filter(m -> m.getOverspendPoints() > 0
							&& !m.getCreatedAt().isBefore(from) && !m.getCreatedAt().isAfter(to)).collect(Collectors.toList());
					return Collections.singletonList(new Object[] { (long) found.size(),
							found.stream().mapToLong(HoLoyaltyMovement::getOverspendPoints).sum() });
				}
				case "save": {
					HoLoyaltyMovement movement = (HoLoyaltyMovement) a[0];
					if (movement.getId() == null) {
						movement.setId(loyalty.nextId());
						movements.add(movement);
					}
					return movement;
				}
				default:
					return UNHANDLED;
			}
		});
	}
}
