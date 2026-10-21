package com.stripe.core.order.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderCancellationService;
import com.stripe.core.testsupport.StripeLogCapture;
import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.catalog.CatalogVersionService;
import de.hybris.platform.core.Registry;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.order.OrderEntryModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.ordercancel.model.OrderCancelConfigModel;
import de.hybris.platform.product.ProductService;
import de.hybris.platform.servicelayer.ServicelayerTransactionalTest;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.user.UserService;
import java.util.Date;
import java.util.List;
import javax.annotation.Resource;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Cancels a Stripe order whose payment failed through the real SAP order cancel service of the tenant. With order
 * management (OMS) installed, the warehousing cancel state mapping and the OMS cancel executor run: a PAYMENT_PENDING
 * order without consignments can be cancelled. The record of the PaymentIntent says that its money is already
 * released, so no Stripe call is made.
 */
@IntegrationTest
public class DefaultStripeOrderCancellationServiceIntegrationTest extends ServicelayerTransactionalTest
{
	private static final String PI_ID = "pi_cancel_it";

	@Resource
	private StripeOrderCancellationService stripeOrderCancellationService;
	@Resource
	private ModelService modelService;
	@Resource
	private UserService userService;
	@Resource
	private CommonI18NService commonI18NService;
	@Resource
	private ProductService productService;
	@Resource
	private CatalogVersionService catalogVersionService;
	@Resource
	private FlexibleSearchService flexibleSearchService;

	@Before
	public void setUp() throws Exception
	{
		createCoreData();
		createDefaultCatalog();
		ensureOrderCancelConfig();
	}

	@Test
	public void pendingStripeOrderIsCancelledThroughTheOrderManagementCancel()
	{
		Assume.assumeTrue("order management is installed",
				Registry.getCoreApplicationContext().containsBean("warehousingOrderCancelStateMappingStrategy"));
		final OrderModel order = pendingStripeOrder("stripe-cancel-it-1", OrderStatus.PAYMENT_PENDING);

		stripeOrderCancellationService.cancelForFailedPayment(order);

		modelService.refresh(order);
		assertEquals(OrderStatus.CANCELLED, order.getStatus());
		assertEquals(Long.valueOf(0L), order.getEntries().get(0).getQuantity());
	}

	@Test
	public void refusedCancelIsLoggedWithTheOrderAndThePaymentIntentAndFailsTheCaller()
	{
		final OrderModel order = pendingStripeOrder("stripe-cancel-it-2", OrderStatus.COMPLETED);

		try (StripeLogCapture log = StripeLogCapture.of(DefaultStripeOrderCancellationService.class))
		{
			final IllegalStateException error = assertThrows(IllegalStateException.class,
					() -> stripeOrderCancellationService.cancelForFailedPayment(order));

			assertTrue(error.getMessage().contains("stripe-cancel-it-2"));
			assertTrue("ERROR log with order and payment intent", log.hasError("stripe-cancel-it-2", PI_ID));
		}
		modelService.refresh(order);
		assertEquals(OrderStatus.COMPLETED, order.getStatus());
	}

	private OrderModel pendingStripeOrder(final String code, final OrderStatus status)
	{
		final ProductModel product = productService.getProductForCode(
				catalogVersionService.getCatalogVersion("testCatalog", "Online"), "testProduct0");

		final StripePaymentInfoModel paymentInfo = modelService.create(StripePaymentInfoModel.class);
		paymentInfo.setCode(code + "-payment");
		paymentInfo.setUser(userService.getAdminUser());
		paymentInfo.setStripePaymentIntentId(PI_ID);

		final OrderModel order = modelService.create(OrderModel.class);
		order.setCode(code);
		order.setUser(userService.getAdminUser());
		order.setCurrency(commonI18NService.getCurrentCurrency());
		order.setDate(new Date());
		order.setNet(Boolean.FALSE);
		order.setStatus(status);
		order.setPaymentInfo(paymentInfo);

		final OrderEntryModel entry = modelService.create(OrderEntryModel.class);
		entry.setOrder(order);
		entry.setEntryNumber(0);
		entry.setProduct(product);
		entry.setUnit(product.getUnit());
		entry.setQuantity(2L);
		entry.setBasePrice(10.0);
		entry.setTotalPrice(20.0);
		order.setEntries(List.of(entry));
		order.setTotalPrice(20.0);
		modelService.saveAll(paymentInfo, order, entry);

		final StripePaymentIntentRecordModel record = modelService.create(StripePaymentIntentRecordModel.class);
		record.setPaymentIntentId(PI_ID);
		record.setCartCode(code);
		record.setCurrencyIso(order.getCurrency().getIsocode());
		record.setAmount(2000L);
		record.setState(StripePaymentIntentState.CANCELED);
		record.setOrder(order);
		modelService.save(record);
		return order;
	}

	private void ensureOrderCancelConfig()
	{
		final List<OrderCancelConfigModel> configs = flexibleSearchService.<OrderCancelConfigModel> search(
				new FlexibleSearchQuery("SELECT {pk} FROM {OrderCancelConfig}")).getResult();
		if (configs.isEmpty())
		{
			final OrderCancelConfigModel config = modelService.create(OrderCancelConfigModel.class);
			config.setOrderCancelAllowed(true);
			config.setCancelAfterWarehouseAllowed(true);
			config.setCompleteCancelAfterShippingStartedAllowed(true);
			config.setPartialCancelAllowed(true);
			config.setPartialOrderEntryCancelAllowed(true);
			config.setQueuedOrderWaitingTime(100);
			modelService.save(config);
		}
	}
}
