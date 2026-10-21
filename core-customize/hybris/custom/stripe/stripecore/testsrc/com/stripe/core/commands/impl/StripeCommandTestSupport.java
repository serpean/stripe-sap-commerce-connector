package com.stripe.core.commands.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.servicelayer.time.TimeService;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Date;

/**
 * Shared fixtures for the Stripe command tests. Amounts use two decimals, as EUR.
 */
final class StripeCommandTestSupport
{
	static final String PI_ID = "pi_123";
	static final Currency EUR = Currency.getInstance("EUR");

	private StripeCommandTestSupport()
	{
	}

	static <T extends AbstractStripeCommand> T wire(final T command, final StripeGateway gateway)
	{
		final StripeAmountConverter converter = mock(StripeAmountConverter.class);
		when(converter.toMinorUnits(any(BigDecimal.class), anyString()))
				.thenAnswer(i -> ((BigDecimal) i.getArgument(0)).movePointRight(2).longValueExact());
		when(converter.fromMinorUnits(anyLong(), anyString()))
				.thenAnswer(i -> BigDecimal.valueOf((Long) i.getArgument(0), 2));
		final TimeService timeService = mock(TimeService.class);
		when(timeService.getCurrentTime()).thenReturn(new Date());

		command.setStripeGateway(gateway);
		command.setStripeAmountConverter(converter);
		command.setTimeService(timeService);
		return command;
	}

	static PaymentIntent intent(final String status, final long amount, final String currency)
	{
		final PaymentIntent intent = new PaymentIntent();
		intent.setId(PI_ID);
		intent.setStatus(status);
		intent.setAmount(amount);
		intent.setAmountReceived("succeeded".equals(status) ? amount : 0L);
		intent.setCurrency(currency);
		if ("succeeded".equals(status))
		{
			final Charge charge = new Charge();
			charge.setId("ch_1");
			charge.setAmount(amount);
			charge.setAmountRefunded(0L);
			intent.setLatestChargeObject(charge);
		}
		return intent;
	}
}
