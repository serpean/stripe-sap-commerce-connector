package com.stripe.core.payment.impl;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.config.StripeCaptureMode;
import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderPlacementLockService;
import com.stripe.core.payment.StripeIntentData;
import com.stripe.core.payment.StripeLineItemsBuilder;
import com.stripe.core.payment.StripePaymentInProgressException;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentUpdateParams;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.servicelayer.model.ModelService;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link StripePaymentIntentService}.
 */
public class DefaultStripePaymentIntentService implements StripePaymentIntentService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripePaymentIntentService.class);

	private static final Set<String> EDITABLE_STATUSES = Set.of("requires_payment_method", "requires_confirmation",
			"requires_action");

	private StripeGateway stripeGateway;
	private StripeConfigurationService stripeConfigurationService;
	private StripeAmountConverter stripeAmountConverter;
	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	private StripeOrderPlacementLockService stripeOrderPlacementLockService;
	private StripePaymentIntentRecordService stripePaymentIntentRecordService;
	private StripeOrderAuthorizationService stripeOrderAuthorizationService;
	private StripeLineItemsBuilder stripeLineItemsBuilder;
	private ModelService modelService;

	@Override
	public StripeIntentData prepareForCart(final CartModel cart)
	{
		final String currencyIso = cart.getCurrency().getIsocode();
		final long amount = stripeAmountConverter.chargeAmountMinor(cart);
		final String existingId = existingPaymentIntentId(cart);

		if (existingId == null)
		{
			return toData(createFor(cart, amount, currencyIso));
		}

		final PaymentIntent existing = stripeGateway.retrievePaymentIntent(existingId);
		ensureRecord(existing, cart);
		if ("canceled".equals(existing.getStatus()))
		{
			releaseFunds(existingId, "cart " + cart.getCode() + " had a canceled payment intent");
			stripeOrderAuthorizationService.removeAuthorizations(cart, existingId);
			return toData(createFor(cart, amount, currencyIso));
		}

		final boolean changed = amount != existing.getAmount() || !currencyIso.equalsIgnoreCase(existing.getCurrency());
		if (!changed)
		{
			return toData(existing);
		}
		if (EDITABLE_STATUSES.contains(existing.getStatus()))
		{
			return toData(update(cart, existing, amount, currencyIso));
		}
		if ("processing".equals(existing.getStatus()))
		{
			throw new StripePaymentInProgressException(existingId);
		}
		releaseConfirmedForChangedCart(cart, existingId);
		return toData(createFor(cart, amount, currencyIso));
	}

	/**
	 * Releases a confirmed PaymentIntent (authorized or captured) of a cart that changed. An order may be placed for it
	 * at the same time (for example in another browser tab), so the release happens only under its order placement
	 * lock and only when no order exists for it. Otherwise nothing changes and the customer is told that the payment is
	 * in progress. The lock stays after the release: the PaymentIntent is never used for an order again. When Stripe
	 * refuses the release, the lock is given back.
	 */
	protected void releaseConfirmedForChangedCart(final CartModel cart, final String paymentIntentId)
	{
		if (!stripeOrderPlacementLockService.tryAcquire(paymentIntentId))
		{
			LOG.info("Cart {} changed while an order is placed for payment intent {}: nothing is released", cart.getCode(),
					paymentIntentId);
			throw new StripePaymentInProgressException(paymentIntentId, "an order is being placed for it");
		}
		if (stripePaymentIntentRecordService.findLiveOrder(paymentIntentId).isPresent())
		{
			LOG.warn("Cart {} changed but payment intent {} already has an order: nothing is released", cart.getCode(),
					paymentIntentId);
			throw new StripePaymentInProgressException(paymentIntentId, "it already has an order");
		}
		try
		{
			releaseFunds(paymentIntentId, "cart " + cart.getCode() + " changed after the payment intent was confirmed");
		}
		catch (final RuntimeException e)
		{
			stripeOrderPlacementLockService.release(paymentIntentId);
			throw e;
		}
		stripeOrderAuthorizationService.removeAuthorizations(cart, paymentIntentId);
	}

	@Override
	public void releaseFunds(final String paymentIntentId, final String reason)
	{
		final PaymentIntent intent = stripeGateway.retrievePaymentIntent(paymentIntentId);
		LOG.warn("Releasing funds of Stripe payment intent {} in status {}. Reason: {}", paymentIntentId, intent.getStatus(),
				reason);

		if ("succeeded".equals(intent.getStatus()))
		{
			final long remaining = remainingCapturedAmount(intent);
			if (remaining > 0)
			{
				stripeGateway.createRefund(paymentIntentId, remaining, "release-" + paymentIntentId + "-refund-" + remaining);
			}
			else
			{
				LOG.info("Payment intent {} has no captured money left to refund", paymentIntentId);
			}
			updateRecordState(paymentIntentId, StripePaymentIntentState.REFUNDED);
		}
		else
		{
			if (!"canceled".equals(intent.getStatus()))
			{
				stripeGateway.cancelPaymentIntent(paymentIntentId, "release-" + paymentIntentId + "-cancel");
			}
			updateRecordState(paymentIntentId, StripePaymentIntentState.CANCELED);
		}
	}

	/**
	 * The captured money that is not refunded yet: amount received minus the amount refunded on the latest charge. The
	 * gateway retrieve expands latest_charge. When the charge details are still missing, the full amount received is
	 * used: Stripe refuses a refund larger than what remains, so this never refunds too much. The amount is part of the
	 * idempotency key, so a retry of the same release uses the same key, and a release after a partial refund gets a
	 * new one.
	 */
	protected long remainingCapturedAmount(final PaymentIntent intent)
	{
		final long received = intent.getAmountReceived() == null ? 0L : intent.getAmountReceived();
		final Charge charge = intent.getLatestChargeObject();
		if (charge == null)
		{
			LOG.warn("Payment intent {} has no charge details: the full received amount {} is refunded", intent.getId(),
					received);
			return received;
		}
		final long refunded = charge.getAmountRefunded() == null ? 0L : charge.getAmountRefunded();
		return Math.max(0L, received - refunded);
	}

	protected PaymentIntent createFor(final CartModel cart, final long amount, final String currencyIso)
	{
		final int created = stripePaymentIntentRecordDao.countByCartCode(cart.getCode());
		final String idempotencyKey = "cart-" + cart.getCode() + "-" + cart.getGuid() + "-pi-" + (created + 1);

		final PaymentIntent intent = stripeGateway.createPaymentIntent(buildCreateParams(cart, amount, currencyIso),
				idempotencyKey);

		saveRecord(intent, cart, amount, currencyIso, StripePaymentIntentState.CREATED);

		final StripePaymentInfoModel info = modelService.create(StripePaymentInfoModel.class);
		info.setCode(intent.getId());
		info.setStripePaymentIntentId(intent.getId());
		info.setUser(cart.getUser());
		modelService.save(info);

		final StripePaymentInfoModel previous = cart.getPaymentInfo() instanceof StripePaymentInfoModel
				? (StripePaymentInfoModel) cart.getPaymentInfo() : null;
		cart.setPaymentInfo(info);
		modelService.save(cart);
		if (previous != null)
		{
			modelService.remove(previous);
		}
		return intent;
	}

	protected void ensureRecord(final PaymentIntent intent, final CartModel cart)
	{
		if (stripePaymentIntentRecordDao.findByPaymentIntentId(intent.getId()).isEmpty())
		{
			saveRecord(intent, cart, intent.getAmount(), intent.getCurrency().toUpperCase(), stateOf(intent.getStatus()));
		}
	}

	protected void saveRecord(final PaymentIntent intent, final CartModel cart, final long amount, final String currencyIso,
			final StripePaymentIntentState state)
	{
		final StripePaymentIntentRecordModel record = modelService.create(StripePaymentIntentRecordModel.class);
		record.setPaymentIntentId(intent.getId());
		record.setCartCode(cart.getCode());
		record.setBaseSiteUid(siteUid(cart));
		record.setUserUid(userUid(cart));
		record.setCurrencyIso(currencyIso);
		record.setAmount(amount);
		record.setState(state);
		modelService.save(record);
	}

	private StripePaymentIntentState stateOf(final String stripeStatus)
	{
		switch (stripeStatus)
		{
			case "requires_capture":
				return StripePaymentIntentState.AUTHORIZED;
			case "succeeded":
				return StripePaymentIntentState.CAPTURED;
			case "processing":
				return StripePaymentIntentState.PROCESSING;
			case "canceled":
				return StripePaymentIntentState.CANCELED;
			default:
				return StripePaymentIntentState.CREATED;
		}
	}

	protected PaymentIntent update(final CartModel cart, final PaymentIntent existing, final long amount,
			final String currencyIso)
	{
		final long changed = cart.getModifiedtime() == null ? 0L : cart.getModifiedtime().getTime();
		final String idempotencyKey = "update-" + existing.getId() + "-" + amount + "-" + currencyIso.toLowerCase() + "-"
				+ changed;
		final PaymentIntentUpdateParams params = PaymentIntentUpdateParams.builder().setAmount(amount)
				.setCurrency(currencyIso.toLowerCase()).putAllExtraParam(stripeLineItemsBuilder.build(cart, amount)).build();
		final PaymentIntent updated = stripeGateway.updatePaymentIntent(existing.getId(), params, idempotencyKey);

		stripePaymentIntentRecordDao.findByPaymentIntentId(existing.getId()).ifPresent(record -> {
			record.setAmount(amount);
			record.setCurrencyIso(currencyIso);
			modelService.save(record);
		});
		return updated;
	}

	protected PaymentIntentCreateParams buildCreateParams(final CartModel cart, final long amount, final String currencyIso)
	{
		final boolean manual = stripeConfigurationService.getCaptureMode() == StripeCaptureMode.MANUAL;
		final PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
				.setAmount(amount)
				.setCurrency(currencyIso.toLowerCase())
				.setAutomaticPaymentMethods(
						PaymentIntentCreateParams.AutomaticPaymentMethods.builder().setEnabled(true).build())
				.setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.AUTOMATIC)
				.putMetadata("cartCode", cart.getCode())
				.putMetadata("baseSiteUid", siteUid(cart))
				.putMetadata("userUid", userUid(cart))
				.putMetadata("currencyIso", currencyIso)
				.putAllExtraParam(stripeLineItemsBuilder.build(cart, amount));
		if (manual)
		{
			builder.setPaymentMethodOptions(buildPaymentMethodOptions(stripeConfigurationService.getManualCaptureMethods()));
		}
		final String suffix = stripeConfigurationService.getStatementDescriptorSuffix();
		if (StringUtils.isNotBlank(suffix))
		{
			builder.setStatementDescriptorSuffix(suffix);
		}
		return builder.build();
	}

	protected PaymentIntentCreateParams.PaymentMethodOptions buildPaymentMethodOptions(final Set<String> manualMethods)
	{
		final PaymentIntentCreateParams.PaymentMethodOptions.Builder builder = PaymentIntentCreateParams.PaymentMethodOptions
				.builder();
		if (manualMethods.contains("card"))
		{
			builder.setCard(PaymentIntentCreateParams.PaymentMethodOptions.Card.builder()
					.setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Card.CaptureMethod.MANUAL).build());
		}
		if (manualMethods.contains("paypal"))
		{
			builder.setPaypal(PaymentIntentCreateParams.PaymentMethodOptions.Paypal.builder()
					.setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Paypal.CaptureMethod.MANUAL).build());
		}
		if (manualMethods.contains("klarna"))
		{
			builder.setKlarna(PaymentIntentCreateParams.PaymentMethodOptions.Klarna.builder()
					.setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Klarna.CaptureMethod.MANUAL).build());
		}
		if (manualMethods.contains("affirm"))
		{
			builder.setAffirm(PaymentIntentCreateParams.PaymentMethodOptions.Affirm.builder()
					.setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Affirm.CaptureMethod.MANUAL).build());
		}
		if (manualMethods.contains("afterpay_clearpay"))
		{
			builder.setAfterpayClearpay(PaymentIntentCreateParams.PaymentMethodOptions.AfterpayClearpay.builder()
					.setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.AfterpayClearpay.CaptureMethod.MANUAL)
					.build());
		}
		return builder.build();
	}

	protected void updateRecordState(final String paymentIntentId, final StripePaymentIntentState state)
	{
		stripePaymentIntentRecordDao.findByPaymentIntentId(paymentIntentId).ifPresent(record -> {
			record.setState(state);
			modelService.save(record);
		});
	}

	private String existingPaymentIntentId(final CartModel cart)
	{
		return Optional.ofNullable(cart.getPaymentInfo())
				.filter(StripePaymentInfoModel.class::isInstance)
				.map(info -> ((StripePaymentInfoModel) info).getStripePaymentIntentId())
				.filter(StringUtils::isNotBlank)
				.orElse(null);
	}

	private String siteUid(final CartModel cart)
	{
		return cart.getSite() == null ? "" : cart.getSite().getUid();
	}

	private String userUid(final CartModel cart)
	{
		return cart.getUser() == null ? "" : cart.getUser().getUid();
	}

	private StripeIntentData toData(final PaymentIntent intent)
	{
		return new StripeIntentData(intent.getId(), intent.getClientSecret(), intent.getStatus());
	}

	public void setStripeGateway(final StripeGateway stripeGateway)
	{
		this.stripeGateway = stripeGateway;
	}

	public void setStripeConfigurationService(final StripeConfigurationService stripeConfigurationService)
	{
		this.stripeConfigurationService = stripeConfigurationService;
	}

	public void setStripeAmountConverter(final StripeAmountConverter stripeAmountConverter)
	{
		this.stripeAmountConverter = stripeAmountConverter;
	}

	public void setStripePaymentIntentRecordDao(final StripePaymentIntentRecordDao stripePaymentIntentRecordDao)
	{
		this.stripePaymentIntentRecordDao = stripePaymentIntentRecordDao;
	}

	public void setStripeLineItemsBuilder(final StripeLineItemsBuilder stripeLineItemsBuilder)
	{
		this.stripeLineItemsBuilder = stripeLineItemsBuilder;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}

	public void setStripeOrderPlacementLockService(final StripeOrderPlacementLockService stripeOrderPlacementLockService)
	{
		this.stripeOrderPlacementLockService = stripeOrderPlacementLockService;
	}

	public void setStripePaymentIntentRecordService(final StripePaymentIntentRecordService stripePaymentIntentRecordService)
	{
		this.stripePaymentIntentRecordService = stripePaymentIntentRecordService;
	}

	public void setStripeOrderAuthorizationService(final StripeOrderAuthorizationService stripeOrderAuthorizationService)
	{
		this.stripeOrderAuthorizationService = stripeOrderAuthorizationService;
	}
}
