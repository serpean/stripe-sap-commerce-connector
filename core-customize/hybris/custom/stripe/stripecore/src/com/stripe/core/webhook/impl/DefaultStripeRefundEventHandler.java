package com.stripe.core.webhook.impl;

import com.stripe.core.payment.StripeTransactionLookup;
import com.stripe.core.webhook.StripeRefundEventHandler;
import com.stripe.core.webhook.StripeWebhookOutcome;
import com.stripe.model.Charge;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.servicelayer.model.ModelService;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link StripeRefundEventHandler}. A succeeded refund gives ACCEPTED; a failed or canceled refund gives
 * REJECTED (the money was not given back); a pending refund keeps the entry as it is. A failed or canceled refund
 * without SAP entry (for example, a release of funds of a PaymentIntent without order) is logged as an ERROR.
 */
public class DefaultStripeRefundEventHandler implements StripeRefundEventHandler
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeRefundEventHandler.class);
	private static final Set<String> REJECTED_STATUSES = Set.of("failed", "canceled");

	private StripeTransactionLookup stripeTransactionLookup;
	private ModelService modelService;

	@Override
	public StripeWebhookOutcome handle(final StripeObject object)
	{
		boolean matched = false;
		for (final Refund refund : refundsOf(object))
		{
			matched |= update(refund);
		}
		return matched ? StripeWebhookOutcome.PROCESSED : StripeWebhookOutcome.IGNORED;
	}

	private List<Refund> refundsOf(final StripeObject object)
	{
		if (object instanceof Refund)
		{
			return List.of((Refund) object);
		}
		if (object instanceof Charge)
		{
			final Charge charge = (Charge) object;
			if (charge.getRefunds() != null && charge.getRefunds().getData() != null)
			{
				return charge.getRefunds().getData();
			}
			LOG.info("Charge {} of payment intent {} has no refund list in the event", charge.getId(),
					charge.getPaymentIntent());
		}
		return List.of();
	}

	private boolean update(final Refund refund)
	{
		final List<PaymentTransactionEntryModel> entries = stripeTransactionLookup.findRefundEntries(refund.getId());
		if (entries.isEmpty())
		{
			if (REJECTED_STATUSES.contains(refund.getStatus()))
			{
				LOG.error("Refund {} of payment intent {} has status {} and no SAP refund entry: the money was not given "
						+ "back. Check the payment in Stripe", refund.getId(), refund.getPaymentIntent(), refund.getStatus());
			}
			else
			{
				LOG.info("Refund {} of payment intent {} has no SAP refund entry", refund.getId(), refund.getPaymentIntent());
			}
			return false;
		}
		final String status = refund.getStatus();
		for (final PaymentTransactionEntryModel entry : entries)
		{
			if ("succeeded".equals(status))
			{
				set(entry, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL);
			}
			else if (REJECTED_STATUSES.contains(status))
			{
				LOG.error("Refund {} of payment intent {} has status {}: the money was not given back", refund.getId(),
						refund.getPaymentIntent(), status);
				set(entry, TransactionStatus.REJECTED, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
			}
			else
			{
				LOG.info("Refund {} of payment intent {} has status {}", refund.getId(), refund.getPaymentIntent(), status);
			}
		}
		return true;
	}

	private void set(final PaymentTransactionEntryModel entry, final TransactionStatus status,
			final TransactionStatusDetails details)
	{
		if (status.name().equals(entry.getTransactionStatus()) && details.name().equals(entry.getTransactionStatusDetails()))
		{
			return;
		}
		entry.setTransactionStatus(status.name());
		entry.setTransactionStatusDetails(details.name());
		modelService.save(entry);
	}

	public void setStripeTransactionLookup(final StripeTransactionLookup stripeTransactionLookup)
	{
		this.stripeTransactionLookup = stripeTransactionLookup;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}
}
