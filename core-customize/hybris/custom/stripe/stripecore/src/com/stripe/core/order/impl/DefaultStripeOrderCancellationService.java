package com.stripe.core.order.impl;

import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderCancellationService;
import com.stripe.core.payment.StripePaymentInfos;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.core.site.StripeSiteContext;
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
 * then gives the money of the PaymentIntent back (cancel, or refund when Stripe captured it). For a failed payment, an
 * order with an ACCEPTED authorization of its PaymentIntent is paid: it is not cancelled. When Stripe refuses the
 * release (for example, the payment is still processing), the order stays cancelled: if the payment succeeds later,
 * the webhook handler refunds it. The work runs in the site of the order, so the Stripe settings of that site are used.
 * When SAP refuses the cancel, an ERROR names the order and the PaymentIntent, and the caller gets the failure.
 */
public class DefaultStripeOrderCancellationService implements StripeOrderCancellationService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeOrderCancellationService.class);

	static final String CANCEL_NOTES = "stripe-payment-failed";
	static final String CANCEL_NOTES_AMOUNT_MISMATCH = "stripe-amount-mismatch";
	private static final Set<StripePaymentIntentState> FUNDS_RELEASED = Set.of(StripePaymentIntentState.CANCELED,
			StripePaymentIntentState.REFUNDED);

	private OrderCancelService orderCancelService;
	private UserService userService;
	private StripePaymentIntentService stripePaymentIntentService;
	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	private StripeSiteContext stripeSiteContext;
	private StripeOrderAuthorizationService stripeOrderAuthorizationService;

	@Override
	public void cancelForFailedPayment(final OrderModel order)
	{
		stripeSiteContext.runInSite(order.getSite(), () -> {
			cancelForFailedPaymentInSite(order);
			return null;
		});
	}

	@Override
	public void cancelForAmountMismatch(final OrderModel order)
	{
		stripeSiteContext.runInSite(order.getSite(), () -> {
			final String paymentIntentId = StripePaymentInfos.paymentIntentId(order);
			if (OrderStatus.CANCELLED.equals(order.getStatus()))
			{
				LOG.info("Order {} is already cancelled", order.getCode());
			}
			else
			{
				cancelOrder(order, paymentIntentId, CANCEL_NOTES_AMOUNT_MISMATCH);
			}
			if (paymentIntentId != null)
			{
				releaseFunds(order, paymentIntentId, "order " + order.getCode() + ": amount differs from the payment");
			}
			return null;
		});
	}

	protected void cancelForFailedPaymentInSite(final OrderModel order)
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
			cancelOrder(order, paymentIntentId, CANCEL_NOTES);
		}
		if (paymentIntentId != null)
		{
			releaseFunds(order, paymentIntentId, "order " + order.getCode() + ": payment failed");
		}
	}

	/**
	 * Cancels the full order. When SAP refuses (or fails), an ERROR names the order and the PaymentIntent first, then
	 * the failure goes to the caller (for example, the order process task fails).
	 */
	protected void cancelOrder(final OrderModel order, final String paymentIntentId, final String notes)
	{
		final OrderCancelRequest request = new OrderCancelRequest(order, CancelReason.OTHER, notes);
		try
		{
			orderCancelService.requestOrderCancel(request, userService.getAdminUser());
			LOG.info("Order {} of payment intent {} is cancelled ({})", order.getCode(), paymentIntentId, notes);
		}
		catch (final OrderCancelException | RuntimeException e)
		{
			LOG.error("SAP refused to cancel order {} of Stripe payment intent {} ({}): {}. Cancel the order and check "
					+ "the payment in Stripe by hand", order.getCode(), paymentIntentId, notes, e.getMessage());
			throw new IllegalStateException("Order " + order.getCode() + " of payment intent " + paymentIntentId
					+ " cannot be cancelled: " + e.getMessage(), e);
		}
	}

	protected void releaseFunds(final OrderModel order, final String paymentIntentId, final String reason)
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
			stripePaymentIntentService.releaseFunds(paymentIntentId, reason);
		}
		catch (final StripeGatewayException e)
		{
			if (StripePaymentIntentState.PROCESSING.equals(state))
			{
				// Expected: Stripe cannot cancel a processing PaymentIntent (SEPA debit, for example).
				LOG.info("Payment intent {} of cancelled order {} is processing and cannot be cancelled now ({}): if the "
						+ "payment succeeds, the webhook refunds it", paymentIntentId, order.getCode(), e.getStripeCode());
				return;
			}
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

	public void setStripeSiteContext(final StripeSiteContext stripeSiteContext)
	{
		this.stripeSiteContext = stripeSiteContext;
	}
}
