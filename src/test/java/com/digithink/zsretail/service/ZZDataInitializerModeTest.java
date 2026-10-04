package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.repository.AppReleaseNoteRepository;
import com.digithink.zsretail.repository.AppRoleRepository;
import com.digithink.zsretail.repository.AppVersionRepository;
import com.digithink.zsretail.repository.CustomerRepository;
import com.digithink.zsretail.erp.repository.ErpSyncJobRepository;
import com.digithink.zsretail.erp.service.ErpSyncCheckpointService;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.repository.LocationRepository;
import com.digithink.zsretail.repository.PaymentMethodRepository;
import com.digithink.zsretail.repository.UserAccountRepository;
import com.digithink.zsretail.support.TestModes;

/**
 * Head office plan, task 9.1e: the three startup branches of ZZDataInitializer that read isStandalone() now ask a step 9
 * question. The real init() over recording repositories, on a first start (no customer, no ERP job): without an ERP
 * the passenger customer is created and nothing of the ERP is seeded; with an ERP (a store or a head office) the ERP
 * checkpoints, the ERP-only settings and the ERP jobs are seeded and no passenger customer is created.
 */
class ZZDataInitializerModeTest {

	/** What one init() wrote. */
	private static final class Writes {
		final List<String> settings = new ArrayList<>();
		int customers;
		int erpJobs;
	}

	/** A repository answering by return type: counts 1 (users, payment methods, versions exist) except where given. */
	@SuppressWarnings("unchecked")
	private static <T> T repository(Class<T> type, long count, Runnable onSave, List<String> settings) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			String name = method.getName();
			Class<?> returns = method.getReturnType();
			if (name.equals("count")) {
				return count;
			}
			if (name.startsWith("save")) {
				if (onSave != null) {
					onSave.run();
				}
				if (settings != null && args[0] instanceof GeneralSetup) {
					settings.add(((GeneralSetup) args[0]).getCode());
				}
				if (args[0] instanceof Collection && settings != null) {
					for (Object row : (Collection<?>) args[0]) {
						if (row instanceof GeneralSetup) {
							settings.add(((GeneralSetup) row).getCode());
						}
					}
				}
				return args[0];
			}
			if (name.equals("hashCode")) {
				return System.identityHashCode(proxy);
			}
			if (name.equals("equals")) {
				return proxy == args[0];
			}
			if (name.equals("toString")) {
				return type.getSimpleName();
			}
			if (returns == Optional.class) {
				return Optional.empty();
			}
			if (returns == boolean.class) {
				return false;
			}
			if (returns == long.class || returns == int.class) {
				return 0;
			}
			if (List.class.isAssignableFrom(returns) || Iterable.class.isAssignableFrom(returns)) {
				return Collections.emptyList();
			}
			return null;
		});
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = ZZDataInitializer.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Writes init(ApplicationModeService mode) throws Exception {
		Writes writes = new Writes();
		ZZDataInitializer initializer = new ZZDataInitializer();
		set(initializer, "applicationModeService", mode);
		set(initializer, "userRepository", repository(UserAccountRepository.class, 1, null, null));
		set(initializer, "appRoleRepository", repository(AppRoleRepository.class, 1, null, null));
		set(initializer, "passwordEncoder", new PasswordEncoder() {
			@Override
			public String encode(CharSequence raw) {
				return "encoded";
			}

			@Override
			public boolean matches(CharSequence raw, String encoded) {
				return false;
			}
		});
		set(initializer, "paymentMethodRepository", repository(PaymentMethodRepository.class, 1, null, null));
		set(initializer, "customerRepository", repository(CustomerRepository.class, 0, () -> writes.customers++, null));
		set(initializer, "itemRepository", repository(ItemRepository.class, 1, null, null));
		set(initializer, "itemFamilyRepository", repository(ItemFamilyRepository.class, 1, null, null));
		set(initializer, "itemSubFamilyRepository", repository(ItemSubFamilyRepository.class, 1, null, null));
		set(initializer, "itemBarcodeRepository", repository(ItemBarcodeRepository.class, 1, null, null));
		set(initializer, "locationRepository", repository(LocationRepository.class, 1, null, null));
		set(initializer, "generalSetupRepository", repository(GeneralSetupRepository.class, 1, null, writes.settings));
		set(initializer, "erpSyncJobRepository", repository(ErpSyncJobRepository.class, 0, () -> writes.erpJobs++, null));
		set(initializer, "companyInformationService", new CompanyInformationService(null) {
			@Override
			public void ensureExists() {
			}
		});
		set(initializer, "appVersionRepository", repository(AppVersionRepository.class, 1, null, null));
		set(initializer, "appReleaseNoteRepository", repository(AppReleaseNoteRepository.class, 1, null, null));
		set(initializer, "appVersion", "9.9.9-test");
		Method init = ZZDataInitializer.class.getDeclaredMethod("init");
		init.invoke(initializer);
		return writes;
	}

	private static boolean erpSetting(Writes writes) {
		return writes.settings.stream().anyMatch(code -> code.startsWith("ERP_"))
				|| writes.settings.stream().anyMatch(ErpSyncCheckpointService.getCheckpointDescriptions()::containsKey);
	}

	@Test
	@DisplayName("Without an ERP (a store, a store fed by its head office, a head office): passenger customer created, nothing of the ERP")
	void withoutErp() throws Exception {
		ApplicationModeService fed = TestModes.of(new MockEnvironment()
				.withProperty("headoffice.url", "http://localhost:888/zsretail/api").withProperty("headoffice.api-key", "k")
				.withProperty("ownership.catalogue", "HEAD_OFFICE").withProperty("ownership.supply", "HEAD_OFFICE"));
		ApplicationModeService headOffice = TestModes.of(new MockEnvironment()
				.withProperty("node.type", "HEAD_OFFICE"));
		for (ApplicationModeService mode : new ApplicationModeService[] { TestModes.standalone(), fed, headOffice }) {
			Writes writes = init(mode);
			assertEquals(1, writes.customers, "the passenger customer");
			assertEquals(0, writes.erpJobs, "no ERP job");
			assertFalse(erpSetting(writes), "no ERP setting: " + writes.settings);
		}
	}

	@Test
	@DisplayName("With an ERP (a store, a head office): ERP checkpoints, ERP-only settings and ERP jobs seeded, no passenger customer")
	void withErp() throws Exception {
		ApplicationModeService headOffice = TestModes.of(TestModes.erpOwners(new MockEnvironment()
				.withProperty("node.type", "HEAD_OFFICE")));
		for (ApplicationModeService mode : new ApplicationModeService[] { TestModes.erp(), headOffice }) {
			Writes writes = init(mode);
			assertEquals(0, writes.customers, "no passenger customer");
			assertTrue(writes.erpJobs > 0, "the ERP jobs");
			assertTrue(writes.settings.contains("ERP_SYNC_TRACKING_LEVEL"), "an ERP-only setting: " + writes.settings);
			assertTrue(writes.settings.containsAll(ErpSyncCheckpointService.getCheckpointDescriptions().keySet()),
					"the ERP checkpoints: " + writes.settings);
		}
	}
}
