package com.digithink.zsretail.core.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.dto.ItemTreeDTO;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.repository.ItemCategoryRepository;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;

import lombok.RequiredArgsConstructor;

/**
 * Item variants (ZEN), step 11b: the read-only pages "Catégories" and "Sections" (store and head office). The ERP's tree
 * is catégorie > famille > sous-famille > section, linked by codes (item_family.category_code,
 * item_section.sub_family_code). The tables are filled by the catalogue read (head office) or the copies down (store);
 * empty for a customer whose ERP gives no tree (EMTOP, Happyness), so the pages do not exist there
 * ({@link #hasCategories()}, {@link #hasSections()}). A few hundred rows at most: read whole, ordered here.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ItemTreeService {

	private static final Comparator<Integer> ORDER = Comparator.nullsLast(Comparator.naturalOrder());
	private static final Comparator<String> CODE = Comparator.nullsLast(Comparator.naturalOrder());

	private final ItemCategoryRepository categoryRepository;
	private final ItemSectionRepository sectionRepository;
	private final ItemFamilyRepository familyRepository;
	private final ItemSubFamilyRepository subFamilyRepository;

	/** GET /config itemCategories: the catégorie table has a row. */
	public boolean hasCategories() {
		return categoryRepository.existsByCodeIsNotNull();
	}

	/** GET /config itemSections: the section table has a row. */
	public boolean hasSections() {
		return sectionRepository.existsByCodeIsNotNull();
	}

	/** Every catégorie by display order then code, with the number of families under it. */
	public List<ItemTreeDTO.Category> categories() {
		Map<String, Long> counts = new HashMap<>();
		for (Object[] row : familyRepository.countFamiliesByCategory()) {
			counts.put((String) row[0], ((Number) row[1]).longValue());
		}
		List<ItemCategory> categories = new ArrayList<>(categoryRepository.findAll());
		categories.sort(Comparator.comparing(ItemCategory::getDisplayOrder, ORDER).thenComparing(ItemCategory::getCode,
				CODE));
		List<ItemTreeDTO.Category> lines = new ArrayList<>();
		for (ItemCategory category : categories) {
			ItemTreeDTO.Category line = new ItemTreeDTO.Category();
			line.setCode(category.getCode());
			line.setName(category.getName());
			line.setDescription(category.getDescription());
			line.setActive(!Boolean.FALSE.equals(category.getActive()));
			line.setFamilies(counts.getOrDefault(category.getCode(), 0L));
			lines.add(line);
		}
		return lines;
	}

	/** The families under one catégorie, in list order; none for a blank code. */
	public List<ItemTreeDTO.Family> familiesOf(String categoryCode) {
		List<ItemTreeDTO.Family> lines = new ArrayList<>();
		if (categoryCode == null || categoryCode.trim().isEmpty()) {
			return lines;
		}
		for (ItemFamily family : familyRepository.findByCategoryCodeOrderByDisplayOrderAscCodeAsc(categoryCode.trim())) {
			ItemTreeDTO.Family line = new ItemTreeDTO.Family();
			line.setCode(family.getCode());
			line.setName(family.getName());
			line.setActive(!Boolean.FALSE.equals(family.getActive()));
			lines.add(line);
		}
		return lines;
	}

	/**
	 * Every section by its sous-famille code, display order then code, with its path: the sous-famille (by code), its
	 * famille and that famille's catégorie (by code). A level without a row leaves its name (and what is above it) null.
	 */
	public List<ItemTreeDTO.Section> sections() {
		List<ItemSection> sections = new ArrayList<>(sectionRepository.findAll());
		sections.sort(Comparator.comparing(ItemSection::getSubFamilyCode, CODE)
				.thenComparing(ItemSection::getDisplayOrder, ORDER).thenComparing(ItemSection::getCode, CODE));
		Set<String> subFamilyCodes = sections.stream().map(ItemSection::getSubFamilyCode).filter(Objects::nonNull)
				.collect(Collectors.toSet());
		Map<String, ItemSubFamily> subFamilies = new HashMap<>();
		if (!subFamilyCodes.isEmpty()) {
			for (ItemSubFamily subFamily : subFamilyRepository.findByCodeIn(subFamilyCodes)) {
				subFamilies.put(subFamily.getCode(), subFamily);
			}
		}
		Set<String> categoryCodes = subFamilies.values().stream().map(ItemSubFamily::getItemFamily)
				.filter(Objects::nonNull).map(ItemFamily::getCategoryCode).filter(Objects::nonNull)
				.collect(Collectors.toSet());
		Map<String, String> categoryNames = new HashMap<>();
		if (!categoryCodes.isEmpty()) {
			for (ItemCategory category : categoryRepository.findByCodeIn(categoryCodes)) {
				categoryNames.put(category.getCode(), category.getName());
			}
		}
		List<ItemTreeDTO.Section> lines = new ArrayList<>();
		for (ItemSection section : sections) {
			ItemTreeDTO.Section line = new ItemTreeDTO.Section();
			line.setCode(section.getCode());
			line.setName(section.getName());
			line.setDescription(section.getDescription());
			line.setActive(!Boolean.FALSE.equals(section.getActive()));
			line.setSubFamilyCode(section.getSubFamilyCode());
			ItemSubFamily subFamily = subFamilies.get(section.getSubFamilyCode());
			if (subFamily != null) {
				line.setSubFamilyName(subFamily.getName());
				ItemFamily family = subFamily.getItemFamily();
				if (family != null) {
					line.setFamilyCode(family.getCode());
					line.setFamilyName(family.getName());
					line.setCategoryCode(family.getCategoryCode());
					line.setCategoryName(categoryNames.get(family.getCategoryCode()));
				}
			}
			lines.add(line);
		}
		return lines;
	}
}
