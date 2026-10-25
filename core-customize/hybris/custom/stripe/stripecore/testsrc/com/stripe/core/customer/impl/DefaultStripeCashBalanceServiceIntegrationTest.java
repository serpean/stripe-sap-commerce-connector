package com.stripe.core.customer.impl;

import static org.junit.Assert.assertEquals;

import com.stripe.core.customer.StripeCashBalanceService;
import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.servicelayer.ServicelayerTransactionalTest;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.task.TaskModel;
import javax.annotation.Resource;
import org.junit.Test;

/**
 * Runs the real FlexibleSearch of the pending-check lookup. The unit test cannot catch a query on a dynamic attribute
 * such as Task.executionDate: the search throws FlexibleSearchException only against the type system.
 */
@IntegrationTest
public class DefaultStripeCashBalanceServiceIntegrationTest extends ServicelayerTransactionalTest
{
	@Resource
	private StripeCashBalanceService stripeCashBalanceService;
	@Resource
	private FlexibleSearchService flexibleSearchService;

	@Test
	public void scheduleRefundCheckPlansOneTaskAndASecondCallFindsItPending()
	{
		stripeCashBalanceService.scheduleRefundCheck("cus_it_cash", "eur");
		stripeCashBalanceService.scheduleRefundCheck("cus_it_cash", "eur");

		final FlexibleSearchQuery query = new FlexibleSearchQuery("SELECT {pk} FROM {Task} WHERE {runnerBean} = ?runner");
		query.addQueryParameter("runner", DefaultStripeCashBalanceService.REFUND_RUNNER_BEAN);
		final String key = DefaultStripeCashBalanceService.contextOf("cus_it_cash", "eur");
		assertEquals(1, flexibleSearchService.<TaskModel>search(query).getResult().stream()
				.filter(task -> key.equals(task.getContext())).count());
	}
}
