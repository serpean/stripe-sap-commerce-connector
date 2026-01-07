/*
 * Copyright (c) 2024 SAP SE or an SAP affiliate company. All rights reserved.
 */

package com.stripe.facades.order.converters.populators;

import com.stripe.core.model.StripePaymentInfoModel;
import de.hybris.platform.commercefacades.order.data.CardTypeData;
import de.hybris.platform.commercefacades.payment.data.GenericPaymentInfoData;
import de.hybris.platform.commercefacades.payment.data.PaymentMethodData;
import de.hybris.platform.converters.Populator;
import de.hybris.platform.core.enums.CreditCardType;
import de.hybris.platform.core.model.order.payment.PaymentInfoModel;
import de.hybris.platform.core.model.order.payment.SAPGenericPaymentInfoModel;
import de.hybris.platform.servicelayer.dto.converter.Converter;
import org.springframework.util.Assert;

import java.util.Optional;


public class StripePaymentInfoPopulator implements Populator<PaymentInfoModel, GenericPaymentInfoData> {

    @Override
    public void populate(final PaymentInfoModel source, final GenericPaymentInfoData target) {
        Assert.notNull(source, "Parameter source cannot be null.");
        Assert.notNull(target, "Parameter target cannot be null.");

        if (source instanceof StripePaymentInfoModel stripePaymentInfo) {
            target.setId(stripePaymentInfo.getCode());
        }
    }

}
