package com.stripe.core.payment.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.payment.StripePaymentDetailsCopier;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import de.hybris.platform.servicelayer.model.ModelService;
import java.util.Date;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link StripePaymentDetailsCopier}.
 * <ul>
 * <li>Payment method (expanded payment_method): type, id and, for a card, brand, last 4 digits and expiry.</li>
 * <li>Multibanco voucher (next_action.multibanco_display_details): reference "entity reference", hosted voucher URL
 * and expiry.</li>
 * <li>Bank transfer (next_action.display_bank_transfer_instructions): reference, hosted instructions URL and amount
 * still to pay, in minor units.</li>
 * <li>Expanded latest_charge: the SEPA mandate id, and the capture_before date of a reserved payment. stripe-java
 * 30.0.0 has a typed capture_before only for a card; for other methods it is read from the raw response of the
 * PaymentIntent, which exists only for a PaymentIntent that the API returned (not for an event object).</li>
 * </ul>
 * Only the display data is copied: the payment cannot fail because of it. A part that cannot be read is logged (without
 * data) and left out.
 */
public class DefaultStripePaymentDetailsCopier implements StripePaymentDetailsCopier
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripePaymentDetailsCopier.class);

	private ModelService modelService;

	@Override
	public void copy(final PaymentIntent paymentIntent, final StripePaymentInfoModel paymentInfo)
	{
		boolean changed = copyPaymentMethod(paymentIntent.getPaymentMethodObject(), paymentInfo);
		try
		{
			changed |= copyInstructions(paymentIntent.getNextAction(), paymentInfo);
			changed |= copyChargeDetails(paymentIntent, paymentInfo);
		}
		catch (final RuntimeException e)
		{
			LOG.warn("Payment details of payment intent {} could not be read: some are not copied ({})", paymentIntent.getId(),
					e.getClass().getSimpleName());
		}
		if (changed)
		{
			modelService.save(paymentInfo);
		}
	}

	protected boolean copyPaymentMethod(final PaymentMethod method, final StripePaymentInfoModel paymentInfo)
	{
		if (method == null)
		{
			return false;
		}
		paymentInfo.setPaymentMethodType(method.getType());
		paymentInfo.setPaymentMethodId(method.getId());
		final PaymentMethod.Card card = method.getCard();
		if (card != null)
		{
			paymentInfo.setCardBrand(card.getBrand());
			paymentInfo.setCardLast4(card.getLast4());
			paymentInfo.setCardExpMonth(card.getExpMonth() == null ? null : Math.toIntExact(card.getExpMonth()));
			paymentInfo.setCardExpYear(card.getExpYear() == null ? null : Math.toIntExact(card.getExpYear()));
		}
		return true;
	}

	protected boolean copyInstructions(final PaymentIntent.NextAction nextAction, final StripePaymentInfoModel paymentInfo)
	{
		if (nextAction == null)
		{
			return false;
		}
		final PaymentIntent.NextAction.MultibancoDisplayDetails voucher = nextAction.getMultibancoDisplayDetails();
		if ("multibanco_display_details".equals(nextAction.getType()) && voucher != null)
		{
			final String reference = StringUtils.trimToNull(
					StringUtils.join(StringUtils.defaultString(voucher.getEntity()), " ",
							StringUtils.defaultString(voucher.getReference())));
			paymentInfo.setPaymentReference(reference);
			paymentInfo.setInstructionsUrl(voucher.getHostedVoucherUrl());
			paymentInfo.setInstructionsExpireAt(toDate(voucher.getExpiresAt()));
			return true;
		}
		final PaymentIntent.NextAction.DisplayBankTransferInstructions transfer = nextAction
				.getDisplayBankTransferInstructions();
		if ("display_bank_transfer_instructions".equals(nextAction.getType()) && transfer != null)
		{
			paymentInfo.setPaymentReference(transfer.getReference());
			paymentInfo.setInstructionsUrl(transfer.getHostedInstructionsUrl());
			paymentInfo.setAmountRemaining(transfer.getAmountRemaining());
			return true;
		}
		return false;
	}

	protected boolean copyChargeDetails(final PaymentIntent paymentIntent, final StripePaymentInfoModel paymentInfo)
	{
		final Charge charge = paymentIntent.getLatestChargeObject();
		final Charge.PaymentMethodDetails details = charge == null ? null : charge.getPaymentMethodDetails();
		if (details == null)
		{
			return false;
		}
		boolean changed = false;
		if (details.getSepaDebit() != null && details.getSepaDebit().getMandate() != null)
		{
			paymentInfo.setSepaMandateId(details.getSepaDebit().getMandate());
			changed = true;
		}
		final Date captureBefore = captureBefore(paymentIntent, details);
		if (captureBefore != null)
		{
			paymentInfo.setCaptureBefore(captureBefore);
			changed = true;
		}
		return changed;
	}

	/**
	 * latest_charge.payment_method_details.&lt;type&gt;.capture_before: typed for a card, else from the raw response.
	 */
	protected Date captureBefore(final PaymentIntent paymentIntent, final Charge.PaymentMethodDetails details)
	{
		if (details.getCard() != null && details.getCard().getCaptureBefore() != null)
		{
			return toDate(details.getCard().getCaptureBefore());
		}
		final String type = details.getType();
		final JsonObject raw = StringUtils.isBlank(type) ? null : paymentIntent.getRawJsonObject();
		final JsonElement value = path(raw, "latest_charge", "payment_method_details", type, "capture_before");
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
				? toDate(value.getAsLong()) : null;
	}

	private static JsonElement path(final JsonObject root, final String... names)
	{
		JsonElement current = root;
		for (final String name : names)
		{
			if (current == null || !current.isJsonObject())
			{
				return null;
			}
			current = current.getAsJsonObject().get(name);
		}
		return current == null || current.isJsonNull() ? null : current;
	}

	private static Date toDate(final Long epochSeconds)
	{
		return epochSeconds == null ? null : new Date(epochSeconds * 1000L);
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}
}
