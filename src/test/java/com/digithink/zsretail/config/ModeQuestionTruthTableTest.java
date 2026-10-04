package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.DataOwner;
import com.digithink.zsretail.model.enumeration.NodeType;

/**
 * Head office plan, task 9.1b: the step 9 questions give the same answer as the mode checks they replace, before any
 * call site is swapped. The old expression is written here from the raw properties, the way ApplicationModeService
 * reads them ({@code @Value("${application.standalone:false}")}), so this test outlives isStandalone().
 * <ul>
 * <li>every real profile file (application.properties under each application-*.properties, found by pattern: a new
 * file is checked without changing this test), and application.properties alone;</li>
 * <li>a grid of every configuration of the mode keys, owners, link and sales upstream: the ones the startup accepts
 * ({@link NodeOwnership#resolve}) must answer the same; with the check of task 9.1a no accepted one differs.</li>
 * </ul>
 * Questions: catalogue, customers and supply from the ERP, an ERP present, each equal to application.standalone=false;
 * the head office helpers {@code isHeadOfficeErpSet} / {@code isHeadOfficeStandaloneSet} equal to a head office with /
 * without an ERP.
 */
class ModeQuestionTruthTableTest {

	private static final String STANDALONE = "application.standalone";

	/** The old answer: application.standalone read like the @Value of ApplicationModeService (false when absent). */
	private static boolean oldStandalone(PropertyResolver env) {
		return Boolean.TRUE.equals(env.getProperty(STANDALONE, Boolean.class, Boolean.FALSE));
	}

	private static boolean flag(PropertyResolver env, String key) {
		return Boolean.TRUE.equals(env.getProperty(key, Boolean.class, Boolean.FALSE));
	}

	/** Resolves like ApplicationModeService; null when the startup refuses the configuration. */
	private static NodeOwnership accepted(PropertyResolver env) {
		try {
			return NodeOwnership.resolve(env, oldStandalone(env));
		} catch (IllegalStateException refused) {
			return null;
		}
	}

	/** The differences between the old answer and each question; empty when they all agree. */
	private static List<String> differences(PropertyResolver env, NodeOwnership ownership) {
		boolean erp = !oldStandalone(env);
		List<String> out = new ArrayList<>();
		if (ownership.isCatalogueFromErp() != erp) {
			out.add("isCatalogueFromErp=" + ownership.isCatalogueFromErp());
		}
		if (ownership.isCustomersFromErp() != erp) {
			out.add("isCustomersFromErp=" + ownership.isCustomersFromErp());
		}
		if (ownership.isSupplyFromErp() != erp) {
			out.add("isSupplyFromErp=" + ownership.isSupplyFromErp());
		}
		if (ownership.hasErp() != erp) {
			out.add("hasErp=" + ownership.hasErp());
		}
		boolean headOffice = ownership.getNodeType() == NodeType.HEAD_OFFICE;
		if (NodeOwnership.isHeadOfficeErpSet(env) != (headOffice && ownership.hasErp())) {
			out.add("isHeadOfficeErpSet=" + NodeOwnership.isHeadOfficeErpSet(env));
		}
		if (NodeOwnership.isHeadOfficeStandaloneSet(env) != (headOffice && !ownership.hasErp())) {
			out.add("isHeadOfficeStandaloneSet=" + NodeOwnership.isHeadOfficeStandaloneSet(env));
		}
		return out;
	}

	/** Why an accepted configuration answers differently (the cases task 9.1a refuses). */
	private static String category(PropertyResolver env, NodeOwnership ownership) {
		boolean standalone = oldStandalone(env);
		if (!standalone && (flag(env, "franchise.admin") || flag(env, "franchise.customer"))) {
			return "a franchise flag with ERP flags (application.standalone false or absent)";
		}
		if (standalone) {
			return "application.standalone=true with an explicit owner ERP";
		}
		return "ERP flags (application.standalone false or absent) with catalogue, customers or supply not ERP";
	}

	private static Map<String, String> load(Resource resource) throws Exception {
		Properties properties = new Properties();
		try (InputStream in = resource.getInputStream()) {
			properties.load(in);
		}
		Map<String, String> map = new HashMap<>();
		for (String key : properties.stringPropertyNames()) {
			map.put(key, properties.getProperty(key));
		}
		return map;
	}

	private static PropertyResolver resolver(Map<String, Object> map) {
		MutablePropertySources sources = new MutablePropertySources();
		sources.addFirst(new MapPropertySource("config", map));
		return new PropertySourcesPropertyResolver(sources);
	}

	@Test
	@DisplayName("Every real profile file starts and answers every step 9 question like application.standalone")
	void profileFiles() throws Exception {
		PathMatchingResourcePatternResolver files = new PathMatchingResourcePatternResolver();
		Map<String, String> base = load(files.getResource("classpath:application.properties"));
		Map<String, Map<String, Object>> profiles = new TreeMap<>();
		profiles.put("(no profile)", new HashMap<>(base));
		for (Resource file : files.getResources("classpath*:application-*.properties")) {
			Map<String, Object> merged = new HashMap<>(base);
			merged.putAll(load(file)); // a profile file overrides application.properties, as in Spring
			profiles.put(file.getFilename(), merged);
		}
		for (String expected : new String[] { "application-dynamics-prod.properties",
				"application-standalone-prod.properties", "application-headoffice-dev.properties",
				"application-headoffice-dynamics-dev.properties", "application-store-b-dev.properties",
				"application-network-headoffice.properties", "application-network-store.properties" }) {
			assertTrue(profiles.containsKey(expected), expected + " is read: " + profiles.keySet());
		}
		for (Map.Entry<String, Map<String, Object>> profile : profiles.entrySet()) {
			PropertyResolver env = resolver(profile.getValue());
			NodeOwnership ownership = accepted(env);
			if (ownership == null) {
				fail(profile.getKey() + " does not start");
			}
			assertEquals(new ArrayList<>(), differences(env, ownership), profile.getKey());
		}
	}

	@Test
	@DisplayName("Grid: every configuration the startup accepts answers every step 9 question like application.standalone")
	void grid() {
		Map<String, String[]> axes = new LinkedHashMap<>();
		axes.put(STANDALONE, new String[] { null, "true", "false" });
		axes.put("franchise.admin", new String[] { null, "true" });
		axes.put("franchise.customer", new String[] { null, "true" });
		axes.put("node.type", new String[] { null, "HEAD_OFFICE" }); // STORE reads like absent (nodeTypeOf)
		axes.put("headoffice.url", new String[] { null, "http://localhost:888/zsretail/api" });
		for (DataDomain domain : DataDomain.values()) {
			// Promotions and loyalty: LOCAL is their derived value everywhere and ERP is always refused
			axes.put(domain.getPropertyKey(), domain.allows(DataOwner.ERP)
					? new String[] { null, "LOCAL", "HEAD_OFFICE", "ERP" }
					: new String[] { null, "HEAD_OFFICE" });
		}
		axes.put("sales.upstream", new String[] { null, "", "ERP", "HEAD_OFFICE", "ERP,HEAD_OFFICE" });
		List<String> keys = new ArrayList<>(axes.keySet());

		Map<String, Object> map = new HashMap<>();
		PropertyResolver env = resolver(map);
		int[] index = new int[keys.size()];
		int combinations = 0;
		int acceptedCount = 0;
		List<String> differing = new ArrayList<>();
		Map<String, Integer> categories = new TreeMap<>();
		while (true) {
			map.clear();
			for (int k = 0; k < keys.size(); k++) {
				String value = axes.get(keys.get(k))[index[k]];
				if (value != null) {
					map.put(keys.get(k), value);
				}
			}
			if (map.containsKey("headoffice.url")) {
				map.put("headoffice.api-key", "key");
			}
			combinations++;
			NodeOwnership ownership = accepted(env);
			if (ownership != null) {
				acceptedCount++;
				List<String> diff = differences(env, ownership);
				if (!diff.isEmpty()) {
					Map<String, Object> shown = new TreeMap<>(map);
					shown.remove("headoffice.api-key");
					differing.add(shown + " -> " + diff);
					categories.merge(category(env, ownership), 1, Integer::sum);
				}
			}
			int k = keys.size() - 1;
			while (k >= 0 && ++index[k] == axes.get(keys.get(k)).length) {
				index[k] = 0;
				k--;
			}
			if (k < 0) {
				break;
			}
		}
		System.out.println("ModeQuestionTruthTableTest grid: " + combinations + " configurations, " + acceptedCount
				+ " accepted by the startup, " + differing.size() + " answering differently");
		categories.forEach((category, count) -> System.out.println("  " + count + " x " + category));
		differing.stream().limit(10).forEach(line -> System.out.println("  e.g. " + line));
		assertTrue(acceptedCount > 0, "some configurations start");
		assertEquals(0, differing.size(), differing.size() + " accepted configurations answer differently, e.g. "
				+ differing.subList(0, Math.min(5, differing.size())));
	}
}
