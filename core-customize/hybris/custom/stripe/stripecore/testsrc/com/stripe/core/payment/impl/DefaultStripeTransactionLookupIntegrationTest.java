package com.stripe.core.payment.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.stripe.core.payment.StripeTransactionLookup;
import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.ServicelayerTransactionalTest;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.user.UserService;
import java.util.Date;
import java.util.Optional;
import javax.annotation.Resource;
import org.junit.Test;

@IntegrationTest
public class DefaultStripeTransactionLookupIntegrationTest extends ServicelayerTransactionalTest
{
	@Resource
	private StripeTransactionLookup stripeTransactionLookup;
	@Resource
	private ModelService modelService;
	@Resource
	private UserService userService;
	@Resource
	private CommonI18NService commonI18NService;

	@Test
	public void findsTransactionOfLiveOrder()
	{
		final PaymentTransactionModel transaction = transaction(order("live-1", null), "txn-live-1", "pi_live_1");

		final Optional<PaymentTransactionModel> found = stripeTransactionLookup.findByPaymentIntentId("pi_live_1");

		assertTrue(found.isPresent());
		assertEquals(transaction, found.get());
		assertFalse(stripeTransactionLookup.findByPaymentIntentId("pi_missing").isPresent());
	}

	@Test
	public void ignoresTransactionOfOrderSnapshot()
	{
		final PaymentTransactionModel live = transaction(order("live-2", null), "txn-live-2", "pi_snap");
		transaction(order("live-2-snapshot", "v1"), "txn-snap-2", "pi_snap");

		final Optional<PaymentTransactionModel> found = stripeTransactionLookup.findByPaymentIntentId("pi_snap");

		assertTrue(found.isPresent());
		assertEquals(live, found.get());
	}

	@Test
	public void twoLiveTransactionsGiveEmpty()
	{
		transaction(order("live-3a", null), "txn-live-3a", "pi_twice");
		transaction(order("live-3b", null), "txn-live-3b", "pi_twice");

		assertFalse(stripeTransactionLookup.findByPaymentIntentId("pi_twice").isPresent());
	}

	@Test
	public void countsAcceptedAndRejectedRefundsButNotErrors()
	{
		final PaymentTransactionModel transaction = transaction(order("live-4", null), "txn-live-4", "pi_count");
		entry(transaction, "txn-live-4-r1", PaymentTransactionType.REFUND_FOLLOW_ON, TransactionStatus.ACCEPTED, "re_1");
		entry(transaction, "txn-live-4-r2", PaymentTransactionType.REFUND_FOLLOW_ON, TransactionStatus.REJECTED, "re_2");
		entry(transaction, "txn-live-4-r3", PaymentTransactionType.REFUND_FOLLOW_ON, TransactionStatus.ERROR, "pi_count");
		entry(transaction, "txn-live-4-c1", PaymentTransactionType.CAPTURE, TransactionStatus.ACCEPTED, "pi_count");

		assertEquals(2, stripeTransactionLookup.countSettledRefunds(transaction));
	}

	private OrderModel order(final String code, final String versionId)
	{
		final OrderModel order = modelService.create(OrderModel.class);
		order.setCode(code);
		order.setVersionID(versionId);
		order.setUser(userService.getAdminUser());
		order.setCurrency(commonI18NService.getCurrentCurrency());
		order.setDate(new Date());
		modelService.save(order);
		return order;
	}

	private PaymentTransactionModel transaction(final OrderModel order, final String code, final String paymentIntentId)
	{
		final PaymentTransactionModel transaction = modelService.create(PaymentTransactionModel.class);
		transaction.setCode(code);
		transaction.setOrder(order);
		transaction.setRequestId(paymentIntentId);
		transaction.setPaymentProvider("Stripe");
		modelService.save(transaction);
		entry(transaction, code + "-auth", PaymentTransactionType.AUTHORIZATION, TransactionStatus.ACCEPTED, paymentIntentId);
		return transaction;
	}

	private void entry(final PaymentTransactionModel transaction, final String code, final PaymentTransactionType type,
			final TransactionStatus status, final String requestId)
	{
		final PaymentTransactionEntryModel entry = modelService.create(PaymentTransactionEntryModel.class);
		entry.setCode(code);
		entry.setPaymentTransaction(transaction);
		entry.setType(type);
		entry.setTransactionStatus(status.name());
		entry.setRequestId(requestId);
		modelService.save(entry);
	}
}
