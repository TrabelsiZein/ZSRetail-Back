package com.digithink.zsretail.store.erp.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 3.1.1 (ParaFendri, step 7): the ERP's invoices of the tickets, one PDF each, under the upload folder:
 * &lt;app.upload.dir&gt;/erp-invoices/&lt;invoice number&gt;.pdf. The ticket keeps only its invoice number
 * (SalesHeader.invoiceNumber); the file's place is derived from it ({@link #pathOf}). Nothing serves the folder (the
 * images are served by ItemImageController from their own folders only).
 */
@Component
public class ErpInvoicePdfFiles {

	static final String FOLDER = "erp-invoices";

	private final String uploadDir;

	public ErpInvoicePdfFiles(@Value("${app.upload.dir:uploads/pos-images}") String uploadDir) {
		this.uploadDir = uploadDir;
	}

	/** Where the PDF of this invoice number is (or goes): a character other than a letter, a digit, . _ or - as _. */
	public Path pathOf(String invoiceNo) {
		String name = invoiceNo == null ? "" : invoiceNo.trim().replaceAll("[^A-Za-z0-9._-]", "_");
		if (name.isEmpty() || name.startsWith(".")) {
			throw new IllegalArgumentException("Not an invoice number for a file: '" + invoiceNo + "'");
		}
		return Paths.get(uploadDir, FOLDER, name + ".pdf");
	}

	/** Writes the PDF (an earlier file of the same number replaced), through a file beside it; its place. */
	public Path write(String invoiceNo, byte[] pdf) throws IOException {
		Path target = pathOf(invoiceNo);
		Files.createDirectories(target.getParent());
		Path partial = target.resolveSibling(target.getFileName() + ".part");
		Files.write(partial, pdf);
		return Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
	}
}
