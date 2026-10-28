package com.stripe.core.customer.impl;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.customer.StripeCustomerService;
import com.stripe.core.customer.dao.StripeCustomerDao;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripeCustomerModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.search.SearchResult;
import de.hybris.platform.servicelayer.time.TimeService;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskService;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

@UnitTest
public class DefaultStripeCashBalanceServiceTest
{
	private static final String CUSTOMER = "cus_1";

	private StripeGateway gateway;
	private ModelService modelService;
	private TaskService taskService;
	private FlexibleSearchService searchService;
	private SearchResult<TaskModel> searchResult;
	private StripePaymentIntentRecordDao recordDao;
	private StripeCustomerDao customerDao;
	private DefaultStripeCashBalanceService service;

	@Before
	@SuppressWarnings("unchecked")
	public void setUp()
	{
		gateway = mock(StripeGateway.class);
		modelService = mock(ModelService.class);
		taskService = mock(TaskService.class);
		searchService = mock(FlexibleSearchService.class);
		searchResult = mock(SearchResult.class);
		final StripeConfigurationService configuration = mock(StripeConfigurationService.class);
		when(configuration.getCashBalanceRefundDelay()).thenReturn(Duration.ofSeconds(3600));
		when(configuration.getPendingPaymentTimeout("customer_balance")).thenReturn(Duration.ofSeconds(500));
		final TimeService timeService = mock(TimeService.class);
		when(timeService.getCurrentTime()).thenReturn(new Date(1_000_000L));
		when(searchService.<TaskModel>search(any(FlexibleSearchQuery.class))).thenReturn(searchResult);
		when(searchResult.getResult()).thenReturn(List.of());
		when(gateway.retrieveLatestCashBalanceTransactionId(anyString())).thenReturn("cbtxn_1");
		recordDao = mock(StripePaymentIntentRecordDao.class);
		when(recordDao.findByPaymentIntentId(any())).thenReturn(Optional.of(new StripePaymentIntentRecordModel()));
		when(modelService.create(TaskModel.class)).thenAnswer(invocation -> new TaskModel());
		customerDao = mock(StripeCustomerDao.class);
		when(customerDao.findByStripeCustomerId(CUSTOMER)).thenReturn(Optional.of(new StripeCustomerModel()));
		when(gateway.retrieveCustomer(CUSTOMER)).thenReturn(customer(true, null));

		service = new DefaultStripeCashBalanceService();
		service.setStripeGateway(gateway);
		service.setStripeConfigurationService(configuration);
		service.setModelService(modelService);
		service.setTaskService(taskService);
		service.setTimeService(timeService);
		service.setFlexibleSearchService(searchService);
		service.setStripePaymentIntentRecordDao(recordDao);
		service.setStripeCustomerDao(customerDao);
	}

	@Test
	public void foreignCustomerWithoutLocalRowIsNotRefunded()
	{
		when(customerDao.findByStripeCustomerId(CUSTOMER)).thenReturn(Optional.empty());
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(5000L);

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verifyNoRefund();
		verify(gateway, never()).retrieveCashBalance(anyString(), anyString());
	}

	@Test
	public void customerWithLocalRowButWithoutMarkerIsNotRefunded()
	{
		when(gateway.retrieveCustomer(CUSTOMER)).thenReturn(customer(false, null));
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(5000L);

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verifyNoRefund();
	}

	@Test
	public void deletedOrMissingCustomerIsNotRefunded()
	{
		final Customer deleted = customer(true, null);
		deleted.setDeleted(Boolean.TRUE);
		when(gateway.retrieveCustomer(CUSTOMER)).thenReturn(deleted);
		service.refundUnappliedBalance(CUSTOMER, "eur");

		when(gateway.retrieveCustomer(CUSTOMER))
				.thenThrow(new StripeGatewayException("No such customer", "resource_missing", false, false, null));
		service.refundUnappliedBalance(CUSTOMER, "eur");

		verifyNoRefund();
	}

	@Test
	public void guestCustomerWithPaymentRecordOfItsCartIsRefunded()
	{
		when(customerDao.findByStripeCustomerId(CUSTOMER)).thenReturn(Optional.empty());
		when(gateway.retrieveCustomer(CUSTOMER)).thenReturn(customer(true, "cart-7"));
		when(recordDao.countByCartCode("cart-7")).thenReturn(1);
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(5000L);
		when(gateway.listOpenPaymentIntents(CUSTOMER)).thenReturn(List.of());

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verify(gateway).createCashBalanceRefund(CUSTOMER, 5000L, "eur", "cash-refund-cus_1-eur-cbtxn_1-5000");
	}

	@Test
	public void guestCustomerWithoutPaymentRecordOfItsCartIsNotRefunded()
	{
		when(customerDao.findByStripeCustomerId(CUSTOMER)).thenReturn(Optional.empty());
		when(gateway.retrieveCustomer(CUSTOMER)).thenReturn(customer(true, "cart-of-another-install"));
		when(recordDao.countByCartCode("cart-of-another-install")).thenReturn(0);
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(5000L);

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verifyNoRefund();
	}

	@Test
	public void foreignCustomerIsNotPlanned()
	{
		when(gateway.retrieveCustomer(CUSTOMER)).thenReturn(customer(false, null));
		service.scheduleRefundCheck(CUSTOMER, "eur");

		when(gateway.retrieveCustomer(CUSTOMER)).thenReturn(customer(true, null));
		when(customerDao.findByStripeCustomerId(CUSTOMER)).thenReturn(Optional.empty());
		service.scheduleRefundCheck(CUSTOMER, "eur");

		verify(taskService, never()).scheduleTask(any());
	}

	@Test
	public void checkIsPlannedWhenStripeCannotBeRead()
	{
		when(gateway.retrieveCustomer(CUSTOMER))
				.thenThrow(new StripeGatewayException("Stripe is not reachable", "api_connection_error", true, false, null));

		service.scheduleRefundCheck(CUSTOMER, "eur");

		verify(taskService).scheduleTask(any());
	}

	@Test
	public void refundCheckFailsWhenStripeCannotBeReadSoTheRunnerRetries()
	{
		when(gateway.retrieveCustomer(CUSTOMER))
				.thenThrow(new StripeGatewayException("Stripe is not reachable", "api_connection_error", true, false, null));

		org.junit.Assert.assertThrows(StripeGatewayException.class, () -> service.refundUnappliedBalance(CUSTOMER, "eur"));
		verifyNoRefund();
	}

	private void verifyNoRefund()
	{
		verify(gateway, never()).createCashBalanceRefund(anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString(),
				anyString());
	}

	private static Customer customer(final boolean marked, final String cartCode)
	{
		final Customer customer = new Customer();
		customer.setId(CUSTOMER);
		final Map<String, String> metadata = new HashMap<>();
		if (marked)
		{
			metadata.put(StripeCustomerService.CONNECTOR_METADATA_KEY, "true");
		}
		if (cartCode != null)
		{
			metadata.put("cartCode", cartCode);
		}
		customer.setMetadata(metadata);
		return customer;
	}

	@Test
	public void refundsBalanceWhenNoOpenTransfer()
	{
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(5000L);
		when(gateway.listOpenPaymentIntents(CUSTOMER)).thenReturn(List.of(paymentIntent("redirect_to_url")));

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verify(gateway).createCashBalanceRefund(CUSTOMER, 5000L, "eur", "cash-refund-cus_1-eur-cbtxn_1-5000");
	}

	@Test
	public void keepsBalanceWhileTransferIsOpen()
	{
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(5000L);
		when(gateway.listOpenPaymentIntents(CUSTOMER))
				.thenReturn(List.of(paymentIntent("display_bank_transfer_instructions")));

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verify(gateway, never()).createCashBalanceRefund(anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString(),
				anyString());
	}

	@Test
	public void keptBalanceSchedulesFollowUpCheck()
	{
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(5000L);
		when(gateway.listOpenPaymentIntents(CUSTOMER))
				.thenReturn(List.of(paymentIntent("display_bank_transfer_instructions")));
		final TaskModel created = mock(TaskModel.class);
		when(modelService.create(TaskModel.class)).thenReturn(created);

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verify(created).setContext("cash-refund-check:cus_1:eur");
		verify(created).setExecutionDate(new Date(1_000_000L + 3_600_000L));
		verify(taskService).scheduleTask(created);
	}

	@Test
	public void refundKeyChangesWhenNewFundsArriveWithSameAmount()
	{
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(1234L);
		when(gateway.listOpenPaymentIntents(CUSTOMER)).thenReturn(List.of());
		when(gateway.retrieveLatestCashBalanceTransactionId(CUSTOMER)).thenReturn("cbtxn_1", "cbtxn_2");

		service.refundUnappliedBalance(CUSTOMER, "eur");
		service.refundUnappliedBalance(CUSTOMER, "eur");

		final ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
		verify(gateway, times(2)).createCashBalanceRefund(any(), org.mockito.ArgumentMatchers.eq(1234L), any(),
				keys.capture());
		org.junit.Assert.assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
	}

	@Test
	public void openTransferUnknownToTheShopDoesNotKeepBalance()
	{
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(5000L);
		final PaymentIntent foreign = paymentIntent("display_bank_transfer_instructions");
		foreign.setId("pi_foreign");
		when(gateway.listOpenPaymentIntents(CUSTOMER)).thenReturn(List.of(foreign));
		when(recordDao.findByPaymentIntentId("pi_foreign")).thenReturn(Optional.empty());

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verify(gateway).createCashBalanceRefund(CUSTOMER, 5000L, "eur", "cash-refund-cus_1-eur-cbtxn_1-5000");
		verify(taskService, never()).scheduleTask(any());
	}

	@Test
	public void oldTransferRecordWithoutOrderDoesNotKeepBalance()
	{
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(5000L);
		final PaymentIntent abandoned = paymentIntent("display_bank_transfer_instructions");
		abandoned.setId("pi_abandoned");
		when(gateway.listOpenPaymentIntents(CUSTOMER)).thenReturn(List.of(abandoned));
		final StripePaymentIntentRecordModel record = org.mockito.Mockito.spy(new StripePaymentIntentRecordModel());
		// Created 600 s before now; the bank transfer timeout is 500 s, and there is no order.
		org.mockito.Mockito.doReturn(new Date(1_000_000L - 600_000L)).when(record).getCreationtime();
		when(recordDao.findByPaymentIntentId("pi_abandoned")).thenReturn(Optional.of(record));

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verify(gateway).createCashBalanceRefund(CUSTOMER, 5000L, "eur", "cash-refund-cus_1-eur-cbtxn_1-5000");
		verify(taskService, never()).scheduleTask(any());
	}

	@Test
	public void recentTransferRecordWithoutOrderKeepsBalance()
	{
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(5000L);
		final PaymentIntent waiting = paymentIntent("display_bank_transfer_instructions");
		waiting.setId("pi_waiting");
		when(gateway.listOpenPaymentIntents(CUSTOMER)).thenReturn(List.of(waiting));
		final StripePaymentIntentRecordModel record = org.mockito.Mockito.spy(new StripePaymentIntentRecordModel());
		org.mockito.Mockito.doReturn(new Date(1_000_000L - 100_000L)).when(record).getCreationtime();
		when(recordDao.findByPaymentIntentId("pi_waiting")).thenReturn(Optional.of(record));

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verifyNoRefund();
	}

	@Test
	public void openTransferInOtherCurrencyDoesNotKeepBalance()
	{
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(5000L);
		final PaymentIntent usd = paymentIntent("display_bank_transfer_instructions");
		usd.setCurrency("usd");
		when(gateway.listOpenPaymentIntents(CUSTOMER)).thenReturn(List.of(usd));

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verify(gateway).createCashBalanceRefund(CUSTOMER, 5000L, "eur", "cash-refund-cus_1-eur-cbtxn_1-5000");
	}

	@Test
	public void refundsBalanceWhenOnlyVoucherPaymentIsOpen()
	{
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(5000L);
		when(gateway.listOpenPaymentIntents(CUSTOMER)).thenReturn(List.of(paymentIntent("multibanco_display_details")));

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verify(gateway).createCashBalanceRefund(CUSTOMER, 5000L, "eur", "cash-refund-cus_1-eur-cbtxn_1-5000");
	}

	@Test
	public void nothingToRefundWhenBalanceIsZero()
	{
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(0L);

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verify(gateway, never()).listOpenPaymentIntents(anyString());
		verify(gateway, never()).createCashBalanceRefund(anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString(),
				anyString());
	}

	@Test
	public void refundKeyIsStablePerBalanceAmount()
	{
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(1234L);
		when(gateway.retrieveLatestCashBalanceTransactionId(CUSTOMER)).thenReturn("cbtxn_1");
		when(gateway.listOpenPaymentIntents(CUSTOMER)).thenReturn(List.of());

		service.refundUnappliedBalance(CUSTOMER, "eur");
		service.refundUnappliedBalance(CUSTOMER, "eur");

		final ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
		verify(gateway, times(2)).createCashBalanceRefund(any(), org.mockito.ArgumentMatchers.eq(1234L), any(),
				keys.capture());
		assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
	}

	@Test
	public void schedulesCheckWithConfiguredDelay()
	{
		final TaskModel created = mock(TaskModel.class);
		when(modelService.create(TaskModel.class)).thenReturn(created);

		service.scheduleRefundCheck(CUSTOMER, "EUR");

		verify(created).setRunnerBean("stripeCashBalanceRefundTaskRunner");
		verify(created).setContext("cash-refund-check:cus_1:eur");
		verify(created).setExecutionDate(new Date(1_000_000L + 3_600_000L));
		verify(taskService).scheduleTask(created);
	}

	@Test
	public void onlyOnePendingCheckPerCustomerAndCurrency()
	{
		final TaskModel pending = new TaskModel();
		pending.setContext("cash-refund-check:cus_1:eur");
		pending.setExecutionDate(new Date(9_000_000L));
		when(searchResult.getResult()).thenReturn(List.of(pending));

		service.scheduleRefundCheck(CUSTOMER, "eur");
		verify(taskService, never()).scheduleTask(any());

		service.scheduleRefundCheck(CUSTOMER, "usd");
		verify(taskService).scheduleTask(any());
	}

	private static PaymentIntent paymentIntent(final String nextActionType)
	{
		final PaymentIntent paymentIntent = new PaymentIntent();
		paymentIntent.setStatus("requires_action");
		paymentIntent.setCurrency("eur");
		final PaymentIntent.NextAction nextAction = new PaymentIntent.NextAction();
		nextAction.setType(nextActionType);
		paymentIntent.setNextAction(nextAction);
		return paymentIntent;
	}
}
