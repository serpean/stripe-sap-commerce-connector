package com.stripe.validators;

import com.stripe.core.model.StripePaymentInfoModel;
import de.hybris.platform.order.CartService;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

/**
 * Advice for the SAP place-order cart validator. SAP's validator rejects a cart without card data or generic payment
 * info with {@value #PAYMENT_INFO_NOT_SET}. A session cart with a Stripe payment info that has a PaymentIntent is ready
 * for payment, so this advice drops that one error. All other checks, and all other carts, stay with SAP's validator.
 * <p>
 * SAP's controllers inject the validator by its class, and that class is in the OCC web application only. So this
 * advice goes on a class proxy of SAP's validator bean, not on a subclass.
 */
public class StripePlaceOrderCartValidator implements MethodInterceptor
{
	static final String PAYMENT_INFO_NOT_SET = "cart.paymentInfoNotSet";

	private CartService cartService;

	@Override
	public Object invoke(final MethodInvocation invocation) throws Throwable
	{
		if (!isValidateCall(invocation.getMethod()) || !sessionCartHasStripePaymentIntent())
		{
			return invocation.proceed();
		}
		final Object[] arguments = invocation.getArguments();
		final Errors errors = (Errors) arguments[1];
		final Errors parentErrors = new BeanPropertyBindingResult(arguments[0], errors.getObjectName());
		arguments[1] = parentErrors;
		final Object result = invocation.proceed();
		copyAllButPaymentInfoNotSet(parentErrors, errors);
		return result;
	}

	private static boolean isValidateCall(final Method method)
	{
		final Class<?>[] parameterTypes = method.getParameterTypes();
		return "validate".equals(method.getName()) && parameterTypes.length == 2 && Errors.class.equals(parameterTypes[1]);
	}

	private boolean sessionCartHasStripePaymentIntent()
	{
		return cartService.hasSessionCart()
				&& cartService.getSessionCart().getPaymentInfo() instanceof StripePaymentInfoModel paymentInfo
				&& StringUtils.isNotBlank(paymentInfo.getStripePaymentIntentId());
	}

	private static void copyAllButPaymentInfoNotSet(final Errors source, final Errors target)
	{
		for (final ObjectError error : source.getAllErrors())
		{
			if (PAYMENT_INFO_NOT_SET.equals(error.getCode()))
			{
				continue;
			}
			if (error instanceof FieldError fieldError)
			{
				target.rejectValue(fieldError.getField(), fieldError.getCode(), fieldError.getArguments(),
						fieldError.getDefaultMessage());
			}
			else
			{
				target.reject(error.getCode(), error.getArguments(), error.getDefaultMessage());
			}
		}
	}

	public void setCartService(final CartService cartService)
	{
		this.cartService = cartService;
	}
}
