package com.stripe.core.commands.impl;

import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.payment.commands.SubscriptionAuthorizationCommand;
import de.hybris.platform.payment.commands.request.SubscriptionAuthorizationRequest;
import de.hybris.platform.payment.commands.result.AuthorizationResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Checks an existing PaymentIntent (the subscription id). It does not move money. The authorization is accepted only
 * when the PaymentIntent is authorized or captured and its amount and currency are those of the request. A captured
 * PaymentIntent must also keep all its money: the full amount received and no refund on its expanded latest charge
 * (without the charge details it is rejected). A pending payment never gives an accepted authorization.
 */
public class StripeAuthorizationCommand extends AbstractStripeCommand implements SubscriptionAuthorizationCommand
{
	private static final Logger LOG = LoggerFactory.getLogger(StripeAuthorizationCommand.class);

	@Override
	public AuthorizationResult perform(final SubscriptionAuthorizationRequest request)
	{
		final String paymentIntentId = requirePaymentIntentId(request.getSubscriptionID());
		final AuthorizationResult result = new AuthorizationResult();
		result.setRequestId(paymentIntentId);
		result.setRequestToken(paymentIntentId);
		result.setCurrency(request.getCurrency());
		result.setTotalAmount(request.getTotalAmount());
		result.setPaymentProvider(request.getPaymentProvider());
		result.setAuthorizationTime(getTimeService().getCurrentTime());

		final PaymentIntent paymentIntent;
		try
		{
			paymentIntent = getStripeGateway().retrievePaymentIntent(paymentIntentId);
		}
		catch (final StripeGatewayException e)
		{
			return error(result, e, request);
		}

		if (!hasStatus(paymentIntent, StripePaymentIntentStatus.AUTHORIZED)
				&& !hasStatus(paymentIntent, StripePaymentIntentStatus.CAPTURED))
		{
			LOG.info("PaymentIntent [{}] is not authorized: status [{}]", paymentIntentId, paymentIntent.getStatus());
			return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
		}
		if (hasStatus(paymentIntent, StripePaymentIntentStatus.CAPTURED) && !keepsAllMoney(paymentIntent))
		{
			LOG.warn("PaymentIntent [{}] is captured but its money is not all kept: amount [{}], received [{}], refunded [{}]",
					paymentIntentId, paymentIntent.getAmount(), paymentIntent.getAmountReceived(),
					paymentIntent.getLatestChargeObject() == null ? "unknown"
							: paymentIntent.getLatestChargeObject().getAmountRefunded());
			return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
		}
		if (!sameAmount(paymentIntent, request))
		{
			LOG.warn("PaymentIntent [{}] amount [{} {}] is not the amount of transaction [{}]", paymentIntentId,
					paymentIntent.getAmount(), paymentIntent.getCurrency(), request.getMerchantTransactionCode());
			return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.INVALID_REQUEST);
		}
		return fill(result, request, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL);
	}

	/**
	 * A captured PaymentIntent keeps all its money when Stripe received the full amount and its charge has no refund.
	 * Without the charge details (latest_charge not expanded) the answer is no: the check fails closed.
	 */
	private boolean keepsAllMoney(final PaymentIntent paymentIntent)
	{
		final Charge charge = paymentIntent.getLatestChargeObject();
		return paymentIntent.getAmount() != null
				&& Objects.equals(paymentIntent.getAmount(), paymentIntent.getAmountReceived())
				&& charge != null && charge.getAmountRefunded() != null && charge.getAmountRefunded() == 0L;
	}

	private boolean sameAmount(final PaymentIntent paymentIntent, final SubscriptionAuthorizationRequest request)
	{
		return request.getCurrency().getCurrencyCode().equalsIgnoreCase(paymentIntent.getCurrency())
				&& Objects.equals(paymentIntent.getAmount(), toMinorUnits(request.getTotalAmount(), request.getCurrency()));
	}
}
