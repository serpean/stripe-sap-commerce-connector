package com.stripe.core.stripe;

import com.stripe.Stripe;
import com.stripe.core.config.StripeConfigurationService;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class StripeClient {
    private static final Logger LOG = LoggerFactory.getLogger(StripeClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 250L;
    private static final long MAX_BACKOFF_MS = TimeUnit.SECONDS.toMillis(5);
    private static final int MAX_METADATA_LENGTH = 500;

    private StripeConfigurationService stripeConfigurationService;

    /**
     * Ensures the PaymentIntent exists and is consistent with the desired amount/metadata.
     * <p>
     * Stripe constraint:
     * - amount cannot be updated once PaymentIntent is in requires_capture.
     * <p>
     * Operational logic:
     * - requires_capture and amount decreased => capture partial
     * - requires_capture and amount increased => cancel + recreate
     */
    public Optional<PaymentIntent> getOrCreateIntent(final String existingIntentId,
                                                     final String cartCode,
                                                     final BigDecimal amount,
                                                     final String currencyIso,
                                                     final String captureMode,
                                                     final Map<String, String> metadata) throws StripeException {
        final String resolvedCurrency = StringUtils.defaultIfBlank(currencyIso, "usd");

        final Map<String, String> metadataMap = sanitizeMetadata(metadata);
        if (StringUtils.isNotBlank(cartCode)) {
            metadataMap.putIfAbsent("cartCode", cartCode);
        }

        if (StringUtils.isNotBlank(existingIntentId)) {
            final RequestOptions retrieveOptions = buildRequestOptions(existingIntentId, "intent-retrieve");
            final PaymentIntent intent = executeWithRetry(() -> PaymentIntent.retrieve(existingIntentId, retrieveOptions),
                    existingIntentId, "intent-retrieve");
            return Optional.of(intent);
        }

        // Create a new PI
        final PaymentIntentCreateParams.Builder createParams = PaymentIntentCreateParams.builder()
                .setAmount(toStripeAmount(amount, resolvedCurrency))
                .setCurrency(StringUtils.lowerCase(resolvedCurrency))
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.ALWAYS)
                                .build()
                );

        // Set payment_method_options with capture_method per payment type
        final Set<String> manualCaptureMethods = stripeConfigurationService.getManualCaptureMethods();
        createParams.setPaymentMethodOptions(buildPaymentMethodOptions(manualCaptureMethods));

        final String statementDescriptor = stripeConfigurationService.getStatementDescriptor();
        if (StringUtils.isNotBlank(statementDescriptor)) {
            createParams.setStatementDescriptor(statementDescriptor);
        }
        final String descriptorSuffix = stripeConfigurationService.getStatementDescriptorSuffix();
        if (StringUtils.isNotBlank(descriptorSuffix)) {
            createParams.setStatementDescriptorSuffix(descriptorSuffix);
        }
        metadataMap.forEach(createParams::putMetadata);

        final RequestOptions createOptions = buildRequestOptions(cartCode, "intent-create");
        final PaymentIntent created = executeWithRetry(() -> PaymentIntent.create(createParams.build(), createOptions),
                cartCode, "intent-create");
        return Optional.ofNullable(created);
    }

    public PaymentIntent capture(final String intentId, final BigDecimal amount) throws StripeException {
        final RequestOptions options = buildRequestOptions(intentId, "intent-capture");
        final PaymentIntent paymentIntent = executeWithRetry(() -> PaymentIntent.retrieve(intentId, options),
                intentId, "intent-retrieve");
        final PaymentIntentCaptureParams.Builder builder = PaymentIntentCaptureParams.builder();
        if (amount != null) {
            builder.setAmountToCapture(toStripeAmount(amount, paymentIntent.getCurrency()));
        }
        return executeWithRetry(() -> paymentIntent.capture(builder.build(), options),
                intentId, "intent-capture");
    }

    public PaymentIntent cancel(final String intentId) throws StripeException {
        final RequestOptions options = buildRequestOptions(intentId, "intent-cancel");
        final PaymentIntent paymentIntent = executeWithRetry(() -> PaymentIntent.retrieve(intentId, options),
                intentId, "intent-retrieve");
        return executeWithRetry(() -> paymentIntent.cancel(PaymentIntentCancelParams.builder().build(), options),
                intentId, "intent-cancel");
    }

    public Refund refund(final String paymentIntentId, final BigDecimal amount) throws StripeException {
        final RequestOptions retrieveOptions = buildRequestOptions(paymentIntentId, "intent-retrieve");
        final PaymentIntent paymentIntent = executeWithRetry(() -> PaymentIntent.retrieve(paymentIntentId, retrieveOptions),
                paymentIntentId, "intent-retrieve");
        final RefundCreateParams.Builder builder = RefundCreateParams.builder()
                .setPaymentIntent(paymentIntentId);
        final String refundOperationSuffix;
        if (amount != null) {
            builder.setAmount(toStripeAmount(amount, paymentIntent.getCurrency()));
            refundOperationSuffix = "intent-refund-" + amount.stripTrailingZeros().toPlainString();
        } else {
            refundOperationSuffix = "intent-refund-full";
        }
        final RequestOptions refundOptions = buildRequestOptions(paymentIntentId, refundOperationSuffix);
        return executeWithRetry(() -> Refund.create(builder.build(), refundOptions),
                paymentIntentId, "intent-refund");
    }

    public Event parseWebhook(final String payload, final String signatureHeader) throws StripeException {
        final String webhookSecret = stripeConfigurationService.getWebhookSecret();
        if (StringUtils.isBlank(webhookSecret)) {
            throw new AuthenticationException("Missing stripe.webhook.secret", null, null, 0, null);
        }
        return Webhook.constructEvent(payload, signatureHeader, webhookSecret);
    }

    protected long toStripeAmount(final BigDecimal amount, final String currencyIso) {
        if (amount == null) {
            return 0L;
        }
        final int fractionDigits = CurrencyUtils.resolveFractionDigits(currencyIso);
        return amount.setScale(fractionDigits, RoundingMode.HALF_UP)
                .movePointRight(fractionDigits)
                .longValueExact();
    }

    protected PaymentIntentCreateParams.CaptureMethod resolveCaptureMethod(final String captureMode) {
        return "manual".equalsIgnoreCase(captureMode)
                ? PaymentIntentCreateParams.CaptureMethod.MANUAL
                : PaymentIntentCreateParams.CaptureMethod.AUTOMATIC;
    }

    /**
     * Builds payment_method_options with capture_method configured per payment type.
     * Methods in manualCaptureMethods list get manual capture, others get automatic.
     */
    protected PaymentIntentCreateParams.PaymentMethodOptions buildPaymentMethodOptions(
            final Set<String> manualCaptureMethods) {
        final PaymentIntentCreateParams.PaymentMethodOptions.Builder builder =
                PaymentIntentCreateParams.PaymentMethodOptions.builder();

        if (manualCaptureMethods.contains("card")) {
            builder.setCard(PaymentIntentCreateParams.PaymentMethodOptions.Card.builder()
                    .setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Card.CaptureMethod.MANUAL)
                    .build());
        }
        if (manualCaptureMethods.contains("paypal")) {
            builder.setPaypal(PaymentIntentCreateParams.PaymentMethodOptions.Paypal.builder()
                    .setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Paypal.CaptureMethod.MANUAL)
                    .build());
        }
        if (manualCaptureMethods.contains("klarna")) {
            builder.setKlarna(PaymentIntentCreateParams.PaymentMethodOptions.Klarna.builder()
                    .setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Klarna.CaptureMethod.MANUAL)
                    .build());
        }
        if (manualCaptureMethods.contains("affirm")) {
            builder.setAffirm(PaymentIntentCreateParams.PaymentMethodOptions.Affirm.builder()
                    .setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Affirm.CaptureMethod.MANUAL)
                    .build());
        }
        if (manualCaptureMethods.contains("afterpay_clearpay")) {
            builder.setAfterpayClearpay(PaymentIntentCreateParams.PaymentMethodOptions.AfterpayClearpay.builder()
                    .setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.AfterpayClearpay.CaptureMethod.MANUAL)
                    .build());
        }

        return builder.build();
    }

    protected RequestOptions buildRequestOptions(final String businessReference, final String operation) {
        final String apiKey = stripeConfigurationService.getSecretKey();
        Stripe.apiKey = apiKey;
        final String idempotencyKey = generateIdempotencyKey(businessReference, operation);
        return RequestOptions.builder()
                .setApiKey(apiKey)
                .setIdempotencyKey(idempotencyKey)
                .build();
    }

    protected String generateIdempotencyKey(final String reference, final String operation) {
        final String base = StringUtils.joinWith("-",
                StringUtils.defaultIfBlank(reference, UUID.randomUUID().toString()),
                UUID.randomUUID().toString(),
                operation);
        return StringUtils.left(base.replaceAll("[^A-Za-z0-9_-]", ""), 255);
    }

    protected Map<String, String> sanitizeMetadata(final Map<String, String> metadata) {
        final Map<String, String> sanitized = new HashMap<>();
        if (metadata == null) {
            return sanitized;
        }
        metadata.forEach((key, value) -> {
            if (StringUtils.isNotBlank(key) && value != null) {
                sanitized.put(key, StringUtils.abbreviate(value, MAX_METADATA_LENGTH));
            }
        });
        return sanitized;
    }

    protected boolean shouldRetry(final StripeException exception) {
        final Integer statusCode = exception.getStatusCode();
        return statusCode != null && (statusCode == 429 || statusCode >= 500);
    }

    protected <T> T executeWithRetry(final StripeCallable<T> callable,
                                     final String reference,
                                     final String operation) throws StripeException {
        StripeException lastException = null;
        long backoff = BASE_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return callable.call();
            } catch (StripeException ex) {
                lastException = ex;
                if (!shouldRetry(ex) || attempt == MAX_ATTEMPTS) {
                    throw ex;
                }
                LOG.warn("Retrying Stripe operation {} for reference {} (attempt {}/{}) due to status {}",
                        operation, reference, attempt, MAX_ATTEMPTS, ex.getStatusCode());
                sleep(backoff);
                backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
            }
        }
        throw lastException;
    }

    private void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void setStripeConfigurationService(final StripeConfigurationService stripeConfigurationService) {
        this.stripeConfigurationService = stripeConfigurationService;
    }

    protected Long getEffectiveAuthorizedAmount(final PaymentIntent intent) {
        if (intent == null) {
            return null;
        }
        final Long capturable = intent.getAmountCapturable();
        return (capturable != null && capturable > 0) ? capturable : intent.getAmount();
    }

    @FunctionalInterface
    protected interface StripeCallable<T> {
        T call() throws StripeException;
    }
}

