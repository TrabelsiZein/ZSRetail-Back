package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.support.Installations;

/**
 * Head office plan, task 9.3: the answers of every installation shape, frozen. Each row is what the old profile file
 * gave (task 9.3a, commit 596f1bb, checked them against the old files while they were still in the WAR); since task 9.3b
 * each preset gives the same row with its machine file (deploy/), the old files being gone. A row: node type, the five owners, where the sales go, the head office switches (link, sales
 * push, pull, catalogue and supply from the head office, head office with / without an ERP), an ERP owner, and
 * erp.dynamicsnav.enabled.
 */
class PresetTruthTableTest {

	/** Installation (machine:<file of deploy/> or preset:<name>) [+ keys of the machine] to its frozen row. */
	private static final Map<String, String> ROWS = new LinkedHashMap<>();
	private static final Map<String, String[]> SOURCES = new LinkedHashMap<>();

	private static final String LINK = "headoffice.url=http://localhost:888/zsretail/api|headoffice.api-key=k";

	static {
		row("store", "machine:customers/store-prod.properties", "",
				"STORE CATALOGUE=LOCAL CUSTOMERS=LOCAL PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=LOCAL up=[] link=false push=false pull=false catalogueHO=false supplyHO=false hoErp=false hoNoErp=false erp=false nav=false");
		row("store-erp", "machine:customers/erp-prod.properties", "",
				"STORE CATALOGUE=ERP CUSTOMERS=ERP PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=ERP up=[ERP] link=false push=false pull=false catalogueHO=false supplyHO=false hoErp=false hoNoErp=false erp=true nav=true");
		row("headoffice", "machine:dev/headoffice.properties", "",
				"HEAD_OFFICE CATALOGUE=LOCAL CUSTOMERS=LOCAL PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=LOCAL up=[] link=false push=false pull=false catalogueHO=false supplyHO=false hoErp=false hoNoErp=true erp=false nav=false");
		row("headoffice-erp", "machine:dev/headoffice-erp.properties", "",
				"HEAD_OFFICE CATALOGUE=ERP CUSTOMERS=ERP PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=ERP up=[] link=false push=false pull=false catalogueHO=false supplyHO=false hoErp=true hoNoErp=false erp=true nav=true");
		row("network-store", "preset:network-store", LINK,
				"STORE CATALOGUE=HEAD_OFFICE CUSTOMERS=LOCAL PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=HEAD_OFFICE up=[HEAD_OFFICE] link=true push=true pull=true catalogueHO=true supplyHO=true hoErp=false hoNoErp=false erp=false nav=false");
		row("network-store-erp", "preset:network-store-erp", LINK,
				"STORE CATALOGUE=ERP CUSTOMERS=ERP PROMOTIONS=HEAD_OFFICE LOYALTY=HEAD_OFFICE SUPPLY=ERP up=[ERP, HEAD_OFFICE] link=true push=true pull=true catalogueHO=false supplyHO=false hoErp=false hoNoErp=false erp=true nav=true");
		// The dev machines of the L2 (devenv): store A linked for its sales, stores B and C of the pair
		row("dev store A (store + link)", "machine:dev/store-a.properties", "",
				"STORE CATALOGUE=LOCAL CUSTOMERS=LOCAL PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=LOCAL up=[HEAD_OFFICE] link=true push=true pull=false catalogueHO=false supplyHO=false hoErp=false hoNoErp=false erp=false nav=false");
		row("dev store B (network-store + loyalty)", "machine:dev/store-b.properties", "",
				"STORE CATALOGUE=HEAD_OFFICE CUSTOMERS=LOCAL PROMOTIONS=LOCAL LOYALTY=HEAD_OFFICE SUPPLY=HEAD_OFFICE up=[HEAD_OFFICE] link=true push=true pull=true catalogueHO=true supplyHO=true hoErp=false hoNoErp=false erp=false nav=false");
		row("dev store C (network-store + loyalty, supply local)", "machine:dev/store-c.properties", "",
				"STORE CATALOGUE=HEAD_OFFICE CUSTOMERS=LOCAL PROMOTIONS=LOCAL LOYALTY=HEAD_OFFICE SUPPLY=LOCAL up=[HEAD_OFFICE] link=true push=true pull=true catalogueHO=true supplyHO=false hoErp=false hoNoErp=false erp=false nav=false");
		row("dev test NAV (store-erp)", "machine:dev/store-test-nav.properties", "",
				"STORE CATALOGUE=ERP CUSTOMERS=ERP PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=ERP up=[ERP] link=false push=false pull=false catalogueHO=false supplyHO=false hoErp=false hoNoErp=false erp=true nav=true");
		row("dev store A on the ERP (store-erp)", "machine:dev/store-a-erp.properties", "",
				"STORE CATALOGUE=ERP CUSTOMERS=ERP PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=ERP up=[ERP] link=false push=false pull=false catalogueHO=false supplyHO=false hoErp=false hoNoErp=false erp=true nav=true");
	}

	private static void row(String shape, String source, String extraKeys, String expected) {
		ROWS.put(shape, expected);
		SOURCES.put(shape, new String[] { source, extraKeys });
	}

	static void putKeys(MockEnvironment env, String keys) {
		if (keys.isEmpty()) {
			return;
		}
		for (String pair : keys.split("\\|")) {
			int eq = pair.indexOf('=');
			env.setProperty(pair.substring(0, eq), pair.substring(eq + 1));
		}
	}

	/** The frozen row of an environment, with the code of today. */
	static String answer(MockEnvironment env) {
		NodeOwnership o = NodeOwnership.resolve(env);
		StringJoiner row = new StringJoiner(" ");
		row.add(o.getNodeType().name());
		for (DataDomain domain : DataDomain.values()) {
			row.add(domain.name() + "=" + o.ownerOf(domain));
		}
		row.add("up=" + o.getSalesUpstreams());
		row.add("link=" + NodeOwnership.isHeadOfficeLinkSet(env));
		row.add("push=" + NodeOwnership.isHeadOfficeSalesPushSet(env));
		row.add("pull=" + NodeOwnership.isHeadOfficePullSet(env));
		row.add("catalogueHO=" + NodeOwnership.isCatalogueFromHeadOffice(env));
		row.add("supplyHO=" + NodeOwnership.isSupplyFromHeadOffice(env));
		row.add("hoErp=" + NodeOwnership.isHeadOfficeErpSet(env));
		row.add("hoNoErp=" + NodeOwnership.isHeadOfficeStandaloneSet(env));
		row.add("erp=" + o.hasErp());
		row.add("nav=" + Boolean.parseBoolean(env.getProperty("erp.dynamicsnav.enabled", "false")));
		return row.toString();
	}

	@Test
	@DisplayName("9.3: every preset, with its machine file, gives the frozen row of the old profile it replaces")
	void presetsGiveTheFrozenRows() {
		for (Map.Entry<String, String[]> source : SOURCES.entrySet()) {
			String spec = source.getValue()[0];
			MockEnvironment env = spec.startsWith("machine:") ? Installations.machine(spec.substring("machine:".length()))
					: Installations.preset(spec.substring("preset:".length()));
			putKeys(env, source.getValue()[1]);
			assertEquals(ROWS.get(source.getKey()), answer(env), source.getKey() + " <- " + spec);
		}
	}
}
