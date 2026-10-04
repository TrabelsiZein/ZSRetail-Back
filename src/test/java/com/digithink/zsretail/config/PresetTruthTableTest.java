package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.StringJoiner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.model.enumeration.DataDomain;

/**
 * Head office plan, task 9.3: the answers of every installation shape, frozen. Each row is what the old profile file
 * gave (task 9.3a: read from the old files, still in the WAR); since task 9.3b the same rows are what each preset gives
 * with its machine file. A row: node type, the five owners, where the sales go, the head office switches (link, sales
 * push, pull, catalogue and supply from the head office, head office with / without an ERP), an ERP owner, and
 * erp.dynamicsnav.enabled.
 */
class PresetTruthTableTest {

	/** Old profile [+ keys of the machine] to its frozen row. */
	private static final Map<String, String> ROWS = new LinkedHashMap<>();
	private static final Map<String, String[]> OLD_SOURCES = new LinkedHashMap<>();

	private static final String LINK = "headoffice.url=http://localhost:888/zsretail/api|headoffice.api-key=k";

	static {
		row("store", "standalone-prod", "",
				"STORE CATALOGUE=LOCAL CUSTOMERS=LOCAL PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=LOCAL up=[] link=false push=false pull=false catalogueHO=false supplyHO=false hoErp=false hoNoErp=false erp=false nav=false");
		row("store-erp", "dynamics-prod", "",
				"STORE CATALOGUE=ERP CUSTOMERS=ERP PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=ERP up=[ERP] link=false push=false pull=false catalogueHO=false supplyHO=false hoErp=false hoNoErp=false erp=true nav=true");
		row("headoffice", "headoffice-dev", "",
				"HEAD_OFFICE CATALOGUE=LOCAL CUSTOMERS=LOCAL PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=LOCAL up=[] link=false push=false pull=false catalogueHO=false supplyHO=false hoErp=false hoNoErp=true erp=false nav=false");
		row("headoffice-erp", "headoffice-dynamics-dev", "",
				"HEAD_OFFICE CATALOGUE=ERP CUSTOMERS=ERP PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=ERP up=[] link=false push=false pull=false catalogueHO=false supplyHO=false hoErp=true hoNoErp=false erp=true nav=true");
		row("network-store", "network-store", LINK,
				"STORE CATALOGUE=HEAD_OFFICE CUSTOMERS=LOCAL PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=HEAD_OFFICE up=[HEAD_OFFICE] link=true push=true pull=true catalogueHO=true supplyHO=true hoErp=false hoNoErp=false erp=false nav=false");
		row("network-store-erp", "dynamics-dev",
				LINK + "|ownership.promotions=HEAD_OFFICE|ownership.loyalty=HEAD_OFFICE|sales.upstream=ERP,HEAD_OFFICE",
				"STORE CATALOGUE=ERP CUSTOMERS=ERP PROMOTIONS=HEAD_OFFICE LOYALTY=HEAD_OFFICE SUPPLY=ERP up=[ERP, HEAD_OFFICE] link=true push=true pull=true catalogueHO=false supplyHO=false hoErp=false hoNoErp=false erp=true nav=true");
		// The dev machines of the L2 (devenv): store A linked for its sales, stores B and C of the pair
		row("dev store A (store + link)", "standalone-dev", "",
				"STORE CATALOGUE=LOCAL CUSTOMERS=LOCAL PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=LOCAL up=[HEAD_OFFICE] link=true push=true pull=false catalogueHO=false supplyHO=false hoErp=false hoNoErp=false erp=false nav=false");
		row("dev store B (network-store + loyalty)", "store-b-dev", "",
				"STORE CATALOGUE=HEAD_OFFICE CUSTOMERS=LOCAL PROMOTIONS=LOCAL LOYALTY=HEAD_OFFICE SUPPLY=HEAD_OFFICE up=[HEAD_OFFICE] link=true push=true pull=true catalogueHO=true supplyHO=true hoErp=false hoNoErp=false erp=false nav=false");
		row("dev store C (network-store + loyalty, supply local)", "store-c-dev", "",
				"STORE CATALOGUE=HEAD_OFFICE CUSTOMERS=LOCAL PROMOTIONS=LOCAL LOYALTY=HEAD_OFFICE SUPPLY=LOCAL up=[HEAD_OFFICE] link=true push=true pull=true catalogueHO=true supplyHO=false hoErp=false hoNoErp=false erp=false nav=false");
		row("dev test NAV (store-erp)", "dynamics-test", "",
				"STORE CATALOGUE=ERP CUSTOMERS=ERP PROMOTIONS=LOCAL LOYALTY=LOCAL SUPPLY=ERP up=[ERP] link=false push=false pull=false catalogueHO=false supplyHO=false hoErp=false hoNoErp=false erp=true nav=true");
	}

	private static void row(String shape, String oldProfile, String extraKeys, String expected) {
		ROWS.put(shape, expected);
		OLD_SOURCES.put(shape, new String[] { oldProfile, extraKeys });
	}

	static Properties load(String resource) throws Exception {
		Properties properties = new Properties();
		try (InputStream in = PresetTruthTableTest.class.getResourceAsStream(resource)) {
			assertNotNull(in, resource);
			properties.load(in);
		}
		return properties;
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
	@DisplayName("9.3a: every old profile file gives its frozen row (the answers each preset must keep)")
	void oldProfilesGiveTheFrozenRows() throws Exception {
		Properties base = load("/application.properties");
		for (Map.Entry<String, String[]> source : OLD_SOURCES.entrySet()) {
			MockEnvironment env = new MockEnvironment();
			Properties merged = new Properties();
			merged.putAll(base);
			merged.putAll(load("/application-" + source.getValue()[0] + ".properties"));
			merged.stringPropertyNames().forEach(key -> env.setProperty(key, merged.getProperty(key)));
			putKeys(env, source.getValue()[1]);
			assertEquals(ROWS.get(source.getKey()), answer(env), source.getKey() + " <- " + source.getValue()[0]);
		}
	}
}
