package com.stripe.core.payment.services.impl;

import com.stripe.core.payment.services.StripePaymentTransactionService;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.search.SearchResult;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of {@link StripePaymentTransactionService}.
 */
public class DefaultStripePaymentTransactionService implements StripePaymentTransactionService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultStripePaymentTransactionService.class);

    private static final String FIND_ENTRY_BY_CODE_QUERY =
            "SELECT {pk} FROM {PaymentTransactionEntry} WHERE {code} = ?code";

    private ModelService modelService;
    private FlexibleSearchService flexibleSearchService;

    @Override
    public String getPaymentReferenceFromTransactionEntryCode(final String transactionEntryCode) {
        if (StringUtils.isBlank(transactionEntryCode)) {
            LOG.warn("Transaction entry code is blank");
            return null;
        }

        final PaymentTransactionEntryModel entry = getPaymentTransactionEntryByCode(transactionEntryCode);
        if (entry == null) {
            LOG.warn("No transaction entry found for code: {}", transactionEntryCode);
            return null;
        }

        final PaymentTransactionModel transaction = entry.getPaymentTransaction();
        if (transaction == null) {
            LOG.warn("No transaction found for entry code: {}", transactionEntryCode);
            return null;
        }

        // The requestId on the authorization entry contains the PaymentIntent ID
        final PaymentTransactionEntryModel authEntry = findAcceptedAuthorizationEntry(transaction);
        if (authEntry != null && StringUtils.isNotBlank(authEntry.getRequestId())) {
            return authEntry.getRequestId();
        }

        // Fallback: check if the transaction itself has the reference
        if (StringUtils.isNotBlank(transaction.getRequestId())) {
            return transaction.getRequestId();
        }

        LOG.warn("Could not find payment reference for transaction entry code: {}", transactionEntryCode);
        return null;
    }

    @Override
    public PaymentTransactionModel getPaymentTransactionForOrder(final OrderModel order) {
        if (order == null || CollectionUtils.isEmpty(order.getPaymentTransactions())) {
            return null;
        }

        // Return the first Stripe transaction (payment provider = "Stripe")
        return order.getPaymentTransactions().stream()
                .filter(t -> "Stripe".equalsIgnoreCase(t.getPaymentProvider()))
                .findFirst()
                .orElse(order.getPaymentTransactions().get(0));
    }

    @Override
    public PaymentTransactionEntryModel findAcceptedAuthorizationEntry(final PaymentTransactionModel transaction) {
        return findEntryByTypeAndStatus(transaction, PaymentTransactionType.AUTHORIZATION,
                TransactionStatus.ACCEPTED.name());
    }

    @Override
    public PaymentTransactionEntryModel findPendingAuthorizationEntry(final PaymentTransactionModel transaction) {
        return findEntryByTypeAndStatus(transaction, PaymentTransactionType.AUTHORIZATION,
                TransactionStatus.WAITING.name());
    }

    protected PaymentTransactionEntryModel findEntryByTypeAndStatus(final PaymentTransactionModel transaction,
                                                                     final PaymentTransactionType type,
                                                                     final String status) {
        if (transaction == null || CollectionUtils.isEmpty(transaction.getEntries())) {
            return null;
        }

        return transaction.getEntries().stream()
                .filter(e -> type.equals(e.getType()))
                .filter(e -> status.equalsIgnoreCase(e.getTransactionStatus()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public PaymentTransactionEntryModel createPaymentTransactionEntry(final PaymentTransactionModel transaction,
                                                                       final PaymentTransactionType type,
                                                                       final String status,
                                                                       final BigDecimal amount) {
        final PaymentTransactionEntryModel entry = modelService.create(PaymentTransactionEntryModel.class);

        final String entryCode = generateEntryCode(transaction, type);
        entry.setCode(entryCode);
        entry.setType(type);
        entry.setTransactionStatus(status);
        entry.setAmount(amount);
        entry.setTime(new Date());
        entry.setPaymentTransaction(transaction);

        if (transaction.getOrder() != null) {
            entry.setCurrency(transaction.getOrder().getCurrency());
        }

        modelService.save(entry);
        LOG.debug("Created payment transaction entry: {} with type: {} and status: {}",
                entryCode, type, status);

        return entry;
    }

    protected String generateEntryCode(final PaymentTransactionModel transaction,
                                        final PaymentTransactionType type) {
        final String orderCode = Optional.ofNullable(transaction.getOrder())
                .map(AbstractOrderModel::getCode)
                .orElse("unknown");
        return String.format("%s-%s-%s", orderCode, type.getCode(), UUID.randomUUID().toString().substring(0, 8));
    }

    @Override
    public boolean isAuthorisedAmountCorrect(final OrderModel order) {
        if (order == null) {
            return false;
        }

        final PaymentTransactionModel transaction = getPaymentTransactionForOrder(order);
        if (transaction == null) {
            return false;
        }

        final PaymentTransactionEntryModel authEntry = findAcceptedAuthorizationEntry(transaction);
        if (authEntry == null || authEntry.getAmount() == null) {
            return false;
        }

        final BigDecimal authorizedAmount = authEntry.getAmount();
        final BigDecimal orderTotal = BigDecimal.valueOf(order.getTotalPrice());

        return authorizedAmount.compareTo(orderTotal) >= 0;
    }

    @Override
    public PaymentTransactionEntryModel getPaymentTransactionEntryByCode(final String code) {
        if (StringUtils.isBlank(code)) {
            return null;
        }

        final FlexibleSearchQuery query = new FlexibleSearchQuery(FIND_ENTRY_BY_CODE_QUERY);
        query.addQueryParameter("code", code);

        final SearchResult<PaymentTransactionEntryModel> result = flexibleSearchService.search(query);
        final List<PaymentTransactionEntryModel> entries = result.getResult();

        if (CollectionUtils.isEmpty(entries)) {
            return null;
        }

        return entries.get(0);
    }

    // Setters for Spring injection

    public void setModelService(final ModelService modelService) {
        this.modelService = modelService;
    }

    public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService) {
        this.flexibleSearchService = flexibleSearchService;
    }
}
