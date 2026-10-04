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
 * <li>every preset (with a head office address for the network presets) and every machine file of deploy/ starts;</li>
 * <li>a grid of configurations of the owners, node.type, headoffice.url, sales.upstream and application.standalone:
 * any one with application.standalone is refused; in every accepted one the catalogue, the customers and the supply are
 * the ERP's together or not at all, so isCatalogueFromErp, isCustomersFromErp, isSupplyFromErp and hasErp answer
 * alike, and isHeadOfficeErpSet / isHeadOfficeWithoutErpSet split a head office by that answer.</li>
 * </ul>
 */
class ModeQuestionTruthTableTest {

	/** The disagreements between the questions; empty when they agree. */
	private static List<String> differences(PropertyResolver env, NodeOwnership ownership) {
		boolean erp = ownership.hasErp();
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
		boolean headOffice = ownership.getNodeType() == NodeType.HEAD_OFFICE;
		if (NodeOwnership.isHeadOfficeErpSet(env) != (headOffice && erp)) {
			out.add("isHeadOfficeErpSet=" + NodeOwnership.isHeadOfficeErpSet(env));
		}
		if (NodeOwnership.isHeadOfficeWithoutErpSet(env) != (headOffice && !erp)) {
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
		for (String preset : NodeOwnership.PRESETS) {
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
		assertTrue(installations.size() >= NodeOwnership.PRESETS.size() + 9, "presets and machine files read");
		for (Map.Entry<String, MockEnvironment> installation : installations.entrySet()) {
			NodeOwnership ownership = accepted(installation.getValue());
			if (ownership == null) {
				fail(installation.getKey() + " does not start");
			}
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
				+ " accepted (" + acceptedWithErp + " with an ERP), " + wrong.size() + " wrong");
		assertTrue(acceptedWithErp > 0 && acceptedCount > acceptedWithErp, "both kinds accepted");
		assertEquals(0, wrong.size(), "e.g. " + wrong.subList(0, Math.min(5, wrong.size())));
	}
}
