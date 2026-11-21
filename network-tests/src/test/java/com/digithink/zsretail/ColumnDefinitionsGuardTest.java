package com.digithink.zsretail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.MappedSuperclass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * 3.3.0: Hibernate writes {@code not null} after a column's {@code columnDefinition} when the column is not nullable
 * ({@code nullable = false}, a primitive field, an id). A definition that says {@code not null} itself then reads
 * {@code ... not null not null}: SQL Server refuses the CREATE TABLE, and a new database starts without that table
 * (only a WARN in the log). loyalty_member.message_consent did it (communication step 5). Every column of every entity
 * of every project: a {@code columnDefinition} never says {@code not null} where Hibernate adds it.
 */
class ColumnDefinitionsGuardTest {

	static final Pattern NOT_NULL = Pattern.compile("\\bnot\\s+null\\b", Pattern.CASE_INSENSITIVE);

	/** The entities, mapped superclasses and embeddables of the application. */
	static List<Class<?>> mappedClasses() throws ClassNotFoundException {
		List<AnnotationTypeFilter> filters = List.of(new AnnotationTypeFilter(Entity.class),
				new AnnotationTypeFilter(MappedSuperclass.class), new AnnotationTypeFilter(Embeddable.class));
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
				for (AnnotationTypeFilter filter : filters) {
					if (filter.match(reader, getMetadataReaderFactory())) {
						return true;
					}
				}
				return false;
			}
		};
		List<Class<?>> classes = new ArrayList<>();
		for (BeanDefinition candidate : scanner.findCandidateComponents("com.digithink.zsretail")) {
			classes.add(Class.forName(candidate.getBeanClassName()));
		}
		return classes;
	}

	/** The columns whose definition says not null where Hibernate writes it too: "Class.field: definition". */
	static List<String> doubledNotNull(List<Class<?>> classes) {
		List<String> found = new ArrayList<>();
		for (Class<?> type : classes) {
			for (Field field : type.getDeclaredFields()) {
				Column column = field.getAnnotation(Column.class);
				JoinColumn join = field.getAnnotation(JoinColumn.class);
				String definition = column != null ? column.columnDefinition() : join != null ? join.columnDefinition() : "";
				boolean nullable = column != null ? column.nullable() : join == null || join.nullable();
				boolean hibernateAddsNotNull = !nullable || field.getType().isPrimitive()
						|| field.isAnnotationPresent(Id.class);
				if (hibernateAddsNotNull && NOT_NULL.matcher(definition).find()) {
					found.add(type.getSimpleName() + "." + field.getName() + ": " + definition);
				}
			}
		}
		return found;
	}

	@Test
	@DisplayName("no columnDefinition says not null where Hibernate adds it (CREATE TABLE refused on a new database)")
	void noDoubledNotNull() throws ClassNotFoundException {
		List<Class<?>> classes = mappedClasses();
		assertTrue(classes.size() > 50, "the scan found " + classes.size() + " mapped classes");
		assertEquals(new ArrayList<>(), doubledNotNull(classes));
	}

	/** The guard itself finds the mistake (not an @Entity: the scan above would find it). */
	static class Doubled {
		@Column(nullable = false, columnDefinition = "bit default 0 not null")
		Boolean wrong;
		@Column(columnDefinition = "int NOT  NULL default 0")
		int primitiveWrong;
		@Column(nullable = false, columnDefinition = "bit default 0")
		Boolean right;
		@Column(columnDefinition = "nvarchar(max) not null")
		String nullableSaysNotNull; // Hibernate adds nothing here: valid
	}

	@Test
	@DisplayName("the guard finds a doubled not null, also on a primitive")
	void findsTheMistake() {
		List<String> found = doubledNotNull(List.of(Doubled.class));
		found.sort(null);
		assertEquals(List.of("Doubled.primitiveWrong: int NOT  NULL default 0", "Doubled.wrong: bit default 0 not null"), found);
	}
}
