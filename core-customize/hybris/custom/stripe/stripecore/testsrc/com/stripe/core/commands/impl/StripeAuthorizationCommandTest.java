package com.stripe.core.commands.impl;

import static com.stripe.core.commands.impl.StripeCommandTestSupport.EUR;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.PI_ID;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.intent;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.wire;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.core.gateway.StripeGateway;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.payment.commands.request.SubscriptionAuthorizationRequest;
import de.hybris.platform.payment.commands.result.AuthorizationResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import java.math.BigDecimal;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class StripeAuthorizationCommandTest
{
	private StripeGateway gateway;
	private StripeAuthorizationCommand command;

	@Before
	public void setUp()
	{
		gateway = mock(StripeGateway.class);
		command = wire(new StripeAuthorizationCommand(), gateway);
	}

	@Test
	public void authorizationAcceptsAuthorizedIntentWithMatchingAmount()
	{
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent("requires_capture", 1999L, "eur"));

		final AuthorizationResult result = command.perform(request(new BigDecimal("19.99")));

		assertEquals(TransactionStatus.ACCEPTED, result.getTransactionStatus());
		assertEquals(TransactionStatusDetails.SUCCESFULL, result.getTransactionStatusDetails());
		assertEquals(PI_ID, result.getRequestId());
		assertEquals(PI_ID, result.getRequestToken());
		assertEquals(0, new BigDecimal("19.99").compareTo(result.getTotalAmount()));
		assertEquals(EUR, result.getCurrency());
	}

	@Test
	public void authorizationRejectsAmountMismatch()
	{
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent("requires_capture", 1500L, "eur"));

		final AuthorizationResult result = command.perform(request(new BigDecimal("19.99")));

		assertEquals(TransactionStatus.REJECTED, result.getTransactionStatus());
		assertEquals(TransactionStatusDetails.INVALID_REQUEST, result.getTransactionStatusDetails());
		assertEquals(PI_ID, result.getRequestId());
	}

	@Test
	public void authorizationRejectsProcessingIntent()
	{
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent("processing", 1999L, "eur"));

		final AuthorizationResult result = command.perform(request(new BigDecimal("19.99")));

		assertEquals(TransactionStatus.REJECTED, result.getTransactionStatus());
		assertEquals(TransactionStatusDetails.GENERAL_SYSTEM_ERROR, result.getTransactionStatusDetails());
	}

	private static SubscriptionAuthorizationRequest request(final BigDecimal amount)
	{
		return new SubscriptionAuthorizationRequest("txn-1", PI_ID, EUR, amount, null, "Stripe");
	}
}
