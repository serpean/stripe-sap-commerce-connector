package com.stripe.core.payment.services;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;

import java.math.BigDecimal;

/**
 * Service for integrating with the Stripe Payment API.
 */
public interface StripePaymentIntegrationService {

    /**
     * Captures a previously authorized payment.
     *
     * @param paymentIntentId the Stripe PaymentIntent ID
     * @param amount          the amount to capture (null for full capture)
     * @return the updated PaymentIntent
     * @throws StripeException if the API call fails
     */
    PaymentIntent capturePayment(String paymentIntentId, BigDecimal amount) throws StripeException;

    /**
     * Cancels/voids a previously authorized payment.
     *
     * @param paymentIntentId the Stripe PaymentIntent ID
     * @return the updated PaymentIntent
     * @throws StripeException if the API call fails
     */
    PaymentIntent voidPayment(String paymentIntentId) throws StripeException;

    /**
     * Refunds a captured payment.
     *
     * @param paymentIntentId the Stripe PaymentIntent ID
     * @param amount          the amount to refund (null for full refund)
     * @return the Refund object
     * @throws StripeException if the API call fails
     */
    Refund refundPayment(String paymentIntentId, BigDecimal amount) throws StripeException;

    /**
     * Retrieves a PaymentIntent by ID.
     *
     * @param paymentIntentId the Stripe PaymentIntent ID
     * @return the PaymentIntent
     * @throws StripeException if the API call fails
     */
    PaymentIntent getPaymentIntent(String paymentIntentId) throws StripeException;
}
