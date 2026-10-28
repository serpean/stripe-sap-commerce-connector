package com.stripe.core.order;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.order.payment.CreditCardPaymentInfoModel;
import de.hybris.platform.ordercancel.DefaultOrderCancelDenialReason;
import de.hybris.platform.ordercancel.OrderCancelDenialReason;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class StripePendingPaymentCancelDenialStrategyTest
{
	private static final String PI_ID = "pi_denial";

	private StripePaymentIntentRecordDao recordDao;
	private StripePendingPaymentCancelDenialStrategy strategy;
	private OrderModel order;

	@Before
	public void setUp()
	{
		recordDao = mock(StripePaymentIntentRecordDao.class);
		strategy = new StripePendingPaymentCancelDenialStrategy();
		strategy.setStripePaymentIntentRecordDao(recordDao);
		final StripePaymentInfoModel paymentInfo = new StripePaymentInfoModel();
		paymentInfo.setStripePaymentIntentId(PI_ID);
		order = new OrderModel();
		order.setPaymentInfo(paymentInfo);
	}

	@Test
	public void deniesPartialCancelWhilePending()
	{
		for (final StripePaymentIntentState state : new StripePaymentIntentState[]
		{ StripePaymentIntentState.PROCESSING, StripePaymentIntentState.AWAITING_FUNDS })
		{
			givenState(state);

			final OrderCancelDenialReason reason = strategy.getCancelDenialReason(null, order, null, true, false);

			assertNotNull(state.getCode(), reason);
			assertTrue(((DefaultOrderCancelDenialReason) reason).getDescription().startsWith("stripe-payment-pending"));
			assertNotNull(strategy.getCancelDenialReason(null, order, null, true, true));
			assertNotNull(strategy.getCancelDenialReason(null, order, null, false, true));
		}
	}

	@Test
	public void allowsFullCancelWhilePending()
	{
		givenState(StripePaymentIntentState.AWAITING_FUNDS);

		assertNull(strategy.getCancelDenialReason(null, order, null, false, false));
	}

	@Test
	public void allowsPartialCancelWhenPaid()
	{
		givenState(StripePaymentIntentState.AUTHORIZED);
		assertNull(strategy.getCancelDenialReason(null, order, null, true, false));
		givenState(StripePaymentIntentState.CAPTURED);
		assertNull(strategy.getCancelDenialReason(null, order, null, true, false));
	}

	@Test
	public void ignoresNonStripeOrders()
	{
		order.setPaymentInfo(new CreditCardPaymentInfoModel());

		assertNull(strategy.getCancelDenialReason(null, order, null, true, false));
		verifyNoInteractions(recordDao);
	}

	@Test
	public void allowsPartialCancelWithoutRecord()
	{
		when(recordDao.findByPaymentIntentId(PI_ID)).thenReturn(Optional.empty());

		assertNull(strategy.getCancelDenialReason(null, order, null, true, false));
		assertEquals(7301, StripePendingPaymentCancelDenialStrategy.REASON_CODE);
	}

	private void givenState(final StripePaymentIntentState state)
	{
		final StripePaymentIntentRecordModel record = mock(StripePaymentIntentRecordModel.class);
		when(record.getState()).thenReturn(state);
		when(recordDao.findByPaymentIntentId(PI_ID)).thenReturn(Optional.of(record));
	}
}
