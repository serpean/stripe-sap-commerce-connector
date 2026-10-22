package com.stripe.core.gateway.impl;

import com.stripe.Stripe;
import com.stripe.StripeClient;
import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.util.StripeDigests;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.CashBalance;
import com.stripe.model.Customer;
import com.stripe.model.CustomerCashBalanceTransaction;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCashBalanceTransactionListParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentListParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.PaymentIntentUpdateParams;
import com.stripe.param.RefundCreateParams;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultStripeGateway implements StripeGateway
{
	public static final String API_VERSION = Stripe.API_VERSION;

	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeGateway.class);
	private static final int MAX_NETWORK_RETRIES = 2;
	private static final int HTTP_SERVER_ERROR = 500;
	private static final int HTTP_CONFLICT = 409;
	private static final Long OPEN_PAYMENT_INTENT_LIMIT = 100L;
	private static final Set<String> OPEN_STATUSES = Set.of("requires_action", "requires_confirmation", "processing");
	private static final Set<String> RETRYABLE_CODES = Set.of("idempotency_key_in_use", "lock_timeout");

	private StripeConfigurationService stripeConfigurationService;

	/** Account key by SHA-256 of the secret key. The secret key itself is never kept. */
	private final Map<String, String> accountKeys = new ConcurrentHashMap<>();

	@Override
	public PaymentIntent createPaymentIntent(final PaymentIntentCreateParams params, final String idempotencyKey)
	{
		final StripeClient client = newClient();
		return call("create_payment_intent", () -> client.v1().paymentIntents().create(params, options(idempotencyKey)));
	}

	@Override
	public PaymentIntent updatePaymentIntent(final String id, final PaymentIntentUpdateParams params,
			final String idempotencyKey)
	{
		final StripeClient client = newClient();
		return call("update_payment_intent", () -> client.v1().paymentIntents().update(id, params, options(idempotencyKey)));
	}

	@Override
	public PaymentIntent retrievePaymentIntent(final String id)
	{
		final StripeClient client = newClient();
		final PaymentIntentRetrieveParams params = PaymentIntentRetrieveParams.builder()
				.addExpand("payment_method")
				.addExpand("latest_charge")
				.build();
		return call("retrieve_payment_intent", () -> client.v1().paymentIntents().retrieve(id, params));
	}

	@Override
	public PaymentIntent cancelPaymentIntent(final String id, final String idempotencyKey)
	{
		final StripeClient client = newClient();
		return call("cancel_payment_intent", () -> client.v1().paymentIntents()
				.cancel(id, PaymentIntentCancelParams.builder().build(), options(idempotencyKey)));
	}

	@Override
	public PaymentIntent capturePaymentIntent(final String id, final long amountToCapture, final String idempotencyKey)
	{
		final StripeClient client = newClient();
		final PaymentIntentCaptureParams params = PaymentIntentCaptureParams.builder()
				.setAmountToCapture(amountToCapture)
				.build();
		return call("capture_payment_intent", () -> client.v1().paymentIntents().capture(id, params, options(idempotencyKey)));
	}

	@Override
	public Refund createRefund(final String paymentIntentId, final long amount, final String idempotencyKey)
	{
		final StripeClient client = newClient();
		final RefundCreateParams params = RefundCreateParams.builder()
				.setPaymentIntent(paymentIntentId)
				.setAmount(amount)
				.build();
		return call("create_refund", () -> client.v1().refunds().create(params, options(idempotencyKey)));
	}

	@Override
	public Customer createCustomer(final CustomerCreateParams params, final String idempotencyKey)
	{
		final StripeClient client = newClient();
		return call("create_customer", () -> client.v1().customers().create(params, options(idempotencyKey)));
	}

	@Override
	public Customer updateCustomer(final String id, final CustomerUpdateParams params, final String idempotencyKey)
	{
		final StripeClient client = newClient();
		return call("update_customer", () -> client.v1().customers().update(id, params, options(idempotencyKey)));
	}

	@Override
	public long retrieveCashBalance(final String customerId, final String currency)
	{
		final StripeClient client = newClient();
		final CashBalance balance = call("retrieve_cash_balance", () -> client.v1().customers().cashBalance().retrieve(customerId));
		final Map<String, Long> available = balance.getAvailable();
		final Long amount = available == null ? null : available.get(currency.toLowerCase(Locale.ROOT));
		return amount == null ? 0L : amount;
	}

	@Override
	public String retrieveLatestCashBalanceTransactionId(final String customerId)
	{
		final StripeClient client = newClient();
		final CustomerCashBalanceTransactionListParams params = CustomerCashBalanceTransactionListParams.builder()
				.setLimit(1L).build();
		return call("list_cash_balance_transactions",
				() -> client.v1().customers().cashBalanceTransactions().list(customerId, params).getData().stream()
						.findFirst().map(CustomerCashBalanceTransaction::getId).orElse(null));
	}

	@Override
	public Refund createCashBalanceRefund(final String customerId, final long amount, final String currency,
			final String idempotencyKey)
	{
		final StripeClient client = newClient();
		final RefundCreateParams params = RefundCreateParams.builder()
				.setCustomer(customerId)
				.setOrigin(RefundCreateParams.Origin.CUSTOMER_BALANCE)
				.setAmount(amount)
				.setCurrency(currency.toLowerCase(Locale.ROOT))
				.build();
		return call("create_cash_balance_refund", () -> client.v1().refunds().create(params, options(idempotencyKey)));
	}

	@Override
	public List<PaymentIntent> listOpenPaymentIntents(final String customerId)
	{
		final StripeClient client = newClient();
		final PaymentIntentListParams params = PaymentIntentListParams.builder()
				.setCustomer(customerId)
				.setLimit(OPEN_PAYMENT_INTENT_LIMIT)
				.build();
		return call("list_payment_intents", () -> client.v1().paymentIntents().list(params).getData().stream()
				.filter(paymentIntent -> OPEN_STATUSES.contains(paymentIntent.getStatus()))
				.toList());
	}

	@Override
	public String retrieveAccountKey()
	{
		final String secretKey = stripeConfigurationService.getSecretKey();
		if (StringUtils.isBlank(secretKey))
		{
			throw new StripeGatewayException("Stripe secret key is not configured", "secret_key_missing", false, false, null);
		}
		final String cacheKey = StripeDigests.sha256Hex(secretKey);
		final String cached = accountKeys.get(cacheKey);
		if (cached != null)
		{
			return cached;
		}
		final StripeClient client = newClient();
		final Account account = call("retrieve_account", () -> client.v1().accounts().retrieveCurrent());
		final String mode = secretKey.startsWith("sk_live_") || secretKey.startsWith("rk_live_") ? ":live" : ":test";
		final String accountKey = account.getId() + mode;
		accountKeys.put(cacheKey, accountKey);
		return accountKey;
	}

	@Override
	public Event constructEvent(final String payload, final String signatureHeader)
	{
		final String webhookSecret = stripeConfigurationService.getWebhookSecret();
		if (StringUtils.isBlank(webhookSecret))
		{
			throw new StripeGatewayException("Stripe webhook secret is not configured", "webhook_secret_missing", false, false,
					null);
		}
		try
		{
			return Webhook.constructEvent(payload, signatureHeader, webhookSecret);
		}
		catch (final SignatureVerificationException e)
		{
			throw new StripeGatewayException("Stripe webhook signature is not valid", "signature_invalid", false, true, e);
		}
	}

	protected StripeClient newClient()
	{
		final String secretKey = stripeConfigurationService.getSecretKey();
		if (StringUtils.isBlank(secretKey))
		{
			throw new StripeGatewayException("Stripe secret key is not configured", "secret_key_missing", false, false, null);
		}
		return StripeClient.builder().setApiKey(secretKey).setMaxNetworkRetries(MAX_NETWORK_RETRIES).build();
	}

	protected RequestOptions options(final String idempotencyKey)
	{
		return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
	}

	protected <T> T call(final String operation, final StripeCall<T> stripeCall)
	{
		try
		{
			return stripeCall.execute();
		}
		catch (final StripeException e)
		{
			LOG.warn("Stripe call {} failed: code={}, status={}, requestId={}", operation, e.getCode(), e.getStatusCode(),
					e.getRequestId());
			throw new StripeGatewayException("Stripe call " + operation + " failed", e.getCode(), isRetryable(e), false, e);
		}
	}

	/**
	 * A retry can help for a network error, a rate limit, a server error, a conflict (HTTP 409, for example
	 * {@code idempotency_key_in_use}: a request with the same key still runs) and {@code lock_timeout} (another request
	 * holds the object). The retry uses the same idempotency key.
	 */
	protected boolean isRetryable(final StripeException e)
	{
		final Integer status = e.getStatusCode();
		return e instanceof ApiConnectionException || e instanceof RateLimitException
				|| (status != null && (status >= HTTP_SERVER_ERROR || status == HTTP_CONFLICT))
				|| RETRYABLE_CODES.contains(StringUtils.defaultString(e.getCode()));
	}

	public void setStripeConfigurationService(final StripeConfigurationService stripeConfigurationService)
	{
		this.stripeConfigurationService = stripeConfigurationService;
	}

	@FunctionalInterface
	protected interface StripeCall<T>
	{
		T execute() throws StripeException;
	}
}
