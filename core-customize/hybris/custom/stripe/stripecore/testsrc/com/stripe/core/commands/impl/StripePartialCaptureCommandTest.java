package com.stripe.core.commands.impl;

import static com.stripe.core.commands.impl.StripeCommandTestSupport.EUR;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.PI_ID;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.intent;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.wire;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.gateway.StripeGateway;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.payment.commands.request.PartialCaptureRequest;
import de.hybris.platform.payment.commands.result.CaptureResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import java.math.BigDecimal;
import org.junit.Test;

@UnitTest
public class StripePartialCaptureCommandTest
{
	@Test
	public void partialCaptureCapturesRequestedAmount()
	{
		final StripeGateway gateway = mock(StripeGateway.class);
		final StripePartialCaptureCommand command = wire(new StripePartialCaptureCommand(), gateway);
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent("requires_capture", 1999L, "eur"));
		when(gateway.capturePaymentIntent(PI_ID, 1000L, "capture-txn-1-PARTIAL_CAPTURE-1"))
				.thenReturn(intent("succeeded", 1999L, "eur"));

		final CaptureResult result = command.perform(new PartialCaptureRequest("txn-1-PARTIAL_CAPTURE-1", PI_ID, PI_ID, EUR,
				new BigDecimal("10.00"), "0", "Stripe"));

		verify(gateway).capturePaymentIntent(PI_ID, 1000L, "capture-txn-1-PARTIAL_CAPTURE-1");
		assertEquals(TransactionStatus.ACCEPTED, result.getTransactionStatus());
		assertEquals(PI_ID, result.getRequestId());
		assertEquals(0, new BigDecimal("10.00").compareTo(result.getTotalAmount()));
	}
}
