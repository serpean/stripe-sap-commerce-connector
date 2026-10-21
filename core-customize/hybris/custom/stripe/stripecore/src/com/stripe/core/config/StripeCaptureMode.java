package com.stripe.core.config;

/**
 * How Stripe takes the money: after the order exists (MANUAL) or right away (AUTOMATIC).
 */
public enum StripeCaptureMode
{
	MANUAL, AUTOMATIC
}
