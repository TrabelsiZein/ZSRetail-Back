package com.digithink.zsretail.headoffice.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.persistence.EntityManager;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.model.enumeration.CatalogueKind;
import com.digithink.zsretail.core.service.CatalogueHeadOfficeHooks;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;

/**
 * ERP catalogue, step 7: the changes of an ERP import reach the stores. On a head office whose catalogue only comes from
 * the ERP, the four imports of ErpItemBootstrapService save with the repositories and never call the catalogue hooks;
 * this aspect (a head office only bean, like {@link HeadOfficeErpGuard}; nothing in erp/ changes) lets each import run,
 * then calls {@link CatalogueHeadOfficeHooks#afterSave} for each record of the returned list (an item also records its
 * barcodes again), exactly as a save on the item pages does. Stock point copies, step 4: the items and the barcodes go
 * in one bulk recording per list instead, for the stores whose point has a row for the item (HoCatalogueService
 * erpItemsSaved / erpBarcodesSaved); before an import of barcodes, the value and the item of the barcodes it may change
 * are read, so a barcode moved to another item, or whose value changed, reaches the stores that had it.
 * <ul>
 * <li>One transaction: the import and the change rows commit or roll back together. The aspect opens it (REQUIRED)
 * around the import and the recording, so the import's own @Transactional joins it whatever the order of the two
 * advices; CopiesDownFeed.recordChange (MANDATORY) runs inside it.</li>
 * <li>Recorded after the import, not during it: the ho_down_sequence row is locked only while recording.</li>
 * <li>Before recording, and every {@value #FLUSH_EVERY} records, the persistence context is flushed and cleared, so the
 * queries of the recording do not dirty-check every row the import loaded.</li>
 * <li>No hooks bean (none expected in this mode, but checked), or a null or empty list: nothing recorded, the sequence
 * untouched. TAX_STAMP is never recorded (afterSave leaves it out).</li>
 * </ul>
 */
@Aspect
@Component
@ConditionalOnInstallation(Installation.HEAD_OFFICE_ERP_CATALOGUE_ONLY)
public class HeadOfficeErpCatalogueRecorder {

	/** Records between two flush-and-clear of the persistence context. */
	static final int FLUSH_EVERY = 200;

	private final ObjectProvider<CatalogueHeadOfficeHooks> hooks;
	private final TransactionOperations transactions;
	private final Runnable flushAndClear;

	@Autowired
	public HeadOfficeErpCatalogueRecorder(ObjectProvider<CatalogueHeadOfficeHooks> hooks,
			PlatformTransactionManager transactionManager, EntityManager entityManager) {
		this(hooks, new TransactionTemplate(transactionManager), () -> {
			entityManager.flush();
			entityManager.clear();
		});
	}

	/** With given collaborators: used by the tests. */
	public HeadOfficeErpCatalogueRecorder(ObjectProvider<CatalogueHeadOfficeHooks> hooks,
			TransactionOperations transactions, Runnable flushAndClear) {
		this.hooks = hooks;
		this.transactions = transactions;
		this.flushAndClear = flushAndClear;
	}

	@Around("execution(* com.digithink.zsretail.core.erp.service.ErpItemBootstrapService.importItemFamilies(..))")
	public Object families(ProceedingJoinPoint call) throws Throwable {
		return importAndRecord(call, CatalogueKind.FAMILY);
	}

	@Around("execution(* com.digithink.zsretail.core.erp.service.ErpItemBootstrapService.importItemSubFamilies(..))")
	public Object subFamilies(ProceedingJoinPoint call) throws Throwable {
		return importAndRecord(call, CatalogueKind.SUBFAMILY);
	}

	/** Item variants (ZEN), step 7: the catégories, recorded for every store as the families. */
	@Around("execution(* com.digithink.zsretail.core.erp.service.ErpItemBootstrapService.importItemCategories(..))")
	public Object categories(ProceedingJoinPoint call) throws Throwable {
		return importAndRecord(call, CatalogueKind.CATEGORY);
	}

	/** Item variants (ZEN), step 7: the sections, recorded for every store as the sub-families. */
	@Around("execution(* com.digithink.zsretail.core.erp.service.ErpItemBootstrapService.importItemSections(..))")
	public Object sections(ProceedingJoinPoint call) throws Throwable {
		return importAndRecord(call, CatalogueKind.SECTION);
	}

	@Around("execution(* com.digithink.zsretail.core.erp.service.ErpItemBootstrapService.importItems(..))")
	public Object items(ProceedingJoinPoint call) throws Throwable {
		return importAndRecord(call, CatalogueKind.ITEM);
	}

	@Around("execution(* com.digithink.zsretail.core.erp.service.ErpItemBootstrapService.importItemBarcodes(..))")
	public Object barcodes(ProceedingJoinPoint call) throws Throwable {
		return importAndRecord(call, CatalogueKind.BARCODE);
	}

	/**
	 * The import, then the recording, in one transaction. Stock point copies, step 4: on a head office whose catalogue
	 * comes from the ERP, the items and the barcodes of the list in one bulk recording, grouped by store
	 * (HoCatalogueService.erpItemsSaved / erpBarcodesSaved; for the barcodes, their value and item read before the
	 * import); otherwise afterSave per saved record.
	 */
	Object importAndRecord(ProceedingJoinPoint call, CatalogueKind kind) throws Throwable {
		CatalogueHeadOfficeHooks recorder = hooks == null ? null : hooks.getIfAvailable();
		if (recorder == null) {
			return call.proceed();
		}
		HoCatalogueService byPoint = (kind == CatalogueKind.ITEM || kind == CatalogueKind.BARCODE)
				&& recorder instanceof HoCatalogueService && ((HoCatalogueService) recorder).recordsByStockPoint()
						? (HoCatalogueService) recorder
						: null;
		try {
			return transactions.execute(status -> {
				Map<Long, HoCatalogueService.BarcodeBefore> before = byPoint != null && kind == CatalogueKind.BARCODE
						? barcodesBefore(byPoint, call.getArgs())
						: Collections.emptyMap();
				Object saved = proceed(call);
				if (saved instanceof List && !((List<?>) saved).isEmpty()) {
					if (byPoint != null) {
						recordInBulk(byPoint, kind, (List<?>) saved, before);
					} else {
						record(recorder, kind, (List<?>) saved);
					}
				}
				return saved;
			});
		} catch (ImportFailure failure) {
			throw failure.getCause();
		}
	}

	/** The value and item of the barcodes the import may change: by the ERP ids and the values of its list. */
	private static Map<Long, HoCatalogueService.BarcodeBefore> barcodesBefore(HoCatalogueService catalogue,
			Object[] args) {
		List<String> externalIds = new ArrayList<>();
		List<String> values = new ArrayList<>();
		if (args != null && args.length > 0 && args[0] instanceof List) {
			for (Object dto : (List<?>) args[0]) {
				if (dto instanceof ErpItemBarcodeDTO) {
					externalIds.add(((ErpItemBarcodeDTO) dto).getExternalId());
					values.add(((ErpItemBarcodeDTO) dto).getBarcode());
				}
			}
		}
		return catalogue.barcodesBefore(externalIds, values);
	}

	@SuppressWarnings("unchecked")
	private void recordInBulk(HoCatalogueService catalogue, CatalogueKind kind, List<?> saved,
			Map<Long, HoCatalogueService.BarcodeBefore> before) {
		flushAndClear.run(); // the import's rows are written; the recording starts with an empty persistence context
		if (kind == CatalogueKind.ITEM) {
			catalogue.erpItemsSaved(
					(List<Item>) saved.stream().filter(Item.class::isInstance).collect(Collectors.toList()));
		} else {
			catalogue.erpBarcodesSaved((List<ItemBarcode>) saved.stream().filter(ItemBarcode.class::isInstance)
					.collect(Collectors.toList()), before);
		}
	}

	private void record(CatalogueHeadOfficeHooks recorder, CatalogueKind kind, List<?> saved) {
		flushAndClear.run(); // the import's rows are written; the recording starts with an empty persistence context
		int recorded = 0;
		for (Object record : saved) {
			if (record instanceof _BaseEntity) {
				recorder.afterSave(kind, null, (_BaseEntity) record);
				if (++recorded % FLUSH_EVERY == 0) {
					flushAndClear.run();
				}
			}
		}
	}

	private static Object proceed(ProceedingJoinPoint call) {
		try {
			return call.proceed();
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable e) {
			throw new ImportFailure(e);
		}
	}

	/** A checked exception of the import, carried through the transaction callback and thrown again as it was. */
	private static final class ImportFailure extends RuntimeException {
		private static final long serialVersionUID = 1L;

		ImportFailure(Throwable cause) {
			super(cause);
		}
	}
}
