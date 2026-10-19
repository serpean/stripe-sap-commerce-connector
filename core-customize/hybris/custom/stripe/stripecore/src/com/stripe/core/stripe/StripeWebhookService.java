package com.stripe.core.stripe;

import com.stripe.model.*;
import de.hybris.platform.assistedserviceservices.AssistedServiceService;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.order.payment.PaymentInfoModel;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.exceptions.ModelNotFoundException;
import de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException;
import de.hybris.platform.servicelayer.model.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Handles Stripe webhook events and applies them to Commerce payment state. Implementation TBD.
 */
public class StripeWebhookService {
    private static final Logger LOG = LoggerFactory.getLogger(StripeWebhookService.class);
    private static final String HEADER_ORDER_CODE = "orderCode";
    private static final String HEADER_ORDER_CUSTOMER = "orderCustomerId";

    private ModelService modelService;
    private AssistedServiceService assistedServiceService;

    public StripeWebhookProcessResult processEvent(final Event event) {
        LOG.info("Processing Stripe event {} type {}", event.getId(), event.getType());
        final StripeObject stripeObject = deserialize(event);
        if (stripeObject == null) {
            LOG.warn("Event {} has no object payload", event.getId());
            return StripeWebhookProcessResult.nack("Missing event object");
        }

        if (stripeObject instanceof PaymentIntent) {
            return handlePaymentIntent((PaymentIntent) stripeObject);
        }
        if (stripeObject instanceof Refund) {
            return handleRefund(event, (Refund) stripeObject);
        }

        LOG.debug("Unhandled Stripe event type {}", event.getType());
        return StripeWebhookProcessResult.ack("Unhandled event type");
    }

    protected StripeWebhookProcessResult handlePaymentIntent(final PaymentIntent paymentIntent) {
        final Optional<OrderModel> orderOpt = resolveOrder(paymentIntent.getMetadata());
        if (!orderOpt.isPresent()) {
            LOG.warn("No order found for payment intent {}", paymentIntent.getId());
            return StripeWebhookProcessResult.nack("Order not found");
        }
        final OrderModel order = orderOpt.get();
        final TransactionStatus status = mapIntentStatus(paymentIntent.getStatus());
        updateTransactionEntry(order, PaymentTransactionType.AUTHORIZATION, paymentIntent.getId(), status,
                paymentIntent.getStatus(), paymentIntent.getClientSecret());
        return StripeWebhookProcessResult.ack("PaymentIntent processed");
    }

    protected StripeWebhookProcessResult handleRefund(final Event event, final Refund refund) {
        final Optional<OrderModel> orderOpt = resolveOrder(refund.getMetadata());
        if (!orderOpt.isPresent()) {
            LOG.warn("No order found for refund {}", refund.getId());
            return StripeWebhookProcessResult.nack("Order not found");
        }
        final OrderModel order = orderOpt.get();
        final TransactionStatus status = mapRefundStatus(refund.getStatus());
        updateTransactionEntry(order, PaymentTransactionType.REFUND_FOLLOW_ON, refund.getId(), status, refund.getStatus(), null);
        return StripeWebhookProcessResult.ack("Refund processed");
    }

    protected Optional<OrderModel> resolveOrder(final Map<String, String> metadata) {
        if (metadata == null || !metadata.containsKey(HEADER_ORDER_CODE) || metadata.containsKey(HEADER_ORDER_CUSTOMER)) {
            return Optional.empty();
        }
        final String orderCode = metadata.get(HEADER_ORDER_CODE);
        final String customerId = metadata.get(HEADER_ORDER_CUSTOMER);
        try {
            final UserModel customer = assistedServiceService.getCustomer(customerId);
            return Optional.ofNullable(assistedServiceService.getOrderByCode(orderCode, customer));
        } catch (UnknownIdentifierException e) {
            LOG.warn("Customer {} not found for stripe metadata", customerId);
            return Optional.empty();
        } catch (ModelNotFoundException e) {
            LOG.warn("Order {} not found for stripe metadata", orderCode);
            return Optional.empty();
        }
    }

    protected void updateTransactionEntry(final OrderModel order, final PaymentTransactionType type,
                                          final String requestId, final TransactionStatus status,
                                          final String statusDetails, final String clientSecret) {
        if (order.getPaymentTransactions() == null) {
            return;
        }
        final Stream<PaymentTransactionEntryModel> entries = order.getPaymentTransactions().stream()
                .flatMap(tx -> tx.getEntries().stream());

        final PaymentTransactionEntryModel entry = entries
                .filter(e -> type.equals(e.getType()) && requestId.equals(e.getRequestId()))
                .findFirst()
                .orElseGet(() -> createEntry(order, type, requestId));

        entry.setTransactionStatus(status.name());
        entry.setTransactionStatusDetails(statusDetails);
        if (clientSecret != null) {
            entry.setRequestToken(clientSecret);
        }
        modelService.save(entry);
    }

    protected PaymentTransactionEntryModel createEntry(final OrderModel order, final PaymentTransactionType type,
                                                       final String requestId) {
        final PaymentTransactionModel transaction = (order.getPaymentTransactions() != null
                && !order.getPaymentTransactions().isEmpty())
                ? order.getPaymentTransactions().get(0)
                : createTransaction(order);
        final PaymentTransactionEntryModel entry = modelService.create(PaymentTransactionEntryModel.class);
        entry.setPaymentTransaction(transaction);
        entry.setType(type);
        entry.setRequestId(requestId);
        entry.setCode(order.getCode() + "-" + type.name() + "-" + requestId);
        return entry;
    }

    protected PaymentTransactionModel createTransaction(final OrderModel order) {
        final PaymentTransactionModel transaction = modelService.create(PaymentTransactionModel.class);
        transaction.setOrder(order);
        transaction.setPaymentProvider("Stripe");
        final PaymentInfoModel info = order.getPaymentInfo();
        if (info != null) {
            transaction.setInfo(info);
        }
        modelService.save(transaction);
        if (order.getPaymentTransactions() == null) {
            order.setPaymentTransactions(new ArrayList<>(Collections.singletonList(transaction)));
        } else {
            order.getPaymentTransactions().add(transaction);
        }
        modelService.save(order);
        return transaction;
    }

    protected TransactionStatus mapIntentStatus(final String status) {
        if ("succeeded".equalsIgnoreCase(status) || "requires_capture".equalsIgnoreCase(status)) {
            return TransactionStatus.ACCEPTED;
        }
        if ("processing".equalsIgnoreCase(status)) {
            return TransactionStatus.REVIEW;
        }
        if ("requires_action".equalsIgnoreCase(status)) {
            return TransactionStatus.REVIEW;
        }
        return TransactionStatus.REJECTED;
    }

    protected TransactionStatus mapRefundStatus(final String status) {
        if ("succeeded".equalsIgnoreCase(status)) {
            return TransactionStatus.ACCEPTED;
        }
        if ("failed".equalsIgnoreCase(status)) {
            return TransactionStatus.REJECTED;
        }
        return TransactionStatus.REVIEW;
    }

    protected StripeObject deserialize(final Event event) {
        final EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        if (deserializer == null) {
            return null;
        }
        return deserializer.getObject().orElse(null);
    }

    public void setModelService(final ModelService modelService) {
        this.modelService = modelService;
    }

    public void setAssistedServiceService(final AssistedServiceService assistedServiceService) {
        this.assistedServiceService = assistedServiceService;
    }
}

