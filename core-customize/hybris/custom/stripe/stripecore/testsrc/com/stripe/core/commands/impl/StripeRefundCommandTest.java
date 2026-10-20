package com.stripe.core.commands.impl;

import static com.stripe.core.commands.impl.StripeCommandTestSupport.EUR;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.PI_ID;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.wire;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.gateway.StripeGateway;
import com.stripe.model.Refund;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.payment.commands.request.FollowOnRefundRequest;
import de.hybris.platform.payment.commands.result.RefundResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import java.math.BigDecimal;
import org.junit.Test;

@UnitTest
public class StripeRefundCommandTest
{
	@Test
	public void refundMapsPendingToAccepted()
	{
		final StripeGateway gateway = mock(StripeGateway.class);
		final StripeRefundCommand command = wire(new StripeRefundCommand(), gateway);
		final Refund refund = new Refund();
		refund.setId("re_1");
		refund.setStatus("pending");
		when(gateway.createRefund(PI_ID, 500L, "refund-txn-1-REFUND_FOLLOW_ON-1")).thenReturn(refund);

		final RefundResult result = command.perform(new FollowOnRefundRequest("txn-1-REFUND_FOLLOW_ON-1", PI_ID, PI_ID, EUR,
				new BigDecimal("5.00"), "Stripe"));

		verify(gateway).createRefund(PI_ID, 500L, "refund-txn-1-REFUND_FOLLOW_ON-1");
		assertEquals(TransactionStatus.ACCEPTED, result.getTransactionStatus());
		assertEquals(TransactionStatusDetails.SUCCESFULL, result.getTransactionStatusDetails());
		assertEquals("re_1", result.getRequestId());
		assertEquals(0, new BigDecimal("5.00").compareTo(result.getTotalAmount()));
	}
}
