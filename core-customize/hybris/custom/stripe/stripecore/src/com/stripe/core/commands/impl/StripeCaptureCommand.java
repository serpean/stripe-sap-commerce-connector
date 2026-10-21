package com.stripe.core.commands.impl;

import de.hybris.platform.payment.commands.CaptureCommand;
import de.hybris.platform.payment.commands.request.CaptureRequest;
import de.hybris.platform.payment.commands.result.CaptureResult;

/**
 * Captures the full amount of the request.
 */
public class StripeCaptureCommand extends AbstractStripeCaptureCommand implements CaptureCommand
{
	@Override
	public CaptureResult perform(final CaptureRequest request)
	{
		return capture(request);
	}
}
