package com.stripe.core.commands.impl;

import static com.stripe.core.commands.impl.StripeCommandTestSupport.EUR;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.PI_ID;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.intent;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.wire;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.gateway.StripeGateway;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.payment.commands.request.PartialCaptureRequest;
import de.hybris.platform.payment.commands.result.CaptureResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import java.math.BigDecimal;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class StripePartialCaptureCommandTest
{
	private StripeGateway gateway;
	private StripePartialCaptureCommand command;

	@Before
	public void setUp()
	{
		gateway = mock(StripeGateway.class);
		command = wire(new StripePartialCaptureCommand(), gateway);
	}

	@Test
	public void partialCaptureCapturesRequestedAmount()
	{
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent("requires_capture", 1999L, "eur"));
		when(gateway.capturePaymentIntent(PI_ID, 1000L, "capture-txn-1-PARTIAL_CAPTURE-1"))
				.thenReturn(intent("succeeded", 1000L, "eur"));

		final CaptureResult result = command.perform(request(new BigDecimal("10.00")));

		verify(gateway).capturePaymentIntent(PI_ID, 1000L, "capture-txn-1-PARTIAL_CAPTURE-1");
		assertEquals(TransactionStatus.ACCEPTED, result.getTransactionStatus());
		assertEquals(PI_ID, result.getRequestId());
		assertEquals(0, new BigDecimal("10.00").compareTo(result.getTotalAmount()));
	}

	@Test
	public void partialCaptureOfCapturedIntentWithDifferentAmountIsRejected()
	{
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent("succeeded", 1999L, "eur"));

		final CaptureResult result = command.perform(request(new BigDecimal("10.00")));

		assertEquals(TransactionStatus.REJECTED, result.getTransactionStatus());
		verify(gateway, never()).capturePaymentIntent(anyString(), anyLong(), anyString());
	}

	private static PartialCaptureRequest request(final BigDecimal amount)
	{
		return new PartialCaptureRequest("txn-1-PARTIAL_CAPTURE-1", PI_ID, PI_ID, EUR, amount, "0", "Stripe");
	}
}
