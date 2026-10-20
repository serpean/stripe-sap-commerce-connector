package com.stripe.core.commands.impl;

import de.hybris.platform.payment.commands.PartialCaptureCommand;
import de.hybris.platform.payment.commands.request.PartialCaptureRequest;
import de.hybris.platform.payment.commands.result.CaptureResult;

/**
 * Captures the amount of the partial capture request. Stripe releases the rest of the authorization.
 */
public class StripePartialCaptureCommand extends AbstractStripeCaptureCommand implements PartialCaptureCommand
{
	@Override
	public CaptureResult perform(final PartialCaptureRequest request)
	{
		return capture(request);
	}
}
