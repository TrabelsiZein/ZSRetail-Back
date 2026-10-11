package com.digithink.zsretail.headoffice.enumeration;

/** Kind of store in the head office stores list (head office design 2.2, 4.6). */
public enum StoreKind {
	OWN,       // a store of the company
	FRANCHISE  // a franchisee: its shipments are invoiced at the franchise price (step 7)
}
