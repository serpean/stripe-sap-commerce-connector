package com.stripe.errors;

import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.payment.StripePaymentInProgressException;
import com.stripe.facades.checkout.StripeCheckoutException;
import de.hybris.platform.webservicescommons.dto.error.ErrorListWsDTO;
import de.hybris.platform.webservicescommons.dto.error.ErrorWsDTO;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns Stripe payment exceptions into a stable {@link StripeErrorCode} for every OCC controller, including the SAP
 * OrdersController that places the order. The detail goes to the log, never to the client.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StripeOccExceptionHandler
{
	static final String ERROR_TYPE = "StripePaymentError";

	private static final Logger LOG = LoggerFactory.getLogger(StripeOccExceptionHandler.class);

	@ExceptionHandler(StripeCheckoutException.class)
	public ResponseEntity<ErrorListWsDTO> handleCheckoutException(final StripeCheckoutException exception)
	{
		LOG.warn("Stripe checkout stopped with reason {}: {}", exception.getReason(), exception.getMessage());
		return response(StripeErrorCode.of(exception.getReason()));
	}

	@ExceptionHandler(StripePaymentInProgressException.class)
	public ResponseEntity<ErrorListWsDTO> handlePaymentInProgress(final StripePaymentInProgressException exception)
	{
		LOG.info(exception.getMessage());
		return response(StripeErrorCode.STRIPE_PAYMENT_IN_PROGRESS);
	}

	@ExceptionHandler(StripeGatewayException.class)
	public ResponseEntity<ErrorListWsDTO> handleGatewayException(final StripeGatewayException exception)
	{
		LOG.error("Stripe call failed (stripe code {}, retryable {})", exception.getStripeCode(), exception.isRetryable(),
				exception);
		return response(StripeErrorCode.STRIPE_UNAVAILABLE);
	}

	private static ResponseEntity<ErrorListWsDTO> response(final StripeErrorCode code)
	{
		final ErrorWsDTO error = new ErrorWsDTO();
		error.setType(ERROR_TYPE);
		error.setReason(code.name());
		error.setMessage(code.getMessage());
		final ErrorListWsDTO errors = new ErrorListWsDTO();
		errors.setErrors(List.of(error));
		return ResponseEntity.status(code.getHttpStatus()).body(errors);
	}
}
