package com.stripe.core.order.impl;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.constants.StripeCoreConstants;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.order.StripeOrderPlacementLockService;
import com.stripe.core.order.StripeOrderPlacementService;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
import de.hybris.platform.commerceservices.enums.SalesApplication;
import de.hybris.platform.commerceservices.impersonation.ImpersonationContext;
import de.hybris.platform.commerceservices.impersonation.ImpersonationService;
import de.hybris.platform.commerceservices.order.CommerceCheckoutService;
import de.hybris.platform.commerceservices.service.data.CommerceCheckoutParameter;
import de.hybris.platform.commerceservices.service.data.CommerceOrderResult;
import de.hybris.platform.core.model.c2l.LanguageModel;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.order.InvalidCartException;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.site.BaseSiteService;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link StripeOrderPlacementService}. It places the order as the customer of the cart, in the site, currency
 * and language of the cart.
 * <p>
 * It takes the order placement lock before it looks at the cart. So when the customer placed the order at the same
 * time, this service sees the lock (or the order) and never releases the funds of a placed order. The cart is found by
 * its code and site with a FlexibleSearch, because a cart service needs a session user or the user model, and the
 * webhook has neither. The cart must hold the PaymentIntent in its Stripe payment info.
 * <p>
 * When there is no cart for the PaymentIntent, or the amount or currency of the PaymentIntent is not the charge amount
 * of the cart, the funds are released and no order is placed. The lock stays: the decision for the PaymentIntent is
 * made. Any other failure after the lock is thrown, so the webhook runner tries again; the lock is given back only
 * when no live order exists for the PaymentIntent.
 */
public class DefaultStripeOrderPlacementService implements StripeOrderPlacementService
{
	static final String CART_NOT_FOUND = "cart-not-found";
	static final String AMOUNT_MISMATCH = "amount-mismatch";

	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeOrderPlacementService.class);

	private static final String CART_BY_CODE_AND_SITE = "SELECT {pk} FROM {Cart} WHERE {code} = ?code AND {site} = ?site";
	private static final Set<StripePaymentIntentStatus> PLACEABLE = EnumSet.of(StripePaymentIntentStatus.AUTHORIZED,
			StripePaymentIntentStatus.CAPTURED, StripePaymentIntentStatus.PROCESSING);

	private FlexibleSearchService flexibleSearchService;
	private BaseSiteService baseSiteService;
	private ImpersonationService impersonationService;
	private CommerceCheckoutService commerceCheckoutService;
	private StripeOrderPlacementLockService stripeOrderPlacementLockService;
	private StripePaymentIntentRecordService stripePaymentIntentRecordService;
	private StripePaymentIntentService stripePaymentIntentService;
	private StripeAmountConverter stripeAmountConverter;
	private ModelService modelService;

	@Override
	public Optional<OrderModel> placeOrderFor(final PaymentIntent paymentIntent)
	{
		final String paymentIntentId = paymentIntent.getId();
		final StripePaymentIntentStatus status = StripePaymentIntentStatus.of(paymentIntent.getStatus());
		if (!PLACEABLE.contains(status))
		{
			LOG.info("Payment intent {} has status {}: no order is placed", paymentIntentId, status);
			return Optional.empty();
		}
		if (!stripeOrderPlacementLockService.tryAcquire(paymentIntentId))
		{
			LOG.info("Order placement of payment intent {} runs on another path", paymentIntentId);
			return Optional.empty();
		}
		try
		{
			return placeUnderLock(paymentIntent, status);
		}
		catch (final RuntimeException e)
		{
			afterFailedPlacement(paymentIntentId);
			throw e;
		}
	}

	protected Optional<OrderModel> placeUnderLock(final PaymentIntent paymentIntent, final StripePaymentIntentStatus status)
	{
		final String paymentIntentId = paymentIntent.getId();
		final Optional<OrderModel> existing = stripePaymentIntentRecordService.findLiveOrder(paymentIntentId);
		if (existing.isPresent())
		{
			LOG.info("Order {} already exists for payment intent {}: no new order", existing.get().getCode(), paymentIntentId);
			stripePaymentIntentRecordService.linkOrder(paymentIntentId, existing.get());
			return existing;
		}

		final Optional<CartModel> found = findCart(paymentIntent);
		if (found.isEmpty())
		{
			stripePaymentIntentService.releaseFunds(paymentIntentId, CART_NOT_FOUND);
			return Optional.empty();
		}
		final CartModel cart = found.get();
		if (!matchesCart(paymentIntent, cart))
		{
			LOG.warn("Payment intent {} has {} {} but cart {} must charge {} {}: releasing the funds", paymentIntentId,
					paymentIntent.getAmount(), paymentIntent.getCurrency(), cart.getCode(),
					stripeAmountConverter.chargeAmountMinor(cart), cart.getCurrency().getIsocode());
			stripePaymentIntentService.releaseFunds(paymentIntentId, AMOUNT_MISMATCH);
			detachPaymentIntent(cart);
			return Optional.empty();
		}

		final OrderModel order = placeInCartContext(cart, paymentIntentId, status);
		stripePaymentIntentRecordService.linkOrder(paymentIntentId, order);
		modelService.remove(cart);
		LOG.info("Order {} placed from cart {} for payment intent {}", order.getCode(), cart.getCode(), paymentIntentId);
		return Optional.of(order);
	}

	protected OrderModel placeInCartContext(final CartModel cart, final String paymentIntentId,
			final StripePaymentIntentStatus status)
	{
		final ImpersonationContext context = new ImpersonationContext();
		context.setOrder(cart);
		context.setSite(cart.getSite());
		context.setUser(cart.getUser());
		context.setCurrency(cart.getCurrency());
		context.setLanguage(languageOf(cart));
		try
		{
			return impersonationService.<OrderModel, InvalidCartException> executeInContext(context, () -> {
				authorize(cart, paymentIntentId, status);
				return placeOrder(cart);
			});
		}
		catch (final InvalidCartException e)
		{
			throw new IllegalStateException(
					"Cart " + cart.getCode() + " of payment intent " + paymentIntentId + " cannot be placed", e);
		}
	}

	/**
	 * Authorizes a paid PaymentIntent as the checkout does. A processing payment gets no AUTHORIZATION entry: the order
	 * waits for the payment.
	 */
	protected void authorize(final CartModel cart, final String paymentIntentId, final StripePaymentIntentStatus status)
	{
		if (status == StripePaymentIntentStatus.PROCESSING)
		{
			LOG.info("Payment intent {} of cart {} is processing: the order is placed without authorization",
					paymentIntentId, cart.getCode());
			return;
		}
		if (hasAcceptedAuthorization(cart, paymentIntentId))
		{
			LOG.info("Cart {} already has an accepted authorization of payment intent {}", cart.getCode(), paymentIntentId);
			return;
		}
		final CommerceCheckoutParameter parameter = new CommerceCheckoutParameter();
		parameter.setCart(cart);
		parameter.setEnableHooks(true);
		parameter.setAuthorizationAmount(stripeAmountConverter.chargeAmount(cart));
		parameter.setPaymentProvider(StripeCoreConstants.STRIPE_PROVIDER);
		final PaymentTransactionEntryModel entry = commerceCheckoutService.authorizePayment(parameter);
		if (!isAccepted(entry))
		{
			throw new IllegalStateException("Authorization of payment intent " + paymentIntentId + " for cart "
					+ cart.getCode() + " was not accepted: " + (entry == null ? "no entry" : entry.getTransactionStatus()));
		}
	}

	protected OrderModel placeOrder(final CartModel cart) throws InvalidCartException
	{
		final CommerceCheckoutParameter parameter = new CommerceCheckoutParameter();
		parameter.setCart(cart);
		parameter.setSalesApplication(SalesApplication.WEB);
		parameter.setEnableHooks(true);
		final CommerceOrderResult result = commerceCheckoutService.placeOrder(parameter);
		if (result == null || result.getOrder() == null)
		{
			throw new IllegalStateException("Placement of cart " + cart.getCode() + " gave no order");
		}
		return result.getOrder();
	}

	/**
	 * The cart of the PaymentIntent metadata, only when its Stripe payment info holds the PaymentIntent. A cart that holds
	 * another PaymentIntent is not the cart of this payment.
	 */
	protected Optional<CartModel> findCart(final PaymentIntent paymentIntent)
	{
		final Map<String, String> metadata = paymentIntent.getMetadata();
		final String cartCode = metadata == null ? null : metadata.get("cartCode");
		final String siteUid = metadata == null ? null : metadata.get("baseSiteUid");
		final BaseSiteModel site = StringUtils.isBlank(siteUid) ? null : baseSiteService.getBaseSiteForUID(siteUid);
		if (StringUtils.isBlank(cartCode) || site == null)
		{
			LOG.warn("Payment intent {} names cart {} of site {}, which is not found: releasing the funds",
					paymentIntent.getId(), cartCode, siteUid);
			return Optional.empty();
		}

		final FlexibleSearchQuery query = new FlexibleSearchQuery(CART_BY_CODE_AND_SITE);
		query.addQueryParameter("code", cartCode);
		query.addQueryParameter("site", site);
		final List<CartModel> carts = flexibleSearchService.<CartModel> search(query).getResult();
		if (carts.isEmpty())
		{
			LOG.warn("Cart {} of site {} for payment intent {} is not found: releasing the funds", cartCode, siteUid,
					paymentIntent.getId());
			return Optional.empty();
		}
		if (carts.size() > 1)
		{
			throw new IllegalStateException("Site " + siteUid + " has " + carts.size() + " carts with code " + cartCode);
		}
		final CartModel cart = carts.get(0);
		final String cartPaymentIntentId = cart.getPaymentInfo() instanceof StripePaymentInfoModel
				? ((StripePaymentInfoModel) cart.getPaymentInfo()).getStripePaymentIntentId() : null;
		if (!paymentIntent.getId().equals(cartPaymentIntentId))
		{
			LOG.warn("Cart {} holds payment intent {}, not {}: releasing the funds of {}", cartCode, cartPaymentIntentId,
					paymentIntent.getId(), paymentIntent.getId());
			return Optional.empty();
		}
		return Optional.of(cart);
	}

	/**
	 * Takes the released PaymentIntent off the cart, so the next checkout of the cart creates a new one.
	 */
	protected void detachPaymentIntent(final CartModel cart)
	{
		final StripePaymentInfoModel paymentInfo = (StripePaymentInfoModel) cart.getPaymentInfo();
		paymentInfo.setStripePaymentIntentId(null);
		modelService.save(paymentInfo);
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
		}
		else
		{
			stripeOrderPlacementLockService.release(paymentIntentId);
		}
	}

	private LanguageModel languageOf(final CartModel cart)
	{
		final LanguageModel language = cart.getUser() == null ? null : cart.getUser().getSessionLanguage();
		if (language != null)
		{
			return language;
		}
		return cart.getSite() == null ? null : cart.getSite().getDefaultLanguage();
	}

	private boolean matchesCart(final PaymentIntent paymentIntent, final CartModel cart)
	{
		return paymentIntent.getAmount() != null && paymentIntent.getAmount() == stripeAmountConverter.chargeAmountMinor(cart)
				&& cart.getCurrency().getIsocode().equalsIgnoreCase(paymentIntent.getCurrency());
	}

	private boolean hasAcceptedAuthorization(final CartModel cart, final String paymentIntentId)
	{
		return cart.getPaymentTransactions() != null && cart.getPaymentTransactions().stream()
				.filter(transaction -> StripeCoreConstants.STRIPE_PROVIDER.equals(transaction.getPaymentProvider()))
				.filter(transaction -> transaction.getEntries() != null)
				.flatMap(transaction -> transaction.getEntries().stream())
				.anyMatch(entry -> PaymentTransactionType.AUTHORIZATION.equals(entry.getType())
						&& paymentIntentId.equals(entry.getRequestId()) && isAccepted(entry));
	}

	private static boolean isAccepted(final PaymentTransactionEntryModel entry)
	{
		return entry != null && TransactionStatus.ACCEPTED.name().equals(entry.getTransactionStatus());
	}

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}

	public void setBaseSiteService(final BaseSiteService baseSiteService)
	{
		this.baseSiteService = baseSiteService;
	}

	public void setImpersonationService(final ImpersonationService impersonationService)
	{
		this.impersonationService = impersonationService;
	}

	public void setCommerceCheckoutService(final CommerceCheckoutService commerceCheckoutService)
	{
		this.commerceCheckoutService = commerceCheckoutService;
	}

	public void setStripeOrderPlacementLockService(final StripeOrderPlacementLockService stripeOrderPlacementLockService)
	{
		this.stripeOrderPlacementLockService = stripeOrderPlacementLockService;
	}

	public void setStripePaymentIntentRecordService(final StripePaymentIntentRecordService stripePaymentIntentRecordService)
	{
		this.stripePaymentIntentRecordService = stripePaymentIntentRecordService;
	}

	public void setStripePaymentIntentService(final StripePaymentIntentService stripePaymentIntentService)
	{
		this.stripePaymentIntentService = stripePaymentIntentService;
	}

	public void setStripeAmountConverter(final StripeAmountConverter stripeAmountConverter)
	{
		this.stripeAmountConverter = stripeAmountConverter;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}
}
