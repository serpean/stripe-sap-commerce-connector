package com.stripe.facades.checkout.impl;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.constants.StripeCoreConstants;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderPlacementLockService;
import com.stripe.core.payment.StripePaymentDetailsCopier;
import com.stripe.core.payment.StripePaymentIntentChecks;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.facades.checkout.StripeCheckoutAuthorizer;
import com.stripe.facades.checkout.StripeCheckoutException;
import com.stripe.facades.checkout.StripeCheckoutException.Reason;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.commerceservices.order.CommerceCheckoutService;
import de.hybris.platform.commerceservices.service.data.CommerceCheckoutParameter;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.servicelayer.model.ModelService;
import java.util.EnumSet;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link StripeCheckoutAuthorizer}. Reads the PaymentIntent of the cart; it never creates or changes one.
 * <p>
 * A PaymentIntent that is processing, or that waits for a voucher or bank transfer payment (AWAITING_FUNDS: status
 * requires_action with payment instructions), places a pending order: no AUTHORIZATION entry, the record state tells
 * which. Any other requires_action (3D Secure, redirect) places no order.
 */
public class DefaultStripeCheckoutAuthorizer implements StripeCheckoutAuthorizer
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeCheckoutAuthorizer.class);

	private static final String AUTHORIZATION_REJECTED = "authorization-rejected";

	private static final Set<StripePaymentIntentStatus> WITH_MONEY = EnumSet.of(StripePaymentIntentStatus.AUTHORIZED,
			StripePaymentIntentStatus.CAPTURED, StripePaymentIntentStatus.PROCESSING,
			StripePaymentIntentStatus.AWAITING_FUNDS);

	private StripeGateway stripeGateway;
	private StripePaymentIntentService stripePaymentIntentService;
	private StripeOrderPlacementLockService stripeOrderPlacementLockService;
	private CommerceCheckoutService commerceCheckoutService;
	private StripeAmountConverter stripeAmountConverter;
	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	private ModelService modelService;
	private StripePaymentDetailsCopier stripePaymentDetailsCopier;
	private StripePaymentIntentChecks stripePaymentIntentChecks;
	private StripePaymentIntentRecordService stripePaymentIntentRecordService;
	private StripeOrderAuthorizationService stripeOrderAuthorizationService;

	@Override
	public boolean authorize(final CartModel cart)
	{
		final StripePaymentInfoModel paymentInfo = stripePaymentInfoOf(cart);
		final String paymentIntentId = paymentInfo.getStripePaymentIntentId();
		final PaymentIntent intent = stripeGateway.retrievePaymentIntent(paymentIntentId);
		final StripePaymentIntentStatus status = StripePaymentIntentStatus.of(intent);

		if (!stripePaymentIntentChecks.matchesAmount(cart, intent))
		{
			if (WITH_MONEY.contains(status))
			{
				releaseMismatchedFunds(cart, paymentInfo, intent);
			}
			throw new StripeCheckoutException(Reason.AMOUNT_MISMATCH, "Payment intent " + paymentIntentId + " in status "
					+ status + " does not match the amount of cart " + cart.getCode());
		}

		if (!stripeOrderPlacementLockService.tryAcquire(paymentIntentId))
		{
			throw new StripeCheckoutException(Reason.ALREADY_PLACED,
					"An order is already placed for payment intent " + paymentIntentId);
		}

		boolean authorized = false;
		try
		{
			authorized = authorize(cart, paymentInfo, intent, status);
			return authorized;
		}
		finally
		{
			if (!authorized)
			{
				stripeOrderPlacementLockService.release(paymentIntentId);
			}
		}
	}

	protected boolean authorize(final CartModel cart, final StripePaymentInfoModel paymentInfo, final PaymentIntent intent,
			final StripePaymentIntentStatus status)
	{
		switch (status)
		{
			case AUTHORIZED:
			case CAPTURED:
				if (stripeOrderAuthorizationService.hasAcceptedAuthorization(cart, intent.getId())
						&& stripePaymentIntentChecks.holdsAllMoney(intent))
				{
					LOG.info("Cart {} already has an accepted authorization of payment intent {}", cart.getCode(),
							intent.getId());
					return true;
				}
				stripePaymentDetailsCopier.copy(intent, paymentInfo);
				final PaymentTransactionEntryModel entry = commerceCheckoutService.authorizePayment(createParameter(cart));
				if (hasStatus(entry, TransactionStatus.REJECTED))
				{
					LOG.warn("Authorization of payment intent {} for cart {} was rejected: the funds are released",
							intent.getId(), cart.getCode());
					releaseHeldFunds(cart, paymentInfo, intent, AUTHORIZATION_REJECTED);
					throw new StripeCheckoutException(Reason.PAYMENT_FAILED,
							"Authorization of payment intent " + intent.getId() + " for cart " + cart.getCode() + " was rejected");
				}
				if (!hasStatus(entry, TransactionStatus.ACCEPTED))
				{
					throw new StripeCheckoutException(Reason.UNAVAILABLE, "Authorization of payment intent " + intent.getId()
							+ " for cart " + cart.getCode() + " did not finish (" + (entry == null ? "no entry"
							: entry.getTransactionStatus()) + "): the payment is kept for a new attempt");
				}
				updateRecordState(intent.getId(), status == StripePaymentIntentStatus.CAPTURED
						? StripePaymentIntentState.CAPTURED : StripePaymentIntentState.AUTHORIZED);
				return true;
			case PROCESSING:
			case AWAITING_FUNDS:
				if (stripeOrderAuthorizationService.hasAcceptedAuthorization(cart, intent.getId()))
				{
					LOG.warn("Cart {} has an accepted authorization of payment intent {}, which is {} again",
							cart.getCode(), intent.getId(), status);
					return false;
				}
				stripePaymentDetailsCopier.copy(intent, paymentInfo);
				updateRecordState(intent.getId(), status == StripePaymentIntentStatus.AWAITING_FUNDS
						? StripePaymentIntentState.AWAITING_FUNDS : StripePaymentIntentState.PROCESSING);
				return true;
			default:
				throw new StripeCheckoutException(Reason.PAYMENT_FAILED,
						"Payment intent " + intent.getId() + " of cart " + cart.getCode() + " is in status " + status + ": no order");
		}
	}

	protected CommerceCheckoutParameter createParameter(final CartModel cart)
	{
		final CommerceCheckoutParameter parameter = new CommerceCheckoutParameter();
		parameter.setCart(cart);
		parameter.setEnableHooks(true);
		parameter.setAuthorizationAmount(stripeAmountConverter.chargeAmount(cart));
		parameter.setPaymentProvider(StripeCoreConstants.STRIPE_PROVIDER);
		return parameter;
	}

	/**
	 * Releases the funds of a PaymentIntent whose amount or currency is not the amount of the cart. An order may be
	 * placed for it at the same time (for example in another browser tab), so the release happens only under its order
	 * placement lock and only when no order exists for it; otherwise nothing changes and ALREADY_PLACED is thrown. The
	 * lock stays after the release (the decision is final), and is given back when Stripe refuses the release.
	 */
	protected void releaseMismatchedFunds(final CartModel cart, final StripePaymentInfoModel paymentInfo,
			final PaymentIntent intent)
	{
		final String paymentIntentId = intent.getId();
		if (!stripeOrderPlacementLockService.tryAcquire(paymentIntentId))
		{
			throw new StripeCheckoutException(Reason.ALREADY_PLACED, "Cart " + cart.getCode() + " differs from payment intent "
					+ paymentIntentId + ", but an order is being placed for it: nothing is released");
		}
		if (stripePaymentIntentRecordService.findLiveOrder(paymentIntentId).isPresent())
		{
			throw new StripeCheckoutException(Reason.ALREADY_PLACED, "Cart " + cart.getCode() + " differs from payment intent "
					+ paymentIntentId + ", which already has an order: nothing is released");
		}
		LOG.warn("Payment intent {} has {} {} but cart {} must charge {} {}", paymentIntentId, intent.getAmount(),
				intent.getCurrency(), cart.getCode(), stripeAmountConverter.chargeAmountMinor(cart),
				cart.getCurrency().getIsocode());
		if (!releaseHeldFunds(cart, paymentInfo, intent, "amount of cart " + cart.getCode() + " differs from the payment intent"))
		{
			stripeOrderPlacementLockService.release(paymentIntentId);
		}
	}

	/**
	 * Gives the money of the PaymentIntent back, takes the PaymentIntent off the cart and removes the cart's
	 * authorizations of it, so the next payment of the cart uses a new PaymentIntent. When Stripe refuses, the
	 * PaymentIntent stays on the cart: the next preparation of the cart releases it.
	 *
	 * @return true when the funds were released
	 */
	protected boolean releaseHeldFunds(final CartModel cart, final StripePaymentInfoModel paymentInfo,
			final PaymentIntent intent, final String reason)
	{
		try
		{
			stripePaymentIntentService.releaseFunds(intent.getId(), reason);
		}
		catch (final RuntimeException e)
		{
			LOG.error("Could not release the funds of payment intent {} for cart {}", intent.getId(), cart.getCode(), e);
			return false;
		}
		paymentInfo.setStripePaymentIntentId(null);
		modelService.save(paymentInfo);
		stripeOrderAuthorizationService.removeAuthorizations(cart, intent.getId());
		return true;
	}

	protected void updateRecordState(final String paymentIntentId, final StripePaymentIntentState state)
	{
		stripePaymentIntentRecordDao.findByPaymentIntentId(paymentIntentId).ifPresentOrElse(record -> {
			record.setState(state);
			modelService.save(record);
		}, () -> LOG.warn("No record for payment intent {}", paymentIntentId));
	}

	private StripePaymentInfoModel stripePaymentInfoOf(final CartModel cart)
	{
		if (cart.getPaymentInfo() instanceof StripePaymentInfoModel)
		{
			final StripePaymentInfoModel paymentInfo = (StripePaymentInfoModel) cart.getPaymentInfo();
			if (StringUtils.isNotBlank(paymentInfo.getStripePaymentIntentId()))
			{
				return paymentInfo;
			}
		}
		throw new StripeCheckoutException(Reason.PAYMENT_NOT_FOUND, "Cart " + cart.getCode() + " has no payment intent");
	}

	private static boolean hasStatus(final PaymentTransactionEntryModel entry, final TransactionStatus status)
	{
		return entry != null && status.name().equals(entry.getTransactionStatus());
	}

	public void setStripeGateway(final StripeGateway stripeGateway)
	{
		this.stripeGateway = stripeGateway;
	}

	public void setStripePaymentIntentService(final StripePaymentIntentService stripePaymentIntentService)
	{
		this.stripePaymentIntentService = stripePaymentIntentService;
	}

	public void setStripeOrderPlacementLockService(final StripeOrderPlacementLockService stripeOrderPlacementLockService)
	{
		this.stripeOrderPlacementLockService = stripeOrderPlacementLockService;
	}

	public void setCommerceCheckoutService(final CommerceCheckoutService commerceCheckoutService)
	{
		this.commerceCheckoutService = commerceCheckoutService;
	}

	public void setStripeAmountConverter(final StripeAmountConverter stripeAmountConverter)
	{
		this.stripeAmountConverter = stripeAmountConverter;
	}

	public void setStripePaymentIntentRecordDao(final StripePaymentIntentRecordDao stripePaymentIntentRecordDao)
	{
		this.stripePaymentIntentRecordDao = stripePaymentIntentRecordDao;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}

	public void setStripePaymentIntentChecks(final StripePaymentIntentChecks stripePaymentIntentChecks)
	{
		this.stripePaymentIntentChecks = stripePaymentIntentChecks;
	}

	public void setStripeOrderAuthorizationService(final StripeOrderAuthorizationService stripeOrderAuthorizationService)
	{
		this.stripeOrderAuthorizationService = stripeOrderAuthorizationService;
	}

	public void setStripePaymentIntentRecordService(final StripePaymentIntentRecordService stripePaymentIntentRecordService)
	{
		this.stripePaymentIntentRecordService = stripePaymentIntentRecordService;
	}

	public void setStripePaymentDetailsCopier(final StripePaymentDetailsCopier stripePaymentDetailsCopier)
	{
		this.stripePaymentDetailsCopier = stripePaymentDetailsCopier;
	}
}
