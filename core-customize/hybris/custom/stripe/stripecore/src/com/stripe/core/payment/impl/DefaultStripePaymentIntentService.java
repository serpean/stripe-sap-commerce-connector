package com.stripe.core.payment.impl;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.config.StripeCaptureMode;
import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.customer.StripeCashBalanceService;
import com.stripe.core.customer.StripeCustomerService;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderPlacementLockService;
import com.stripe.core.payment.StripeIntentData;
import com.stripe.core.payment.StripeLineItemsBuilder;
import com.stripe.core.payment.StripePaymentInProgressException;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.model.Charge;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentUpdateParams;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.servicelayer.model.ModelService;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
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

	/**
	 * Statuses in which the PaymentIntent holds no money and has issued nothing to the customer, so its amount can
	 * change: requires_payment_method (FAILED), requires_confirmation and requires_action without payment instructions
	 * (REQUIRES_ACTION). A PaymentIntent that issued a voucher or bank transfer instructions (AWAITING_FUNDS) is not
	 * changed: the customer may pay the old amount, so it is released and replaced like a confirmed one.
	 */
	private static final Set<StripePaymentIntentStatus> EDITABLE_STATUSES = EnumSet.of(StripePaymentIntentStatus.FAILED,
			StripePaymentIntentStatus.REQUIRES_ACTION);

	private StripeGateway stripeGateway;
	private StripeConfigurationService stripeConfigurationService;
	private StripeAmountConverter stripeAmountConverter;
	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	private StripeOrderPlacementLockService stripeOrderPlacementLockService;
	private StripePaymentIntentRecordService stripePaymentIntentRecordService;
	private StripeOrderAuthorizationService stripeOrderAuthorizationService;
	private StripeLineItemsBuilder stripeLineItemsBuilder;
	private StripeCustomerService stripeCustomerService;
	private StripeCashBalanceService stripeCashBalanceService;
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
		final StripePaymentIntentStatus status = StripePaymentIntentStatus.of(existing);
		if (status == StripePaymentIntentStatus.CANCELED)
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
		if (EDITABLE_STATUSES.contains(status))
		{
			return toData(update(cart, existing, amount, currencyIso));
		}
		if (status == StripePaymentIntentStatus.PROCESSING)
		{
			throw new StripePaymentInProgressException(existingId);
		}
		releaseConfirmedForChangedCart(cart, existingId);
		return toData(createFor(cart, amount, currencyIso));
	}

	/**
	 * Releases a confirmed PaymentIntent (authorized, captured, or with issued payment instructions) of a cart that
	 * changed. An order may be placed for it
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
			scheduleCashBalanceCheck(intent);
		}
	}

	/**
	 * A bank transfer PaymentIntent shows bank transfer instructions, or its charge was paid from the customer balance.
	 * The list of enabled payment method types is not used: with automatic payment methods it names every method.
	 * The refund of a succeeded transfer PaymentIntent does not return to the cash balance. The Refunds API sends it to
	 * the bank account of the customer (Stripe emails the customer); only the Dashboard offers the cash balance as
	 * destination.
	 */
	private static boolean isBankTransfer(final PaymentIntent intent)
	{
		if (intent.getNextAction() != null && "display_bank_transfer_instructions".equals(intent.getNextAction().getType()))
		{
			return true;
		}
		final Charge charge = intent.getLatestChargeObject();
		return charge != null && charge.getPaymentMethodDetails() != null
				&& "customer_balance".equals(charge.getPaymentMethodDetails().getType());
	}

	/**
	 * A cancelled bank transfer PaymentIntent returns the money it received to the cash balance of the customer, and
	 * Stripe sends no event for it. A check of the balance is planned, so that money without an order is refunded. A
	 * failure to plan never breaks the cancel.
	 */
	private void scheduleCashBalanceCheck(final PaymentIntent intent)
	{
		if (StringUtils.isBlank(intent.getCustomer()) || StringUtils.isBlank(intent.getCurrency())
				|| !isBankTransfer(intent))
		{
			return;
		}
		try
		{
			stripeCashBalanceService.scheduleRefundCheck(intent.getCustomer(), intent.getCurrency());
		}
		catch (final RuntimeException e)
		{
			LOG.warn("Cash balance check of Stripe customer {} is not planned after the cancel of {}: {}",
					intent.getCustomer(), intent.getId(), e.getMessage());
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

		final Map<String, Object> lineItems = stripeLineItemsBuilder.build(cart, amount);
		final String customerId = customerIdFor(cart);
		PaymentIntent intent;
		try
		{
			intent = createWithLineItemsFallback(cart, amount, currencyIso, lineItems, customerId,
					customerSuffix(idempotencyKey, customerId));
		}
		catch (final StripeGatewayException e)
		{
			if (customerId == null || !isMissingCustomer(e))
			{
				throw e;
			}
			final String replacement = replaceCustomer(cart, customerId);
			intent = createWithLineItemsFallback(cart, amount, currencyIso, lineItems, replacement,
					customerSuffix(idempotencyKey, replacement));
		}

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

	private PaymentIntent createWithLineItemsFallback(final CartModel cart, final long amount, final String currencyIso,
			final Map<String, Object> lineItems, final String customerId, final String idempotencyKey)
	{
		try
		{
			return createWithOptionsFallback(cart, amount, currencyIso, lineItems, customerId, idempotencyKey);
		}
		catch (final StripeGatewayException e)
		{
			if (lineItems.isEmpty() || !isLineItemsRejection(e))
			{
				throw e;
			}
			LOG.warn("Stripe rejected the line items of cart {}: the payment intent is created without them", cart.getCode());
			return createWithOptionsFallback(cart, amount, currencyIso, Map.of(), customerId, idempotencyKey + "-nolines");
		}
	}

	/**
	 * Creates the PaymentIntent. When Stripe does not know the manual capture option of one payment method (the account
	 * or the API version has no such option), the PaymentIntent is created once more without that option: a method in
	 * the manual capture list never blocks the payment. The second call has its own stable idempotency key.
	 */
	private PaymentIntent createWithOptionsFallback(final CartModel cart, final long amount, final String currencyIso,
			final Map<String, Object> lineItems, final String customerId, final String idempotencyKey)
	{
		try
		{
			return stripeGateway.createPaymentIntent(
					buildCreateParams(cart, amount, currencyIso, lineItems, customerId, Set.of()), idempotencyKey);
		}
		catch (final StripeGatewayException e)
		{
			final String method = unknownPaymentMethodOption(e);
			if (method == null)
			{
				throw e;
			}
			LOG.warn("Stripe does not know the manual capture option of payment method [{}] (property "
					+ "stripe.capture.manual.methods): the payment intent of cart {} is created without it", method,
					cart.getCode());
			return stripeGateway.createPaymentIntent(
					buildCreateParams(cart, amount, currencyIso, lineItems, customerId, Set.of(method)),
					idempotencyKey + "-without-" + method);
		}
	}

	/**
	 * The payment method whose {@code payment_method_options} Stripe does not know ({@code parameter_unknown} on
	 * {@code payment_method_options[<method>]...}), or null for any other error.
	 */
	protected String unknownPaymentMethodOption(final StripeGatewayException e)
	{
		if (!"parameter_unknown".equals(e.getStripeCode()) || !(e.getCause() instanceof InvalidRequestException))
		{
			return null;
		}
		final String param = ((InvalidRequestException) e.getCause()).getParam();
		final String prefix = "payment_method_options[";
		if (!StringUtils.startsWith(param, prefix))
		{
			return null;
		}
		final String method = StringUtils.substringBefore(param.substring(prefix.length()), "]");
		return StringUtils.isBlank(method) ? null : method;
	}

	/**
	 * The Stripe Customer of the cart user. A failure here never blocks the payment: the PaymentIntent is made without a
	 * customer (a method that needs a customer is then refused by Stripe).
	 */
	protected String customerIdFor(final CartModel cart)
	{
		try
		{
			return stripeCustomerService.customerIdFor(cart).orElse(null);
		}
		catch (final RuntimeException e)
		{
			LOG.warn("No Stripe customer for cart {}: the payment intent is made without one ({})", cart.getCode(),
					e.getClass().getSimpleName());
			return null;
		}
	}

	private String replaceCustomer(final CartModel cart, final String missingCustomerId)
	{
		LOG.warn("Stripe customer {} of cart {} is missing at Stripe: a new one is created", missingCustomerId, cart.getCode());
		return stripeCustomerService.replaceCustomer(cart, missingCustomerId);
	}

	/**
	 * True when Stripe says that the customer of the request does not exist.
	 */
	protected boolean isMissingCustomer(final StripeGatewayException e)
	{
		if (!"resource_missing".equals(e.getStripeCode()))
		{
			return false;
		}
		return !(e.getCause() instanceof InvalidRequestException)
				|| "customer".equals(((InvalidRequestException) e.getCause()).getParam());
	}

	protected void ensureRecord(final PaymentIntent intent, final CartModel cart)
	{
		if (stripePaymentIntentRecordDao.findByPaymentIntentId(intent.getId()).isEmpty())
		{
			saveRecord(intent, cart, intent.getAmount(), intent.getCurrency().toUpperCase(), stateOf(intent));
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

	private StripePaymentIntentState stateOf(final PaymentIntent intent)
	{
		switch (StripePaymentIntentStatus.of(intent))
		{
			case AUTHORIZED:
				return StripePaymentIntentState.AUTHORIZED;
			case CAPTURED:
				return StripePaymentIntentState.CAPTURED;
			case PROCESSING:
				return StripePaymentIntentState.PROCESSING;
			case AWAITING_FUNDS:
				return StripePaymentIntentState.AWAITING_FUNDS;
			case CANCELED:
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
		final Map<String, Object> lineItems = stripeLineItemsBuilder.build(cart, amount);
		PaymentIntent updated;
		try
		{
			updated = stripeGateway.updatePaymentIntent(existing.getId(), buildUpdateParams(amount, currencyIso, lineItems),
					idempotencyKey);
		}
		catch (final StripeGatewayException e)
		{
			if (lineItems.isEmpty() || !isLineItemsRejection(e))
			{
				throw e;
			}
			LOG.warn("Stripe rejected the line items of cart {}: the payment intent is updated without them", cart.getCode());
			updated = stripeGateway.updatePaymentIntent(existing.getId(), buildUpdateParams(amount, currencyIso, Map.of()),
					idempotencyKey + "-nolines");
		}

		stripePaymentIntentRecordDao.findByPaymentIntentId(existing.getId()).ifPresent(record -> {
			record.setAmount(amount);
			record.setCurrencyIso(currencyIso);
			modelService.save(record);
		});
		return updated;
	}

	private String customerSuffix(final String idempotencyKey, final String customerId)
	{
		return customerId == null ? idempotencyKey : idempotencyKey + "-cus-" + customerId;
	}

	/**
	 * Update parameters. Without line items, amount_details is cleared: Stripe keeps the old line items otherwise and
	 * refuses a new amount that does not match them.
	 */
	protected PaymentIntentUpdateParams buildUpdateParams(final long amount, final String currencyIso,
			final Map<String, Object> lineItems)
	{
		final PaymentIntentUpdateParams.Builder builder = PaymentIntentUpdateParams.builder().setAmount(amount)
				.setCurrency(currencyIso.toLowerCase());
		if (lineItems.isEmpty())
		{
			builder.putExtraParam("amount_details", "");
		}
		else
		{
			builder.putAllExtraParam(lineItems);
		}
		return builder.build();
	}

	/**
	 * True when Stripe refused the request because of the amount_details parameters.
	 */
	protected boolean isLineItemsRejection(final StripeGatewayException e)
	{
		if (e.getStripeCode() != null && e.getStripeCode().startsWith("amount_details"))
		{
			return true;
		}
		return e.getCause() instanceof InvalidRequestException
				&& StringUtils.startsWith(((InvalidRequestException) e.getCause()).getParam(), "amount_details");
	}

	protected PaymentIntentCreateParams buildCreateParams(final CartModel cart, final long amount, final String currencyIso)
	{
		return buildCreateParams(cart, amount, currencyIso, stripeLineItemsBuilder.build(cart, amount), null);
	}

	protected PaymentIntentCreateParams buildCreateParams(final CartModel cart, final long amount, final String currencyIso,
			final Map<String, Object> lineItems, final String customerId)
	{
		return buildCreateParams(cart, amount, currencyIso, lineItems, customerId, Set.of());
	}

	/**
	 * @param skippedManualMethods
	 *           payment methods of the manual capture list whose option is not sent (Stripe does not know it)
	 */
	protected PaymentIntentCreateParams buildCreateParams(final CartModel cart, final long amount, final String currencyIso,
			final Map<String, Object> lineItems, final String customerId, final Set<String> skippedManualMethods)
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
				.putAllExtraParam(lineItems);
		if (customerId != null)
		{
			builder.setCustomer(customerId);
		}
		if (manual)
		{
			final Set<String> manualMethods = new LinkedHashSet<>(stripeConfigurationService.getManualCaptureMethods());
			manualMethods.removeAll(skippedManualMethods);
			builder.setPaymentMethodOptions(buildPaymentMethodOptions(manualMethods));
		}
		final String suffix = stripeConfigurationService.getStatementDescriptorSuffix();
		if (StringUtils.isNotBlank(suffix))
		{
			builder.setStatementDescriptorSuffix(suffix);
		}
		return builder.build();
	}

	/**
	 * Asks for manual capture for each configured method. Methods with an options class in stripe-java use it; sequra
	 * and scalapay have none, so their options go in as a nested extra parameter, which stripe-java encodes as
	 * payment_method_options[type][capture_method]. Any other type is ignored with a WARN. Mondu is not supported:
	 * Stripe answers parameter_unknown for payment_method_options[mondu].
	 */
	protected PaymentIntentCreateParams.PaymentMethodOptions buildPaymentMethodOptions(final Set<String> manualMethods)
	{
		final PaymentIntentCreateParams.PaymentMethodOptions.Builder builder = PaymentIntentCreateParams.PaymentMethodOptions
				.builder();
		for (final String method : manualMethods)
		{
			switch (method)
			{
				case "card" -> builder.setCard(PaymentIntentCreateParams.PaymentMethodOptions.Card.builder()
						.setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Card.CaptureMethod.MANUAL).build());
				case "link" -> builder.setLink(PaymentIntentCreateParams.PaymentMethodOptions.Link.builder()
						.setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Link.CaptureMethod.MANUAL).build());
				case "paypal" -> builder.setPaypal(PaymentIntentCreateParams.PaymentMethodOptions.Paypal.builder()
						.setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Paypal.CaptureMethod.MANUAL).build());
				case "amazon_pay" -> builder.setAmazonPay(PaymentIntentCreateParams.PaymentMethodOptions.AmazonPay.builder()
						.setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.AmazonPay.CaptureMethod.MANUAL).build());
				case "revolut_pay" -> builder.setRevolutPay(PaymentIntentCreateParams.PaymentMethodOptions.RevolutPay.builder()
						.setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.RevolutPay.CaptureMethod.MANUAL).build());
				case "mobilepay" -> builder.setMobilepay(PaymentIntentCreateParams.PaymentMethodOptions.Mobilepay.builder()
						.setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Mobilepay.CaptureMethod.MANUAL).build());
				case "satispay" -> builder.setSatispay(PaymentIntentCreateParams.PaymentMethodOptions.Satispay.builder()
						.setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Satispay.CaptureMethod.MANUAL).build());
				case "klarna" -> builder.setKlarna(PaymentIntentCreateParams.PaymentMethodOptions.Klarna.builder()
						.setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Klarna.CaptureMethod.MANUAL).build());
				case "billie" -> builder.setBillie(PaymentIntentCreateParams.PaymentMethodOptions.Billie.builder()
						.setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Billie.CaptureMethod.MANUAL).build());
				case "sequra", "scalapay" -> builder.putExtraParam(method, Map.of("capture_method", "manual"));
				default -> LOG.warn("Payment method [{}] in the manual capture list is unknown: ignored", method);
			}
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

	public void setStripeCashBalanceService(final StripeCashBalanceService stripeCashBalanceService)
	{
		this.stripeCashBalanceService = stripeCashBalanceService;
	}

	public void setStripeCustomerService(final StripeCustomerService stripeCustomerService)
	{
		this.stripeCustomerService = stripeCustomerService;
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
