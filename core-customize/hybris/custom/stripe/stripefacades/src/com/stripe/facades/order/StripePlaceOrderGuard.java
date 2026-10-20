package com.stripe.facades.order;

import com.stripe.core.order.StripeOrderPlacementLockService;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import de.hybris.platform.commercefacades.order.data.OrderData;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.order.InvalidCartException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Places the order of a Stripe cart and keeps the order placement lock right. The lock is given back only when no live
 * order exists for the PaymentIntent: SAP saves the order before its submit and after-placement steps, so a failure
 * in those steps leaves an order that must keep the lock. The record of the PaymentIntent is linked to the order.
 */
public class StripePlaceOrderGuard
{
	private static final Logger LOG = LoggerFactory.getLogger(StripePlaceOrderGuard.class);

	/**
	 * The SAP order placement, for example {@code super::placeOrder} of a checkout facade.
	 */
	@FunctionalInterface
	public interface PlaceOrder
	{
		OrderData place() throws InvalidCartException;
	}

	private StripeOrderPlacementLockService stripeOrderPlacementLockService;
	private StripePaymentIntentRecordService stripePaymentIntentRecordService;

	public OrderData placeOrder(final String paymentIntentId, final PlaceOrder placeOrder) throws InvalidCartException
	{
		final OrderData order;
		try
		{
			order = placeOrder.place();
		}
		catch (final InvalidCartException | RuntimeException e)
		{
			afterFailedPlacement(paymentIntentId);
			throw e;
		}
		if (order == null)
		{
			afterFailedPlacement(paymentIntentId);
			return null;
		}
		linkLiveOrder(paymentIntentId);
		return order;
	}

	protected void afterFailedPlacement(final String paymentIntentId)
	{
		final Optional<OrderModel> order;
		try
		{
			order = stripePaymentIntentRecordService.findLiveOrder(paymentIntentId);
		}
		catch (final RuntimeException e)
		{
			LOG.error("Could not check for an order of payment intent {}: the placement lock stays", paymentIntentId, e);
			return;
		}
		if (order.isPresent())
		{
			LOG.error("Order {} exists for payment intent {} although its placement failed: the placement lock stays",
					order.get().getCode(), paymentIntentId);
			link(paymentIntentId, order.get());
		}
		else
		{
			stripeOrderPlacementLockService.release(paymentIntentId);
		}
	}

	protected void linkLiveOrder(final String paymentIntentId)
	{
		try
		{
			stripePaymentIntentRecordService.findLiveOrder(paymentIntentId).ifPresentOrElse(
					order -> link(paymentIntentId, order),
					() -> LOG.error("No live order found for payment intent {} after its placement", paymentIntentId));
		}
		catch (final RuntimeException e)
		{
			LOG.error("Could not find the order of payment intent {} after its placement", paymentIntentId, e);
		}
	}

	private void link(final String paymentIntentId, final OrderModel order)
	{
		try
		{
			stripePaymentIntentRecordService.linkOrder(paymentIntentId, order);
		}
		catch (final RuntimeException e)
		{
			LOG.error("Could not link order {} to payment intent {}", order.getCode(), paymentIntentId, e);
		}
	}

	public void setStripeOrderPlacementLockService(final StripeOrderPlacementLockService stripeOrderPlacementLockService)
	{
		this.stripeOrderPlacementLockService = stripeOrderPlacementLockService;
	}

	public void setStripePaymentIntentRecordService(final StripePaymentIntentRecordService stripePaymentIntentRecordService)
	{
		this.stripePaymentIntentRecordService = stripePaymentIntentRecordService;
	}
}
