package com.stripe.controllers;

import com.stripe.dto.StripeIntentWsDTO;
import com.stripe.dto.StripeWebhookResponseWsDTO;
import com.stripe.exception.StripeException;
import com.stripe.exception.StripeGenericWebserviceException;
import com.stripe.facades.StripePaymentFacade;
import com.stripe.facades.data.StripeIntentData;
import com.stripe.facades.data.StripeWebhookResultData;
import de.hybris.platform.webservicescommons.errors.exceptions.WebserviceValidationException;
import de.hybris.platform.webservicescommons.swagger.ApiBaseSiteIdUserIdAndCartIdParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.annotation.Secured;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/{baseSiteId}")
@Validated
@Tag(name = "Stripe Payment")
public class StripePaymentController extends BaseController {
    private static final Logger LOG = LoggerFactory.getLogger(StripePaymentController.class);

    @Resource(name = "stripePaymentFacade")
    private StripePaymentFacade stripePaymentFacade;

    @Secured({"ROLE_CUSTOMERGROUP", "ROLE_CLIENT", "ROLE_CUSTOMERMANAGERGROUP", "ROLE_TRUSTED_CLIENT"})
    @RequestMapping(value = "/users/{userId}/carts/{cartId}/payments/stripe/intent", method = RequestMethod.POST)
    @ResponseStatus(HttpStatus.CREATED)
    @ResponseBody
    @Operation(operationId = "intent", summary = "Creates a Stripe Payment Intent", description = "Creates a Stripe Payment Intent for the current cart")
    @ApiBaseSiteIdUserIdAndCartIdParam
    public StripeIntentWsDTO getOrCreatePaymentIntent() {
        try {
            final StripeIntentData response = stripePaymentFacade.getIntentForSession();
            final StripeIntentWsDTO stripeIntentWsDTO = new StripeIntentWsDTO();
            stripeIntentWsDTO.setIntentId(response.getIntentId());
            stripeIntentWsDTO.setClientSecret(response.getClientSecret());
            stripeIntentWsDTO.setStatus(response.getStatus());
            return stripeIntentWsDTO;
        } catch (StripeException e) {
            LOG.error("Error ensuring Stripe intent", e);
            throw new StripeGenericWebserviceException(e.getMessage(), e.getCode(), e);
        } catch (IllegalStateException e) {
            throw new WebserviceValidationException(e.getMessage());
        }
    }

    @PostMapping("/payments/stripe/webhook")
    @ResponseBody
    public StripeWebhookResponseWsDTO webhook(@RequestHeader("Stripe-Signature") final String signature,
                                              @RequestBody final String payload) {
        LOG.debug("Received Stripe webhook");
        try {
            final StripeWebhookResponseWsDTO response = new StripeWebhookResponseWsDTO();
            final StripeWebhookResultData result = stripePaymentFacade.processWebhook(payload, signature);
            response.setProcessedEventId(result.getEventId());
            response.setStatus(result.isProcessed() ? "ACK" : "NOK");
            response.setMessage(result.getMessage());
            return response;
        } catch (StripeException e) {
            LOG.error("Failed to process Stripe webhook", e);
            throw new StripeGenericWebserviceException(e.getMessage(), e.getCode(), e);
        } catch (IllegalArgumentException e) {
            throw new WebserviceValidationException(e.getMessage());
        }
    }

    public void setStripePaymentFacade(final StripePaymentFacade stripePaymentFacade) {
        this.stripePaymentFacade = stripePaymentFacade;
    }
}
