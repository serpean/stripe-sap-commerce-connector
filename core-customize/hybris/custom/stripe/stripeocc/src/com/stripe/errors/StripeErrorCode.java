package com.stripe.errors;

import com.stripe.facades.checkout.StripeCheckoutException;
import org.springframework.http.HttpStatus;

/**
 * Stable error codes that the storefront API gives for a Stripe payment problem. The message is generic. The detail of
 * the problem goes to the log only.
 */
public enum StripeErrorCode
{
	STRIPE_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "The cart total changed. Check the cart and pay again."),
	STRIPE_PAYMENT_FAILED(HttpStatus.BAD_REQUEST, "The payment was not completed. Try again or use a different payment method."),
	STRIPE_ALREADY_PLACED(HttpStatus.BAD_REQUEST, "An order for this payment already exists or is in progress."),
	STRIPE_PAYMENT_IN_PROGRESS(HttpStatus.BAD_REQUEST, "The payment is processing. Wait for the result."),
	STRIPE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The payment service is not available. Try again later.");

	private final HttpStatus httpStatus;
	private final String message;

	StripeErrorCode(final HttpStatus httpStatus, final String message)
	{
		this.httpStatus = httpStatus;
		this.message = message;
	}

	public static StripeErrorCode of(final StripeCheckoutException.Reason reason)
	{
		switch (reason)
		{
			case AMOUNT_MISMATCH:
				return STRIPE_AMOUNT_MISMATCH;
			case ALREADY_PLACED:
				return STRIPE_ALREADY_PLACED;
			default:
				return STRIPE_PAYMENT_FAILED;
		}
	}

	public HttpStatus getHttpStatus()
	{
		return httpStatus;
	}

	public String getMessage()
	{
		return message;
	}
}
