package com.stripe.core.commands.impl;

import static com.stripe.core.commands.impl.StripeCommandTestSupport.EUR;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.PI_ID;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.wire;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.payment.StripeTransactionLookup;
import com.stripe.model.Refund;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.payment.commands.request.FollowOnRefundRequest;
import de.hybris.platform.payment.commands.result.RefundResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

@UnitTest
public class StripeRefundCommandTest
{
	private StripeGateway gateway;
	private StripeTransactionLookup lookup;
	private StripeRefundCommand command;
	private PaymentTransactionModel transaction;

	@Before
	public void setUp()
	{
		gateway = mock(StripeGateway.class);
		lookup = mock(StripeTransactionLookup.class);
		transaction = new PaymentTransactionModel();
		when(lookup.findByPaymentIntentId(PI_ID)).thenReturn(Optional.of(transaction));
		when(lookup.countSettledRefunds(transaction)).thenReturn(0);
		command = wire(new StripeRefundCommand(), gateway);
		command.setStripeTransactionLookup(lookup);
		when(gateway.createRefund(anyString(), anyLong(), anyString())).thenReturn(refund("pending"));
	}

	@Test
	public void refundMapsPendingToAccepted()
	{
		final RefundResult result = command.perform(request("txn-1-REFUND_FOLLOW_ON-1"));

		verify(gateway).createRefund(PI_ID, 500L, "refund-" + PI_ID + "-0-500");
		assertEquals(TransactionStatus.ACCEPTED, result.getTransactionStatus());
		assertEquals(TransactionStatusDetails.SUCCESFULL, result.getTransactionStatusDetails());
		assertEquals("re_1", result.getRequestId());
		assertEquals(0, new BigDecimal("5.00").compareTo(result.getTotalAmount()));
	}

	@Test
	public void failedRefundMapsToRejected()
	{
		when(gateway.createRefund(anyString(), anyLong(), anyString())).thenReturn(refund("failed"));

		final RefundResult result = command.perform(request("txn-1-REFUND_FOLLOW_ON-1"));

		assertEquals(TransactionStatus.REJECTED, result.getTransactionStatus());
		assertEquals("re_1", result.getRequestId());
	}

	@Test
	public void refundWithPermanentStripeCodeIsRejected()
	{
		for (final String code : new String[]
		{ "charge_disputed", "refund_disputed_payment" })
		{
			when(gateway.createRefund(anyString(), anyLong(), anyString()))
					.thenThrow(new StripeGatewayException("no", code, false, false, null));

			final RefundResult result = command.perform(request("txn-1-REFUND_FOLLOW_ON-1"));

			assertEquals(TransactionStatus.REJECTED, result.getTransactionStatus());
			assertEquals("stripe:" + code, result.getRequestToken());
		}
	}

	@Test
	public void refundWithOtherStripeErrorIsError()
	{
		when(gateway.createRefund(anyString(), anyLong(), anyString()))
				.thenThrow(new StripeGatewayException("no", "api_error", false, false, null));

		final RefundResult result = command.perform(request("txn-1-REFUND_FOLLOW_ON-1"));

		assertEquals(TransactionStatus.ERROR, result.getTransactionStatus());
	}

	@Test
	public void unknownRefundStatusMapsToError()
	{
		when(gateway.createRefund(anyString(), anyLong(), anyString())).thenReturn(refund("something_new"));
		assertEquals(TransactionStatus.ERROR, command.perform(request("txn-1-REFUND_FOLLOW_ON-1")).getTransactionStatus());

		when(gateway.createRefund(anyString(), anyLong(), anyString())).thenReturn(refund(null));
		assertEquals(TransactionStatus.ERROR, command.perform(request("txn-1-REFUND_FOLLOW_ON-2")).getTransactionStatus());
	}

	@Test
	public void refundFailsClosedWithoutLiveTransaction()
	{
		when(lookup.findByPaymentIntentId(PI_ID)).thenReturn(Optional.empty());

		final RefundResult result = command.perform(request("txn-1-REFUND_FOLLOW_ON-1"));

		assertEquals(TransactionStatus.ERROR, result.getTransactionStatus());
		assertEquals(TransactionStatusDetails.GENERAL_SYSTEM_ERROR, result.getTransactionStatusDetails());
		verify(gateway, never()).createRefund(anyString(), anyLong(), anyString());
	}

	@Test
	public void refundKeyIsStableAcrossRetries()
	{
		command.perform(request("txn-1-REFUND_FOLLOW_ON-1"));
		command.perform(request("txn-1-REFUND_FOLLOW_ON-2"));

		final ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
		verify(gateway, times(2)).createRefund(eq(PI_ID), eq(500L), keys.capture());
		assertEquals("refund-" + PI_ID + "-0-500", keys.getAllValues().get(0));
		assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
	}

	@Test
	public void refundKeyChangesAfterAcceptedRefund()
	{
		assertKeyChangesAfterSettledRefund();
	}

	@Test
	public void refundKeyChangesAfterRejectedRefund()
	{
		when(gateway.createRefund(anyString(), anyLong(), anyString())).thenReturn(refund("failed"));
		assertKeyChangesAfterSettledRefund();
	}

	private void assertKeyChangesAfterSettledRefund()
	{
		command.perform(request("txn-1-REFUND_FOLLOW_ON-1"));
		when(lookup.countSettledRefunds(transaction)).thenReturn(1);
		command.perform(request("txn-1-REFUND_FOLLOW_ON-2"));

		final ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
		verify(gateway, times(2)).createRefund(eq(PI_ID), eq(500L), keys.capture());
		assertEquals("refund-" + PI_ID + "-0-500", keys.getAllValues().get(0));
		assertEquals("refund-" + PI_ID + "-1-500", keys.getAllValues().get(1));
	}

	private static FollowOnRefundRequest request(final String merchantTransactionCode)
	{
		return new FollowOnRefundRequest(merchantTransactionCode, PI_ID, PI_ID, EUR, new BigDecimal("5.00"), "Stripe");
	}

	private static Refund refund(final String status)
	{
		final Refund refund = new Refund();
		refund.setId("re_1");
		refund.setStatus(status);
		return refund;
	}
}
