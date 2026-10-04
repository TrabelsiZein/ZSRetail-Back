package com.digithink.zsretail.headoffice.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;

import javax.persistence.Entity;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.query.Query;
import org.hibernate.query.spi.QueryParameterBindingValidator;
import org.hibernate.type.Type;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.holink.repository.DownRecordRepository;
import com.digithink.zsretail.holink.repository.LoyaltyMemberCopyRepository;
import com.digithink.zsretail.holink.repository.LoyaltyMovementCopyRepository;
import com.digithink.zsretail.holink.repository.LinkExchangeRepository;
import com.digithink.zsretail.holink.repository.ReceivedDeliveryRepository;
import com.digithink.zsretail.holink.repository.StockCopyRepository;
import com.digithink.zsretail.holink.repository.SalesCopyRepository;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.repository.LoyaltyProgramRepository;
import com.digithink.zsretail.repository.PromotionRepository;
import com.digithink.zsretail.repository.SalesPriceRepository;

/**
 * Head office plan, step 2: every JPQL {@code @Query} of the step 2 repositories is parsed by Hibernate and a value of
 * each declared parameter type is bound to it, as Spring Data does at the call. A parameter whose type Hibernate infers
 * differently (e.g. ":storeId = 0" types it Integer while the method passes a long) fails here instead of answering
 * 500 at run time. Hibernate with the SQL Server dialect and the entities, no database (nothing is executed), no
 * Spring context.
 */
class QueryParameterBindingTest {

	private static final Class<?>[] REPOSITORIES = { HoTicketRepository.class, HoReturnRepository.class,
			HoDownChangeRepository.class, HoDownSequenceRepository.class, PromotionRepository.class,
			HoSessionRepository.class, LinkExchangeRepository.class, SalesCopyRepository.class,
			DownRecordRepository.class, LoyaltyMemberRepository.class, LoyaltyProgramRepository.class,
			LoyaltyMemberCopyRepository.class, LoyaltyMovementCopyRepository.class, HoLoyaltyMovementRepository.class,
			HoPriceListLineRepository.class, StoreRepository.class, ItemRepository.class, ItemFamilyRepository.class,
			ItemSubFamilyRepository.class, ItemBarcodeRepository.class, SalesPriceRepository.class,
			HoDeliveryRepository.class, HoNumberSequenceRepository.class, ReceivedDeliveryRepository.class,
			StockCopyRepository.class, HoStoreStockRepository.class, HoItemSupplyPriceRepository.class };

	private static SessionFactory sessionFactory;

	@BeforeAll
	static void build() throws Exception {
		MetadataSources sources = new MetadataSources(new StandardServiceRegistryBuilder()
				.applySetting("hibernate.dialect", "org.hibernate.dialect.SQLServer2012Dialect")
				.applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
				.applySetting("hibernate.physical_naming_strategy",
						"org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy")
				.applySetting("hibernate.hbm2ddl.auto", "none").build());
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
		for (BeanDefinition definition : scanner.findCandidateComponents("com.digithink.zsretail")) {
			sources.addAnnotatedClass(Class.forName(definition.getBeanClassName()));
		}
		sessionFactory = sources.buildMetadata().buildSessionFactory();
	}

	@AfterAll
	static void close() {
		sessionFactory.close();
	}

	@Test
	@DisplayName("Each @Query of the step 2 repositories parses and accepts a value of each declared parameter type")
	void bindAll() {
		int checked = 0;
		try (Session session = sessionFactory.openSession()) {
			for (Class<?> repository : REPOSITORIES) {
				for (Method method : repository.getDeclaredMethods()) {
					org.springframework.data.jpa.repository.Query annotation = method
							.getAnnotation(org.springframework.data.jpa.repository.Query.class);
					if (annotation == null || annotation.nativeQuery()) {
						continue;
					}
					Query<?> query = session.createQuery(annotation.value());
					for (Parameter parameter : method.getParameters()) {
						Param name = parameter.getAnnotation(Param.class);
						if (name == null) {
							continue; // Pageable
						}
						Object value;
						if (Collection.class.isAssignableFrom(parameter.getType())) {
							Class<?> element = (Class<?>) ((ParameterizedType) parameter.getParameterizedType())
									.getActualTypeArguments()[0];
							value = Collections.singletonList(sample(element));
						} else {
							value = sample(parameter.getType());
						}
						// The check JPA (and so Spring Data) applies at setParameter; native Hibernate skips it
						Type expected = query.getParameterMetadata().getQueryParameter(name.value()).getHibernateType();
						try {
							QueryParameterBindingValidator.INSTANCE.validate(expected, value, null);
						} catch (IllegalArgumentException e) {
							throw new AssertionError(repository.getSimpleName() + "." + method.getName() + " :"
									+ name.value() + ": " + e.getMessage(), e);
						}
					}
					checked++;
				}
			}
		}
		assertTrue(checked >= 13, "queries checked: " + checked);
	}

	/** A value of the type, as the services pass it. */
	private static Object sample(Class<?> type) {
		if (type == long.class || type == Long.class) {
			return 3L;
		}
		if (type == String.class) {
			return "COMPLETED";
		}
		if (type == LocalDateTime.class) {
			return LocalDateTime.of(2026, 10, 3, 12, 0);
		}
		if (type == LocalDate.class) {
			return LocalDate.of(2026, 10, 3);
		}
		if (type == Boolean.class || type == boolean.class) {
			return Boolean.FALSE;
		}
		if (type.isEnum()) {
			return type.getEnumConstants()[0];
		}
		throw new IllegalArgumentException("No sample for " + type);
	}
}
