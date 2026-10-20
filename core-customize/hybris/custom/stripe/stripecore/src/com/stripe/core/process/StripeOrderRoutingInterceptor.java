package com.stripe.core.process;

import com.stripe.core.payment.StripePaymentInfos;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.ordercancel.OrderCancelPaymentServiceAdapter;
import de.hybris.platform.ordercancel.model.OrderCancelRecordEntryModel;
import de.hybris.platform.orderprocessing.model.OrderProcessModel;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

/**
 * Around advice of a proxied SAP bean. A call of the intercepted method for a Stripe order (the first argument is the
 * order, or an order process of it) goes to the Stripe bean; other calls go to the SAP bean.
 */
public final class StripeOrderRoutingInterceptor implements MethodInterceptor
{
	static final String EXECUTE_ACTION = "executeAction";
	static final String RECALCULATE_ORDER_AND_MODIFY_PAYMENTS = "recalculateOrderAndModifyPayments";

	/**
	 * Calls the Stripe bean with the arguments of the intercepted call.
	 */
	@FunctionalInterface
	interface StripeCall
	{
		Object call(Object[] arguments) throws Exception;
	}

	private final String methodName;
	private final Object stripeTarget;
	private final StripeCall stripeCall;

	private StripeOrderRoutingInterceptor(final String methodName, final Object stripeTarget, final StripeCall stripeCall)
	{
		this.methodName = methodName;
		this.stripeTarget = stripeTarget;
		this.stripeCall = stripeCall;
	}

	/**
	 * Routes {@code executeAction(process)} of an order process action. The process engine calls the final method
	 * {@code execute(process)}, which calls {@code executeAction(process)} on the proxy.
	 */
	public static StripeOrderRoutingInterceptor forAction(final AbstractSimpleDecisionAction<OrderProcessModel> stripeAction)
	{
		return new StripeOrderRoutingInterceptor(EXECUTE_ACTION, stripeAction,
				arguments -> stripeAction.executeAction((OrderProcessModel) arguments[0]));
	}

	/**
	 * Routes both {@code recalculateOrderAndModifyPayments} methods of a cancel payment adapter.
	 */
	public static StripeOrderRoutingInterceptor forCancelPaymentAdapter(final OrderCancelPaymentServiceAdapter stripeAdapter)
	{
		return new StripeOrderRoutingInterceptor(RECALCULATE_ORDER_AND_MODIFY_PAYMENTS, stripeAdapter, arguments -> {
			if (arguments.length == 2)
			{
				stripeAdapter.recalculateOrderAndModifyPayments((OrderModel) arguments[0],
						(OrderCancelRecordEntryModel) arguments[1]);
			}
			else
			{
				stripeAdapter.recalculateOrderAndModifyPayments((OrderModel) arguments[0]);
			}
			return null;
		});
	}

	@Override
	public Object invoke(final MethodInvocation invocation) throws Throwable
	{
		final Object[] arguments = invocation.getArguments();
		if (methodName.equals(invocation.getMethod().getName()) && arguments.length > 0
				&& StripePaymentInfos.paymentIntentId(orderOf(arguments[0])) != null)
		{
			return stripeCall.call(arguments);
		}
		return invocation.proceed();
	}

	private static AbstractOrderModel orderOf(final Object argument)
	{
		if (argument instanceof OrderProcessModel process)
		{
			return process.getOrder();
		}
		return argument instanceof AbstractOrderModel order ? order : null;
	}

	/**
	 * @return the Stripe bean that gets the calls for Stripe orders
	 */
	public Object getStripeTarget()
	{
		return stripeTarget;
	}
}
