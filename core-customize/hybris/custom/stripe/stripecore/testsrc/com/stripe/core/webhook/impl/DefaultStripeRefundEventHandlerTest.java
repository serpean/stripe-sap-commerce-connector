package com.stripe.core.webhook.impl;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stripe.core.payment.StripeTransactionLookup;
import com.stripe.core.webhook.StripeWebhookOutcome;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.RefundCollection;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.servicelayer.model.ModelService;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class DefaultStripeRefundEventHandlerTest
{
	private StripeTransactionLookup lookup;
	private ModelService modelService;
	private DefaultStripeRefundEventHandler handler;

	@Before
	public void setUp()
	{
		lookup = mock(StripeTransactionLookup.class);
		modelService = mock(ModelService.class);
		handler = new DefaultStripeRefundEventHandler();
		handler.setStripeTransactionLookup(lookup);
		handler.setModelService(modelService);
	}

	@Test
	public void succeededRefundIsAccepted()
	{
		final PaymentTransactionEntryModel entry = givenEntry("re_ok", TransactionStatus.ERROR);

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(refund("re_ok", "succeeded")));

		assertEquals(TransactionStatus.ACCEPTED.name(), entry.getTransactionStatus());
		assertEquals(TransactionStatusDetails.SUCCESFULL.name(), entry.getTransactionStatusDetails());
		verify(modelService).save(entry);
	}

	@Test
	public void pendingRefundKeepsEntry()
	{
		final PaymentTransactionEntryModel entry = givenEntry("re_pending", TransactionStatus.ACCEPTED);

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(refund("re_pending", "pending")));

		assertEquals(TransactionStatus.ACCEPTED.name(), entry.getTransactionStatus());
		verify(modelService, never()).save(any());
	}

	@Test
	public void unknownRefundIsIgnored()
	{
		when(lookup.findRefundEntries("re_other")).thenReturn(List.of());

		assertEquals(StripeWebhookOutcome.IGNORED, handler.handle(refund("re_other", "succeeded")));

		verifyNoInteractions(modelService);
	}

	@Test
	public void chargeRefundedUpdatesEachRefund()
	{
		final PaymentTransactionEntryModel first = givenEntry("re_a", TransactionStatus.ACCEPTED);
		final PaymentTransactionEntryModel second = givenEntry("re_b", TransactionStatus.ACCEPTED);
		final RefundCollection refunds = new RefundCollection();
		refunds.setData(List.of(refund("re_a", "succeeded"), refund("re_b", "canceled")));
		final Charge charge = new Charge();
		charge.setRefunds(refunds);

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(charge));

		assertEquals(TransactionStatus.ACCEPTED.name(), first.getTransactionStatus());
		assertEquals(TransactionStatus.REJECTED.name(), second.getTransactionStatus());
	}

	@Test
	public void chargeWithoutRefundListIsIgnored()
	{
		assertEquals(StripeWebhookOutcome.IGNORED, handler.handle(new Charge()));

		verifyNoInteractions(lookup);
	}

	@Test
	public void otherObjectIsIgnored()
	{
		assertEquals(StripeWebhookOutcome.IGNORED, handler.handle(new PaymentIntent()));

		verifyNoInteractions(lookup);
	}

	private PaymentTransactionEntryModel givenEntry(final String refundId, final TransactionStatus status)
	{
		final PaymentTransactionEntryModel entry = new PaymentTransactionEntryModel();
		entry.setRequestId(refundId);
		entry.setTransactionStatus(status.name());
		when(lookup.findRefundEntries(refundId)).thenReturn(List.of(entry));
		return entry;
	}

	private Refund refund(final String id, final String status)
	{
		final Refund refund = new Refund();
		refund.setId(id);
		refund.setStatus(status);
		return refund;
	}
}
