package com.stripe.core.commands.impl;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import de.hybris.platform.payment.commands.request.AbstractRequest;
import de.hybris.platform.payment.commands.result.AbstractResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import de.hybris.platform.servicelayer.time.TimeService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import java.util.Set;

/**
 * Base of the Stripe payment commands. The requestId of the results is the PaymentIntent id, so later requests of the
 * framework carry it. The requestToken never holds a secret.
 */
public abstract class AbstractStripeCommand
{
	private static final Logger LOG = LoggerFactory.getLogger(AbstractStripeCommand.class);
	/**
	 * Codes of Stripe that no retry can fix. Source: docs.stripe.com/error-codes (charge_expired_for_capture,
	 * capture_charge_authorization_expired: the authorization expired; charge_disputed, refund_disputed_payment: the
	 * charge is disputed). The page lists no code for a refund outside the window of the method, so such a refund is an
	 * ERROR until Stripe documents one.
	 */
	static final Set<String> PERMANENT_CODES = Set.of("charge_expired_for_capture", "capture_charge_authorization_expired",
			"charge_disputed", "refund_disputed_payment");
	static final String PERMANENT_PREFIX = "stripe:";
	private static final Set<String> ACCEPTED_REFUND_STATUSES = Set.of("succeeded", "pending", "requires_action");
	private static final Set<String> REJECTED_REFUND_STATUSES = Set.of("failed", "canceled");

	private StripeGateway stripeGateway;
	private StripeAmountConverter stripeAmountConverter;
	private TimeService timeService;

	/**
	 * Stable idempotency key: the prefix plus the merchant transaction code, which SAP makes unique per entry.
	 */
	protected String idempotencyKey(final String prefix, final AbstractRequest request)
	{
		final String code = request.getMerchantTransactionCode();
		if (StringUtils.isBlank(code))
		{
			throw new IllegalArgumentException("The request has no merchant transaction code");
		}
		return prefix + code;
	}

	protected String requirePaymentIntentId(final String paymentIntentId)
	{
		if (StringUtils.isBlank(paymentIntentId))
		{
			throw new IllegalArgumentException("The request has no PaymentIntent id");
		}
		return paymentIntentId;
	}

	/**
	 * Status of the PaymentIntent, or empty when Stripe sends a status that this connector does not know.
	 */
	protected Optional<StripePaymentIntentStatus> statusOf(final PaymentIntent paymentIntent)
	{
		try
		{
			return Optional.of(StripePaymentIntentStatus.of(paymentIntent));
		}
		catch (final IllegalArgumentException e)
		{
			LOG.warn("PaymentIntent [{}] has an unknown status [{}]", paymentIntent.getId(), paymentIntent.getStatus());
			return Optional.empty();
		}
	}

	protected boolean hasStatus(final PaymentIntent paymentIntent, final StripePaymentIntentStatus status)
	{
		return statusOf(paymentIntent).filter(status::equals).isPresent();
	}

	protected long toMinorUnits(final BigDecimal amount, final Currency currency)
	{
		return stripeAmountConverter.toMinorUnits(amount, currency.getCurrencyCode());
	}

	/**
	 * Fills the result for an error of Stripe: a permanent code gives REJECTED, which is final; the enum of the details
	 * has no room for the code, so the request token of the entry holds "stripe:" and the code. Any other error gives
	 * ERROR with COMMUNICATION_PROBLEM when a retry can help, else GENERAL_SYSTEM_ERROR.
	 */
	protected <R extends AbstractResult> R error(final R result, final StripeGatewayException exception,
			final AbstractRequest request)
	{
		LOG.warn("Stripe call failed for transaction [{}]: code [{}], retryable [{}]", request.getMerchantTransactionCode(),
				exception.getStripeCode(), exception.isRetryable());
		if (exception.getStripeCode() != null && PERMANENT_CODES.contains(exception.getStripeCode()))
		{
			result.setRequestToken(PERMANENT_PREFIX + exception.getStripeCode());
			return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
		}
		return fill(result, request, TransactionStatus.ERROR, exception.isRetryable()
				? TransactionStatusDetails.COMMUNICATION_PROBLEM : TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
	}

	/**
	 * Fills the result from the status of a refund. succeeded, pending and requires_action give ACCEPTED (the webhook
	 * confirms a pending refund later); failed and canceled give REJECTED; any other status, null included, gives ERROR.
	 */
	protected <R extends AbstractResult> R fillFromRefund(final R result, final AbstractRequest request, final Refund refund)
	{
		final String status = StringUtils.defaultString(refund.getStatus());
		if (ACCEPTED_REFUND_STATUSES.contains(status))
		{
			return fill(result, request, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL);
		}
		if (REJECTED_REFUND_STATUSES.contains(status))
		{
			return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
		}
		LOG.error("Refund [{}] has an unknown status [{}]", refund.getId(), refund.getStatus());
		return fill(result, request, TransactionStatus.ERROR, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
	}

	protected <R extends AbstractResult> R fill(final R result, final AbstractRequest request, final TransactionStatus status,
			final TransactionStatusDetails details)
	{
		result.setMerchantTransactionCode(request.getMerchantTransactionCode());
		result.setTransactionStatus(status);
		result.setTransactionStatusDetails(details);
		return result;
	}

	protected StripeGateway getStripeGateway()
	{
		return stripeGateway;
	}

	public void setStripeGateway(final StripeGateway stripeGateway)
	{
		this.stripeGateway = stripeGateway;
	}

	protected StripeAmountConverter getStripeAmountConverter()
	{
		return stripeAmountConverter;
	}

	public void setStripeAmountConverter(final StripeAmountConverter stripeAmountConverter)
	{
		this.stripeAmountConverter = stripeAmountConverter;
	}

	protected TimeService getTimeService()
	{
		return timeService;
	}

	public void setTimeService(final TimeService timeService)
	{
		this.timeService = timeService;
	}
}
