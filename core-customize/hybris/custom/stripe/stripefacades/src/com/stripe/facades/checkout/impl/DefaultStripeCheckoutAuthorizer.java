package com.stripe.facades.checkout.impl;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.constants.StripeCoreConstants;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderPlacementLockService;
import com.stripe.core.payment.StripePaymentInfoUpdater;
import com.stripe.core.payment.StripePaymentIntentChecks;
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
 */
public class DefaultStripeCheckoutAuthorizer implements StripeCheckoutAuthorizer
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeCheckoutAuthorizer.class);

	private static final Set<StripePaymentIntentStatus> WITH_MONEY = EnumSet.of(StripePaymentIntentStatus.AUTHORIZED,
			StripePaymentIntentStatus.CAPTURED, StripePaymentIntentStatus.PROCESSING);

	private StripeGateway stripeGateway;
	private StripePaymentIntentService stripePaymentIntentService;
	private StripeOrderPlacementLockService stripeOrderPlacementLockService;
	private CommerceCheckoutService commerceCheckoutService;
	private StripeAmountConverter stripeAmountConverter;
	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	private ModelService modelService;
	private StripePaymentInfoUpdater stripePaymentInfoUpdater;
	private StripePaymentIntentChecks stripePaymentIntentChecks;
	private StripeOrderAuthorizationService stripeOrderAuthorizationService;

	@Override
	public boolean authorize(final CartModel cart)
	{
		final StripePaymentInfoModel paymentInfo = stripePaymentInfoOf(cart);
		final String paymentIntentId = paymentInfo.getStripePaymentIntentId();
		final PaymentIntent intent = stripeGateway.retrievePaymentIntent(paymentIntentId);
		final StripePaymentIntentStatus status = StripePaymentIntentStatus.of(intent.getStatus());

		if (!stripePaymentIntentChecks.matchesAmount(cart, intent))
		{
			if (WITH_MONEY.contains(status))
			{
				releaseMismatchedFunds(cart, paymentInfo, intent);
				throw new StripeCheckoutException(Reason.AMOUNT_MISMATCH,
						"Payment intent " + paymentIntentId + " does not match the amount of cart " + cart.getCode());
			}
			LOG.info("Payment intent {} in status {} does not match cart {}", paymentIntentId, status, cart.getCode());
			return false;
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
				stripePaymentInfoUpdater.copyPaymentMethod(paymentInfo, intent);
				if (!isAccepted(commerceCheckoutService.authorizePayment(createParameter(cart))))
				{
					throw new StripeCheckoutException(Reason.PAYMENT_FAILED,
							"Authorization of payment intent " + intent.getId() + " for cart " + cart.getCode() + " was not accepted");
				}
				updateRecordState(intent.getId(), status == StripePaymentIntentStatus.CAPTURED
						? StripePaymentIntentState.CAPTURED : StripePaymentIntentState.AUTHORIZED);
				return true;
			case PROCESSING:
				if (stripeOrderAuthorizationService.hasAcceptedAuthorization(cart, intent.getId()))
				{
					LOG.warn("Cart {} has an accepted authorization of payment intent {}, which is processing again",
							cart.getCode(), intent.getId());
					return false;
				}
				stripePaymentInfoUpdater.copyPaymentMethod(paymentInfo, intent);
				updateRecordState(intent.getId(), StripePaymentIntentState.PROCESSING);
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
	 * Gives the money back and takes the PaymentIntent off the cart. When Stripe refuses (for example, a processing
	 * PaymentIntent), the PaymentIntent stays on the cart: the next preparation of the cart releases it.
	 */
	protected void releaseMismatchedFunds(final CartModel cart, final StripePaymentInfoModel paymentInfo,
			final PaymentIntent intent)
	{
		LOG.warn("Payment intent {} has {} {} but cart {} must charge {} {}", intent.getId(), intent.getAmount(),
				intent.getCurrency(), cart.getCode(), stripeAmountConverter.chargeAmountMinor(cart),
				cart.getCurrency().getIsocode());
		try
		{
			stripePaymentIntentService.releaseFunds(intent.getId(),
					"amount of cart " + cart.getCode() + " differs from the payment intent");
			paymentInfo.setStripePaymentIntentId(null);
			modelService.save(paymentInfo);
		}
		catch (final RuntimeException e)
		{
			LOG.error("Could not release the funds of payment intent {} for cart {}", intent.getId(), cart.getCode(), e);
		}
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

	private boolean isAccepted(final PaymentTransactionEntryModel entry)
	{
		return entry != null && TransactionStatus.ACCEPTED.name().equals(entry.getTransactionStatus());
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

	public void setStripePaymentInfoUpdater(final StripePaymentInfoUpdater stripePaymentInfoUpdater)
	{
		this.stripePaymentInfoUpdater = stripePaymentInfoUpdater;
	}

	public void setStripePaymentIntentChecks(final StripePaymentIntentChecks stripePaymentIntentChecks)
	{
		this.stripePaymentIntentChecks = stripePaymentIntentChecks;
	}

	public void setStripeOrderAuthorizationService(final StripeOrderAuthorizationService stripeOrderAuthorizationService)
	{
		this.stripeOrderAuthorizationService = stripeOrderAuthorizationService;
	}
}
