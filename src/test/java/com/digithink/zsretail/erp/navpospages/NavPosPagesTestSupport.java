package com.digithink.zsretail.erp.navpospages;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.StreamUtils;

import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCollection;
import com.digithink.zsretail.erp.navpospages.dto.NavPosInvoiceRow;
import com.digithink.zsretail.support.Installations;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** ERP catalogue, step 5: the samples of src/test/resources/navpospages and valid settings, for the tests. */
final class NavPosPagesTestSupport {

	static final String BASE_URL = "http://bc.test:7048/BC140/ODataV4";
	static final String COMPANY_URL = BASE_URL + "/Company('HAPPYNESS')/";

	private NavPosPagesTestSupport() {
	}

	/** A sample file of the three pages, as text. */
	static String sample(String page) {
		try (InputStream in = NavPosPagesTestSupport.class.getResourceAsStream("/navpospages/" + page + ".json")) {
			return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The rows of a sample file. */
	static <T> List<T> rows(String page, TypeReference<NavPosCollection<T>> type) {
		try {
			return new ObjectMapper().readValue(sample(page), type).getValue();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Invoices from the ERP: the two invoices of FactureFranchise.json (invented), their lines resolved. */
	static List<NavPosInvoiceRow> invoiceRows() {
		List<NavPosInvoiceRow> rows = rows("FactureFranchise", new TypeReference<NavPosCollection<NavPosInvoiceRow>>() {
		});
		rows.forEach(row -> row.resolveLines("FactureFranchiseSalesInvLines"));
		return rows;
	}

	/** Settings that pass every startup check (no ERP is called with them). */
	static NavPosPagesProperties properties() {
		NavPosPagesProperties properties = new NavPosPagesProperties();
		properties.setEnabled(true);
		properties.setBaseUrl(BASE_URL);
		properties.setCompany("HAPPYNESS");
		properties.setDomain("DOMAIN");
		properties.setUsername("user");
		properties.setPassword("secret");
		properties.setLocationCode("FRANCHISE");
		properties.setDefaultVat(19);
		properties.setPriceIncludesVat(true);
		return properties;
	}

	/** A head office whose catalogue only comes from the ERP, with valid navpospages keys. */
	static MockEnvironment validEnvironment() {
		MockEnvironment env = Installations.type("headoffice");
		env.setProperty("ownership.catalogue", "ERP");
		env.setProperty("erp.navpospages.enabled", "true");
		env.setProperty("erp.navpospages.base-url", BASE_URL);
		env.setProperty("erp.navpospages.company", "HAPPYNESS");
		env.setProperty("erp.navpospages.username", "user");
		env.setProperty("erp.navpospages.password", "secret");
		env.setProperty("erp.navpospages.location-code", "FRANCHISE");
		env.setProperty("erp.navpospages.default-vat", "19");
		env.setProperty("erp.navpospages.price-includes-vat", "true");
		return env;
	}
}
