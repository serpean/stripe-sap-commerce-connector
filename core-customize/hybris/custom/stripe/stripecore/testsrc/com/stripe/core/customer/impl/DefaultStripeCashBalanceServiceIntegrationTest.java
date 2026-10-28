package com.stripe.core.customer.impl;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.customer.StripeCustomerService;
import com.stripe.core.customer.dao.StripeCustomerDao;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.model.StripeCustomerModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.model.Customer;
import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.core.model.user.CustomerModel;
import de.hybris.platform.servicelayer.ServicelayerTransactionalTest;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.time.TimeService;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskService;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Resource;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs the real FlexibleSearch of the pending-check lookup and the real DAOs of the customer ownership guard, with a
 * stub Stripe gateway. The unit test cannot catch a query on a dynamic attribute such as Task.executionDate: the search
 * throws FlexibleSearchException only against the type system.
 */
@IntegrationTest
public class DefaultStripeCashBalanceServiceIntegrationTest extends ServicelayerTransactionalTest
{
	@Resource
	private FlexibleSearchService flexibleSearchService;
	@Resource
	private ModelService modelService;
	@Resource
	private TaskService taskService;
	@Resource
	private TimeService timeService;
	@Resource
	private StripeConfigurationService stripeConfigurationService;
	@Resource
	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	@Resource
	private StripeCustomerDao stripeCustomerDao;

	private StripeGateway gateway;
	private DefaultStripeCashBalanceService service;

	@Before
	public void setUp()
	{
		gateway = mock(StripeGateway.class);
		service = new DefaultStripeCashBalanceService();
		service.setStripeGateway(gateway);
		service.setStripeConfigurationService(stripeConfigurationService);
		service.setModelService(modelService);
		service.setTaskService(taskService);
		service.setTimeService(timeService);
		service.setFlexibleSearchService(flexibleSearchService);
		service.setStripePaymentIntentRecordDao(stripePaymentIntentRecordDao);
		service.setStripeCustomerDao(stripeCustomerDao);
	}

	@Test
	public void scheduleRefundCheckPlansOneTaskAndASecondCallFindsItPending()
	{
		storeStripeCustomer("cus_it_cash");
		when(gateway.retrieveCustomer("cus_it_cash")).thenReturn(stripeCustomer("cus_it_cash", null));

		service.scheduleRefundCheck("cus_it_cash", "eur");
		service.scheduleRefundCheck("cus_it_cash", "eur");

		assertEquals(1, plannedChecks("cus_it_cash"));
	}

	@Test
	public void customerWithoutLocalRowIsNotPlanned()
	{
		when(gateway.retrieveCustomer("cus_it_foreign")).thenReturn(stripeCustomer("cus_it_foreign", null));

		service.scheduleRefundCheck("cus_it_foreign", "eur");

		assertEquals(0, plannedChecks("cus_it_foreign"));
	}

	@Test
	public void guestCustomerWithPaymentRecordOfItsCartIsPlanned()
	{
		final StripePaymentIntentRecordModel record = modelService.create(StripePaymentIntentRecordModel.class);
		record.setPaymentIntentId("pi_it_guest_" + System.nanoTime());
		record.setCartCode("it-guest-cart");
		modelService.save(record);
		when(gateway.retrieveCustomer("cus_it_guest")).thenReturn(stripeCustomer("cus_it_guest", "it-guest-cart"));
		when(gateway.retrieveCustomer("cus_it_other_guest"))
				.thenReturn(stripeCustomer("cus_it_other_guest", "it-cart-of-another-install"));

		service.scheduleRefundCheck("cus_it_guest", "eur");
		service.scheduleRefundCheck("cus_it_other_guest", "eur");

		assertEquals(1, plannedChecks("cus_it_guest"));
		assertEquals(0, plannedChecks("cus_it_other_guest"));
	}

	private void storeStripeCustomer(final String stripeCustomerId)
	{
		final CustomerModel user = modelService.create(CustomerModel.class);
		user.setUid("cash-it-" + System.nanoTime() + "@example.com");
		user.setName("Cash IT");
		modelService.save(user);
		final StripeCustomerModel stored = modelService.create(StripeCustomerModel.class);
		stored.setCustomer(user);
		stored.setAccountKey("acct_it:test");
		stored.setStripeCustomerId(stripeCustomerId);
		modelService.save(stored);
	}

	private static Customer stripeCustomer(final String id, final String cartCode)
	{
		final Customer customer = new Customer();
		customer.setId(id);
		final Map<String, String> metadata = new HashMap<>();
		metadata.put(StripeCustomerService.CONNECTOR_METADATA_KEY, StripeCustomerService.CONNECTOR_METADATA_VALUE);
		if (cartCode != null)
		{
			metadata.put(StripeCustomerService.CART_CODE_METADATA_KEY, cartCode);
		}
		customer.setMetadata(metadata);
		return customer;
	}

	private long plannedChecks(final String stripeCustomerId)
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery("SELECT {pk} FROM {Task} WHERE {runnerBean} = ?runner");
		query.addQueryParameter("runner", DefaultStripeCashBalanceService.REFUND_RUNNER_BEAN);
		final String key = DefaultStripeCashBalanceService.contextOf(stripeCustomerId, "eur");
		return flexibleSearchService.<TaskModel>search(query).getResult().stream()
				.filter(task -> key.equals(task.getContext())).count();
	}
}
