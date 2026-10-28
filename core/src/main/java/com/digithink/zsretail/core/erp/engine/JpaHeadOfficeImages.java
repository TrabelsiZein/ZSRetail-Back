package com.digithink.zsretail.core.erp.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.persistence.EntityManager;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.config.ConditionalOnErpAbility;
import com.digithink.zsretail.core.model.ItemImage;
import com.digithink.zsretail.core.model.enumeration.CatalogueKind;
import com.digithink.zsretail.core.model.enumeration.ImageSource;
import com.digithink.zsretail.core.repository.ItemImageRepository;
import com.digithink.zsretail.core.service.CatalogueHeadOfficeHooks;
import com.digithink.zsretail.erp.api.ErpAbility;

/**
 * 3.1.1 (item images): {@link HeadOfficeImages} on the head office tables. The variants of the NAV item numbers asked
 * are read with one JPQL projection (no item loaded), their images with ItemImageRepository; one transaction per call
 * writes the URL rows of the variants that differ (FILE rows untouched), then every variant written is recorded for the
 * stores (CatalogueHeadOfficeHooks.afterImport, an item copy goes down again with its images).
 */
@Component
@ConditionalOnErpAbility(ErpAbility.HEAD_OFFICE_STOCK_POINTS)
public class JpaHeadOfficeImages implements HeadOfficeImages {

	/** Item ids per IN (...) of the images' read (the repository takes at most 2,000). */
	static final int IDS_PER_READ = 1000;

	/** created_by / updated_by of the rows written. */
	static final String WRITER = "ERP";

	private final EntityManager entityManager;
	private final ItemImageRepository images;
	private final TransactionOperations transactions;
	/** The head office's hooks (the copies down), or null. */
	private final Supplier<CatalogueHeadOfficeHooks> hooks;

	@Autowired
	public JpaHeadOfficeImages(EntityManager entityManager, ItemImageRepository images,
			PlatformTransactionManager transactionManager, ObjectProvider<CatalogueHeadOfficeHooks> hooks) {
		this(entityManager, images, new TransactionTemplate(transactionManager), hooks::getIfAvailable);
	}

	/** With given transactions and hooks: used by the tests. */
	JpaHeadOfficeImages(EntityManager entityManager, ItemImageRepository images, TransactionOperations transactions,
			Supplier<CatalogueHeadOfficeHooks> hooks) {
		this.entityManager = entityManager;
		this.images = images;
		this.transactions = transactions;
		this.hooks = hooks;
	}

	@Override
	public List<String> navItemNos() {
		return transactions.execute(status -> entityManager.createQuery("select distinct i.navItemNo from Item i"
				+ " where i.navItemNo is not null and i.navItemNo <> '' order by i.navItemNo", String.class)
				.getResultList());
	}

	@Override
	public Applied apply(Map<String, List<String>> urlsByNavItemNo, boolean write) {
		Applied applied = new Applied();
		if (urlsByNavItemNo.isEmpty()) {
			return applied;
		}
		List<String> written = transactions.execute(status -> applyIn(urlsByNavItemNo, write, applied));
		CatalogueHeadOfficeHooks recorder = hooks.get();
		if (recorder != null && !written.isEmpty()) {
			recorder.afterImport(CatalogueKind.ITEM, written);
		}
		return applied;
	}

	/** In the transaction: compares and (write) replaces; the item codes written. */
	private List<String> applyIn(Map<String, List<String>> urlsByNavItemNo, boolean write, Applied applied) {
		List<Object[]> variants = entityManager.createQuery(
				"select i.id, i.itemCode, i.navItemNo from Item i where i.navItemNo in :navItemNos", Object[].class)
				.setParameter("navItemNos", new ArrayList<>(urlsByNavItemNo.keySet())).getResultList();
		Map<String, Boolean> known = new HashMap<>();
		variants.forEach(row -> known.put((String) row[2], Boolean.TRUE));
		urlsByNavItemNo.keySet().stream().filter(no -> !known.containsKey(no)).forEach(applied.unknown::add);

		List<Long> ids = variants.stream().map(row -> (Long) row[0]).collect(Collectors.toList());
		Map<Long, List<ItemImage>> urlRows = new HashMap<>();
		for (int from = 0; from < ids.size(); from += IDS_PER_READ) {
			for (ItemImage image : images.findOfItems(ids.subList(from, Math.min(from + IDS_PER_READ, ids.size())))) {
				if (image.getSource() == ImageSource.URL) {
					urlRows.computeIfAbsent(image.getItemId(), id -> new ArrayList<>()).add(image);
				}
			}
		}

		List<String> written = new ArrayList<>();
		for (Object[] row : variants) {
			Long itemId = (Long) row[0];
			List<String> wanted = urlsByNavItemNo.get((String) row[2]);
			List<ItemImage> current = urlRows.getOrDefault(itemId, new ArrayList<>());
			if (same(current, wanted)) {
				applied.itemsEqual++;
				continue;
			}
			applied.itemsChanged++;
			if (wanted.isEmpty()) {
				applied.itemsCleared++;
			}
			if (write) {
				images.deleteAll(current);
				for (int sort = 0; sort < wanted.size(); sort++) {
					images.save(row(itemId, wanted.get(sort), sort));
				}
				written.add((String) row[1]);
			}
		}
		return written;
	}

	/** The URL rows are these addresses, in order, the first one main and only it. */
	static boolean same(List<ItemImage> current, List<String> wanted) {
		if (current.size() != wanted.size()) {
			return false;
		}
		for (int i = 0; i < wanted.size(); i++) {
			ItemImage image = current.get(i);
			if (!wanted.get(i).equals(image.getPath()) || image.isMain() != (i == 0) || image.getSortOrder() != i) {
				return false;
			}
		}
		return true;
	}

	static ItemImage row(Long itemId, String url, int sort) {
		ItemImage image = new ItemImage();
		image.setItemId(itemId);
		image.setSource(ImageSource.URL);
		image.setPath(url);
		image.setSortOrder(sort);
		image.setMain(sort == 0);
		image.setCreatedBy(WRITER);
		image.setUpdatedBy(WRITER);
		return image;
	}
}
