package com.stripe.core.payment.services;

import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;

import java.math.BigDecimal;

/**
 * Service for managing Stripe payment transactions in Hybris.
 */
public interface StripePaymentTransactionService {

    /**
     * Gets the Stripe PaymentIntent ID from a transaction entry code.
     *
     * @param transactionEntryCode the transaction entry code
     * @return the Stripe PaymentIntent ID
     */
    String getPaymentReferenceFromTransactionEntryCode(String transactionEntryCode);

    /**
     * Gets the payment transaction for an order.
     *
     * @param order the order
     * @return the payment transaction, or null if not found
     */
    PaymentTransactionModel getPaymentTransactionForOrder(OrderModel order);

    /**
     * Finds an accepted authorization entry in a payment transaction.
     *
     * @param transaction the payment transaction
     * @return the accepted authorization entry, or null if not found
     */
    PaymentTransactionEntryModel findAcceptedAuthorizationEntry(PaymentTransactionModel transaction);

    /**
     * Finds a pending authorization entry in a payment transaction.
     *
     * @param transaction the payment transaction
     * @return the pending authorization entry, or null if not found
     */
    PaymentTransactionEntryModel findPendingAuthorizationEntry(PaymentTransactionModel transaction);

    /**
     * Creates a new payment transaction entry.
     *
     * @param transaction the parent transaction
     * @param type        the transaction type
     * @param status      the transaction status
     * @param amount      the amount
     * @return the created entry
     */
    PaymentTransactionEntryModel createPaymentTransactionEntry(PaymentTransactionModel transaction,
                                                                PaymentTransactionType type,
                                                                String status,
                                                                BigDecimal amount);

    /**
     * Checks if the authorized amount matches the order total.
     *
     * @param order the order
     * @return true if amounts match
     */
    boolean isAuthorisedAmountCorrect(OrderModel order);

    /**
     * Gets the payment transaction entry by code.
     *
     * @param code the entry code
     * @return the payment transaction entry, or null if not found
     */
    PaymentTransactionEntryModel getPaymentTransactionEntryByCode(String code);
}
