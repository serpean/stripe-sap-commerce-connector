package com.stripe.core.webhook;

/**
 * A webhook event could not be handled. The message never holds the payload or a secret.
 */
public class StripeWebhookException extends RuntimeException
{
	private final boolean retryable;

	public StripeWebhookException(final String message, final boolean retryable)
	{
		super(message);
		this.retryable = retryable;
	}

	public StripeWebhookException(final String message, final boolean retryable, final Throwable cause)
	{
		super(message, cause);
		this.retryable = retryable;
	}

	/**
	 * @return true when a later attempt can succeed
	 */
	public boolean isRetryable()
	{
		return retryable;
	}
}
