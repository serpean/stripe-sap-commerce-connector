package com.stripe.controllers;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.payment.StripeIntentData;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.dto.StripeConfigWsDTO;
import com.stripe.dto.StripeIntentWsDTO;
import com.stripe.dto.StripeWebhookResponseWsDTO;
import com.stripe.facades.webhook.StripeWebhookFacade;
import com.stripe.facades.webhook.StripeWebhookReceipt;
import de.hybris.platform.commercewebservicescommons.errors.exceptions.CartException;
import de.hybris.platform.order.CartService;
import de.hybris.platform.webservicescommons.swagger.ApiBaseSiteIdParam;
import de.hybris.platform.webservicescommons.swagger.ApiBaseSiteIdUserIdAndCartIdParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
	@Resource(name = "stripePaymentIntentService")
	private StripePaymentIntentService stripePaymentIntentService;

	@Resource(name = "stripeConfigurationService")
	private StripeConfigurationService stripeConfigurationService;

	@Resource(name = "cartService")
	private CartService cartService;

	@Resource(name = "stripeWebhookFacade")
	private StripeWebhookFacade stripeWebhookFacade;

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
	public ResponseEntity<StripeWebhookResponseWsDTO> webhook(
			@RequestHeader(value = "Stripe-Signature", required = false) final String signature,
			@RequestBody final String payload)
	{
		final StripeWebhookReceipt receipt = stripeWebhookFacade.receive(payload, signature);
		if (receipt == StripeWebhookReceipt.INVALID_SIGNATURE)
		{
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
		}
		final StripeWebhookResponseWsDTO response = new StripeWebhookResponseWsDTO();
		response.setStatus(receipt.name());
		return ResponseEntity.ok(response);
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

	public void setStripeWebhookFacade(final StripeWebhookFacade stripeWebhookFacade)
	{
		this.stripeWebhookFacade = stripeWebhookFacade;
	}
}
