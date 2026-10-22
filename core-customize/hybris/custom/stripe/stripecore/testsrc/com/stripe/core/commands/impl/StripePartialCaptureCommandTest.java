package com.stripe.core.commands.impl;

import static com.stripe.core.commands.impl.StripeCommandTestSupport.EUR;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.PI_ID;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.intent;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.intentWithLineItems;
import static com.stripe.core.commands.impl.StripeCommandTestSupport.wire;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.payment.StripeLineItemsBuilder;
import com.stripe.core.payment.StripeTransactionLookup;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.stripe.model.PaymentIntent;
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
	private static final String KEY = "capture-txn-1-PARTIAL_CAPTURE-1";

	private StripeGateway gateway;
	private StripePartialCaptureCommand command;
	private StripeLineItemsBuilder lineItemsBuilder;
	private OrderModel order;

	@Before
	public void setUp()
	{
		gateway = mock(StripeGateway.class);
		command = wire(new StripePartialCaptureCommand(), gateway);
		lineItemsBuilder = mock(StripeLineItemsBuilder.class);
		final StripeTransactionLookup lookup = mock(StripeTransactionLookup.class);
		order = new OrderModel();
		order.setCode("00004182");
		final PaymentTransactionModel transaction = new PaymentTransactionModel();
		transaction.setOrder(order);
		when(lookup.findByPaymentIntentId(PI_ID)).thenReturn(Optional.of(transaction));
		command.setStripeTransactionLookup(lookup);
		command.setStripeLineItemsBuilder(lineItemsBuilder);
	}

	@Test
	public void partialCaptureSendsMatchingLineItems()
	{
		final Map<String, Object> orderLines = Map.of("line_items",
				List.of(Map.of("product_name", "Thing", "quantity", 1L, "unit_cost", 1000L)), "shipping", Map.of("amount", 0L));
		when(command.getStripeAmountConverter().chargeAmountMinor(order)).thenReturn(1000L);
		when(lineItemsBuilder.build(order, 1000L))
				.thenReturn(Map.of("amount_details", orderLines, "payment_details", Map.of("order_reference", "00004182")));
		final PaymentIntent withLines = intentWithLineItems("requires_capture", 2000L, "eur");
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(withLines);
		when(gateway.capturePaymentIntent(eq(PI_ID), eq(1000L), anyMap(), eq(KEY)))
				.thenReturn(intent("succeeded", 1000L, "eur"));

		final CaptureResult result = command.perform(request(new BigDecimal("10.00")));

		assertEquals(TransactionStatus.ACCEPTED, result.getTransactionStatus());
		verify(gateway).capturePaymentIntent(PI_ID, 1000L, orderLines, KEY);
		verify(gateway, never()).capturePaymentIntent(anyString(), anyLong(), anyString());
	}

	@Test
	public void partialCaptureFallsBackToSingleLine()
	{
		when(command.getStripeAmountConverter().chargeAmountMinor(order)).thenReturn(2000L);
		final PaymentIntent withLines = intentWithLineItems("requires_capture", 2000L, "eur");
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(withLines);
		when(gateway.capturePaymentIntent(eq(PI_ID), eq(1000L), anyMap(), eq(KEY)))
				.thenReturn(intent("succeeded", 1000L, "eur"));

		final CaptureResult result = command.perform(request(new BigDecimal("10.00")));

		assertEquals(TransactionStatus.ACCEPTED, result.getTransactionStatus());
		verify(gateway).capturePaymentIntent(PI_ID, 1000L,
				Map.of("line_items", List.of(Map.of("product_name", "Order 00004182", "quantity", 1L, "unit_cost", 1000L))), KEY);
		verify(lineItemsBuilder, never()).build(any(), anyLong());
	}

	@Test
	public void partialCaptureWithoutLineItemsSendsNoAmountDetails()
	{
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent("requires_capture", 2000L, "eur"));
		when(gateway.capturePaymentIntent(PI_ID, 1000L, KEY)).thenReturn(intent("succeeded", 1000L, "eur"));

		final CaptureResult result = command.perform(request(new BigDecimal("10.00")));

		assertEquals(TransactionStatus.ACCEPTED, result.getTransactionStatus());
		verify(gateway, never()).capturePaymentIntent(anyString(), anyLong(), anyMap(), anyString());
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

	@Test
	public void capturedIntentWithRefundMatchesNetAmount()
	{
		final PaymentIntent captured = intent("succeeded", 1999L, "eur");
		captured.getLatestChargeObject().setAmountRefunded(999L);
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(captured);

		final CaptureResult result = command.perform(request(new BigDecimal("10.00")));

		assertEquals(TransactionStatus.ACCEPTED, result.getTransactionStatus());
		verify(gateway, never()).capturePaymentIntent(anyString(), anyLong(), anyString());
	}

	@Test
	public void capturedIntentWithRefundRejectsGrossAmount()
	{
		final PaymentIntent captured = intent("succeeded", 1999L, "eur");
		captured.getLatestChargeObject().setAmountRefunded(999L);
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(captured);

		final CaptureResult result = command.perform(request(new BigDecimal("19.99")));

		assertEquals(TransactionStatus.REJECTED, result.getTransactionStatus());
	}

	private static PartialCaptureRequest request(final BigDecimal amount)
	{
		return new PartialCaptureRequest("txn-1-PARTIAL_CAPTURE-1", PI_ID, PI_ID, EUR, amount, "0", "Stripe");
	}
}
