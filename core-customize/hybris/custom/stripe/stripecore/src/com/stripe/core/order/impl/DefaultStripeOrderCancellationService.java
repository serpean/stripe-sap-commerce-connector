package com.stripe.core.order.impl;

import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderCancellationService;
import com.stripe.core.payment.StripePaymentInfos;
import com.stripe.core.payment.StripePaymentIntentService;
import de.hybris.platform.basecommerce.enums.CancelReason;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.ordercancel.OrderCancelException;
import de.hybris.platform.ordercancel.OrderCancelRequest;
import de.hybris.platform.ordercancel.OrderCancelService;
import de.hybris.platform.servicelayer.user.UserService;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link StripeOrderCancellationService}. It cancels the full order with the SAP {@link OrderCancelService},
 * then gives the money of the PaymentIntent back (cancel, or refund when Stripe captured it). An order with an ACCEPTED
 * authorization of its PaymentIntent is paid: it is not cancelled. When Stripe refuses the release (for example, the
 * payment is still processing), the order stays cancelled: if the payment succeeds later, the webhook handler refunds
 * it.
 */
public class DefaultStripeOrderCancellationService implements StripeOrderCancellationService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeOrderCancellationService.class);

	static final String CANCEL_NOTES = "stripe-payment-failed";
	private static final Set<StripePaymentIntentState> FUNDS_RELEASED = Set.of(StripePaymentIntentState.CANCELED,
			StripePaymentIntentState.REFUNDED);

	private OrderCancelService orderCancelService;
	private UserService userService;
	private StripePaymentIntentService stripePaymentIntentService;
	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	private StripeOrderAuthorizationService stripeOrderAuthorizationService;

	@Override
	public void cancelForFailedPayment(final OrderModel order)
	{
		final String paymentIntentId = StripePaymentInfos.paymentIntentId(order);
		if (paymentIntentId != null && stripeOrderAuthorizationService.hasAcceptedAuthorization(order, paymentIntentId))
		{
			LOG.warn("Order {} has an accepted authorization of payment intent {}: it is not cancelled", order.getCode(),
					paymentIntentId);
			return;
		}
		if (OrderStatus.CANCELLED.equals(order.getStatus()))
		{
			LOG.info("Order {} is already cancelled", order.getCode());
		}
		else
		{
			cancelOrder(order);
		}
		if (paymentIntentId != null)
		{
			releaseFunds(order, paymentIntentId);
		}
	}

	protected void cancelOrder(final OrderModel order)
	{
		final OrderCancelRequest request = new OrderCancelRequest(order, CancelReason.OTHER, CANCEL_NOTES);
		try
		{
			orderCancelService.requestOrderCancel(request, userService.getAdminUser());
			LOG.info("Order {} is cancelled because its Stripe payment failed", order.getCode());
		}
		catch (final OrderCancelException e)
		{
			throw new IllegalStateException("Order " + order.getCode() + " cannot be cancelled: " + e.getMessage(), e);
		}
	}

	protected void releaseFunds(final OrderModel order, final String paymentIntentId)
	{
		final StripePaymentIntentState state = stripePaymentIntentRecordDao.findByPaymentIntentId(paymentIntentId)
				.map(StripePaymentIntentRecordModel::getState)
				.orElse(null);
		if (state != null && FUNDS_RELEASED.contains(state))
		{
			LOG.info("Funds of payment intent {} are already released (record state {})", paymentIntentId, state);
			return;
		}
		try
		{
			stripePaymentIntentService.releaseFunds(paymentIntentId, "order " + order.getCode() + ": payment failed");
		}
		catch (final StripeGatewayException e)
		{
			LOG.error("Stripe did not release the funds of payment intent {} of cancelled order {} ({}). If the payment "
					+ "succeeds later, the webhook refunds it", paymentIntentId, order.getCode(), e.getMessage());
		}
	}

	public void setOrderCancelService(final OrderCancelService orderCancelService)
	{
		this.orderCancelService = orderCancelService;
	}

	public void setUserService(final UserService userService)
	{
		this.userService = userService;
	}

	public void setStripePaymentIntentService(final StripePaymentIntentService stripePaymentIntentService)
	{
		this.stripePaymentIntentService = stripePaymentIntentService;
	}

	public void setStripePaymentIntentRecordDao(final StripePaymentIntentRecordDao stripePaymentIntentRecordDao)
	{
		this.stripePaymentIntentRecordDao = stripePaymentIntentRecordDao;
	}

	public void setStripeOrderAuthorizationService(final StripeOrderAuthorizationService stripeOrderAuthorizationService)
	{
		this.stripeOrderAuthorizationService = stripeOrderAuthorizationService;
	}
}
