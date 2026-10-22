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
import com.stripe.core.gateway.StripeGateway;
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
import java.util.List;
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
		final TimeService timeService = mock(TimeService.class);
		when(timeService.getCurrentTime()).thenReturn(new Date(1_000_000L));
		when(searchService.<TaskModel>search(any(FlexibleSearchQuery.class))).thenReturn(searchResult);
		when(searchResult.getResult()).thenReturn(List.of());
		when(modelService.create(TaskModel.class)).thenAnswer(invocation -> new TaskModel());

		service = new DefaultStripeCashBalanceService();
		service.setStripeGateway(gateway);
		service.setStripeConfigurationService(configuration);
		service.setModelService(modelService);
		service.setTaskService(taskService);
		service.setTimeService(timeService);
		service.setFlexibleSearchService(searchService);
	}

	@Test
	public void refundsBalanceWhenNoOpenTransfer()
	{
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(5000L);
		when(gateway.listOpenPaymentIntents(CUSTOMER)).thenReturn(List.of(paymentIntent("redirect_to_url")));

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verify(gateway).createCashBalanceRefund(CUSTOMER, 5000L, "eur", "cash-refund-cus_1-eur-5000");
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
	public void refundsBalanceWhenOnlyVoucherPaymentIsOpen()
	{
		when(gateway.retrieveCashBalance(CUSTOMER, "eur")).thenReturn(5000L);
		when(gateway.listOpenPaymentIntents(CUSTOMER)).thenReturn(List.of(paymentIntent("multibanco_display_details")));

		service.refundUnappliedBalance(CUSTOMER, "eur");

		verify(gateway).createCashBalanceRefund(CUSTOMER, 5000L, "eur", "cash-refund-cus_1-eur-5000");
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
		final PaymentIntent.NextAction nextAction = new PaymentIntent.NextAction();
		nextAction.setType(nextActionType);
		paymentIntent.setNextAction(nextAction);
		return paymentIntent;
	}
}
