package com.digithink.zsretail.security;

import javax.servlet.Filter;

/**
 * The filter that checks a store's key on /ho/**: exists only on a head office (headoffice.security.StoreApiKeyFilter).
 * The /ho/** chain (core, both installation types) adds it when it exists. Code cleanup step 5: the chain no longer
 * names the head office's class.
 */
public interface StoreKeyFilter extends Filter {
}
