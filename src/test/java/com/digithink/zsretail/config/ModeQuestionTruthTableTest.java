package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.DataOwner;
import com.digithink.zsretail.model.enumeration.NodeType;
import com.digithink.zsretail.support.Installations;

/**
 * Head office plan, tasks 9.1b and 9.3: the step 9 questions agree with each other for every configuration that starts.
 * Until task 9.3 this test proved each question equal to application.standalone=false (the old expression); the
 * property is gone (a configuration that sets it is refused), and the answers of each old profile are frozen in
 * PresetTruthTableTest. Here:
 * <ul>
 * <li>every preset (with a head office address for the network presets), every machine file of deploy/ and every
 * installation file of configs/ starts, none is the new head office combination, and each answers the old way;</li>
 * <li>a grid of configurations of the owners, node.type, headoffice.url, sales.upstream and application.standalone:
 * any one with application.standalone is refused; in every accepted one the catalogue, the customers and the supply are
 * the ERP's together or not at all, so isCatalogueFromErp, isCustomersFromErp, isSupplyFromErp and hasErp answer
 * alike, and isHeadOfficeErpSet / isHeadOfficeWithoutErpSet split a head office by that answer.</li>
 * <li>ERP catalogue, step 1: the one exception is a head office with the catalogue from the ERP and the customers and
 * the supply not (isErpCatalogueOnly). The grid's counts before that step are frozen: the only rows added are those
 * head offices, where isCustomersFromErp and isSupplyFromErp are false, isHeadOfficeWithoutErpSet true and
 * isHeadOfficeErpSet false.</li>
 * </ul>
 */
class ModeQuestionTruthTableTest {

	/** Grid answers before ERP catalogue step 1 (frozen from the run of 2026-10-07): accepted, accepted with an ERP. */
	static final int ACCEPTED_BEFORE = 497;
	static final int ACCEPTED_WITH_ERP_BEFORE = 25;

	/**
	 * Grid rows accepted since ERP catalogue step 1: a head office, catalogue ERP, customers and supply absent or LOCAL
	 * (2 x 2), sales.upstream absent or empty (2); every other axis at the only value a head office accepts.
	 */
	static final int NEW_HEAD_OFFICE_ROWS = 8;

	/** The installation file of configs/ whose catalogue only comes from the ERP (Zein, ERP catalogue step 5). */
	static final String HAPPYNESS_HEAD_OFFICE = "local/happyness_ho.properties";

	/** ERP catalogue, step 1: the head office combination, read from the owners (not from the class under test). */
	private static boolean catalogueOnly(NodeOwnership ownership) {
		return ownership.getNodeType() == NodeType.HEAD_OFFICE
				&& ownership.ownerOf(DataDomain.CATALOGUE) == DataOwner.ERP
				&& ownership.ownerOf(DataDomain.CUSTOMERS) != DataOwner.ERP
				&& ownership.ownerOf(DataDomain.SUPPLY) != DataOwner.ERP;
	}

	/**
	 * The disagreements with the expected answers; empty when they agree. Outside the new head office combination the
	 * expected answers are the old ones: the four questions alike, the two head office predicates split by hasErp.
	 */
	private static List<String> differences(PropertyResolver env, NodeOwnership ownership) {
		boolean erp = ownership.hasErp();
		boolean catalogueOnly = catalogueOnly(ownership);
		List<String> out = new ArrayList<>();
		if (ownership.isErpCatalogueOnly() != catalogueOnly) {
			out.add("isErpCatalogueOnly=" + ownership.isErpCatalogueOnly());
		}
		if (NodeOwnership.isErpCatalogueOnlySet(env) != catalogueOnly) {
			out.add("isErpCatalogueOnlySet=" + NodeOwnership.isErpCatalogueOnlySet(env));
		}
		if (ownership.isCatalogueFromErp() != erp) {
			out.add("isCatalogueFromErp=" + ownership.isCatalogueFromErp());
		}
		if (ownership.isCustomersFromErp() != (erp && !catalogueOnly)) {
			out.add("isCustomersFromErp=" + ownership.isCustomersFromErp());
		}
		if (ownership.isSupplyFromErp() != (erp && !catalogueOnly)) {
			out.add("isSupplyFromErp=" + ownership.isSupplyFromErp());
		}
		boolean headOffice = ownership.getNodeType() == NodeType.HEAD_OFFICE;
		if (NodeOwnership.isHeadOfficeErpSet(env) != (headOffice && erp && !catalogueOnly)) {
			out.add("isHeadOfficeErpSet=" + NodeOwnership.isHeadOfficeErpSet(env));
		}
		if (NodeOwnership.isHeadOfficeWithoutErpSet(env) != (headOffice && (!erp || catalogueOnly))) {
			out.add("isHeadOfficeWithoutErpSet=" + NodeOwnership.isHeadOfficeWithoutErpSet(env));
		}
		return out;
	}

	private static NodeOwnership accepted(PropertyResolver env) {
		try {
			return NodeOwnership.resolve(env);
		} catch (IllegalStateException refused) {
			return null;
		}
	}

	@Test
	@DisplayName("Every preset and every machine file of deploy/ starts, and the four questions agree")
	void presetsAndMachineFiles() {
		Map<String, MockEnvironment> installations = new LinkedHashMap<>();
		for (String preset : Installations.variants()) {
			MockEnvironment env = Installations.preset(preset);
			if (preset.startsWith("network-")) {
				env.setProperty("headoffice.url", "http://localhost:888/zsretail/api");
				env.setProperty("headoffice.api-key", "k");
			}
			installations.put("preset " + preset, env);
		}
		for (String machine : Installations.machineFiles()) {
			installations.put("machine " + machine, Installations.machine(machine));
		}
		for (String config : Installations.configFiles()) {
			installations.put("config " + config, Installations.config(config));
		}
		assertTrue(installations.size() >= Installations.variants().size() + 9 + 3,
				"presets, machine files and configs read");
		for (Map.Entry<String, MockEnvironment> installation : installations.entrySet()) {
			NodeOwnership ownership = accepted(installation.getValue());
			if (ownership == null) {
				fail(installation.getKey() + " does not start");
			}
			// The Happyness head office is the real example of the new head office combination (ERP catalogue, step 5),
			// checked against its answers; every other installation is checked against the old answers
			assertEquals(installation.getKey().equals("config " + HAPPYNESS_HEAD_OFFICE), catalogueOnly(ownership),
					installation.getKey());
			assertEquals(new ArrayList<>(), differences(installation.getValue(), ownership), installation.getKey());
		}
	}

	@Test
	@DisplayName("Grid: application.standalone always refused; in every accepted configuration the four questions agree")
	void grid() {
		Map<String, String[]> axes = new LinkedHashMap<>();
		axes.put("application.standalone", new String[] { null, "true", "false" });
		axes.put("node.type", new String[] { null, "HEAD_OFFICE" }); // STORE reads like absent (nodeTypeOf)
		axes.put("headoffice.url", new String[] { null, "http://localhost:888/zsretail/api" });
		for (DataDomain domain : DataDomain.values()) {
			// Promotions and loyalty: LOCAL is their default and ERP is always refused
			axes.put(domain.getPropertyKey(), domain.allows(DataOwner.ERP)
					? new String[] { null, "LOCAL", "HEAD_OFFICE", "ERP" }
					: new String[] { null, "HEAD_OFFICE" });
		}
		axes.put("sales.upstream", new String[] { null, "", "ERP", "HEAD_OFFICE", "ERP,HEAD_OFFICE" });
		List<String> keys = new ArrayList<>(axes.keySet());

		Map<String, Object> map = new HashMap<>();
		MutablePropertySources sources = new MutablePropertySources();
		sources.addFirst(new MapPropertySource("config", map));
		PropertyResolver env = new PropertySourcesPropertyResolver(sources);
		int[] index = new int[keys.size()];
		int combinations = 0;
		int acceptedCount = 0;
		int acceptedWithErp = 0;
		int newRows = 0;
		List<String> wrong = new ArrayList<>();
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
				acceptedWithErp += ownership.hasErp() ? 1 : 0;
				newRows += catalogueOnly(ownership) ? 1 : 0;
				List<String> diff = differences(env, ownership);
				if (map.containsKey("application.standalone")) {
					diff.add("accepted with application.standalone");
				}
				if (!diff.isEmpty()) {
					wrong.add(map + " -> " + diff);
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
				+ " accepted (" + acceptedWithErp + " with an ERP, " + newRows + " head offices with the catalogue only"
				+ " from the ERP), " + wrong.size() + " wrong");
		assertTrue(acceptedWithErp > 0 && acceptedCount > acceptedWithErp, "both kinds accepted");
		// ERP catalogue, step 1: the old rows answer as before, the only rows added are the new head office combination
		assertEquals(15360, combinations, "grid size");
		assertEquals(NEW_HEAD_OFFICE_ROWS, newRows, "new head office rows");
		assertEquals(ACCEPTED_BEFORE, acceptedCount - newRows, "accepted before step 1");
		assertEquals(ACCEPTED_WITH_ERP_BEFORE, acceptedWithErp - newRows, "accepted with an ERP before step 1");
		assertEquals(0, wrong.size(), "e.g. " + wrong.subList(0, Math.min(5, wrong.size())));
	}
}
