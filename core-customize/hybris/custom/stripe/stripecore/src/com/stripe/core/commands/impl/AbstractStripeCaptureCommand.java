package com.stripe.core.commands.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.payment.StripeLineItemsBuilder;
import com.stripe.core.payment.StripeTransactionLookup;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.payment.commands.request.CaptureRequest;
import de.hybris.platform.payment.commands.result.CaptureResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures the amount of a capture request. A PaymentIntent that Stripe already captured (automatic capture) is
 * accepted without a call to Stripe, but only when the amount Stripe holds (received minus refunded) is the requested
 * amount.
 * <p>
 * Stripe checks that the line items of a PaymentIntent add up to the amount to capture, and line items cannot be
 * removed at capture. A capture of less than the PaymentIntent amount therefore sends new {@code amount_details}: the
 * lines of the order when the order total is the amount to capture, else one line "Order &lt;code&gt;" for the whole
 * amount. The line items never change the amount captured.
 */
public abstract class AbstractStripeCaptureCommand extends AbstractStripeCommand
{
	private static final Logger LOG = LoggerFactory.getLogger(AbstractStripeCaptureCommand.class);

	private StripeTransactionLookup stripeTransactionLookup;
	private StripeLineItemsBuilder stripeLineItemsBuilder;

	protected CaptureResult capture(final CaptureRequest request)
	{
		final String paymentIntentId = requirePaymentIntentId(request.getRequestId());
		final String key = idempotencyKey("capture-", request);
		final CaptureResult result = new CaptureResult();
		result.setRequestId(paymentIntentId);
		result.setRequestToken(paymentIntentId);
		result.setCurrency(request.getCurrency());
		result.setTotalAmount(request.getTotalAmount());
		result.setRequestTime(getTimeService().getCurrentTime());

		try
		{
			final long amount = toMinorUnits(request.getTotalAmount(), request.getCurrency());
			final PaymentIntent paymentIntent = getStripeGateway().retrievePaymentIntent(paymentIntentId);
			if (hasStatus(paymentIntent, StripePaymentIntentStatus.CAPTURED))
			{
				return alreadyCaptured(paymentIntent, amount, result, request);
			}
			if (hasStatus(paymentIntent, StripePaymentIntentStatus.AUTHORIZED))
			{
				final Map<String, Object> amountDetails = amountDetailsForCapture(paymentIntent, amount);
				final PaymentIntent captured = amountDetails.isEmpty()
						? getStripeGateway().capturePaymentIntent(paymentIntentId, amount, key)
						: getStripeGateway().capturePaymentIntent(paymentIntentId, amount, amountDetails, key);
				if (hasStatus(captured, StripePaymentIntentStatus.CAPTURED))
				{
					return fill(result, request, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL);
				}
			}
			LOG.warn("PaymentIntent [{}] cannot be captured: status [{}]", paymentIntentId, paymentIntent.getStatus());
			return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
		}
		catch (final StripeGatewayException e)
		{
			return error(result, e, request);
		}
	}

	/**
	 * Stripe captured the PaymentIntent before (automatic capture). The capture is accepted only for the net amount that
	 * Stripe holds: the amount received minus the amount refunded on the latest charge (for example, after a partial
	 * cancel). When the charge details are missing, the amount received is used.
	 */
	private CaptureResult alreadyCaptured(final PaymentIntent paymentIntent, final long amount, final CaptureResult result,
			final CaptureRequest request)
	{
		final long received = paymentIntent.getAmountReceived() == null ? 0L : paymentIntent.getAmountReceived();
		final Charge charge = paymentIntent.getLatestChargeObject();
		final long refunded = charge == null || charge.getAmountRefunded() == null ? 0L : charge.getAmountRefunded();
		final long net = received - refunded;
		if (net == amount)
		{
			return fill(result, request, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL);
		}
		LOG.warn("PaymentIntent [{}] holds [{}] (received [{}], refunded [{}]), not the requested [{}] of transaction [{}]",
				paymentIntent.getId(), net, received, refunded, amount, request.getMerchantTransactionCode());
		return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.INVALID_REQUEST);
	}

	/**
	 * The {@code amount_details} for the capture: none when the whole amount is captured or the PaymentIntent has no
	 * line items. Otherwise the lines of the order, when the order total is the amount to capture (for example after a
	 * partial cancel), else one line for the whole amount.
	 */
	protected Map<String, Object> amountDetailsForCapture(final PaymentIntent paymentIntent, final long amount)
	{
		if (paymentIntent.getAmount() == null || amount >= paymentIntent.getAmount() || !hasLineItems(paymentIntent))
		{
			return Map.of();
		}
		final Optional<AbstractOrderModel> order = orderOf(paymentIntent.getId());
		if (order.isPresent() && stripeLineItemsBuilder != null && orderTotalIs(order.get(), amount))
		{
			final Object built = stripeLineItemsBuilder.build(order.get(), amount).get("amount_details");
			if (built instanceof Map)
			{
				@SuppressWarnings("unchecked")
				final Map<String, Object> details = (Map<String, Object>) built;
				return details;
			}
		}
		final String orderCode = order.map(AbstractOrderModel::getCode).orElse(paymentIntent.getId());
		LOG.info("Capture of [{}] of PaymentIntent [{}] sends one line item: the order lines do not add up to it", amount,
				paymentIntent.getId());
		final Map<String, Object> line = new LinkedHashMap<>();
		line.put("product_name", "Order " + orderCode);
		line.put("quantity", 1L);
		line.put("unit_cost", amount);
		return Map.of("line_items", List.of(line));
	}

	/**
	 * True when the PaymentIntent has line items. The gateway expands {@code amount_details.line_items}; stripe-java has
	 * no field for them, so the raw JSON is read.
	 */
	protected boolean hasLineItems(final PaymentIntent paymentIntent)
	{
		final JsonObject raw = paymentIntent.getRawJsonObject();
		final JsonObject details = child(raw, "amount_details");
		final JsonObject lineItems = child(details, "line_items");
		final JsonElement data = lineItems == null ? null : lineItems.get("data");
		return data != null && data.isJsonArray() && data.getAsJsonArray().size() > 0;
	}

	private static JsonObject child(final JsonObject parent, final String name)
	{
		final JsonElement element = parent == null ? null : parent.get(name);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private Optional<AbstractOrderModel> orderOf(final String paymentIntentId)
	{
		if (stripeTransactionLookup == null)
		{
			return Optional.empty();
		}
		return stripeTransactionLookup.findByPaymentIntentId(paymentIntentId).map(PaymentTransactionModel::getOrder);
	}

	private boolean orderTotalIs(final AbstractOrderModel order, final long amount)
	{
		try
		{
			return getStripeAmountConverter().chargeAmountMinor(order) == amount;
		}
		catch (final IllegalArgumentException e)
		{
			return false;
		}
	}

	public void setStripeTransactionLookup(final StripeTransactionLookup stripeTransactionLookup)
	{
		this.stripeTransactionLookup = stripeTransactionLookup;
	}

	public void setStripeLineItemsBuilder(final StripeLineItemsBuilder stripeLineItemsBuilder)
	{
		this.stripeLineItemsBuilder = stripeLineItemsBuilder;
	}
}
