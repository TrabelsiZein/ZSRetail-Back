package com.digithink.zsretail.core.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.dto.PosLevelDTO;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.repository.ItemCategoryRepository;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.core.utils.SearchText;

/**
 * 2.2.2: the families and sub-families of the POS screen (the till's grid, ItemSelection.vue), nothing else. A family or
 * a sub-family is empty when the grid would list none of its items ({@link ItemService#listedInPos}); a family counts
 * its items whatever their sub-family, including none. With the store settings {@link #HIDE_EMPTY_FAMILIES} /
 * {@link #HIDE_EMPTY_SUB_FAMILIES} on, the empty ones are left out; off (the default), the lists are returned untouched,
 * so the till gets the answers of 2.2.1. Counted with one grouped query ({@code ItemRepository.countPosGridItems}).
 * <p>
 * 3.1.0 (ZEN): the two optional levels around them, read-only, for an installation whose tables hold them
 * ({@link ItemTreeService#hasCategories()}, {@link ItemTreeService#hasSections()}, GET /config); else every list of
 * theirs is empty. The till's tree is the item's own: catégorie (the famille's) &gt; famille &gt; sous-famille &gt;
 * section (a section of the item's own sous-famille, by the item's section code). What no tile of a level holds falls
 * through to the level above: the familles without a catégorie, the items without famille that carry a catégorie code,
 * the items of a sous-famille without one of its sections. An inactive catégorie or section hides what it holds, as an
 * inactive sub-family does. Catégories follow {@link #HIDE_EMPTY_FAMILIES}, sections {@link #HIDE_EMPTY_SUB_FAMILIES}.
 */
@Service
public class PosCatalogueService {

	/** General Setup: hide the families without an item for the grid (store only, false by default). */
	public static final String HIDE_EMPTY_FAMILIES = "POS_HIDE_EMPTY_FAMILIES";

	/** General Setup: hide the sub-families without an item for the grid (store only, false by default). */
	public static final String HIDE_EMPTY_SUB_FAMILIES = "POS_HIDE_EMPTY_SUB_FAMILIES";

	private static final Comparator<Integer> ORDER = Comparator.nullsLast(Comparator.naturalOrder());
	private static final Comparator<String> CODE = Comparator.nullsLast(Comparator.naturalOrder());

	@Autowired
	private ItemRepository itemRepository;

	@Autowired
	private ItemFamilyRepository itemFamilyRepository;

	@Autowired
	private ItemSubFamilyRepository itemSubFamilyRepository;

	@Autowired
	private ItemCategoryRepository itemCategoryRepository;

	@Autowired
	private ItemSectionRepository itemSectionRepository;

	@Autowired
	private ItemTreeService itemTreeService;

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

	/**
	 * 3.1.0 (ZEN), GET /item-category/pos: the active catégories by display order then code; with
	 * {@link #HIDE_EMPTY_FAMILIES} on, only those leading to an item ({@link #categoryCodesWithItems}).
	 */
	public List<ItemCategory> categories() {
		if (!itemTreeService.hasCategories()) {
			return new ArrayList<>();
		}
		List<ItemCategory> categories = itemCategoryRepository.findAll().stream()
				.filter(category -> isActive(category.getActive()))
				.sorted(Comparator.comparing(ItemCategory::getDisplayOrder, ORDER).thenComparing(ItemCategory::getCode,
						CODE))
				.collect(Collectors.toList());
		if (categories.isEmpty() || !settingOn(HIDE_EMPTY_FAMILIES)) {
			return categories;
		}
		Set<String> withItems = categoryCodesWithItems(counts());
		return categories.stream().filter(category -> withItems.contains(levelCode(category.getCode())))
				.collect(Collectors.toList());
	}

	/** 3.1.0 (ZEN), GET /item-family/pos?category: the active families of one catégorie, as {@link #families}. */
	public List<ItemFamily> familiesOfCategory(String categoryCode) {
		String code = levelCode(categoryCode);
		if (code == null || !itemTreeService.hasCategories()) {
			return new ArrayList<>();
		}
		return families(itemFamilyRepository.findByCategoryCodeOrderByDisplayOrderAscCodeAsc(code).stream()
				.filter(family -> isActive(family.getActive())).collect(Collectors.toList()));
	}

	/**
	 * 3.1.0 (ZEN), GET /item-family/pos?withoutCategory=true: the active families that fall through the catégories, their
	 * catégorie code empty or naming no catégorie, as {@link #families}. One of an inactive catégorie stays hidden.
	 */
	public List<ItemFamily> familiesWithoutCategory() {
		if (!itemTreeService.hasCategories()) {
			return new ArrayList<>();
		}
		Set<String> categoryCodes = itemCategoryRepository.findAllCodes().stream().map(PosCatalogueService::levelCode)
				.filter(Objects::nonNull).collect(Collectors.toSet());
		return families(itemFamilyRepository.findAll().stream().filter(family -> isActive(family.getActive())
				&& !categoryCodes.contains(levelCode(family.getCategoryCode()))).collect(Collectors.toList()));
	}

	/**
	 * 3.1.0 (ZEN), GET /item-section/pos?subFamily: the active sections of one sub-family by display order then code; with
	 * {@link #HIDE_EMPTY_SUB_FAMILIES} on, only those holding an item. Unknown sub-family: IllegalArgumentException.
	 */
	public List<ItemSection> sections(Long subFamilyId) {
		if (!itemTreeService.hasSections()) {
			return new ArrayList<>();
		}
		ItemSubFamily subFamily = itemSubFamilyRepository.findById(subFamilyId)
				.orElseThrow(() -> new IllegalArgumentException("Item sub-family not found: " + subFamilyId));
		List<ItemSection> sections = itemSectionRepository.findBySubFamilyCode(subFamily.getCode()).stream()
				.filter(section -> isActive(section.getActive()))
				.sorted(Comparator.comparing(ItemSection::getDisplayOrder, ORDER).thenComparing(ItemSection::getCode,
						CODE))
				.collect(Collectors.toList());
		if (sections.isEmpty() || !settingOn(HIDE_EMPTY_SUB_FAMILIES)) {
			return sections;
		}
		SectionCounts counts = sectionCounts();
		return sections.stream().filter(section -> counts.has(subFamily.getId(), section.getCode()))
				.collect(Collectors.toList());
	}

	/**
	 * 3.1.0: the families and sub-families the till's search found (at most {@link #SEARCH_LIMIT} each); with the ZEN
	 * levels, the catégories and sections too (null when the installation has none) and the path of every hit.
	 */
	public static final class Found {
		private final List<ItemFamily> families;
		private final List<ItemSubFamily> subFamilies;
		private final List<ItemCategory> categories;
		private final List<ItemSection> sections;
		private final Map<Object, List<PosLevelDTO.Step>> paths;

		Found(List<ItemFamily> families, List<ItemSubFamily> subFamilies) {
			this(families, subFamilies, null, null, null);
		}

		Found(List<ItemFamily> families, List<ItemSubFamily> subFamilies, List<ItemCategory> categories,
				List<ItemSection> sections, Map<Object, List<PosLevelDTO.Step>> paths) {
			this.families = families;
			this.subFamilies = subFamilies;
			this.categories = categories;
			this.sections = sections;
			this.paths = paths;
		}

		public List<ItemFamily> getFamilies() {
			return families;
		}

		public List<ItemSubFamily> getSubFamilies() {
			return subFamilies;
		}

		/** The catégories found; null when the installation has none (the answer then has no "categories"). */
		public List<ItemCategory> getCategories() {
			return categories;
		}

		/** The sections found; null when the installation has none (the answer then has no "sections"). */
		public List<ItemSection> getSections() {
			return sections;
		}

		/** True when the hits carry their path: the installation has catégories or sections. */
		public boolean hasPaths() {
			return paths != null;
		}

		/** The path of one hit of this answer (the very object), from its catégorie down to itself; null when none. */
		public List<PosLevelDTO.Step> pathOf(Object hit) {
			return paths == null ? null : paths.get(hit);
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
	 * <p>
	 * 3.1.0 (ZEN): with catégories, the catégories found the same way (active, leading to an item); with sections, the
	 * sections (active, in an active sub-family of an active family, holding an item); and then every hit carries its
	 * path ({@link Found#pathOf}). Without both, the answer is the one above.
	 */
	public Found search(String query) {
		List<String> words = SearchText.words(query);
		if (words.isEmpty()) {
			return new Found(List.of(), List.of());
		}
		boolean withCategories = itemTreeService.hasCategories();
		boolean withSections = itemTreeService.hasSections();
		List<ItemFamily> families = itemFamilyRepository.findAll().stream()
				.filter(family -> isActive(family.getActive()) && SearchText.matches(words, family.getName()))
				.collect(Collectors.toList());
		List<ItemSubFamily> subFamilies = itemSubFamilyRepository.findAll().stream()
				.filter(subFamily -> isActive(subFamily.getActive()) && subFamily.getItemFamily() != null
						&& isActive(subFamily.getItemFamily().getActive())
						&& SearchText.matches(words, subFamily.getName()))
				.collect(Collectors.toList());
		List<ItemCategory> allCategories = withCategories ? itemCategoryRepository.findAll() : List.of();
		List<ItemCategory> categories = allCategories.stream()
				.filter(category -> isActive(category.getActive()) && SearchText.matches(words, category.getName()))
				.collect(Collectors.toList());
		List<ItemSection> sections = withSections ? itemSectionRepository.findAll().stream()
				.filter(section -> isActive(section.getActive()) && SearchText.matches(words, section.getName()))
				.collect(Collectors.toList()) : List.of();
		boolean levels = withCategories || withSections;
		if (families.isEmpty() && subFamilies.isEmpty() && categories.isEmpty() && sections.isEmpty()) {
			return levels ? new Found(families, subFamilies, withCategories ? categories : null,
					withSections ? sections : null, new IdentityHashMap<>()) : new Found(families, subFamilies);
		}
		GridCounts counts = counts();
		families = families.stream().filter(family -> counts.familyHasItems(family.getId()))
				.sorted(searchOrder(words, ItemFamily::getName)).limit(SEARCH_LIMIT).collect(Collectors.toList());
		subFamilies = subFamilies.stream().filter(subFamily -> counts.subFamilyHasItems(subFamily.getId()))
				.sorted(searchOrder(words, ItemSubFamily::getName)).limit(SEARCH_LIMIT).collect(Collectors.toList());
		if (!levels) {
			return new Found(families, subFamilies);
		}
		if (!categories.isEmpty()) {
			Set<String> withItems = categoryCodesWithItems(counts);
			categories = categories.stream().filter(category -> withItems.contains(levelCode(category.getCode())))
					.sorted(searchOrder(words, ItemCategory::getName)).limit(SEARCH_LIMIT).collect(Collectors.toList());
		}
		Map<String, ItemSubFamily> sectionSubFamilies = subFamiliesByCode(sections);
		if (!sections.isEmpty()) {
			SectionCounts sectionCounts = sectionCounts();
			sections = sections.stream().filter(section -> {
				ItemSubFamily subFamily = sectionSubFamilies.get(levelCode(section.getSubFamilyCode()));
				return subFamily != null && isActive(subFamily.getActive()) && subFamily.getItemFamily() != null
						&& isActive(subFamily.getItemFamily().getActive())
						&& sectionCounts.has(subFamily.getId(), section.getCode());
			}).sorted(searchOrder(words, ItemSection::getName)).limit(SEARCH_LIMIT).collect(Collectors.toList());
		}
		Map<String, ItemCategory> categoriesByCode = new HashMap<>();
		for (ItemCategory category : allCategories) {
			categoriesByCode.putIfAbsent(levelCode(category.getCode()), category);
		}
		Map<Object, List<PosLevelDTO.Step>> paths = new IdentityHashMap<>();
		categories.forEach(category -> paths.put(category, List.of(step(category))));
		families.forEach(family -> paths.put(family, familyPath(family, categoriesByCode)));
		subFamilies.forEach(subFamily -> paths.put(subFamily, subFamilyPath(subFamily, categoriesByCode)));
		sections.forEach(section -> {
			List<PosLevelDTO.Step> path = new ArrayList<>(
					subFamilyPath(sectionSubFamilies.get(levelCode(section.getSubFamilyCode())), categoriesByCode));
			path.add(new PosLevelDTO.Step(PosLevelDTO.SECTION, section.getId(), section.getCode(), section.getName()));
			paths.put(section, path);
		});
		return new Found(families, subFamilies, withCategories ? categories : null, withSections ? sections : null,
				paths);
	}

	/** The sub-families of these sections, by code (one query; none when no section). */
	private Map<String, ItemSubFamily> subFamiliesByCode(List<ItemSection> sections) {
		Set<String> codes = sections.stream().map(section -> levelCode(section.getSubFamilyCode()))
				.filter(Objects::nonNull).collect(Collectors.toSet());
		Map<String, ItemSubFamily> byCode = new HashMap<>();
		if (!codes.isEmpty()) {
			for (ItemSubFamily subFamily : itemSubFamilyRepository.findByCodeIn(codes)) {
				byCode.put(levelCode(subFamily.getCode()), subFamily);
			}
		}
		return byCode;
	}

	/** [its catégorie, when one of that code exists], the family. */
	private static List<PosLevelDTO.Step> familyPath(ItemFamily family, Map<String, ItemCategory> categoriesByCode) {
		List<PosLevelDTO.Step> path = new ArrayList<>();
		ItemCategory category = categoriesByCode.get(levelCode(family.getCategoryCode()));
		if (category != null) {
			path.add(step(category));
		}
		path.add(new PosLevelDTO.Step(PosLevelDTO.FAMILY, family.getId(), family.getCode(), family.getName()));
		return path;
	}

	/** The family's path, then the sub-family. */
	private static List<PosLevelDTO.Step> subFamilyPath(ItemSubFamily subFamily,
			Map<String, ItemCategory> categoriesByCode) {
		List<PosLevelDTO.Step> path = subFamily.getItemFamily() == null ? new ArrayList<>()
				: familyPath(subFamily.getItemFamily(), categoriesByCode);
		path.add(new PosLevelDTO.Step(PosLevelDTO.SUB_FAMILY, subFamily.getId(), subFamily.getCode(),
				subFamily.getName()));
		return path;
	}

	private static PosLevelDTO.Step step(ItemCategory category) {
		return new PosLevelDTO.Step(PosLevelDTO.CATEGORY, category.getId(), category.getCode(), category.getName());
	}

	private static boolean isActive(Boolean active) {
		return active == null || active;
	}

	/** A level's code as the till compares it: trimmed, null when blank. */
	static String levelCode(String code) {
		if (code == null) {
			return null;
		}
		String trimmed = code.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** Names starting with the first word first, then by name (case and accents ignored). */
	private static <T> Comparator<T> searchOrder(List<String> words, Function<T, String> name) {
		return Comparator.<T, Boolean>comparing(entry -> !SearchText.startsWithFirstWord(words, name.apply(entry)))
				.thenComparing(entry -> SearchText.fold(name.apply(entry)));
	}

	GridCounts counts() {
		return GridCounts.of(itemRepository.countPosGridItems());
	}

	/**
	 * 3.1.0 (ZEN): the codes of the catégories leading to an item: an active family of that catégorie holding an item
	 * of the grid ({@link GridCounts#familyHasItems}), or an item without famille that carries the code.
	 */
	Set<String> categoryCodesWithItems(GridCounts counts) {
		Set<String> codes = new HashSet<>();
		for (ItemFamily family : itemFamilyRepository.findAll()) {
			String code = levelCode(family.getCategoryCode());
			if (code != null && isActive(family.getActive()) && counts.familyHasItems(family.getId())) {
				codes.add(code);
			}
		}
		for (Object[] row : itemRepository.countPosCategoryItems()) {
			String code = levelCode((String) row[0]);
			if (code != null && ((Number) row[1]).longValue() > 0) {
				codes.add(code);
			}
		}
		return codes;
	}

	SectionCounts sectionCounts() {
		return SectionCounts.of(itemRepository.countPosSectionItems());
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

	/** 3.1.0 (ZEN): the grid's items counted by sub-family and section code, from the rows of {@code countPosSectionItems}. */
	static final class SectionCounts {

		private final Map<Long, Map<String, Long>> bySubFamily = new HashMap<>();

		static SectionCounts of(List<Object[]> rows) {
			SectionCounts counts = new SectionCounts();
			for (Object[] row : rows) {
				String code = levelCode((String) row[1]);
				long count = ((Number) row[2]).longValue();
				if (row[0] != null && code != null && count > 0) {
					counts.bySubFamily.computeIfAbsent((Long) row[0], id -> new HashMap<>()).merge(code, count, Long::sum);
				}
			}
			return counts;
		}

		/** The number of the grid's items of this sub-family that carry this section code. */
		long count(Long subFamilyId, String sectionCode) {
			Map<String, Long> codes = bySubFamily.get(subFamilyId);
			String code = levelCode(sectionCode);
			return codes == null || code == null ? 0L : codes.getOrDefault(code, 0L);
		}

		boolean has(Long subFamilyId, String sectionCode) {
			return count(subFamilyId, sectionCode) > 0;
		}
	}
}
