package com.digithink.zsretail.connector.parafendri.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.connector.parafendri.dto.ParafendriCollection;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriItemCategoryRow;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSectionDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ParaFendri, step 2: the tree itemCategories split into its four levels (the sample itemCategories.json, the shape of
 * the API): each level its rows, its parent and its display order; an orphan famille kept without a catégorie and noted;
 * an orphan sous-famille, a section under a famille, a row of level 4 and a blank code left out, each with its reason,
 * at its level only.
 */
class ParafendriMapperTest {

	private final ParafendriMapper mapper = new ParafendriMapper();

	static List<ParafendriItemCategoryRow> sample() throws IOException {
		try (InputStream in = ParafendriMapperTest.class.getResourceAsStream("/parafendri/itemCategories.json")) {
			ParafendriCollection<ParafendriItemCategoryRow> page = new ObjectMapper().readValue(in,
					new TypeReference<ParafendriCollection<ParafendriItemCategoryRow>>() {
					});
			return page.getValue();
		}
	}

	private static <T> String codes(List<T> rows, Function<T, String> code) {
		return rows.stream().map(code).collect(Collectors.joining(", "));
	}

	@Test
	@DisplayName("The sample read: its 13 rows, the fields of the API (the etag and the id ignored)")
	void sampleRead() throws IOException {
		List<ParafendriItemCategoryRow> rows = sample();
		assertEquals(13, rows.size());
		ParafendriItemCategoryRow cof = rows.get(1);
		assertEquals("COS-COF", cof.getCode());
		assertEquals("Coffrets", cof.getDescription());
		assertEquals("COS", cof.getParentCategory());
		assertEquals(Integer.valueOf(1), cof.getIndentation());
		assertEquals(Boolean.TRUE, cof.getHasChildren());
		assertEquals(Integer.valueOf(20000), cof.getPresentationOrder());
		assertEquals("2026-10-23T16:02:11.127Z", cof.getLastModifiedDateTime());
	}

	@Test
	@DisplayName("Level 0, the catégories: COS and HYG, nothing left out")
	void categories() throws IOException {
		ErpCategories tree = mapper.categories(sample());
		List<ErpItemCategoryDTO> rows = tree.getCategories().getRows();
		assertEquals("COS, HYG", codes(rows, ErpItemCategoryDTO::getCode));
		ErpItemCategoryDTO cos = rows.get(0);
		assertEquals("COS", cos.getExternalId());
		assertEquals("Cosmétique", cos.getName());
		assertEquals("Cosmétique", cos.getDescription());
		assertEquals(Integer.valueOf(10000), cos.getDisplayOrder());
		assertEquals(Boolean.TRUE, cos.getActive());
		assertEquals(2, tree.getCategories().getRead());
		assertEquals(Collections.emptyMap(), tree.getCategories().getLeftOut());
	}

	@Test
	@DisplayName("Level 1, the familles: their catégorie; the orphan kept without one and noted; a blank code left out")
	void families() throws IOException {
		ErpCategories tree = mapper.categories(sample());
		List<ErpItemFamilyDTO> rows = tree.getFamilies().getRows();
		assertEquals("COS-COF, COS-PAR, FAM-ORPH", codes(rows, ErpItemFamilyDTO::getCode));
		assertEquals("COS", rows.get(0).getCategoryExternalId());
		assertEquals("COS", rows.get(1).getCategoryExternalId());
		assertNull(rows.get(2).getCategoryExternalId(), "parent XXX is no catégorie of the read");
		assertEquals("Coffrets", rows.get(0).getName());
		assertEquals(Integer.valueOf(20000), rows.get(0).getDisplayOrder());
		assertEquals(4, tree.getFamilies().getRead());
		assertEquals(3, tree.getFamilies().getKept());
		assertEquals(Collections.singletonMap(ParafendriMapper.BLANK_CODE, 1), tree.getFamilies().getLeftOut());
		assertEquals(Collections.singletonMap(ParafendriMapper.FAMILY_WITHOUT_CATEGORY, 1), tree.getFamilies().getNotes());
	}

	@Test
	@DisplayName("Level 2, the sous-familles: their famille (the orphan famille's too); one whose parent is no famille left out")
	void subFamilies() throws IOException {
		ErpCategories tree = mapper.categories(sample());
		List<ErpItemSubFamilyDTO> rows = tree.getSubFamilies().getRows();
		assertEquals("COS-COF-PAR, COS-PAR-HOM, FAM-ORPH-SF", codes(rows, ErpItemSubFamilyDTO::getCode));
		assertEquals(Arrays.asList("COS-COF", "COS-PAR", "FAM-ORPH"),
				rows.stream().map(ErpItemSubFamilyDTO::getFamilyExternalId).collect(Collectors.toList()));
		assertEquals("Coffrets parfum", rows.get(0).getName());
		assertEquals(4, tree.getSubFamilies().getRead());
		assertEquals(Collections.singletonMap(ParafendriMapper.PARENT_NOT_FAMILY, 1), tree.getSubFamilies().getLeftOut());
	}

	@Test
	@DisplayName("Level 3, the sections: their sous-famille; one under a famille and a row of level 4 left out")
	void sections() throws IOException {
		ErpCategories tree = mapper.categories(sample());
		List<ErpItemSectionDTO> rows = tree.getSections().getRows();
		assertEquals("COS-COF-PAR-LUX", codes(rows, ErpItemSectionDTO::getCode));
		assertEquals("COS-COF-PAR", rows.get(0).getSubFamilyExternalId());
		assertEquals(3, tree.getSections().getRead(), "the two sections and the row of level 4");
		assertEquals(1, tree.getSections().getLeftOut(ParafendriMapper.PARENT_NOT_SUB_FAMILY));
		assertEquals(1, tree.getSections().getLeftOut("indentation 4 (deeper than a section)"));
		assertEquals(2, tree.getSections().getLeftOut().size());
	}

	@Test
	@DisplayName("A row without indentation or below 0: left out with the sections, by its reason")
	void outsideTheLevels() {
		ParafendriItemCategoryRow none = new ParafendriItemCategoryRow();
		none.setCode("X");
		ParafendriItemCategoryRow negative = new ParafendriItemCategoryRow();
		negative.setCode("Y");
		negative.setIndentation(-1);
		ErpCategories tree = mapper.categories(Arrays.asList(none, negative));
		assertEquals(1, tree.getSections().getLeftOut(ParafendriMapper.NO_INDENTATION));
		assertEquals(1, tree.getSections().getLeftOut("indentation -1 (not a level)"));
		assertTrue(tree.getCategories().getLeftOut().isEmpty());
		assertEquals(0, tree.getFamilies().getRead());
	}
}
