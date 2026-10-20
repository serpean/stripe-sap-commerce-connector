package com.stripe.core.commands.impl;

import static com.stripe.core.commands.impl.StripeCommandTestSupport.PI_ID;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.intent;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.wire;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.gateway.StripeGateway;
import com.stripe.model.Refund;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.payment.commands.request.VoidRequest;
import de.hybris.platform.payment.commands.result.VoidResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class StripeVoidCommandTest
{
	private StripeGateway gateway;
	private StripeVoidCommand command;

	@Before
	public void setUp()
	{
		gateway = mock(StripeGateway.class);
		command = wire(new StripeVoidCommand(), gateway);
	}

	@Test
	public void voidCancelsAuthorizedIntent()
	{
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent("requires_capture", 1999L, "eur"));
		when(gateway.cancelPaymentIntent(PI_ID, "void-" + PI_ID)).thenReturn(intent("canceled", 1999L, "eur"));

		final VoidResult result = command.perform(request());

		verify(gateway).cancelPaymentIntent(PI_ID, "void-" + PI_ID);
		verify(gateway, never()).createRefund(anyString(), anyLong(), anyString());
		assertEquals(TransactionStatus.ACCEPTED, result.getTransactionStatus());
		assertEquals(TransactionStatusDetails.SUCCESFULL, result.getTransactionStatusDetails());
		assertEquals(PI_ID, result.getRequestId());
	}

	@Test
	public void voidRefundsCapturedIntent()
	{
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent("succeeded", 1999L, "eur"));
		final Refund refund = new Refund();
		refund.setId("re_1");
		refund.setStatus("succeeded");
		when(gateway.createRefund(PI_ID, 1999L, "void-refund-" + PI_ID)).thenReturn(refund);

		final VoidResult result = command.perform(request());

		verify(gateway).createRefund(PI_ID, 1999L, "void-refund-" + PI_ID);
		verify(gateway, never()).cancelPaymentIntent(anyString(), anyString());
		assertEquals(TransactionStatus.ACCEPTED, result.getTransactionStatus());
		assertEquals(PI_ID, result.getRequestId());
	}

	@Test
	public void voidKeyDependsOnlyOnPaymentIntent()
	{
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent("requires_capture", 1999L, "eur"));
		when(gateway.cancelPaymentIntent(anyString(), anyString())).thenReturn(intent("canceled", 1999L, "eur"));

		command.perform(request("txn-1-CANCEL-1"));
		command.perform(request("txn-1-CANCEL-2"));

		verify(gateway, times(2)).cancelPaymentIntent(PI_ID, "void-" + PI_ID);
	}

	private static VoidRequest request()
	{
		return request("txn-1-CANCEL-1");
	}

	private static VoidRequest request(final String merchantTransactionCode)
	{
		return new VoidRequest(merchantTransactionCode, PI_ID, PI_ID, "Stripe");
	}
}
