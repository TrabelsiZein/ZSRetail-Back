package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeErpCatalogue;
import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.service.ErpItemBootstrapService;
import com.digithink.zsretail.headoffice.model.HoDownChange;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.service.CatalogueHeadOfficeHooks;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.Installations;

/**
 * ERP catalogue, step 7: the changes of an ERP import reach the stores. The real HoCatalogueService and CopiesDownFeed
 * over in-memory tables; the four imports of ErpItemBootstrapService are stubs returning what they "saved", called
 * through the aspect (Spring AOP); a minimal transaction manager shows the import and the recording in one transaction.
 */
class HeadOfficeErpCatalogueRecorderTest {

	private InMemoryCatalogue ho;
	private InMemoryDownTables down;
	private HoCatalogueService catalogue;
	private ItemFamily f1;
	private ItemSubFamily sf1;
	private Item b001;
	private ItemBarcode bc1;
	private ItemBarcode bc2;
	private Transactions transactions;
	private int flushes;

	/** What each stub import returns; and whether it ran inside a transaction. */
	private List<?> returned;
	private final List<Boolean> importInTransaction = new ArrayList<>();

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		down = new InMemoryDownTables();
		CopiesDownFeed[] feedRef = new CopiesDownFeed[1];
		catalogue = new HoCatalogueService(ho.familyRepository(), ho.subFamilyRepository(), ho.itemRepository(),
				ho.barcodeRepository(), ho.compositionRepository(), ho.priceLineRepository(), () -> feedRef[0],
				TransactionOperations.withoutTransaction());
		feedRef[0] = down.feed(Collections.singletonList(catalogue));
		f1 = ho.family("F1");
		sf1 = ho.subFamily("SF1", f1);
		b001 = ho.item("B001", 10.0, sf1);
		bc1 = ho.barcode("6191234567890", b001);
		bc2 = ho.barcode("6191234567891", b001);
		transactions = new Transactions();
		flushes = 0;
	}

	private ErpItemBootstrapService guarded(CatalogueHeadOfficeHooks hooks) {
		ErpItemBootstrapService imports = new ErpItemBootstrapService(null, null, null, null, null, null, null, null,
				null, null) {
			@Override
			@SuppressWarnings("unchecked")
			public List<ItemFamily> importItemFamilies(List<ErpItemFamilyDTO> families) {
				importInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
				return (List<ItemFamily>) returned;
			}

			@Override
			@SuppressWarnings("unchecked")
			public List<ItemSubFamily> importItemSubFamilies(List<ErpItemSubFamilyDTO> subFamilies) {
				importInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
				return (List<ItemSubFamily>) returned;
			}

			@Override
			@SuppressWarnings("unchecked")
			public List<Item> importItems(List<ErpItemDTO> items) {
				importInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
				return (List<Item>) returned;
			}

			@Override
			@SuppressWarnings("unchecked")
			public List<ItemBarcode> importItemBarcodes(List<ErpItemBarcodeDTO> barcodes) {
				importInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
				return (List<ItemBarcode>) returned;
			}
		};
		AspectJProxyFactory factory = new AspectJProxyFactory(imports);
		factory.setProxyTargetClass(true);
		factory.addAspect(new HeadOfficeErpCatalogueRecorder(provider(hooks), new TransactionTemplate(transactions),
				() -> flushes++));
		return factory.getProxy();
	}

	private static ObjectProvider<CatalogueHeadOfficeHooks> provider(CatalogueHeadOfficeHooks hooks) {
		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		if (hooks != null) {
			beans.registerSingleton("hooks", hooks);
		}
		return beans.getBeanProvider(CatalogueHeadOfficeHooks.class);
	}

	/** The record codes of the change rows, in change order. */
	private List<String> changes() {
		return down.changes.stream().sorted((a, b) -> Long.compare(a.getChangeVersion(), b.getChangeVersion()))
				.map(HoDownChange::getRecordCode).collect(Collectors.toList());
	}

	private Long sequence() {
		return down.sequences.get(DataDomain.CATALOGUE);
	}

	@Test
	@DisplayName("One change per saved family, sub-family, item and barcode; an item records its barcodes again")
	void onePerRecord() {
		ErpItemBootstrapService imports = guarded(catalogue);
		returned = Arrays.asList(f1);
		assertSame(returned, imports.importItemFamilies(null), "the import's answer is returned as it is");
		returned = Arrays.asList(sf1);
		imports.importItemSubFamilies(null);
		returned = Arrays.asList(b001);
		imports.importItems(null);
		returned = Arrays.asList(bc1);
		imports.importItemBarcodes(null);
		// Six changes numbered (the item recorded its two barcodes); one row per record code, the barcode imported
		// afterwards moved to the last number
		assertEquals(Long.valueOf(6), sequence());
		assertEquals(Arrays.asList("FAMILY:F1", "SUBFAMILY:SF1", "ITEM:B001", "BARCODE:6191234567891",
				"BARCODE:6191234567890"), changes());
		assertEquals(Arrays.asList(true, true, true, true), importInTransaction, "the import runs inside the transaction");
		assertEquals(4, transactions.commits);
		assertEquals(0, transactions.rollbacks);
		assertEquals(4, flushes, "flushed and cleared before each recording");
	}

	@Test
	@DisplayName("A failure while recording rolls back the transaction of the import, and the error goes up")
	void failureRollsBack() {
		CatalogueHeadOfficeHooks failing = new CatalogueHeadOfficeHooks() {
			@Override
			public void beforeSave(CatalogueKind kind, String previousCode, _BaseEntity record) {
			}

			@Override
			public void afterSave(CatalogueKind kind, String previousCode, _BaseEntity saved) {
				throw new IllegalStateException("ho_down_change unreachable");
			}

			@Override
			public void beforeDelete(CatalogueKind kind, _BaseEntity record) {
			}

			@Override
			public void afterPackChanged(Long parentItemId) {
			}

			@Override
			public void afterImport(CatalogueKind kind, java.util.Collection<String> codes) {
			}
		};
		returned = Arrays.asList(b001);
		IllegalStateException error = assertThrows(IllegalStateException.class, () -> guarded(failing).importItems(null));
		assertEquals("ho_down_change unreachable", error.getMessage());
		assertEquals(Arrays.asList(true), importInTransaction, "the import ran inside the transaction rolled back");
		assertEquals(1, transactions.rollbacks);
		assertEquals(0, transactions.commits);
	}

	@Test
	@DisplayName("A failing import: nothing recorded, rolled back, its error goes up")
	void failingImport() {
		ErpItemBootstrapService imports = new ErpItemBootstrapService(null, null, null, null, null, null, null, null,
				null, null) {
			@Override
			public List<Item> importItems(List<ErpItemDTO> items) {
				throw new IllegalArgumentException("save failed");
			}
		};
		AspectJProxyFactory factory = new AspectJProxyFactory(imports);
		factory.setProxyTargetClass(true);
		factory.addAspect(new HeadOfficeErpCatalogueRecorder(provider(catalogue), new TransactionTemplate(transactions),
				() -> flushes++));
		ErpItemBootstrapService proxy = factory.getProxy();
		assertThrows(IllegalArgumentException.class, () -> proxy.importItems(null));
		assertTrue(down.changes.isEmpty());
		assertEquals(1, transactions.rollbacks);
	}

	@Test
	@DisplayName("A null or empty list, or TAX_STAMP: nothing recorded, the sequence untouched")
	void nothingToRecord() {
		ErpItemBootstrapService imports = guarded(catalogue);
		returned = null;
		assertNull(imports.importItems(null));
		returned = Collections.emptyList();
		imports.importItemBarcodes(null);
		Item taxStamp = ho.item("TAX_STAMP", 1.0, null);
		ho.barcode("TS-1", taxStamp);
		returned = Arrays.asList(taxStamp);
		imports.importItems(null);
		returned = Arrays.asList(ho.barcodes.values().stream().filter(b -> b.getBarcode().equals("TS-1")).findFirst()
				.get());
		imports.importItemBarcodes(null);
		assertTrue(down.changes.isEmpty(), changes().toString());
		assertNull(sequence());
	}

	@Test
	@DisplayName("No hooks bean: the import runs as it is, without a transaction of the aspect")
	void noHooks() {
		returned = Arrays.asList(b001);
		assertSame(returned, guarded(null).importItems(null));
		assertEquals(Arrays.asList(false), importInTransaction);
		assertEquals(0, transactions.commits);
		assertTrue(down.changes.isEmpty());
	}

	@Test
	@DisplayName("Flush and clear every 200 records while recording")
	void flushEvery200() {
		List<Item> items = new ArrayList<>();
		for (int i = 0; i < 450; i++) {
			items.add(ho.item(String.format("I%03d", i), 1.0, sf1));
		}
		returned = items;
		guarded(catalogue).importItems(null);
		assertEquals(1 + 2, flushes, "before recording, after 200 and 400");
		assertEquals(450, down.changes.size());
	}

	@Test
	@DisplayName("The bean exists only on a head office whose catalogue only comes from the ERP")
	void whereTheBeanExists() {
		assertTrue(HeadOfficeErpCatalogueRecorder.class.isAnnotationPresent(ConditionalOnHeadOfficeErpCatalogue.class));
		MockEnvironment catalogueOnly = Installations.type("headoffice");
		catalogueOnly.setProperty("ownership.catalogue", "ERP");
		assertTrue(registered(catalogueOnly));
		assertFalse(registered(Installations.type("headoffice")), "head office without an ERP");
		assertFalse(registered(Installations.preset("headoffice-erp")), "head office with the full ERP");
		assertFalse(registered(Installations.type("store")), "store");
		assertFalse(registered(Installations.preset("store-erp")), "ERP store");
		for (String machine : Installations.machineFiles()) {
			assertFalse(registered(Installations.machine(machine)), "deploy/" + machine);
		}
		for (String config : Installations.configFiles()) {
			assertEquals(config.equals("local/happyness_ho.properties"), registered(Installations.config(config)),
					"configs/" + config);
		}
	}

	private static boolean registered(MockEnvironment env) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(HeadOfficeErpCatalogueRecorder.class);
		return registry.getBeanNamesForType(HeadOfficeErpCatalogueRecorder.class, true, false).length == 1;
	}

	/** A transaction manager that only counts: the transaction is real for Spring (synchronization active). */
	static final class Transactions extends AbstractPlatformTransactionManager {
		private static final long serialVersionUID = 1L;
		int commits;
		int rollbacks;

		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) {
			commits++;
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) {
			rollbacks++;
		}
	}
}
