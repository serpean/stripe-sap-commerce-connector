package com.stripe.core.order;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.order.impl.DefaultStripePaymentAdjustmentService;
import com.stripe.core.payment.StripePaymentIntentChecks;
import com.stripe.core.site.StripeSiteContext;
import com.stripe.core.testsupport.StripeSiteContextMocks;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.OrderEntryModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.order.payment.CreditCardPaymentInfoModel;
import de.hybris.platform.order.CalculationService;
import de.hybris.platform.ordercancel.model.OrderCancelRecordEntryModel;
import de.hybris.platform.payment.PaymentService;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.time.TimeService;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

@UnitTest
public class StripeOrderCancelPaymentServiceStrategyTest
{
	private StripeSiteContext siteContext;
	private BaseSiteModel site;
	private static final String PI_ID = "pi_cancel";

	private static final Date NOW = new Date(1_700_000_000_000L);

	private CalculationService calculationService;
	private TaskService taskService;
	private TaskModel retryTask;
	private PaymentService paymentService;
	private StripeGateway stripeGateway;
	private StripePaymentIntentRecordDao recordDao;
	private StripePaymentIntentService paymentIntentService;
	private StripeAmountConverter amountConverter;
	private StripeOrderCancelPaymentServiceStrategy strategy;
	private OrderModel order;
	private PaymentTransactionModel transaction;
	private PaymentTransactionEntryModel authorization;

	@Before
	public void setUp()
	{
		calculationService = mock(CalculationService.class);
		paymentService = mock(PaymentService.class);
		stripeGateway = mock(StripeGateway.class);
		amountConverter = mock(StripeAmountConverter.class);
		when(amountConverter.fromMinorUnits(anyLong(), anyString())).thenAnswer(
				invocation -> BigDecimal.valueOf((long) invocation.getArgument(0), 2));

		taskService = mock(TaskService.class);
		final ModelService modelService = mock(ModelService.class);
		retryTask = mock(TaskModel.class);
		when(modelService.create(TaskModel.class)).thenReturn(retryTask);
		final TimeService timeService = mock(TimeService.class);
		when(timeService.getCurrentTime()).thenReturn(NOW);
		final StripePaymentIntentChecks checks = new StripePaymentIntentChecks();
		checks.setStripeAmountConverter(amountConverter);

		final DefaultStripePaymentAdjustmentService adjustmentService = new DefaultStripePaymentAdjustmentService();
		adjustmentService.setPaymentService(paymentService);
		adjustmentService.setStripeGateway(stripeGateway);
		adjustmentService.setStripeAmountConverter(amountConverter);
		adjustmentService.setStripePaymentIntentChecks(checks);
		adjustmentService.setModelService(modelService);
		adjustmentService.setTaskService(taskService);
		adjustmentService.setTimeService(timeService);
		recordDao = mock(StripePaymentIntentRecordDao.class);
		paymentIntentService = mock(StripePaymentIntentService.class);
		adjustmentService.setStripePaymentIntentRecordDao(recordDao);
		adjustmentService.setStripePaymentIntentService(paymentIntentService);

		strategy = new StripeOrderCancelPaymentServiceStrategy();
		strategy.setCalculationService(calculationService);
		strategy.setStripePaymentAdjustmentService(adjustmentService);
		siteContext = StripeSiteContextMocks.direct();
		strategy.setStripeSiteContext(siteContext);
		site = new BaseSiteModel();
		site.setUid("site-e");

		final CurrencyModel currency = new CurrencyModel();
		currency.setIsocode("EUR");
		final StripePaymentInfoModel paymentInfo = new StripePaymentInfoModel();
		paymentInfo.setStripePaymentIntentId(PI_ID);
		order = new OrderModel();
		order.setCode("order-cancel");
		order.setSite(site);
		order.setCurrency(currency);
		order.setPaymentInfo(paymentInfo);
		final OrderEntryModel orderEntry = new OrderEntryModel();
		orderEntry.setQuantity(2L);
		order.setEntries(new ArrayList<>(List.of(orderEntry)));

		authorization = new PaymentTransactionEntryModel();
		authorization.setType(PaymentTransactionType.AUTHORIZATION);
		authorization.setTransactionStatus(TransactionStatus.ACCEPTED.name());
		authorization.setRequestId(PI_ID);
		authorization.setAmount(new BigDecimal("100.00"));
		transaction = new PaymentTransactionModel();
		transaction.setCode("txn-cancel");
		transaction.setEntries(new ArrayList<>(List.of(authorization)));
		order.setPaymentTransactions(new ArrayList<>(List.of(transaction)));
	}

	@Test
	public void fullCancelOfPendingTransferOrderReleasesTheIntent() throws Exception
	{
		givenPendingOrderFullyCancelled("requires_action", StripePaymentIntentState.AWAITING_FUNDS);

		strategy.recalculateOrderAndModifyPayments(order);

		verify(calculationService).calculateTotals(order, true);
		verify(paymentIntentService).releaseFunds(eq(PI_ID), anyString());
		verify(taskService, never()).scheduleTask(any());
	}

	@Test
	public void fullCancelOfProcessingSepaOrderDoesNotFail() throws Exception
	{
		givenPendingOrderFullyCancelled("processing", StripePaymentIntentState.PROCESSING);

		strategy.recalculateOrderAndModifyPayments(order);

		verify(calculationService).calculateTotals(order, true);
		verify(paymentIntentService, never()).releaseFunds(anyString(), anyString());
		verify(taskService, never()).scheduleTask(any());
	}

	@Test
	public void partialCancelOfPendingOrderReleasesNothing() throws Exception
	{
		givenPendingOrderFullyCancelled("requires_action", StripePaymentIntentState.AWAITING_FUNDS);
		order.getEntries().get(0).setQuantity(1L);

		strategy.recalculateOrderAndModifyPayments(order);

		verify(paymentIntentService, never()).releaseFunds(anyString(), anyString());
	}

	private void givenPendingOrderFullyCancelled(final String stripeStatus, final StripePaymentIntentState state)
	{
		transaction.setEntries(new ArrayList<>());
		order.getEntries().get(0).setQuantity(0L);
		final StripePaymentIntentRecordModel record = new StripePaymentIntentRecordModel();
		record.setState(state);
		when(recordDao.findByPaymentIntentId(PI_ID)).thenReturn(Optional.of(record));
		livePaymentIntent(stripeStatus, 0L, null);
	}

	@Test
	public void fullCancelBeforeCaptureVoids() throws Exception
	{
		livePaymentIntent("requires_capture", 0L, null);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(0L);
		when(paymentService.cancel(authorization)).thenReturn(entry(TransactionStatus.ACCEPTED));

		strategy.recalculateOrderAndModifyPayments(order);

		final InOrder inOrder = inOrder(calculationService, paymentService);
		inOrder.verify(calculationService).calculateTotals(order, true);
		inOrder.verify(paymentService).cancel(authorization);
		verify(paymentService, never()).refundFollowOn(any(), any());
	}

	@Test
	public void partialCancelBeforeCaptureDoesNothing() throws Exception
	{
		livePaymentIntent("requires_capture", 0L, null);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(6000L);

		strategy.recalculateOrderAndModifyPayments(order);

		verify(calculationService).calculateTotals(order, true);
		verifyNoInteractions(paymentService);
	}

	@Test
	public void cancelAfterCaptureRefundsDifference() throws Exception
	{
		livePaymentIntent("succeeded", 10000L, 0L);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(6000L);
		when(paymentService.refundFollowOn(eq(transaction), any())).thenReturn(entry(TransactionStatus.ACCEPTED));

		strategy.recalculateOrderAndModifyPayments(order);

		verify(paymentService).refundFollowOn(transaction, new BigDecimal("40.00"));
		verify(paymentService, never()).cancel(any());
	}

	@Test
	public void fullCancelAfterCaptureRefundsEverything() throws Exception
	{
		livePaymentIntent("succeeded", 10000L, 0L);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(0L);
		when(paymentService.refundFollowOn(eq(transaction), any())).thenReturn(entry(TransactionStatus.ACCEPTED));

		strategy.recalculateOrderAndModifyPayments(order);

		verify(paymentService).refundFollowOn(transaction, new BigDecimal("100.00"));
		verify(paymentService, never()).cancel(any());
	}

	@Test
	public void repeatedCancelRefundsOnlyWhatIsStillCaptured() throws Exception
	{
		livePaymentIntent("succeeded", 10000L, 4000L);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(6000L);

		strategy.recalculateOrderAndModifyPayments(order);

		verifyNoInteractions(paymentService);
	}

	@Test
	public void secondPartialCancelRefundsOnlyTheNewDifference() throws Exception
	{
		livePaymentIntent("succeeded", 10000L, 4000L);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(2500L);
		when(paymentService.refundFollowOn(eq(transaction), any())).thenReturn(entry(TransactionStatus.ACCEPTED));

		strategy.recalculateOrderAndModifyPayments(order);

		verify(paymentService).refundFollowOn(transaction, new BigDecimal("35.00"));
	}

	@Test
	public void capturedPaymentIntentWithoutChargeUsesAmountReceived() throws Exception
	{
		livePaymentIntent("succeeded", 10000L, null);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(7000L);
		when(paymentService.refundFollowOn(eq(transaction), any())).thenReturn(entry(TransactionStatus.ACCEPTED));

		strategy.recalculateOrderAndModifyPayments(order);

		verify(paymentService).refundFollowOn(transaction, new BigDecimal("30.00"));
	}

	@Test
	public void processingPaymentIntentIsLeftToTheAwaitAndWebhookPaths() throws Exception
	{
		livePaymentIntent("processing", 0L, null);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(0L);

		strategy.recalculateOrderAndModifyPayments(order);

		verifyNoInteractions(paymentService);
	}

	@Test
	public void canceledPaymentIntentIsNotVoidedAgain() throws Exception
	{
		livePaymentIntent("canceled", 0L, null);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(0L);

		strategy.recalculateOrderAndModifyPayments(order);

		verifyNoInteractions(paymentService);
	}

	@Test
	public void fullCancelWithDeliveryCostVoidsAuthorizedIntent() throws Exception
	{
		livePaymentIntent("requires_capture", 0L, null);
		order.getEntries().get(0).setQuantity(0L);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(495L);
		when(paymentService.cancel(authorization)).thenReturn(entry(TransactionStatus.ACCEPTED));

		strategy.recalculateOrderAndModifyPayments(order);

		verify(paymentService).cancel(authorization);
		verify(paymentService, never()).refundFollowOn(any(), any());
	}

	@Test
	public void fullCancelWithDeliveryCostRefundsEverythingCaptured() throws Exception
	{
		livePaymentIntent("succeeded", 10000L, 1500L);
		order.setStatus(OrderStatus.CANCELLED);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(495L);
		when(paymentService.refundFollowOn(eq(transaction), any())).thenReturn(entry(TransactionStatus.ACCEPTED));

		strategy.recalculateOrderAndModifyPayments(order);

		verify(paymentService).refundFollowOn(transaction, new BigDecimal("85.00"));
		verify(paymentService, never()).cancel(any());
	}

	@Test
	public void piReadFailureHappensBeforeRecalculation() throws Exception
	{
		when(stripeGateway.retrievePaymentIntent(PI_ID)).thenThrow(new StripeGatewayException("down", null, true, false, null));

		try
		{
			strategy.recalculateOrderAndModifyPayments(order);
			fail("expected StripeGatewayException");
		}
		catch (final StripeGatewayException expected)
		{
			verifyNoInteractions(calculationService, paymentService, taskService);
		}
	}

	@Test
	public void rejectedRefundSchedulesAdjustmentRetry() throws Exception
	{
		livePaymentIntent("succeeded", 10000L, 0L);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(6000L);
		when(paymentService.refundFollowOn(eq(transaction), any())).thenReturn(entry(TransactionStatus.REJECTED));

		strategy.recalculateOrderAndModifyPayments(order);

		assertRetryScheduled();
	}

	@Test
	public void rejectedVoidSchedulesAdjustmentRetry() throws Exception
	{
		livePaymentIntent("requires_capture", 0L, null);
		order.setStatus(OrderStatus.CANCELLED);
		when(paymentService.cancel(authorization)).thenReturn(entry(TransactionStatus.ERROR));

		strategy.recalculateOrderAndModifyPayments(order);

		assertRetryScheduled();
	}

	@Test
	public void gatewayFailureAfterRecalculationSchedulesRetry() throws Exception
	{
		livePaymentIntent("succeeded", 10000L, 0L);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(6000L);
		when(paymentService.refundFollowOn(eq(transaction), any())).thenThrow(new StripeGatewayException("down", null, true, false, null));

		strategy.recalculateOrderAndModifyPayments(order);

		verify(calculationService).calculateTotals(order, true);
		assertRetryScheduled();
	}

	@Test
	public void acceptedAdjustmentSchedulesNoRetry() throws Exception
	{
		livePaymentIntent("succeeded", 10000L, 0L);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(6000L);
		when(paymentService.refundFollowOn(eq(transaction), any())).thenReturn(entry(TransactionStatus.ACCEPTED));

		strategy.recalculateOrderAndModifyPayments(order);

		verifyNoInteractions(taskService);
	}

	@Test
	public void nonStripeOrderIsDelegated() throws Exception
	{
		order.setPaymentInfo(new CreditCardPaymentInfoModel());

		strategy.recalculateOrderAndModifyPayments(order);

		verify(calculationService).calculateTotals(order, true);
		verifyNoInteractions(stripeGateway, paymentService);
	}

	@Test
	public void stripeOrderWithoutPaymentTransactionIsOnlyRecalculated() throws Exception
	{
		order.setPaymentTransactions(new ArrayList<>());

		strategy.recalculateOrderAndModifyPayments(order);

		verify(calculationService).calculateTotals(order, true);
		verifyNoInteractions(stripeGateway, paymentService);
	}

	@Test
	public void transactionOfAnotherPaymentIntentIsNotChanged() throws Exception
	{
		authorization.setRequestId("pi_other");

		strategy.recalculateOrderAndModifyPayments(order);

		verifyNoInteractions(stripeGateway, paymentService);
	}

	@Test
	public void cancelRecordFormRecalculatesAndAdjustsStripeOrders() throws Exception
	{
		livePaymentIntent("succeeded", 10000L, 0L);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(6000L);
		when(paymentService.refundFollowOn(eq(transaction), any())).thenReturn(entry(TransactionStatus.ACCEPTED));

		strategy.recalculateOrderAndModifyPayments(order, new OrderCancelRecordEntryModel());

		verify(calculationService).calculateTotals(order, true);
		verify(paymentService).refundFollowOn(transaction, new BigDecimal("40.00"));
	}

	@Test
	public void cancelRecordFormOfNonStripeOrderIsDelegated() throws Exception
	{
		order.setPaymentInfo(new CreditCardPaymentInfoModel());

		strategy.recalculateOrderAndModifyPayments(order, new OrderCancelRecordEntryModel());

		verifyNoInteractions(calculationService, stripeGateway, paymentService);
	}

	private void assertRetryScheduled()
	{
		verify(taskService).scheduleTask(retryTask);
		verify(retryTask).setRunnerBean("stripePaymentAdjustmentTaskRunner");
		verify(retryTask).setContextItem(order);
		verify(retryTask).setExecutionDate(new Date(NOW.getTime() + 30_000L));
	}

	private void livePaymentIntent(final String status, final Long amountReceived, final Long amountRefunded)
	{
		final PaymentIntent paymentIntent = new PaymentIntent();
		paymentIntent.setId(PI_ID);
		paymentIntent.setStatus(status);
		paymentIntent.setAmount(10000L);
		paymentIntent.setAmountReceived(amountReceived);
		if (amountRefunded != null)
		{
			final Charge charge = new Charge();
			charge.setId("ch_cancel");
			charge.setAmountRefunded(amountRefunded);
			paymentIntent.setLatestChargeObject(charge);
		}
		when(stripeGateway.retrievePaymentIntent(PI_ID)).thenReturn(paymentIntent);
	}

	private static PaymentTransactionEntryModel entry(final TransactionStatus status)
	{
		final PaymentTransactionEntryModel entry = new PaymentTransactionEntryModel();
		entry.setTransactionStatus(status.name());
		return entry;
	}

	@Test
	public void adjustmentRunsInTheSiteOfTheOrder() throws Exception
	{
		livePaymentIntent("requires_capture", 0L, null);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(0L);
		when(paymentService.cancel(authorization)).thenReturn(entry(TransactionStatus.ACCEPTED));

		strategy.recalculateOrderAndModifyPayments(order);

		final InOrder inOrder = inOrder(siteContext, stripeGateway);
		inOrder.verify(siteContext).runInSite(same(site), any());
		inOrder.verify(stripeGateway).retrievePaymentIntent(PI_ID);
	}
}
