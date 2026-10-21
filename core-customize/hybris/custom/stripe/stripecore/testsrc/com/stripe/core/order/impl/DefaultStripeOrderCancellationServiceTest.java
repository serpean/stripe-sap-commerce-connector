package com.stripe.core.order.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.core.site.StripeSiteContext;
import com.stripe.core.testsupport.StripeLogCapture;
import com.stripe.core.testsupport.StripeSiteContextMocks;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.basecommerce.enums.CancelReason;
import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.order.OrderEntryModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.user.EmployeeModel;
import de.hybris.platform.ordercancel.OrderCancelException;
import de.hybris.platform.ordercancel.OrderCancelRequest;
import de.hybris.platform.ordercancel.OrderCancelService;
import de.hybris.platform.servicelayer.user.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@UnitTest
public class DefaultStripeOrderCancellationServiceTest
{
	private static final String PI_ID = "pi_cancel";

	private OrderCancelService orderCancelService;
	private StripePaymentIntentService paymentIntentService;
	private StripeOrderAuthorizationService authorizationService;
	private DefaultStripeOrderCancellationService service;
	private EmployeeModel admin;
	private OrderModel order;
	private StripePaymentIntentRecordModel record;
	private StripeSiteContext siteContext;
	private BaseSiteModel site;

	@Before
	public void setUp() throws Exception
	{
		orderCancelService = mock(OrderCancelService.class);
		paymentIntentService = mock(StripePaymentIntentService.class);
		authorizationService = mock(StripeOrderAuthorizationService.class);
		final StripePaymentIntentRecordDao recordDao = mock(StripePaymentIntentRecordDao.class);
		final UserService userService = mock(UserService.class);
		admin = new EmployeeModel();
		when(userService.getAdminUser()).thenReturn(admin);

		service = new DefaultStripeOrderCancellationService();
		service.setOrderCancelService(orderCancelService);
		service.setUserService(userService);
		service.setStripePaymentIntentService(paymentIntentService);
		service.setStripePaymentIntentRecordDao(recordDao);
		service.setStripeOrderAuthorizationService(authorizationService);
		siteContext = StripeSiteContextMocks.direct();
		service.setStripeSiteContext(siteContext);
		site = new BaseSiteModel();
		site.setUid("site-b");

		final StripePaymentInfoModel paymentInfo = new StripePaymentInfoModel();
		paymentInfo.setStripePaymentIntentId(PI_ID);
		order = new OrderModel();
		order.setCode("order-cancel");
		order.setPaymentInfo(paymentInfo);
		order.setStatus(OrderStatus.PAYMENT_PENDING);
		order.setSite(site);
		final OrderEntryModel entry = new OrderEntryModel();
		entry.setQuantity(2L);
		order.setEntries(List.of(entry));
		record = new StripePaymentIntentRecordModel();
		record.setPaymentIntentId(PI_ID);
		record.setState(StripePaymentIntentState.PROCESSING);
		when(recordDao.findByPaymentIntentId(PI_ID)).thenReturn(Optional.of(record));

		doAnswer(invocation -> {
			order.setStatus(OrderStatus.CANCELLED);
			return null;
		}).when(orderCancelService).requestOrderCancel(any(), any());
		doAnswer(invocation -> {
			record.setState(StripePaymentIntentState.CANCELED);
			return null;
		}).when(paymentIntentService).releaseFunds(anyString(), anyString());
	}

	@Test
	public void cancelForFailedPaymentCancelsFullOrderThenReleasesFunds() throws Exception
	{
		service.cancelForFailedPayment(order);

		final ArgumentCaptor<OrderCancelRequest> request = ArgumentCaptor.forClass(OrderCancelRequest.class);
		final InOrder inOrder = inOrder(orderCancelService, paymentIntentService);
		inOrder.verify(orderCancelService).requestOrderCancel(request.capture(), any());
		inOrder.verify(paymentIntentService).releaseFunds(PI_ID, "order order-cancel: payment failed");
		verify(orderCancelService).requestOrderCancel(any(), same(admin));
		assertEquals(order, request.getValue().getOrder());
		assertEquals(false, request.getValue().isPartialCancel());
		assertEquals(CancelReason.OTHER, request.getValue().getCancelReason());
		assertEquals("stripe-payment-failed", request.getValue().getNotes());
	}

	@Test
	public void cancelForFailedPaymentIsIdempotent() throws Exception
	{
		service.cancelForFailedPayment(order);
		service.cancelForFailedPayment(order);

		verify(orderCancelService, times(1)).requestOrderCancel(any(), any());
		verify(paymentIntentService, times(1)).releaseFunds(anyString(), anyString());
	}

	@Test
	public void refundedPaymentIsNotReleasedAgain() throws Exception
	{
		record.setState(StripePaymentIntentState.REFUNDED);

		service.cancelForFailedPayment(order);

		verify(orderCancelService).requestOrderCancel(any(), any());
		verifyNoInteractions(paymentIntentService);
	}

	@Test
	public void stripeErrorDoesNotUndoTheCancellation() throws Exception
	{
		doThrow(new StripeGatewayException("processing", "payment_intent_unexpected_state", false, false, null))
				.when(paymentIntentService).releaseFunds(anyString(), anyString());

		service.cancelForFailedPayment(order);

		assertEquals(OrderStatus.CANCELLED, order.getStatus());
	}

	@Test
	public void refusedCancellationLogsTheOrderAndThePaymentIntentThenFails() throws Exception
	{
		doThrow(new OrderCancelException("order-cancel", "denied")).when(orderCancelService).requestOrderCancel(any(), any());

		try (StripeLogCapture log = StripeLogCapture.of(DefaultStripeOrderCancellationService.class))
		{
			assertThrows(IllegalStateException.class, () -> service.cancelForFailedPayment(order));

			assertTrue("ERROR log with order and payment intent", log.hasError("order-cancel", PI_ID));
		}
		verifyNoInteractions(paymentIntentService);
	}

	@Test
	public void cancellationRunsInTheSiteOfTheOrder() throws Exception
	{
		service.cancelForFailedPayment(order);

		verify(siteContext).runInSite(same(site), any());
	}

	@Test
	public void amountMismatchCancelsAPaidOrderAndReleasesTheFunds() throws Exception
	{
		when(authorizationService.hasAcceptedAuthorization(order, PI_ID)).thenReturn(true);
		record.setState(StripePaymentIntentState.AUTHORIZED);

		service.cancelForAmountMismatch(order);

		final InOrder inOrder = inOrder(orderCancelService, paymentIntentService);
		inOrder.verify(orderCancelService).requestOrderCancel(any(), same(admin));
		inOrder.verify(paymentIntentService).releaseFunds(PI_ID, "order order-cancel: amount differs from the payment");
		verify(siteContext).runInSite(same(site), any());
	}

	@Test
	public void amountMismatchOfACancelledOrderOnlyReleasesTheFunds() throws Exception
	{
		order.setStatus(OrderStatus.CANCELLED);

		service.cancelForAmountMismatch(order);

		verify(orderCancelService, never()).requestOrderCancel(any(), any());
		verify(paymentIntentService).releaseFunds(eq(PI_ID), anyString());
	}

	@Test
	public void paidOrderIsNotCancelled() throws Exception
	{
		when(authorizationService.hasAcceptedAuthorization(order, PI_ID)).thenReturn(true);

		service.cancelForFailedPayment(order);

		verify(orderCancelService, never()).requestOrderCancel(any(), any());
		verifyNoInteractions(paymentIntentService);
	}
}
