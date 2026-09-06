package com.digithink.zsretail.service;

import java.awt.print.PrinterException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.Copies;

import org.springframework.stereotype.Service;

import com.digithink.zsretail.model.Payment;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;

import lombok.extern.log4j.Log4j2;

/**
 * Service for generating and printing POS tickets/receipts
 */
@Service
@Log4j2
public class TicketPrintingService {

	private static final int TICKET_WIDTH = 48; // Characters per line for 80mm thermal printer
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/**
	 * Generate and print ticket/receipt (with single payment - for backward compatibility)
	 */
	public void printTicket(SalesHeader salesHeader, List<SalesLine> salesLines, Payment payment) 
			throws Exception {
		printTicket(salesHeader, salesLines, java.util.Arrays.asList(payment));
	}
	
	/**
	 * Generate and print ticket/receipt (with multiple payments)
	 */
	public void printTicket(SalesHeader salesHeader, List<SalesLine> salesLines, List<Payment> payments) 
			throws Exception {
		
		log.info("Generating ticket for sale: " + salesHeader.getSalesNumber());
		
		// Generate ticket content
		String ticketContent = generateTicketContent(salesHeader, salesLines, payments);
		
		// Print ticket
		printToPrinter(ticketContent);
		
		log.info("Ticket printed successfully");
	}

	/**
	 * Generate ticket content as text (with multiple payments)
	 */
	private String generateTicketContent(SalesHeader salesHeader, List<SalesLine> salesLines, List<Payment> payments) {
		StringBuilder ticket = new StringBuilder();
		
		// Header
		ticket.append(centerText("POS SYSTEM")).append("\n");
		ticket.append(repeatChar('-', TICKET_WIDTH)).append("\n");
		ticket.append(centerText("SALES RECEIPT")).append("\n");
		ticket.append(repeatChar('-', TICKET_WIDTH)).append("\n");
		
		// Sale information
		ticket.append(String.format("%-20s: %s", "Sale Number", 
			salesHeader.getSalesNumber() != null ? salesHeader.getSalesNumber() : "N/A")).append("\n");
		ticket.append(String.format("%-20s: %s", "Date", 
			salesHeader.getSalesDate() != null ? 
				salesHeader.getSalesDate().format(DATE_FORMATTER) : 
				LocalDateTime.now().format(DATE_FORMATTER))).append("\n");
		
		if (salesHeader.getCreatedByUser() != null) {
			String cashierName = salesHeader.getCreatedByUser().getFullName();
			if (cashierName == null || cashierName.isEmpty()) {
				cashierName = salesHeader.getCreatedByUser().getUsername();
			}
			ticket.append(String.format("%-20s: %s", "Cashier", cashierName)).append("\n");
		}
		
		if (salesHeader.getCashierSession() != null && salesHeader.getCashierSession().getSessionNumber() != null) {
			ticket.append(String.format("%-20s: %s", "Session", 
				salesHeader.getCashierSession().getSessionNumber())).append("\n");
		}
		
		ticket.append(repeatChar('-', TICKET_WIDTH)).append("\n");
		
		// Items
		ticket.append(String.format("%-25s %6s %10s", "Item", "Qty", "Total")).append("\n");
		ticket.append(repeatChar('-', TICKET_WIDTH)).append("\n");
		
		for (SalesLine line : salesLines) {
			if (line.getItem() == null) continue;
			
			String itemName = line.getItem().getName() != null ? line.getItem().getName() : "Unknown Item";
			if (itemName.length() > 25) {
				itemName = itemName.substring(0, 22) + "...";
			}
			ticket.append(String.format("%-25s %6d %10.2f", 
				itemName, 
				line.getQuantity() != null ? line.getQuantity() : 0, 
				line.getLineTotal() != null ? line.getLineTotal() : 0.0)).append("\n");
			
			if (line.getItem().getItemCode() != null) {
				ticket.append(String.format("  Code: %s", line.getItem().getItemCode())).append("\n");
			}
		}
		
		ticket.append(repeatChar('-', TICKET_WIDTH)).append("\n");
		
		// Totals
		ticket.append(String.format("%-20s: %10.2f", "Subtotal", 
			salesHeader.getSubtotal() != null ? salesHeader.getSubtotal() : 0.0)).append("\n");
		
		if (salesHeader.getDiscountAmount() != null && salesHeader.getDiscountAmount() > 0) {
			ticket.append(String.format("%-20s: %10.2f", "Discount", salesHeader.getDiscountAmount())).append("\n");
		}
		
		if (salesHeader.getTaxAmount() != null && salesHeader.getTaxAmount() > 0) {
			ticket.append(String.format("%-20s: %10.2f", "Tax", salesHeader.getTaxAmount())).append("\n");
		}
		
		ticket.append(repeatChar('-', TICKET_WIDTH)).append("\n");
		ticket.append(String.format("%-20s: %10.2f", "TOTAL", 
			salesHeader.getTotalAmount() != null ? salesHeader.getTotalAmount() : 0.0)).append("\n");
		ticket.append(repeatChar('-', TICKET_WIDTH)).append("\n");
		
		// Payment information
		if (payments != null && !payments.isEmpty()) {
			ticket.append(repeatChar('-', TICKET_WIDTH)).append("\n");
			ticket.append("PAYMENTS:").append("\n");
			ticket.append(repeatChar('-', TICKET_WIDTH)).append("\n");
			
			for (Payment payment : payments) {
				if (payment != null && payment.getPaymentMethod() != null) {
					ticket.append(String.format("%-20s: %s", "Method", 
						payment.getPaymentMethod().getName())).append("\n");
					ticket.append(String.format("%-20s: %10.2f", "Amount", 
						payment.getTotalAmount() != null ? payment.getTotalAmount() : 0.0)).append("\n");
					
					if (payment.getPaymentReference() != null && !payment.getPaymentReference().isEmpty()) {
						ticket.append(String.format("%-20s: %s", "Reference", payment.getPaymentReference())).append("\n");
					}
					
					ticket.append(repeatChar('-', TICKET_WIDTH)).append("\n");
				}
			}
			
			if (salesHeader.getPaidAmount() != null) {
				ticket.append(String.format("%-20s: %10.2f", "Total Paid", salesHeader.getPaidAmount())).append("\n");
			}
			
			if (salesHeader.getChangeAmount() != null && salesHeader.getChangeAmount() > 0) {
				ticket.append(String.format("%-20s: %10.2f", "Change", salesHeader.getChangeAmount())).append("\n");
			}
			
			ticket.append(repeatChar('-', TICKET_WIDTH)).append("\n");
		}
		
		// Footer
		ticket.append(centerText("Thank you for your purchase!")).append("\n");
		ticket.append(repeatChar('=', TICKET_WIDTH)).append("\n");
		ticket.append("\n\n\n"); // Feed paper
		
		return ticket.toString();
	}

	/**
	 * Print ticket to default printer
	 */
	private void printToPrinter(String content) throws PrinterException, IOException {
		try {
			// Try to find default printer
			PrintService defaultPrinter = PrintServiceLookup.lookupDefaultPrintService();
			
			if (defaultPrinter == null) {
				// No printer found, write to file instead
				log.warn("No printer found, writing ticket to file");
				writeToFile(content);
				return;
			}
			
			// Create print job
			DocPrintJob printJob = defaultPrinter.createPrintJob();
			DocFlavor flavor = DocFlavor.STRING.TEXT_PLAIN;
			Doc doc = new SimpleDoc(content, flavor, null);
			
			PrintRequestAttributeSet attributes = new HashPrintRequestAttributeSet();
			attributes.add(new Copies(1));
			
			// Print
			printJob.print(doc, attributes);
			log.info("Ticket sent to printer: " + defaultPrinter.getName());
			
		} catch (Exception e) {
			log.error("Error printing to printer, writing to file instead", e);
			writeToFile(content);
		}
	}

	/**
	 * Write ticket to file as fallback
	 */
	private void writeToFile(String content) throws IOException {
		String fileName = "ticket_" + System.currentTimeMillis() + ".txt";
		String filePath = System.getProperty("user.home") + "/POS_Tickets/" + fileName;
		
		// Create directory if it doesn't exist
		java.io.File dir = new java.io.File(System.getProperty("user.home") + "/POS_Tickets/");
		if (!dir.exists()) {
			dir.mkdirs();
		}
		
		try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
			writer.print(content);
			log.info("Ticket written to file: " + filePath);
		}
	}

	/**
	 * Center text for ticket
	 */
	private String centerText(String text) {
		if (text.length() >= TICKET_WIDTH) {
			return text.substring(0, TICKET_WIDTH);
		}
		int padding = (TICKET_WIDTH - text.length()) / 2;
		return repeatChar(' ', padding) + text;
	}

	/**
	 * Repeat character
	 */
	private String repeatChar(char c, int count) {
		return new String(new char[count]).replace('\0', c);
	}
}

