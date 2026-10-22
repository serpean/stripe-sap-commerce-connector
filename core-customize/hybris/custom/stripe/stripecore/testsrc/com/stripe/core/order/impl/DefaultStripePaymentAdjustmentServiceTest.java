package com.stripe.core.order.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.payment.StripePaymentIntentChecks;
import com.stripe.core.testsupport.StripeLogCapture;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.payment.PaymentService;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import java.math.BigDecimal;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class DefaultStripePaymentAdjustmentServiceTest
{
	private static final String PI_ID = "pi_adjust";

	private PaymentService paymentService;
	private DefaultStripePaymentAdjustmentService service;
	private OrderModel order;
	private PaymentTransactionEntryModel authorization;

	@Before
	public void setUp()
	{
		paymentService = mock(PaymentService.class);
		final StripeAmountConverter converter = mock(StripeAmountConverter.class);
		when(converter.fromMinorUnits(anyLong(), anyString()))
				.thenAnswer(i -> BigDecimal.valueOf((long) i.getArgument(0), 2));
		final StripePaymentIntentChecks checks = new StripePaymentIntentChecks();
		checks.setStripeAmountConverter(converter);
		service = new DefaultStripePaymentAdjustmentService();
		service.setPaymentService(paymentService);
		service.setStripeAmountConverter(converter);
		service.setStripePaymentIntentChecks(checks);

		final CurrencyModel currency = new CurrencyModel();
		currency.setIsocode("EUR");
		final StripePaymentInfoModel info = new StripePaymentInfoModel();
		info.setStripePaymentIntentId(PI_ID);
		order = new OrderModel();
		order.setCode("order-adjust");
		order.setStatus(OrderStatus.CANCELLED);
		order.setCurrency(currency);
		order.setPaymentInfo(info);
		authorization = new PaymentTransactionEntryModel();
		authorization.setType(PaymentTransactionType.AUTHORIZATION);
		authorization.setTransactionStatus(TransactionStatus.ACCEPTED.name());
		authorization.setRequestId(PI_ID);
		final PaymentTransactionModel transaction = new PaymentTransactionModel();
		transaction.setCode("txn-adjust");
		transaction.setEntries(List.of(authorization));
		order.setPaymentTransactions(List.of(transaction));
	}

	@Test
	public void rejectedRefundIsNotRetried()
	{
		when(paymentService.refundFollowOn(any(), any())).thenReturn(entry(TransactionStatus.REJECTED, "stripe:charge_disputed"));

		try (StripeLogCapture log = StripeLogCapture.of(DefaultStripePaymentAdjustmentService.class))
		{
			assertTrue(service.adjust(order, captured()));

			assertTrue(log.hasError("order-adjust", "stripe:charge_disputed"));
		}
	}

	@Test
	public void errorRefundIsRetried()
	{
		when(paymentService.refundFollowOn(any(), any())).thenReturn(entry(TransactionStatus.ERROR, null));

		assertFalse(service.adjust(order, captured()));
	}

	@Test
	public void rejectedVoidIsNotRetried()
	{
		when(paymentService.cancel(authorization)).thenReturn(entry(TransactionStatus.REJECTED, "stripe:capture_charge_authorization_expired"));
		final PaymentIntent authorized = new PaymentIntent();
		authorized.setId(PI_ID);
		authorized.setStatus("requires_capture");

		try (StripeLogCapture log = StripeLogCapture.of(DefaultStripePaymentAdjustmentService.class))
		{
			assertTrue(service.adjust(order, authorized));

			assertTrue(log.hasError("order-adjust", "stripe:capture_charge_authorization_expired"));
		}
		verify(paymentService, never()).refundFollowOn(any(), any());
	}

	@Test
	public void errorVoidIsRetried()
	{
		when(paymentService.cancel(authorization)).thenReturn(entry(TransactionStatus.ERROR, null));
		final PaymentIntent authorized = new PaymentIntent();
		authorized.setId(PI_ID);
		authorized.setStatus("requires_capture");

		assertFalse(service.adjust(order, authorized));
	}

	private PaymentIntent captured()
	{
		final PaymentIntent intent = new PaymentIntent();
		intent.setId(PI_ID);
		intent.setStatus("succeeded");
		intent.setAmount(1000L);
		intent.setAmountReceived(1000L);
		final Charge charge = new Charge();
		charge.setAmountRefunded(0L);
		intent.setLatestChargeObject(charge);
		return intent;
	}

	private static PaymentTransactionEntryModel entry(final TransactionStatus status, final String token)
	{
		final PaymentTransactionEntryModel entry = new PaymentTransactionEntryModel();
		entry.setTransactionStatus(status.name());
		entry.setRequestToken(token);
		return entry;
	}
}
