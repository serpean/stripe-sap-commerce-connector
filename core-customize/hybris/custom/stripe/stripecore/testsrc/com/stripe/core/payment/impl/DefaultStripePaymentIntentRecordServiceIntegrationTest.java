package com.stripe.core.payment.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.servicelayer.ServicelayerTransactionalTest;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.user.UserService;
import java.util.Date;
import javax.annotation.Resource;
import org.junit.Test;

@IntegrationTest
public class DefaultStripePaymentIntentRecordServiceIntegrationTest extends ServicelayerTransactionalTest
{
	@Resource
	private StripePaymentIntentRecordService stripePaymentIntentRecordService;
	@Resource
	private ModelService modelService;
	@Resource
	private UserService userService;
	@Resource
	private CommonI18NService commonI18NService;

	@Test
	public void findsLiveOrderWithoutAuthorizationByPaymentInfo()
	{
		order("order-processing", null, "pi_processing");

		assertEquals("order-processing",
				stripePaymentIntentRecordService.findLiveOrder("pi_processing").orElseThrow().getCode());
	}

	@Test
	public void ignoresOrderHistorySnapshot()
	{
		order("order-snapshot", "v1", "pi_snapshot");

		assertFalse(stripePaymentIntentRecordService.findLiveOrder("pi_snapshot").isPresent());
	}

	private void order(final String code, final String versionId, final String paymentIntentId)
	{
		final StripePaymentInfoModel paymentInfo = modelService.create(StripePaymentInfoModel.class);
		paymentInfo.setCode(code + "-info");
		paymentInfo.setUser(userService.getAdminUser());
		paymentInfo.setStripePaymentIntentId(paymentIntentId);
		modelService.save(paymentInfo);

		final OrderModel order = modelService.create(OrderModel.class);
		order.setCode(code);
		order.setVersionID(versionId);
		order.setUser(userService.getAdminUser());
		order.setCurrency(commonI18NService.getCurrentCurrency());
		order.setDate(new Date());
		order.setPaymentInfo(paymentInfo);
		modelService.save(order);
	}
}
