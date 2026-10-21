package com.stripe.facades.order.converters.populators;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.facades.data.StripePaymentInfoData;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.commercefacades.order.data.CartData;
import de.hybris.platform.commercefacades.order.data.OrderData;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.order.payment.CreditCardPaymentInfoModel;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class StripeOrderPaymentPopulatorTest
{
	private static final String PI_ID = "pi_1";

	private StripePaymentIntentRecordDao recordDao;
	private StripeOrderPaymentPopulator populator;
	private StripePaymentInfoModel paymentInfo;
	private StripePaymentIntentRecordModel record;
	private OrderModel order;

	@Before
	public void setUp()
	{
		recordDao = mock(StripePaymentIntentRecordDao.class);
		populator = new StripeOrderPaymentPopulator();
		populator.setStripePaymentIntentRecordDao(recordDao);

		paymentInfo = new StripePaymentInfoModel();
		paymentInfo.setStripePaymentIntentId(PI_ID);
		record = new StripePaymentIntentRecordModel();
		record.setPaymentIntentId(PI_ID);
		when(recordDao.findByPaymentIntentId(PI_ID)).thenReturn(Optional.of(record));

		order = new OrderModel();
		order.setPaymentInfo(paymentInfo);
		order.setPaymentTransactions(List.of());
	}

	@Test
	public void populatorCopiesCardDisplayData()
	{
		paymentInfo.setPaymentMethodType("card");
		paymentInfo.setCardBrand("visa");
		paymentInfo.setCardLast4("4242");
		paymentInfo.setCardExpMonth(12);
		paymentInfo.setCardExpYear(2030);
		order.setPaymentTransactions(List.of(transaction(PaymentTransactionType.AUTHORIZATION, TransactionStatus.ACCEPTED)));
		record.setState(StripePaymentIntentState.AUTHORIZED);

		final OrderData data = new OrderData();
		populator.populate(order, data);

		final StripePaymentInfoData stripe = data.getStripePaymentInfo();
		assertEquals("card", stripe.getPaymentMethodType());
		assertEquals("visa", stripe.getCardBrand());
		assertEquals("4242", stripe.getCardLast4());
		assertEquals(Integer.valueOf(12), stripe.getCardExpMonth());
		assertEquals(Integer.valueOf(2030), stripe.getCardExpYear());
		assertEquals("ACCEPTED", stripe.getPaymentStatus());
	}

	@Test
	public void populatorMarksPendingPayment()
	{
		paymentInfo.setPaymentMethodType("sepa_debit");
		record.setState(StripePaymentIntentState.PROCESSING);

		final OrderData data = new OrderData();
		populator.populate(order, data);

		assertEquals("sepa_debit", data.getStripePaymentInfo().getPaymentMethodType());
		assertEquals("PENDING", data.getStripePaymentInfo().getPaymentStatus());
	}

	@Test
	public void populatorMarksFailedPayment()
	{
		record.setState(StripePaymentIntentState.FAILED);
		final OrderData failed = new OrderData();
		populator.populate(order, failed);

		record.setState(StripePaymentIntentState.CANCELED);
		final OrderData canceled = new OrderData();
		populator.populate(order, canceled);

		assertEquals("FAILED", failed.getStripePaymentInfo().getPaymentStatus());
		assertEquals("FAILED", canceled.getStripePaymentInfo().getPaymentStatus());
	}

	@Test
	public void rejectedAuthorizationIsNotAccepted()
	{
		order.setPaymentTransactions(List.of(transaction(PaymentTransactionType.AUTHORIZATION, TransactionStatus.REJECTED)));
		record.setState(StripePaymentIntentState.FAILED);

		final OrderData data = new OrderData();
		populator.populate(order, data);

		assertEquals("FAILED", data.getStripePaymentInfo().getPaymentStatus());
	}

	@Test
	public void cartWithoutRecordStateHasNoStatus()
	{
		final CartModel cart = new CartModel();
		cart.setPaymentInfo(paymentInfo);
		cart.setPaymentTransactions(List.of());
		record.setState(StripePaymentIntentState.CREATED);

		final CartData data = new CartData();
		populator.populate(cart, data);

		assertNull(data.getStripePaymentInfo().getPaymentStatus());
	}

	@Test
	public void otherPaymentInfoIsIgnored()
	{
		order.setPaymentInfo(new CreditCardPaymentInfoModel());

		final OrderData data = new OrderData();
		populator.populate(order, data);

		assertNull(data.getStripePaymentInfo());
	}

	@Test
	public void staleAuthorizationOfAReleasedPaymentIntentIsNotAccepted()
	{
		order.setPaymentTransactions(
				List.of(transaction(PaymentTransactionType.AUTHORIZATION, TransactionStatus.ACCEPTED, "pi_released")));
		record.setState(StripePaymentIntentState.PROCESSING);

		final OrderData data = new OrderData();
		populator.populate(order, data);

		assertEquals("PENDING", data.getStripePaymentInfo().getPaymentStatus());
	}

	private static PaymentTransactionModel transaction(final PaymentTransactionType type, final TransactionStatus status)
	{
		return transaction(type, status, PI_ID);
	}

	private static PaymentTransactionModel transaction(final PaymentTransactionType type, final TransactionStatus status,
			final String requestId)
	{
		final PaymentTransactionEntryModel entry = new PaymentTransactionEntryModel();
		entry.setType(type);
		entry.setTransactionStatus(status.name());
		entry.setRequestId(requestId);
		final PaymentTransactionModel transaction = new PaymentTransactionModel();
		transaction.setEntries(List.of(entry));
		return transaction;
	}
}
