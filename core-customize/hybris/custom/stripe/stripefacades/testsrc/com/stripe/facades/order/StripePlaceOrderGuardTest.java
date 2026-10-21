package com.stripe.facades.order;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.order.StripeOrderCancellationService;
import com.stripe.core.order.StripeOrderPlacementLockService;
import com.stripe.core.payment.StripePaymentIntentChecks;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import com.stripe.facades.checkout.StripeCheckoutException;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.commercefacades.order.data.OrderData;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.OrderModel;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

@UnitTest
public class StripePlaceOrderGuardTest
{
	private static final String PI_ID = "pi_guard";

	private StripeOrderPlacementLockService lockService;
	private StripePaymentIntentRecordService recordService;
	private StripeGateway gateway;
	private StripeAmountConverter converter;
	private StripeOrderCancellationService cancellationService;
	private StripePlaceOrderGuard guard;
	private OrderModel order;
	private OrderData orderData;
	private PaymentIntent paymentIntent;

	@Before
	public void setUp()
	{
		lockService = mock(StripeOrderPlacementLockService.class);
		recordService = mock(StripePaymentIntentRecordService.class);
		gateway = mock(StripeGateway.class);
		converter = mock(StripeAmountConverter.class);
		cancellationService = mock(StripeOrderCancellationService.class);
		final StripePaymentIntentChecks checks = new StripePaymentIntentChecks();
		checks.setStripeAmountConverter(converter);

		guard = new StripePlaceOrderGuard();
		guard.setStripeOrderPlacementLockService(lockService);
		guard.setStripePaymentIntentRecordService(recordService);
		guard.setStripeGateway(gateway);
		guard.setStripePaymentIntentChecks(checks);
		guard.setStripeAmountConverter(converter);
		guard.setStripeOrderCancellationService(cancellationService);

		final CurrencyModel currency = new CurrencyModel();
		currency.setIsocode("EUR");
		order = new OrderModel();
		order.setCode("order-guard");
		order.setCurrency(currency);
		orderData = new OrderData();
		when(recordService.findLiveOrder(PI_ID)).thenReturn(Optional.of(order));
		when(converter.chargeAmountMinor(order)).thenReturn(1999L);
		paymentIntent = new PaymentIntent();
		paymentIntent.setId(PI_ID);
		paymentIntent.setAmount(1999L);
		paymentIntent.setCurrency("eur");
		paymentIntent.setStatus("requires_capture");
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(paymentIntent);
	}

	@Test
	public void orderWithTheAmountOfThePaymentIsKept() throws Exception
	{
		assertSame(orderData, guard.placeOrder(PI_ID, () -> orderData));

		verify(recordService).linkOrder(PI_ID, order);
		verify(cancellationService, never()).cancelForAmountMismatch(any());
		verify(lockService, never()).release(anyString());
	}

	@Test
	public void cartChangedInAnotherTabWhileTheOrderIsPlacedCancelsTheOrderAndReleasesTheMoney()
	{
		when(converter.chargeAmountMinor(order)).thenReturn(2999L);

		final StripeCheckoutException error = assertThrows(StripeCheckoutException.class,
				() -> guard.placeOrder(PI_ID, () -> orderData));

		assertEquals(StripeCheckoutException.Reason.AMOUNT_MISMATCH, error.getReason());
		final InOrder inOrder = inOrder(recordService, cancellationService);
		inOrder.verify(recordService).linkOrder(PI_ID, order);
		inOrder.verify(cancellationService).cancelForAmountMismatch(order);
		verify(lockService, never()).release(anyString());
	}

	@Test
	public void orderInAnotherCurrencyIsCancelled()
	{
		paymentIntent.setCurrency("usd");

		assertThrows(StripeCheckoutException.class, () -> guard.placeOrder(PI_ID, () -> orderData));

		verify(cancellationService).cancelForAmountMismatch(order);
	}

	@Test
	public void unreadablePaymentIntentKeepsTheOrder() throws Exception
	{
		when(gateway.retrievePaymentIntent(PI_ID))
				.thenThrow(new StripeGatewayException("down", "api_connection_error", true, false, null));

		assertSame(orderData, guard.placeOrder(PI_ID, () -> orderData));

		verify(cancellationService, never()).cancelForAmountMismatch(any());
	}
}
