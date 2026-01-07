/*
 * Copyright (c) 2024 SAP SE or an SAP affiliate company. All rights reserved.
 */

package com.stripe.facades.order.converters.populators;

import com.stripe.core.model.StripePaymentInfoModel;
import de.hybris.platform.commercefacades.order.converters.populator.AbstractOrderPopulator;
import de.hybris.platform.commercefacades.order.data.AbstractOrderData;
import de.hybris.platform.commercefacades.payment.data.GenericPaymentInfoData;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.order.payment.PaymentInfoModel;
import de.hybris.platform.core.model.order.payment.SAPGenericPaymentInfoModel;
import de.hybris.platform.servicelayer.dto.converter.ConversionException;
import de.hybris.platform.servicelayer.dto.converter.Converter;
import org.springframework.util.Assert;


public class StripeOrderPaymentInfoPopulator<S extends AbstractOrderModel, T extends AbstractOrderData>
        extends AbstractOrderPopulator<S, T> {
    private final Converter<PaymentInfoModel, GenericPaymentInfoData> stripePaymentInfoConverter;

    public StripeOrderPaymentInfoPopulator(final Converter<PaymentInfoModel, GenericPaymentInfoData> stripePaymentInfoConverter) {
        this.stripePaymentInfoConverter = stripePaymentInfoConverter;
    }

    @Override
    public void populate(final S source, final T target) throws ConversionException {
        Assert.notNull(source, "Parameter source cannot be null.");
        Assert.notNull(target, "Parameter target cannot be null.");

        if (source.getPaymentInfo() instanceof StripePaymentInfoModel stripePaymentInfoModel) {
            target.setSapGenericPaymentInfo(getStripePaymentInfoConverter().convert(stripePaymentInfoModel));
        }
    }

    protected Converter<PaymentInfoModel, GenericPaymentInfoData> getStripePaymentInfoConverter() {
        return this.stripePaymentInfoConverter;
    }
}
