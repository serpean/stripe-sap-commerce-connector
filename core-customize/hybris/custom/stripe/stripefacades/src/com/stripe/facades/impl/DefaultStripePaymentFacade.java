package com.stripe.facades.impl;

import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.facades.StripePaymentFacade;
import com.stripe.facades.data.StripeIntentData;
import com.stripe.facades.data.StripeWebhookResultData;
import com.stripe.core.stripe.StripeClient;
import com.stripe.core.stripe.StripeWebhookProcessResult;
import com.stripe.core.stripe.StripeWebhookService;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.user.CustomerModel;
import de.hybris.platform.order.CartService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.user.UserService;
import de.hybris.platform.store.BaseStoreModel;
import de.hybris.platform.store.services.BaseStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Optional;

public class DefaultStripePaymentFacade implements StripePaymentFacade {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultStripePaymentFacade.class);

    private StripeClient stripeClient;
    private StripeWebhookService stripeWebhookService;
    private CartService cartService;
    private BaseStoreService baseStoreService;
    private UserService userService;
    private ModelService modelService;

    @Override
    public StripeIntentData getIntentForSession() throws StripeException {
        final CartModel cart = getSessionCart();
        final BigDecimal amount = toBigDecimal(cart.getTotalPrice());
        final String currencyIso = cart.getCurrency() != null ? cart.getCurrency().getIsocode() : "USD";
        final var metadata = buildMetadata(cart);
        LOG.debug("Ensuring Stripe PaymentIntent for cart {}", cart.getCode());
        final Optional<PaymentIntent> intent = stripeClient.getOrCreateIntent(null, cart.getCode(), amount,
                currencyIso, null, metadata);

        if (intent.isPresent()) {
            if (cart.getPaymentInfo() != null ) {
                modelService.remove(cart.getPaymentInfo());
            }
            final StripePaymentInfoModel paymentInfoModel = modelService.create(StripePaymentInfoModel.class);
            paymentInfoModel.setCode(intent.get().getId());
            paymentInfoModel.setStripePaymentIntentId(intent.get().getId());
            paymentInfoModel.setUser(cart.getUser());
            modelService.save(paymentInfoModel);
            cart.setPaymentInfo(paymentInfoModel);
            modelService.save(cart);
        }

        return intent.map(this::toIntentData).orElseThrow(IllegalStateException::new);
    }

    @Override
    public StripeWebhookResultData processWebhook(final String payload, final String signature) throws StripeException {
        final Event event = stripeClient.parseWebhook(payload, signature);
        LOG.info("Processing Stripe webhook event {}", event.getId());
        final StripeWebhookProcessResult coreResult = stripeWebhookService != null
                ? stripeWebhookService.processEvent(event)
                : StripeWebhookProcessResult.ack("No webhook service configured");
        final StripeWebhookResultData resultData = new StripeWebhookResultData();
        resultData.setEventId(event.getId());
        resultData.setProcessed(coreResult.isProcessed());
        resultData.setMessage(coreResult.getMessage());
        return resultData;
    }

    public void setStripeClient(final StripeClient stripeClient) {
        this.stripeClient = stripeClient;
    }

    public void setStripeWebhookService(final StripeWebhookService stripeWebhookService) {
        this.stripeWebhookService = stripeWebhookService;
    }

    public void setCartService(final CartService cartService) {
        this.cartService = cartService;
    }

    public void setBaseStoreService(final BaseStoreService baseStoreService) {
        this.baseStoreService = baseStoreService;
    }

    public void setUserService(final UserService userService) {
        this.userService = userService;
    }

    public void setModelService(final ModelService modelService) {
        this.modelService = modelService;
    }

    protected CartModel getSessionCart() {
        if (!cartService.hasSessionCart()) {
            throw new IllegalStateException("No session cart available for Stripe intent creation");
        }
        return cartService.getSessionCart();
    }

    protected StripeIntentData toIntentData(final PaymentIntent intent) {
        final StripeIntentData data = new StripeIntentData();
        data.setIntentId(intent.getId());
        data.setClientSecret(intent.getClientSecret());
        data.setStatus(intent.getStatus());
        return data;
    }

    protected BigDecimal toBigDecimal(final Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    protected HashMap<String, String> buildMetadata(final CartModel cart) {
        final HashMap<String, String> metadata = new HashMap<>();
        metadata.put("cartCode", cart.getCode());
        final BaseStoreModel baseStore = baseStoreService.getCurrentBaseStore();
        if (baseStore != null) {
            metadata.put("siteUid", baseStore.getUid());
        }
        final Object currentUser = userService.getCurrentUser();
        final CustomerModel customer = currentUser instanceof CustomerModel ? (CustomerModel) currentUser : null;
        if (customer != null) {
            metadata.put("userUid", customer.getUid());
            if (!userService.isAnonymousUser(customer)) {
                metadata.put("customerId", customer.getCustomerID());
            }
        }
        return metadata;
    }
}
