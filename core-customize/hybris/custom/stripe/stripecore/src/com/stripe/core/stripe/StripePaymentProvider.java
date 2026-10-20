package com.stripe.core.stripe;

import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.core.config.StripeConfigurationService;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.order.payment.PaymentInfoModel;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.model.ModelService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Stream;

import static com.stripe.core.constants.StripeCoreConstants.STRIPE_PROVIDER;

/**
 * Payment provider bridge to Stripe. Full implementation to be delivered in later iterations.
 */
public class StripePaymentProvider {
    private static final Logger LOG = LoggerFactory.getLogger(StripePaymentProvider.class);

    private StripeClient stripeClient;
    private StripeConfigurationService stripeConfigurationService;
    private ModelService modelService;


    /**
     * Checks if the cart/order has an accepted authorization entry.
     *
     * @param order the cart/order model
     * @return true if an accepted authorization exists
     */
    public boolean hasAcceptedAuthorization(final AbstractOrderModel order) {
        return Optional.ofNullable(order.getPaymentTransactions()).stream()
                .flatMap(Collection::stream)
                .filter(tx -> STRIPE_PROVIDER.equalsIgnoreCase(tx.getPaymentProvider()))
                .flatMap(tx -> tx.getEntries() != null ? tx.getEntries().stream() : Stream.empty())
                .anyMatch(entry -> PaymentTransactionType.AUTHORIZATION.equals(entry.getType())
                        && TransactionStatus.ACCEPTED.name().equals(entry.getTransactionStatus()));
    }

    public PaymentTransactionEntryModel authorize(final AbstractOrderModel order) {
        LOG.debug("Authorizing Stripe payment for order {}", order.getCode());
        final BigDecimal amount = toBigDecimal(order.getTotalPrice());
        final Optional<PaymentTransactionEntryModel> oAuthorizedEntry = Optional.ofNullable(order.getPaymentTransactions()).stream()
                .flatMap(Collection::stream)
                .flatMap(tx -> tx.getEntries() != null ? tx.getEntries().stream() : java.util.stream.Stream.empty())
                .filter(entry -> PaymentTransactionType.AUTHORIZATION.equals(entry.getType())
                        && TransactionStatus.ACCEPTED.name().equals(entry.getTransactionStatus()))
                .findAny();

        if (oAuthorizedEntry.isPresent()) {
            return oAuthorizedEntry.get();
        }
        try {
            final Map<String, String> metadata = buildMetadata(order);
            final String currencyIso = order.getCurrency() != null ? order.getCurrency().getIsocode() : "USD";
            // TODO simplify logic removing transaction if payment fails
            final Optional<PaymentIntent> paymentIntent = stripeClient.getOrCreateIntent(resolvePaymentIntentId(order),
                    order.getCode(), amount, currencyIso, stripeConfigurationService.getCaptureMode().name().toLowerCase(Locale.ROOT), metadata);
            if (paymentIntent.isPresent()) {
                final PaymentIntent intent = paymentIntent.get();
                final PaymentTransactionEntryModel entry = createEntry(order, PaymentTransactionType.AUTHORIZATION, amount);
                applyIntentToEntry(entry, intent, mapIntentStatus(intent.getStatus()));
                return entry;
            }
        } catch (StripeException ex) {
            LOG.error("Stripe authorization failed for order {}", order.getCode(), ex);
        }
        return null;
    }


    public PaymentTransactionEntryModel refund(final OrderModel order, final BigDecimal amount) {
        final BigDecimal refundAmount = amount != null ? amount : toBigDecimal(order.getTotalPrice());
        LOG.debug("Refunding Stripe payment for order {} amount {}", order.getCode(), refundAmount);
        final PaymentTransactionEntryModel entry = createEntry(order, PaymentTransactionType.REFUND_FOLLOW_ON, refundAmount);
        final String intentId = resolvePaymentIntentId(order);
        if (StringUtils.isBlank(intentId)) {
            markEntryRejected(entry, "missing_intent");
            modelService.save(entry);
            return entry;
        }
        try {
            final Refund refund = stripeClient.refund(intentId, refundAmount);
            entry.setRequestId(refund.getId());
            entry.setTransactionStatus(mapRefundStatus(refund.getStatus()).name());
            entry.setTransactionStatusDetails(refund.getStatus());
        } catch (StripeException ex) {
            LOG.error("Stripe refund failed for order {}", order.getCode(), ex);
            markEntryRejected(entry, ex.getCode());
        }
        modelService.save(entry);
        return entry;
    }

    protected PaymentTransactionEntryModel createEntry(final AbstractOrderModel order, final PaymentTransactionType type,
                                                       final BigDecimal amount) {
        final PaymentTransactionModel transaction = resolveTransaction(order);
        final PaymentTransactionEntryModel entry = modelService.create(PaymentTransactionEntryModel.class);
        entry.setPaymentTransaction(transaction);
        entry.setType(type);
        entry.setAmount(toHybrisAmount(amount, order.getCurrency()));
        entry.setCurrency(order.getCurrency());
        entry.setTime(new Date());
        entry.setCode(order.getCode() + "-" + type.name() + "-" + System.currentTimeMillis());
        entry.setTransactionStatus(TransactionStatus.REVIEW.name());
        entry.setTransactionStatusDetails(TransactionStatusDetails.UNKNOWN_CODE.name());
        modelService.save(entry);
        return entry;
    }

    protected PaymentTransactionModel resolveTransaction(final AbstractOrderModel order) {
        if (order.getPaymentTransactions() != null && !order.getPaymentTransactions().isEmpty()) {
            return order.getPaymentTransactions().get(0);
        }
        final PaymentTransactionModel transaction = modelService.create(PaymentTransactionModel.class);
        transaction.setCode(order.getCode() + "-STRIPE");
        transaction.setOrder(order);
        transaction.setPaymentProvider(STRIPE_PROVIDER);
        final PaymentInfoModel paymentInfo = order.getPaymentInfo();
        if (paymentInfo != null) {
            transaction.setInfo(paymentInfo);
        }
        transaction.setPlannedAmount(BigDecimal.valueOf(order.getTotalPrice()));
        transaction.setCurrency(order.getCurrency());
        modelService.save(transaction);
        modelService.refresh(order);
        return transaction;
    }

    protected String resolvePaymentIntentId(final AbstractOrderModel order) {
        return Optional.ofNullable(order.getPaymentInfo())
                .filter(info -> info instanceof StripePaymentInfoModel)
                .map(info -> (StripePaymentInfoModel) info)
                .map(StripePaymentInfoModel::getStripePaymentIntentId)
                .filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalStateException("No Stripe PaymentInfo with PaymentIntent ID associated with order " + order.getCode()));
    }

    protected void applyIntentToEntry(final PaymentTransactionEntryModel entry, final PaymentIntent intent,
                                      final TransactionStatus status) {
        entry.setRequestId(intent.getId());
        entry.setTransactionStatus(status.name());
        entry.setTransactionStatusDetails(intent.getStatus());

        final PaymentTransactionModel transaction = entry.getPaymentTransaction();
        if (transaction != null) {
            transaction.setRequestId(intent.getId());
        }
    }

    protected void markEntryRejected(final PaymentTransactionEntryModel entry, final String details) {
        entry.setTransactionStatus(TransactionStatus.REJECTED.name());
        entry.setTransactionStatusDetails(StringUtils.defaultIfBlank(details, TransactionStatusDetails.UNKNOWN_CODE.name()));
    }

    protected TransactionStatus mapIntentStatus(final String stripeStatus) {
        if ("succeeded".equalsIgnoreCase(stripeStatus) || "requires_capture".equalsIgnoreCase(stripeStatus)) {
            return TransactionStatus.ACCEPTED;
        }
        if ("requires_action".equalsIgnoreCase(stripeStatus) || "processing".equalsIgnoreCase(stripeStatus)) {
            return TransactionStatus.REVIEW;
        }
        if ("requires_payment_method".equalsIgnoreCase(stripeStatus)) {
            return TransactionStatus.REJECTED;
        }
        return TransactionStatus.REVIEW;
    }

    protected TransactionStatus mapCaptureStatus(final String stripeStatus) {
        if ("succeeded".equalsIgnoreCase(stripeStatus)) {
            return TransactionStatus.ACCEPTED;
        }
        if ("requires_capture".equalsIgnoreCase(stripeStatus)) {
            return TransactionStatus.REVIEW;
        }
        if ("canceled".equalsIgnoreCase(stripeStatus) || "requires_payment_method".equalsIgnoreCase(stripeStatus)) {
            return TransactionStatus.REJECTED;
        }
        return TransactionStatus.REVIEW;
    }

    protected TransactionStatus mapCancelStatus(final String stripeStatus) {
        if ("canceled".equalsIgnoreCase(stripeStatus)) {
            return TransactionStatus.ACCEPTED;
        }
        return TransactionStatus.REVIEW;
    }

    protected TransactionStatus mapRefundStatus(final String refundStatus) {
        if ("succeeded".equalsIgnoreCase(refundStatus)) {
            return TransactionStatus.ACCEPTED;
        }
        if ("failed".equalsIgnoreCase(refundStatus)) {
            return TransactionStatus.REJECTED;
        }
        return TransactionStatus.REVIEW;
    }

    protected Map<String, String> buildMetadata(final AbstractOrderModel order) {
        final Map<String, String> metadata = new HashMap<>();
        metadata.put("orderCode", order.getCode());
        if (order.getSite() != null) {
            metadata.put("siteUid", order.getSite().getUid());
        }
        if (order.getUser() != null) {
            metadata.put("userId", order.getUser().getUid());
        }
        return metadata;
    }

    protected BigDecimal toBigDecimal(final Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    protected BigDecimal toHybrisAmount(final BigDecimal amount, final CurrencyModel currency) {
        if (amount == null) {
            return null;
        }
        final int digits = currency != null && currency.getDigits() != null ? currency.getDigits() : 2;
        return amount.setScale(digits, RoundingMode.HALF_UP);
    }

    public void setStripeConfigurationService(final StripeConfigurationService stripeConfigurationService) {
        this.stripeConfigurationService = stripeConfigurationService;
    }

    public void setStripeClient(final StripeClient stripeClient) {
        this.stripeClient = stripeClient;
    }

    public void setModelService(final ModelService modelService) {
        this.modelService = modelService;
    }
}
