package com.stripe.core.commands.impl;

import static com.stripe.core.commands.impl.StripeCommandTestSupport.EUR;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.PI_ID;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.intent;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.intentWithLineItems;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.wire;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.payment.commands.request.CaptureRequest;
import de.hybris.platform.payment.commands.result.CaptureResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import java.math.BigDecimal;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class StripeCaptureCommandTest
{
	private StripeGateway gateway;
	private StripeCaptureCommand command;

	@Before
	public void setUp()
	{
		gateway = mock(StripeGateway.class);
		command = wire(new StripeCaptureCommand(), gateway);
	}

	@Test
	public void captureOfCapturedIntentDoesNotCallStripe()
	{
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent("succeeded", 1999L, "eur"));

		final CaptureResult result = command.perform(request());

		assertEquals(TransactionStatus.ACCEPTED, result.getTransactionStatus());
		assertEquals(TransactionStatusDetails.SUCCESFULL, result.getTransactionStatusDetails());
		assertEquals(PI_ID, result.getRequestId());
		verify(gateway, never()).capturePaymentIntent(anyString(), anyLong(), anyString());
	}

	@Test
	public void fullCaptureSendsNoAmountDetails()
	{
		final PaymentIntent withLines = intentWithLineItems("requires_capture", 1999L, "eur");
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(withLines);
		when(gateway.capturePaymentIntent(PI_ID, 1999L, "capture-txn-1-CAPTURE-1"))
				.thenReturn(intent("succeeded", 1999L, "eur"));

		final CaptureResult result = command.perform(request());

		assertEquals(TransactionStatus.ACCEPTED, result.getTransactionStatus());
		verify(gateway).capturePaymentIntent(PI_ID, 1999L, "capture-txn-1-CAPTURE-1");
		verify(gateway, never()).capturePaymentIntent(anyString(), anyLong(), anyMap(), anyString());
	}

	@Test
	public void captureUsesStableIdempotencyKey()
	{
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent("requires_capture", 1999L, "eur"));
		when(gateway.capturePaymentIntent(PI_ID, 1999L, "capture-txn-1-CAPTURE-1"))
				.thenReturn(intent("succeeded", 1999L, "eur"));

		final CaptureResult result = command.perform(request());

		verify(gateway).capturePaymentIntent(PI_ID, 1999L, "capture-txn-1-CAPTURE-1");
		assertEquals(TransactionStatus.ACCEPTED, result.getTransactionStatus());
		assertEquals(PI_ID, result.getRequestId());
		assertEquals(0, new BigDecimal("19.99").compareTo(result.getTotalAmount()));
	}

	@Test
	public void stripeErrorMapsToErrorStatus()
	{
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent("requires_capture", 1999L, "eur"));
		when(gateway.capturePaymentIntent(PI_ID, 1999L, "capture-txn-1-CAPTURE-1"))
				.thenThrow(new StripeGatewayException("timeout", null, true, false, null));

		final CaptureResult retryable = command.perform(request());

		assertEquals(TransactionStatus.ERROR, retryable.getTransactionStatus());
		assertEquals(TransactionStatusDetails.COMMUNICATION_PROBLEM, retryable.getTransactionStatusDetails());

		doThrow(new StripeGatewayException("declined", "card_declined", false, false, null)).when(gateway)
				.capturePaymentIntent(PI_ID, 1999L, "capture-txn-1-CAPTURE-1");

		final CaptureResult other = command.perform(request());

		assertEquals(TransactionStatus.ERROR, other.getTransactionStatus());
		assertEquals(TransactionStatusDetails.GENERAL_SYSTEM_ERROR, other.getTransactionStatusDetails());
	}

	@Test
	public void expiredAuthorizationIsRejectedNotRetried()
	{
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent("requires_capture", 1999L, "eur"));
		for (final String code : new String[]
		{ "charge_expired_for_capture", "capture_charge_authorization_expired" })
		{
			doThrow(new StripeGatewayException("expired", code, false, false, null)).when(gateway)
					.capturePaymentIntent(PI_ID, 1999L, "capture-txn-1-CAPTURE-1");

			final CaptureResult result = command.perform(request());

			assertEquals(TransactionStatus.REJECTED, result.getTransactionStatus());
			assertEquals("stripe:" + code, result.getRequestToken());
		}
	}

	private static CaptureRequest request()
	{
		return new CaptureRequest("txn-1-CAPTURE-1", PI_ID, PI_ID, EUR, new BigDecimal("19.99"), "Stripe");
	}
}
