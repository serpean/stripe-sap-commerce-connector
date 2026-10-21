package com.stripe.facades.order;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.order.StripeOrderCancellationService;
import com.stripe.core.order.StripeOrderPlacementLockService;
import com.stripe.core.payment.StripePaymentIntentChecks;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import com.stripe.facades.checkout.StripeCheckoutException;
import com.stripe.facades.checkout.StripeCheckoutException.Reason;
import com.stripe.model.PaymentIntent;
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
 * <p>
 * The cart can change between the authorization and the placement (for example in another browser tab). So after the
 * placement the charge amount and currency of the order are compared with the PaymentIntent. When they differ, the
 * order is cancelled, its money is given back (as for a rejected authorization on the server path), and
 * {@link Reason#AMOUNT_MISMATCH} is thrown.
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
	private StripeGateway stripeGateway;
	private StripePaymentIntentChecks stripePaymentIntentChecks;
	private StripeAmountConverter stripeAmountConverter;
	private StripeOrderCancellationService stripeOrderCancellationService;
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
		linkLiveOrder(paymentIntentId).ifPresent(placed -> checkOrderAmount(paymentIntentId, placed));
		return order;
	}

	/**
	 * Cancels the order and gives its money back when its charge amount is not the amount of the PaymentIntent. When
	 * the PaymentIntent cannot be read, the order stays and an ERROR is logged.
	 */
	protected void checkOrderAmount(final String paymentIntentId, final OrderModel order)
	{
		final PaymentIntent paymentIntent;
		try
		{
			paymentIntent = stripeGateway.retrievePaymentIntent(paymentIntentId);
		}
		catch (final RuntimeException e)
		{
			LOG.error("Could not read payment intent {} to check the amount of order {}: check it by hand", paymentIntentId,
					order.getCode(), e);
			return;
		}
		if (stripePaymentIntentChecks.matchesAmount(order, paymentIntent))
		{
			return;
		}
		LOG.error("Order {} must charge {} {} but payment intent {} has {} {} (the cart changed while the order was "
				+ "placed): cancelling the order and releasing the funds", order.getCode(),
				stripeAmountConverter.chargeAmountMinor(order), order.getCurrency() == null ? null
						: order.getCurrency().getIsocode(), paymentIntentId, paymentIntent.getAmount(),
				paymentIntent.getCurrency());
		stripeOrderCancellationService.cancelForAmountMismatch(order);
		throw new StripeCheckoutException(Reason.AMOUNT_MISMATCH, "Order " + order.getCode()
				+ " does not match the amount of payment intent " + paymentIntentId + ": it is cancelled");
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

	protected Optional<OrderModel> linkLiveOrder(final String paymentIntentId)
	{
		try
		{
			final Optional<OrderModel> order = stripePaymentIntentRecordService.findLiveOrder(paymentIntentId);
			order.ifPresentOrElse(placed -> link(paymentIntentId, placed),
					() -> LOG.error("No live order found for payment intent {} after its placement", paymentIntentId));
			return order;
		}
		catch (final RuntimeException e)
		{
			LOG.error("Could not find the order of payment intent {} after its placement", paymentIntentId, e);
			return Optional.empty();
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

	public void setStripeGateway(final StripeGateway stripeGateway)
	{
		this.stripeGateway = stripeGateway;
	}

	public void setStripePaymentIntentChecks(final StripePaymentIntentChecks stripePaymentIntentChecks)
	{
		this.stripePaymentIntentChecks = stripePaymentIntentChecks;
	}

	public void setStripeAmountConverter(final StripeAmountConverter stripeAmountConverter)
	{
		this.stripeAmountConverter = stripeAmountConverter;
	}

	public void setStripeOrderCancellationService(final StripeOrderCancellationService stripeOrderCancellationService)
	{
		this.stripeOrderCancellationService = stripeOrderCancellationService;
	}
}
