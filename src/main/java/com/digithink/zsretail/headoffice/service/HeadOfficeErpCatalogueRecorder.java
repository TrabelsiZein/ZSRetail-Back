package com.digithink.zsretail.headoffice.service;

import java.util.List;

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

import com.digithink.zsretail.config.ConditionalOnHeadOfficeErpCatalogue;
import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.service.CatalogueHeadOfficeHooks;

/**
 * ERP catalogue, step 7: the changes of an ERP import reach the stores. On a head office whose catalogue only comes from
 * the ERP, the four imports of ErpItemBootstrapService save with the repositories and never call the catalogue hooks;
 * this aspect (a head office only bean, like {@link HeadOfficeErpGuard}; nothing in erp/ changes) lets each import run,
 * then calls {@link CatalogueHeadOfficeHooks#afterSave} for each record of the returned list (an item also records its
 * barcodes again), exactly as a save on the item pages does.
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
@ConditionalOnHeadOfficeErpCatalogue
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

	@Around("execution(* com.digithink.zsretail.erp.service.ErpItemBootstrapService.importItemFamilies(..))")
	public Object families(ProceedingJoinPoint call) throws Throwable {
		return importAndRecord(call, CatalogueKind.FAMILY);
	}

	@Around("execution(* com.digithink.zsretail.erp.service.ErpItemBootstrapService.importItemSubFamilies(..))")
	public Object subFamilies(ProceedingJoinPoint call) throws Throwable {
		return importAndRecord(call, CatalogueKind.SUBFAMILY);
	}

	@Around("execution(* com.digithink.zsretail.erp.service.ErpItemBootstrapService.importItems(..))")
	public Object items(ProceedingJoinPoint call) throws Throwable {
		return importAndRecord(call, CatalogueKind.ITEM);
	}

	@Around("execution(* com.digithink.zsretail.erp.service.ErpItemBootstrapService.importItemBarcodes(..))")
	public Object barcodes(ProceedingJoinPoint call) throws Throwable {
		return importAndRecord(call, CatalogueKind.BARCODE);
	}

	/** The import, then afterSave per saved record, in one transaction. */
	Object importAndRecord(ProceedingJoinPoint call, CatalogueKind kind) throws Throwable {
		CatalogueHeadOfficeHooks recorder = hooks == null ? null : hooks.getIfAvailable();
		if (recorder == null) {
			return call.proceed();
		}
		try {
			return transactions.execute(status -> {
				Object saved = proceed(call);
				if (saved instanceof List && !((List<?>) saved).isEmpty()) {
					record(recorder, kind, (List<?>) saved);
				}
				return saved;
			});
		} catch (ImportFailure failure) {
			throw failure.getCause();
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
