package com.digithink.zsretail.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.repository.ItemRepository;

/**
 * 2.2.2: the families and sub-families of the POS screen (the till's grid, ItemSelection.vue), nothing else. A family or
 * a sub-family is empty when the grid would list none of its items ({@link ItemService#listedInPos}); a family counts
 * its items whatever their sub-family, including none. With the store settings {@link #HIDE_EMPTY_FAMILIES} /
 * {@link #HIDE_EMPTY_SUB_FAMILIES} on, the empty ones are left out; off (the default), the lists are returned untouched,
 * so the till gets the answers of 2.2.1. Counted with one grouped query ({@code ItemRepository.countPosGridItems}).
 */
@Service
public class PosCatalogueService {

	/** General Setup: hide the families without an item for the grid (store only, false by default). */
	public static final String HIDE_EMPTY_FAMILIES = "POS_HIDE_EMPTY_FAMILIES";

	/** General Setup: hide the sub-families without an item for the grid (store only, false by default). */
	public static final String HIDE_EMPTY_SUB_FAMILIES = "POS_HIDE_EMPTY_SUB_FAMILIES";

	@Autowired
	private ItemRepository itemRepository;

	@Autowired
	private GeneralSetupService generalSetupService;

	/** The families for the grid: all of them, or those with an item when {@link #HIDE_EMPTY_FAMILIES} is on. */
	public List<ItemFamily> families(List<ItemFamily> families) {
		if (!settingOn(HIDE_EMPTY_FAMILIES)) {
			return families;
		}
		GridCounts counts = counts();
		return families.stream().filter(family -> counts.familyHasItems(family.getId())).collect(Collectors.toList());
	}

	/** The sub-families for the grid: all, or those with an item when {@link #HIDE_EMPTY_SUB_FAMILIES} is on. */
	public List<ItemSubFamily> subFamilies(List<ItemSubFamily> subFamilies) {
		if (!settingOn(HIDE_EMPTY_SUB_FAMILIES)) {
			return subFamilies;
		}
		GridCounts counts = counts();
		return subFamilies.stream().filter(subFamily -> counts.subFamilyHasItems(subFamily.getId()))
				.collect(Collectors.toList());
	}

	GridCounts counts() {
		return GridCounts.of(itemRepository.countPosGridItems());
	}

	private boolean settingOn(String code) {
		String value = generalSetupService.findValueByCode(code);
		return value != null && "true".equalsIgnoreCase(value.trim());
	}

	/** The grid's items counted by family and by sub-family, from the rows of {@code countPosGridItems}. */
	static final class GridCounts {

		private final Set<Long> families = new HashSet<>();
		private final Map<Long, Long> bySubFamily = new HashMap<>();

		static GridCounts of(List<Object[]> rows) {
			GridCounts counts = new GridCounts();
			for (Object[] row : rows) {
				Long subFamilyFamily = (Long) row[0];
				Long itemFamily = (Long) row[1];
				Long subFamily = (Long) row[2];
				long count = ((Number) row[3]).longValue();
				if (count <= 0) {
					continue;
				}
				// an item with a sub-family is shown under the sub-family's family; one without, under its own family
				Long family = subFamily != null ? subFamilyFamily : itemFamily;
				if (family != null) {
					counts.families.add(family);
				}
				if (subFamily != null) {
					counts.bySubFamily.merge(subFamily, count, Long::sum);
				}
			}
			return counts;
		}

		boolean familyHasItems(Long familyId) {
			return families.contains(familyId);
		}

		boolean subFamilyHasItems(Long subFamilyId) {
			return bySubFamily.getOrDefault(subFamilyId, 0L) > 0;
		}

		long subFamilyCount(Long subFamilyId) {
			return bySubFamily.getOrDefault(subFamilyId, 0L);
		}
	}
}
