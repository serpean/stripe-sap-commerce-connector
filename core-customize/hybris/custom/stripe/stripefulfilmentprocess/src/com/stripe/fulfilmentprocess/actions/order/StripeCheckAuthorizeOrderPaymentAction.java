/*
 * Copyright (c) 2019 SAP SE or an SAP affiliate company. All rights reserved.
 */
package com.stripe.fulfilmentprocess.actions.order;

import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.stripe.StripePaymentProvider;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.order.payment.CreditCardPaymentInfoModel;
import de.hybris.platform.core.model.order.payment.InvoicePaymentInfoModel;
import de.hybris.platform.orderprocessing.model.OrderProcessModel;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction;


/**
 * This action implements payment authorization using {@link CreditCardPaymentInfoModel}. Any other payment model could
 * be implemented here, or in a separate action, if the process flow differs.
 */
public class StripeCheckAuthorizeOrderPaymentAction extends AbstractSimpleDecisionAction<OrderProcessModel> {

    private final StripePaymentProvider stripePaymentProvider;

    public StripeCheckAuthorizeOrderPaymentAction(StripePaymentProvider stripePaymentProvider) {
        this.stripePaymentProvider = stripePaymentProvider;
    }

    @Override
    public Transition executeAction(final OrderProcessModel process) {
        final OrderModel order = process.getOrder();

        if (order != null) {
            if (order.getPaymentInfo() instanceof InvoicePaymentInfoModel) {
                return Transition.OK;
            } else if (order.getPaymentInfo() instanceof StripePaymentInfoModel) {
                if (getStripePaymentProvider().hasAcceptedAuthorization(order)) {
                    order.setStatus(OrderStatus.PAYMENT_AUTHORIZED);
                    modelService.save(order);
                    return Transition.OK;
                }
            }
        }
        return Transition.NOK;
    }

    protected StripePaymentProvider getStripePaymentProvider() {
        return stripePaymentProvider;
    }
}
