package com.digithink.zsretail.erp.navpospages.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.service.GeneralSetupService;

/**
 * ERP catalogue, step 6: the head office tables read with a few JPQL projections (no entity loaded, nothing written).
 */
@Component
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
@Transactional(readOnly = true)
public class JpaNavPosPagesHeadOffice implements NavPosPagesHeadOffice {

	/** Values per IN (...) query (SQL Server takes at most 2100 parameters). */
	static final int IN_CHUNK = 1000;

	static final String TAX_STAMP_ERP_ITEM_CODE = "TAX_STAMP_ERP_ITEM_CODE";

	private final EntityManager entityManager;
	private final GeneralSetupService generalSetupService;

	public JpaNavPosPagesHeadOffice(EntityManager entityManager, GeneralSetupService generalSetupService) {
		this.entityManager = entityManager;
		this.generalSetupService = generalSetupService;
	}

	@Override
	public Map<String, Family> families() {
		Map<String, Family> families = new LinkedHashMap<>();
		for (Object[] row : rows("select f.code, f.name, f.description, f.active, f.erpExternalId from ItemFamily f")) {
			families.put((String) row[0],
					new Family((String) row[0], (String) row[1], (String) row[2], (Boolean) row[3], (String) row[4]));
		}
		return families;
	}

	@Override
	public Map<String, SubFamily> subFamilies() {
		Map<String, SubFamily> subFamilies = new LinkedHashMap<>();
		for (Object[] row : rows("select s.code, s.name, s.description, s.active, s.erpExternalId, f.code"
				+ " from ItemSubFamily s left join s.itemFamily f")) {
			subFamilies.put((String) row[0], new SubFamily((String) row[0], (String) row[1], (String) row[2],
					(Boolean) row[3], (String) row[4], (String) row[5]));
		}
		return subFamilies;
	}

	@Override
	public Map<String, Item> items() {
		Map<String, Item> items = new LinkedHashMap<>();
		for (Object[] row : rows("select i.itemCode, i.name, i.description, i.unitPrice, i.defaultVAT, i.active,"
				+ " i.erpExternalId, i.itemDiscGroup, i.maximumAuthorizedDiscount, f.code, sf.code"
				+ " from Item i left join i.itemFamily f left join i.itemSubFamily sf")) {
			items.put((String) row[0], new Item((String) row[0], (String) row[1], (String) row[2], (Double) row[3],
					(Integer) row[4], (Boolean) row[5], (String) row[6], (String) row[7], (Double) row[8], (String) row[9],
					(String) row[10]));
		}
		return items;
	}

	@Override
	public Map<String, Barcode> barcodes(Collection<String> values) {
		Map<String, Barcode> barcodes = new LinkedHashMap<>();
		List<String> all = new ArrayList<>(values);
		for (int from = 0; from < all.size(); from += IN_CHUNK) {
			List<String> chunk = all.subList(from, Math.min(from + IN_CHUNK, all.size()));
			List<?> found = entityManager
					.createQuery("select b.barcode, i.itemCode, b.active from ItemBarcode b join b.item i"
							+ " where b.barcode in :values")
					.setParameter("values", chunk).getResultList();
			for (Object result : found) {
				Object[] row = (Object[]) result;
				barcodes.put((String) row[0], new Barcode((String) row[0], (String) row[1], (Boolean) row[2]));
			}
		}
		return barcodes;
	}

	@Override
	public String taxStampErpCode() {
		String code = generalSetupService.findValueByCode(TAX_STAMP_ERP_ITEM_CODE);
		return code == null || code.trim().isEmpty() ? null : code.trim();
	}

	@Override
	public String invoicesReadAfter() {
		String value = generalSetupService.findValueByCode(INVOICES_READ_AFTER);
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

	private List<Object[]> rows(String jpql) {
		List<Object[]> rows = new ArrayList<>();
		for (Object result : entityManager.createQuery(jpql).getResultList()) {
			rows.add((Object[]) result);
		}
		return rows;
	}
}
