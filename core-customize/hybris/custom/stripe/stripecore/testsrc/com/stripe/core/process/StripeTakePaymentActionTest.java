package com.stripe.core.process;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.constants.StripeCoreConstants;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.site.StripeSiteContext;
import com.stripe.core.testsupport.StripeSiteContextMocks;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.order.payment.CreditCardPaymentInfoModel;
import de.hybris.platform.orderprocessing.model.OrderProcessModel;
import de.hybris.platform.payment.PaymentService;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction.Transition;
import de.hybris.platform.servicelayer.model.ModelService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class StripeTakePaymentActionTest
{
	private StripeSiteContext siteContext;
	private BaseSiteModel site;
	private static final String PI_ID = "pi_take";
	private static final BigDecimal AUTHORIZED = new BigDecimal("100.00");

	private AbstractSimpleDecisionAction<OrderProcessModel> delegate;
	private PaymentService paymentService;
	private StripeAmountConverter amountConverter;
	private StripeTakePaymentAction action;
	private OrderModel order;
	private OrderProcessModel process;
	private PaymentTransactionModel transaction;

	@Before
	@SuppressWarnings("unchecked")
	public void setUp() throws Exception
	{
		delegate = mock(AbstractSimpleDecisionAction.class);
		paymentService = mock(PaymentService.class);
		amountConverter = mock(StripeAmountConverter.class);
		when(amountConverter.toMinorUnits(any(BigDecimal.class), anyString())).thenAnswer(
				invocation -> ((BigDecimal) invocation.getArgument(0)).movePointRight(2).longValueExact());

		action = new StripeTakePaymentAction(delegate);
		action.setModelService(mock(ModelService.class));
		action.setPaymentService(paymentService);
		action.setStripeAmountConverter(amountConverter);
		siteContext = StripeSiteContextMocks.direct();
		action.setStripeSiteContext(siteContext);
		site = new BaseSiteModel();
		site.setUid("site-d");

		final CurrencyModel currency = new CurrencyModel();
		currency.setIsocode("EUR");
		final StripePaymentInfoModel paymentInfo = new StripePaymentInfoModel();
		paymentInfo.setStripePaymentIntentId(PI_ID);
		order = new OrderModel();
		order.setCode("order-take");
		order.setSite(site);
		order.setCurrency(currency);
		order.setPaymentInfo(paymentInfo);
		transaction = transaction(PI_ID, AUTHORIZED);
		order.setPaymentTransactions(new ArrayList<>(List.of(transaction)));
		process = new OrderProcessModel();
		process.setOrder(order);
	}

	@Test
	public void takePaymentCapturesCurrentOrderTotal() throws Exception
	{
		when(amountConverter.chargeAmount(order)).thenReturn(new BigDecimal("100.0"));
		when(paymentService.capture(transaction)).thenReturn(entry(TransactionStatus.ACCEPTED));

		assertEquals("OK", action.execute(process));

		verify(paymentService).capture(transaction);
		verify(paymentService, never()).partialCapture(any(), any());
		assertEquals(OrderStatus.PAYMENT_CAPTURED, order.getStatus());
	}

	@Test
	public void takePaymentAfterPartialCancelUsesPartialCapture() throws Exception
	{
		final BigDecimal current = new BigDecimal("60.00");
		when(amountConverter.chargeAmount(order)).thenReturn(current);
		when(paymentService.partialCapture(transaction, current)).thenReturn(entry(TransactionStatus.ACCEPTED));

		assertEquals("OK", action.execute(process));

		verify(paymentService).partialCapture(transaction, current);
		verify(paymentService, never()).capture(any());
		assertEquals(OrderStatus.PAYMENT_CAPTURED, order.getStatus());
	}

	@Test
	public void takePaymentDoesNotCallDelegateForStripeOrders() throws Exception
	{
		when(amountConverter.chargeAmount(order)).thenReturn(AUTHORIZED);
		when(paymentService.capture(transaction)).thenReturn(entry(TransactionStatus.ACCEPTED));

		action.execute(process);

		verifyNoInteractions(delegate);
	}

	@Test
	public void takePaymentDoesNotCaptureTwice() throws Exception
	{
		final PaymentTransactionEntryModel capture = entry(TransactionStatus.ACCEPTED);
		capture.setType(PaymentTransactionType.PARTIAL_CAPTURE);
		transaction.getEntries().add(capture);
		when(amountConverter.chargeAmount(order)).thenReturn(new BigDecimal("60.00"));

		assertEquals("OK", action.execute(process));

		verifyNoInteractions(paymentService);
		assertEquals(OrderStatus.PAYMENT_CAPTURED, order.getStatus());
	}

	@Test
	public void rejectedEarlierCaptureIsRetried() throws Exception
	{
		transaction.getEntries().add(entry(TransactionStatus.REJECTED));
		when(amountConverter.chargeAmount(order)).thenReturn(AUTHORIZED);
		when(paymentService.capture(transaction)).thenReturn(entry(TransactionStatus.ACCEPTED));

		assertEquals("OK", action.execute(process));

		verify(paymentService).capture(transaction);
	}

	@Test
	public void rejectedCaptureSetsPaymentNotCaptured() throws Exception
	{
		when(amountConverter.chargeAmount(order)).thenReturn(AUTHORIZED);
		when(paymentService.capture(transaction)).thenReturn(entry(TransactionStatus.REJECTED));

		assertEquals("NOK", action.execute(process));

		assertEquals(OrderStatus.PAYMENT_NOT_CAPTURED, order.getStatus());
	}

	@Test
	public void totalAboveAuthorizedAmountIsNotCaptured() throws Exception
	{
		when(amountConverter.chargeAmount(order)).thenReturn(new BigDecimal("100.01"));

		assertEquals("NOK", action.execute(process));

		verifyNoInteractions(paymentService);
		assertEquals(OrderStatus.PAYMENT_NOT_CAPTURED, order.getStatus());
	}

	@Test
	public void staleStripeTransactionIsSkipped() throws Exception
	{
		final PaymentTransactionModel stale = transaction("pi_stale", AUTHORIZED);
		order.setPaymentTransactions(new ArrayList<>(List.of(stale, transaction)));
		when(amountConverter.chargeAmount(order)).thenReturn(AUTHORIZED);
		when(paymentService.capture(transaction)).thenReturn(entry(TransactionStatus.ACCEPTED));

		assertEquals("OK", action.execute(process));

		verify(paymentService).capture(transaction);
		verify(paymentService, never()).capture(stale);
	}

	@Test
	public void orderWithoutAuthorizationIsNotCaptured() throws Exception
	{
		order.setPaymentTransactions(new ArrayList<>(List.of(transaction("pi_stale", AUTHORIZED))));
		when(amountConverter.chargeAmount(order)).thenReturn(AUTHORIZED);

		assertEquals("NOK", action.execute(process));

		verifyNoInteractions(paymentService);
		assertEquals(OrderStatus.PAYMENT_NOT_CAPTURED, order.getStatus());
	}

	@Test
	public void nonStripeOrderDelegates() throws Exception
	{
		order.setPaymentInfo(new CreditCardPaymentInfoModel());
		when(delegate.executeAction(process)).thenReturn(Transition.OK);

		assertEquals("OK", action.execute(process));

		verify(delegate).executeAction(process);
		verifyNoInteractions(paymentService);
	}

	private static PaymentTransactionModel transaction(final String paymentIntentId, final BigDecimal amount)
	{
		final PaymentTransactionModel txn = new PaymentTransactionModel();
		txn.setCode("txn-" + paymentIntentId);
		txn.setPaymentProvider(StripeCoreConstants.STRIPE_PROVIDER);
		final PaymentTransactionEntryModel authorization = new PaymentTransactionEntryModel();
		authorization.setType(PaymentTransactionType.AUTHORIZATION);
		authorization.setTransactionStatus(TransactionStatus.ACCEPTED.name());
		authorization.setRequestId(paymentIntentId);
		authorization.setAmount(amount);
		authorization.setPaymentTransaction(txn);
		txn.setEntries(new ArrayList<>(List.of(authorization)));
		return txn;
	}

	private static PaymentTransactionEntryModel entry(final TransactionStatus status)
	{
		final PaymentTransactionEntryModel entry = new PaymentTransactionEntryModel();
		entry.setType(PaymentTransactionType.CAPTURE);
		entry.setTransactionStatus(status.name());
		return entry;
	}

	@Test
	public void captureRunsInTheSiteOfTheOrder() throws Exception
	{
		when(amountConverter.chargeAmount(order)).thenReturn(new BigDecimal("100.0"));
		when(paymentService.capture(transaction)).thenReturn(entry(TransactionStatus.ACCEPTED));

		action.execute(process);

		final org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(siteContext, paymentService);
		inOrder.verify(siteContext).runInSite(same(site), any());
		inOrder.verify(paymentService).capture(transaction);
	}
}
