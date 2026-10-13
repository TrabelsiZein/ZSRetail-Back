package com.digithink.zsretail.core.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.core.utils.SearchText;

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
	private ItemFamilyRepository itemFamilyRepository;

	@Autowired
	private ItemSubFamilyRepository itemSubFamilyRepository;

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

	/** 3.1.0: the families and sub-families the till's search found (at most {@link #SEARCH_LIMIT} each). */
	public static final class Found {
		private final List<ItemFamily> families;
		private final List<ItemSubFamily> subFamilies;

		Found(List<ItemFamily> families, List<ItemSubFamily> subFamilies) {
			this.families = families;
			this.subFamilies = subFamilies;
		}

		public List<ItemFamily> getFamilies() {
			return families;
		}

		public List<ItemSubFamily> getSubFamilies() {
			return subFamilies;
		}
	}

	/** 3.1.0: the most families, and the most sub-families, one search answer holds. */
	public static final int SEARCH_LIMIT = 10;

	/**
	 * 3.1.0, the till's search (shown before its items): the families and sub-families whose name holds every word typed,
	 * anywhere and in any order, case and accents ignored ({@link SearchText}). Only tiles that lead to something to sell,
	 * whatever the settings {@link #HIDE_EMPTY_FAMILIES} / {@link #HIDE_EMPTY_SUB_FAMILIES}: active (a sub-family in an
	 * active family) and holding an item the grid lists ({@link #counts}, counted only when a name matched). Names
	 * starting with the first word come first, then by name.
	 */
	public Found search(String query) {
		List<String> words = SearchText.words(query);
		if (words.isEmpty()) {
			return new Found(List.of(), List.of());
		}
		List<ItemFamily> families = itemFamilyRepository.findAll().stream()
				.filter(family -> isActive(family.getActive()) && SearchText.matches(words, family.getName()))
				.collect(Collectors.toList());
		List<ItemSubFamily> subFamilies = itemSubFamilyRepository.findAll().stream()
				.filter(subFamily -> isActive(subFamily.getActive()) && subFamily.getItemFamily() != null
						&& isActive(subFamily.getItemFamily().getActive())
						&& SearchText.matches(words, subFamily.getName()))
				.collect(Collectors.toList());
		if (families.isEmpty() && subFamilies.isEmpty()) {
			return new Found(families, subFamilies);
		}
		GridCounts counts = counts();
		return new Found(
				families.stream().filter(family -> counts.familyHasItems(family.getId()))
						.sorted(searchOrder(words, ItemFamily::getName)).limit(SEARCH_LIMIT).collect(Collectors.toList()),
				subFamilies.stream().filter(subFamily -> counts.subFamilyHasItems(subFamily.getId()))
						.sorted(searchOrder(words, ItemSubFamily::getName)).limit(SEARCH_LIMIT)
						.collect(Collectors.toList()));
	}

	private static boolean isActive(Boolean active) {
		return active == null || active;
	}

	/** Names starting with the first word first, then by name (case and accents ignored). */
	private static <T> Comparator<T> searchOrder(List<String> words, Function<T, String> name) {
		return Comparator.<T, Boolean>comparing(entry -> !SearchText.startsWithFirstWord(words, name.apply(entry)))
				.thenComparing(entry -> SearchText.fold(name.apply(entry)));
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
