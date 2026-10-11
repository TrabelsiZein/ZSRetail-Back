package com.digithink.zsretail.store.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.SalesHeader;

/**
 * RFID POS step: a SaleTagHooks that writes down each call, as a line of text, for the tests of the sale and return
 * code (the real one, RfidSaleTags, has its own tests).
 */
class RecordingSaleTagHooks implements SaleTagHooks {

	final List<String> calls = new ArrayList<>();
	final List<LineTags> lastLines = new ArrayList<>();
	final List<ReturnedLine> lastReturned = new ArrayList<>();

	/** The provider the services are given, as Spring gives it when the hook exists. */
	ObjectProvider<SaleTagHooks> provider() {
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("saleTagHooks", this);
		return beans.getBeanProvider(SaleTagHooks.class);
	}

	@Override
	public void ticketHeld(SalesHeader ticket, List<LineTags> lines) {
		calls.add("held " + ticket.getSalesNumber() + " " + describe(lines));
		lastLines.clear();
		lastLines.addAll(lines);
	}

	@Override
	public void ticketCompleted(SalesHeader ticket, List<LineTags> lines) {
		calls.add("completed " + ticket.getSalesNumber() + " " + ticket.getStatus() + " " + describe(lines));
		lastLines.clear();
		lastLines.addAll(lines);
	}

	@Override
	public void ticketCancelled(SalesHeader ticket) {
		calls.add("cancelled " + ticket.getSalesNumber() + " " + ticket.getStatus());
	}

	@Override
	public void returned(ReturnHeader ret, List<ReturnedLine> lines, List<String> epcsRead) {
		calls.add("returned " + ret.getReturnNumber() + " "
				+ lines.stream().map(l -> l.getLine().getQuantity().toPlainString() + (l.isWholeRemaining() ? " whole" : " part"))
						.collect(Collectors.toList())
				+ " " + epcsRead);
		lastReturned.clear();
		lastReturned.addAll(lines);
	}

	private static String describe(List<LineTags> lines) {
		return lines.stream().map(l -> l.getLine().getItem().getItemCode() + l.getEpcs()).collect(Collectors.toList())
				.toString();
	}
}
