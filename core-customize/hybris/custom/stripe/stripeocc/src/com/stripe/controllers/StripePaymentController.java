package com.stripe.controllers;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.payment.StripeIntentData;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.dto.StripeConfigWsDTO;
import com.stripe.dto.StripeIntentWsDTO;
import com.stripe.dto.StripeWebhookResponseWsDTO;
import com.stripe.exception.StripeException;
import com.stripe.exception.StripeGenericWebserviceException;
import com.stripe.facades.StripePaymentFacade;
import com.stripe.facades.data.StripeWebhookResultData;
import de.hybris.platform.commercewebservicescommons.errors.exceptions.CartException;
import de.hybris.platform.order.CartService;
import de.hybris.platform.webservicescommons.errors.exceptions.WebserviceValidationException;
import de.hybris.platform.webservicescommons.swagger.ApiBaseSiteIdParam;
import de.hybris.platform.webservicescommons.swagger.ApiBaseSiteIdUserIdAndCartIdParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.annotation.Secured;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/{baseSiteId}")
@Validated
@Tag(name = "Stripe Payment")
public class StripePaymentController
{
	private static final Logger LOG = LoggerFactory.getLogger(StripePaymentController.class);

	@Resource(name = "stripePaymentIntentService")
	private StripePaymentIntentService stripePaymentIntentService;

	@Resource(name = "stripeConfigurationService")
	private StripeConfigurationService stripeConfigurationService;

	@Resource(name = "cartService")
	private CartService cartService;

	@Resource(name = "stripePaymentFacade")
	private StripePaymentFacade stripePaymentFacade;

	@Secured({ "ROLE_CUSTOMERGROUP", "ROLE_CLIENT", "ROLE_CUSTOMERMANAGERGROUP", "ROLE_TRUSTED_CLIENT" })
	@PostMapping("/users/{userId}/carts/{cartId}/payments/stripe/intent")
	@ResponseStatus(HttpStatus.CREATED)
	@ResponseBody
	@Operation(operationId = "prepareStripePaymentIntent", summary = "Prepares the Stripe PaymentIntent of the cart",
			description = "Creates the Stripe PaymentIntent of the cart, or updates its amount and currency to the cart total. "
					+ "Call it when the payment form opens and again just before the payment is confirmed.")
	@ApiBaseSiteIdUserIdAndCartIdParam
	public StripeIntentWsDTO prepareIntent()
	{
		if (!cartService.hasSessionCart())
		{
			throw new CartException("No cart for the Stripe payment", CartException.NOT_FOUND);
		}
		final StripeIntentData intent = stripePaymentIntentService.prepareForCart(cartService.getSessionCart());
		final StripeIntentWsDTO response = new StripeIntentWsDTO();
		response.setPaymentIntentId(intent.getPaymentIntentId());
		response.setClientSecret(intent.getClientSecret());
		response.setStatus(intent.getStatus());
		return response;
	}

	@GetMapping("/payments/stripe/config")
	@ResponseBody
	@Operation(operationId = "getStripeConfig", summary = "Gets the Stripe configuration of the base site",
			description = "Gives the Stripe publishable key of the base site. The key is public.")
	@ApiBaseSiteIdParam
	public StripeConfigWsDTO getConfig()
	{
		final StripeConfigWsDTO response = new StripeConfigWsDTO();
		response.setPublishableKey(stripeConfigurationService.getPublishableKey());
		return response;
	}

	@PostMapping("/payments/stripe/webhook")
	@ResponseBody
	public StripeWebhookResponseWsDTO webhook(@RequestHeader("Stripe-Signature") final String signature,
			@RequestBody final String payload)
	{
		LOG.debug("Received Stripe webhook");
		try
		{
			final StripeWebhookResponseWsDTO response = new StripeWebhookResponseWsDTO();
			final StripeWebhookResultData result = stripePaymentFacade.processWebhook(payload, signature);
			response.setProcessedEventId(result.getEventId());
			response.setStatus(result.isProcessed() ? "ACK" : "NOK");
			response.setMessage(result.getMessage());
			return response;
		}
		catch (final StripeException e)
		{
			LOG.error("Failed to process Stripe webhook", e);
			throw new StripeGenericWebserviceException(e.getMessage(), e.getCode(), e);
		}
		catch (final IllegalArgumentException e)
		{
			throw new WebserviceValidationException(e.getMessage());
		}
	}

	public void setStripePaymentIntentService(final StripePaymentIntentService stripePaymentIntentService)
	{
		this.stripePaymentIntentService = stripePaymentIntentService;
	}

	public void setStripeConfigurationService(final StripeConfigurationService stripeConfigurationService)
	{
		this.stripeConfigurationService = stripeConfigurationService;
	}

	public void setCartService(final CartService cartService)
	{
		this.cartService = cartService;
	}

	public void setStripePaymentFacade(final StripePaymentFacade stripePaymentFacade)
	{
		this.stripePaymentFacade = stripePaymentFacade;
	}
}
