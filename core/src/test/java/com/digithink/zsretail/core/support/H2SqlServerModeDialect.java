package com.digithink.zsretail.core.support;

import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.dialect.pagination.SQL2008StandardLimitHandler;

/**
 * Boot 4 migration, step 1.4: the dialect of the tests that run a query on H2 in SQL Server mode. H2 2.x refuses LIMIT in that
 * mode and Hibernate 5.6's H2Dialect writes "limit ?"; the standard OFFSET/FETCH form is accepted, and is what SQL Server 2012
 * and later use too. To remove at step 1.7: Hibernate 6's H2Dialect writes that form itself.
 */
public class H2SqlServerModeDialect extends H2Dialect {

	@Override
	public LimitHandler getLimitHandler() {
		return SQL2008StandardLimitHandler.INSTANCE;
	}
}
