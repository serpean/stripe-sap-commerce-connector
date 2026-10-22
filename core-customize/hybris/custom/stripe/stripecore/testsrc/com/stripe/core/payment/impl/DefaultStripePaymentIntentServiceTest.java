package com.stripe.core.payment.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.config.StripeCaptureMode;
import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderPlacementLockService;
import com.stripe.core.payment.StripeIntentData;
import com.stripe.core.payment.StripePaymentInProgressException;
import com.stripe.core.payment.StripeLineItemsBuilder;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentUpdateParams;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.servicelayer.model.ModelService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

@UnitTest
public class DefaultStripePaymentIntentServiceTest
{
	private StripeGateway gateway;
	private StripeConfigurationService config;
	private StripePaymentIntentRecordDao recordDao;
	private ModelService modelService;
	private StripeOrderPlacementLockService lockService;
	private StripePaymentIntentRecordService recordService;
	private StripeOrderAuthorizationService authorizationService;
	private StripeLineItemsBuilder lineItemsBuilder;
	private DefaultStripePaymentIntentService service;
	private CartModel cart;

	@Before
	public void setUp()
	{
		gateway = mock(StripeGateway.class);
		config = mock(StripeConfigurationService.class);
		recordDao = mock(StripePaymentIntentRecordDao.class);
		modelService = mock(ModelService.class);
		final StripeAmountConverter converter = mock(StripeAmountConverter.class);
		when(converter.chargeAmountMinor(any(CartModel.class)))
				.thenAnswer(i -> BigDecimal.valueOf(((CartModel) i.getArgument(0)).getTotalPrice()).movePointRight(2).longValueExact());

		when(config.getCaptureMode()).thenReturn(StripeCaptureMode.MANUAL);
		when(config.getManualCaptureMethods()).thenReturn(Set.of("card", "paypal"));
		when(config.getStatementDescriptorSuffix()).thenReturn("SHOP");
		when(modelService.create(StripePaymentInfoModel.class)).thenAnswer(i -> new StripePaymentInfoModel());
		when(modelService.create(StripePaymentIntentRecordModel.class)).thenAnswer(i -> new StripePaymentIntentRecordModel());
		when(recordDao.findByPaymentIntentId(anyString())).thenReturn(Optional.empty());

		service = new DefaultStripePaymentIntentService();
		service.setStripeGateway(gateway);
		service.setStripeConfigurationService(config);
		service.setStripeAmountConverter(converter);
		service.setStripePaymentIntentRecordDao(recordDao);
		service.setModelService(modelService);
		lockService = mock(StripeOrderPlacementLockService.class);
		recordService = mock(StripePaymentIntentRecordService.class);
		authorizationService = mock(StripeOrderAuthorizationService.class);
		when(lockService.tryAcquire(anyString())).thenReturn(true);
		when(recordService.findLiveOrder(anyString())).thenReturn(Optional.empty());
		service.setStripeOrderPlacementLockService(lockService);
		service.setStripePaymentIntentRecordService(recordService);
		service.setStripeOrderAuthorizationService(authorizationService);
		lineItemsBuilder = mock(StripeLineItemsBuilder.class);
		when(lineItemsBuilder.build(any(), anyLong())).thenReturn(Map.of());
		service.setStripeLineItemsBuilder(lineItemsBuilder);

		final CurrencyModel currency = new CurrencyModel();
		currency.setIsocode("EUR");
		final BaseSiteModel site = new BaseSiteModel();
		site.setUid("electronics");
		final UserModel user = new UserModel();
		user.setUid("ana@example.com");
		cart = new CartModel();
		cart.setCode("c1");
		cart.setGuid("g1");
		cart.setTotalPrice(12.0);
		cart.setCurrency(currency);
		cart.setSite(site);
		cart.setUser(user);
	}

	private PaymentIntent intent(final String id, final String status, final long amount)
	{
		final PaymentIntent intent = new PaymentIntent();
		intent.setId(id);
		intent.setStatus(status);
		intent.setAmount(amount);
		intent.setCurrency("eur");
		intent.setClientSecret("secret_" + id);
		return intent;
	}

	private void cartHasIntent(final String id)
	{
		final StripePaymentInfoModel info = new StripePaymentInfoModel();
		info.setStripePaymentIntentId(id);
		cart.setPaymentInfo(info);
	}

	private PaymentIntentCreateParams createdParams()
	{
		final ArgumentCaptor<PaymentIntentCreateParams> captor = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
		verify(gateway).createPaymentIntent(captor.capture(), anyString());
		return captor.getValue();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> options(final PaymentIntentCreateParams params)
	{
		return (Map<String, Object>) params.toMap().get("payment_method_options");
	}

	@Test
	public void createsOnePaymentIntentPerCart()
	{
		when(gateway.createPaymentIntent(any(), anyString())).thenReturn(intent("pi_1", "requires_payment_method", 1200));
		when(gateway.retrievePaymentIntent("pi_1")).thenReturn(intent("pi_1", "requires_payment_method", 1200));

		final StripeIntentData first = service.prepareForCart(cart);
		final StripeIntentData second = service.prepareForCart(cart);

		verify(gateway, times(1)).createPaymentIntent(any(), anyString());
		assertEquals("pi_1", first.getPaymentIntentId());
		assertEquals("secret_pi_1", first.getClientSecret());
		assertEquals("pi_1", second.getPaymentIntentId());
		assertEquals("pi_1", ((StripePaymentInfoModel) cart.getPaymentInfo()).getStripePaymentIntentId());
	}

	@Test
	public void createsRecordAndUsesStableIdempotencyKey()
	{
		when(recordDao.countByCartCode("c1")).thenReturn(2);
		when(gateway.createPaymentIntent(any(), anyString())).thenReturn(intent("pi_1", "requires_payment_method", 1200));

		service.prepareForCart(cart);

		verify(gateway).createPaymentIntent(any(), eq("cart-c1-g1-pi-3"));
		final ArgumentCaptor<Object> saved = ArgumentCaptor.forClass(Object.class);
		verify(modelService, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
		final StripePaymentIntentRecordModel record = saved.getAllValues().stream()
				.filter(StripePaymentIntentRecordModel.class::isInstance).map(StripePaymentIntentRecordModel.class::cast)
				.findFirst().orElseThrow();
		assertEquals("pi_1", record.getPaymentIntentId());
		assertEquals(Long.valueOf(1200), record.getAmount());
		assertEquals(StripePaymentIntentState.CREATED, record.getState());
		assertEquals("c1", record.getCartCode());
	}

	@Test
	public void updatesAmountWhenCartTotalChanges()
	{
		cartHasIntent("pi_1");
		cart.setTotalPrice(12.0);
		when(gateway.retrievePaymentIntent("pi_1")).thenReturn(intent("pi_1", "requires_payment_method", 1000));
		when(gateway.updatePaymentIntent(anyString(), any(), anyString()))
				.thenReturn(intent("pi_1", "requires_payment_method", 1200));

		service.prepareForCart(cart);

		final ArgumentCaptor<PaymentIntentUpdateParams> captor = ArgumentCaptor.forClass(PaymentIntentUpdateParams.class);
		verify(gateway).updatePaymentIntent(eq("pi_1"), captor.capture(), anyString());
		assertEquals(1200L, captor.getValue().toMap().get("amount"));
		verify(gateway, never()).createPaymentIntent(any(), anyString());
	}

	@Test
	public void createSendsLineItems()
	{
		when(lineItemsBuilder.build(cart, 1200L))
				.thenReturn(Map.of("amount_details", Map.of("shipping", Map.of("amount", 100L))));
		when(gateway.createPaymentIntent(any(), anyString())).thenReturn(intent("pi_1", "requires_payment_method", 1200));

		service.prepareForCart(cart);

		final PaymentIntentCreateParams params = createdParams();
		assertEquals(Map.of("shipping", Map.of("amount", 100L)), params.toMap().get("amount_details"));
		assertEquals(1200L, params.toMap().get("amount"));
	}

	@Test
	public void createWithoutLineItemsKeepsTheAmount()
	{
		when(gateway.createPaymentIntent(any(), anyString())).thenReturn(intent("pi_1", "requires_payment_method", 1200));

		service.prepareForCart(cart);

		final PaymentIntentCreateParams params = createdParams();
		assertFalse(params.toMap().containsKey("amount_details"));
		assertEquals(1200L, params.toMap().get("amount"));
	}

	@Test
	public void updateSendsLineItems()
	{
		cartHasIntent("pi_1");
		when(lineItemsBuilder.build(cart, 1200L))
				.thenReturn(Map.of("amount_details", Map.of("shipping", Map.of("amount", 100L))));
		when(gateway.retrievePaymentIntent("pi_1")).thenReturn(intent("pi_1", "requires_payment_method", 1000));
		when(gateway.updatePaymentIntent(anyString(), any(), anyString()))
				.thenReturn(intent("pi_1", "requires_payment_method", 1200));

		service.prepareForCart(cart);

		final ArgumentCaptor<PaymentIntentUpdateParams> captor = ArgumentCaptor.forClass(PaymentIntentUpdateParams.class);
		verify(gateway).updatePaymentIntent(eq("pi_1"), captor.capture(), anyString());
		assertEquals(Map.of("shipping", Map.of("amount", 100L)), captor.getValue().toMap().get("amount_details"));
		assertEquals(1200L, captor.getValue().toMap().get("amount"));
	}

	@Test
	public void updateWithoutLineItemsKeepsTheAmount()
	{
		cartHasIntent("pi_1");
		when(gateway.retrievePaymentIntent("pi_1")).thenReturn(intent("pi_1", "requires_payment_method", 1000));
		when(gateway.updatePaymentIntent(anyString(), any(), anyString()))
				.thenReturn(intent("pi_1", "requires_payment_method", 1200));

		service.prepareForCart(cart);

		final ArgumentCaptor<PaymentIntentUpdateParams> captor = ArgumentCaptor.forClass(PaymentIntentUpdateParams.class);
		verify(gateway).updatePaymentIntent(eq("pi_1"), captor.capture(), anyString());
		assertFalse(captor.getValue().toMap().containsKey("amount_details"));
		assertEquals(1200L, captor.getValue().toMap().get("amount"));
	}

	@Test
	public void doesNotUpdateWhenAmountAndCurrencyMatch()
	{
		cartHasIntent("pi_1");
		when(gateway.retrievePaymentIntent("pi_1")).thenReturn(intent("pi_1", "requires_action", 1200));

		service.prepareForCart(cart);

		verify(gateway, never()).updatePaymentIntent(anyString(), any(), anyString());
	}

	@Test
	public void replacesConfirmedIntentWhenCartChanges()
	{
		cartHasIntent("pi_old");
		when(gateway.retrievePaymentIntent("pi_old")).thenReturn(intent("pi_old", "requires_capture", 1000));
		when(gateway.createPaymentIntent(any(), anyString())).thenReturn(intent("pi_new", "requires_payment_method", 1200));

		final StripeIntentData data = service.prepareForCart(cart);

		verify(gateway).cancelPaymentIntent(eq("pi_old"), eq("release-pi_old-cancel"));
		verify(gateway).createPaymentIntent(any(), anyString());
		assertEquals("pi_new", data.getPaymentIntentId());
		assertEquals("pi_new", ((StripePaymentInfoModel) cart.getPaymentInfo()).getStripePaymentIntentId());
		verify(lockService).tryAcquire("pi_old");
		verify(lockService, never()).release(anyString());
		verify(authorizationService).removeAuthorizations(cart, "pi_old");
	}

	@Test
	public void cartChangeInAnotherTabDoesNotReleaseWhileTheOrderIsPlaced()
	{
		cartHasIntent("pi_old");
		final de.hybris.platform.core.model.order.payment.PaymentInfoModel info = cart.getPaymentInfo();
		when(gateway.retrievePaymentIntent("pi_old")).thenReturn(intent("pi_old", "requires_capture", 1000));
		when(lockService.tryAcquire("pi_old")).thenReturn(false);

		assertPaymentInProgress();

		assertTrue(info == cart.getPaymentInfo());
		verify(gateway, never()).cancelPaymentIntent(anyString(), anyString());
		verify(gateway, never()).createRefund(anyString(), anyLong(), anyString());
		verify(gateway, never()).createPaymentIntent(any(), anyString());
		verify(lockService, never()).release(anyString());
		verify(authorizationService, never()).removeAuthorizations(any(), anyString());
	}

	@Test
	public void confirmedIntentWithAnOrderIsNotReleased()
	{
		cartHasIntent("pi_old");
		when(gateway.retrievePaymentIntent("pi_old")).thenReturn(intent("pi_old", "succeeded", 1000));
		when(recordService.findLiveOrder("pi_old")).thenReturn(Optional.of(new OrderModel()));

		assertPaymentInProgress();

		verify(gateway, never()).createRefund(anyString(), anyLong(), anyString());
		verify(gateway, never()).createPaymentIntent(any(), anyString());
		verify(lockService, never()).release(anyString());
	}

	@Test
	public void failedReleaseGivesTheLockBack()
	{
		cartHasIntent("pi_old");
		when(gateway.retrievePaymentIntent("pi_old")).thenReturn(intent("pi_old", "requires_capture", 1000));
		final StripeGatewayException down = new StripeGatewayException("down", "api_error", true, false, null);
		doThrow(down).when(gateway).cancelPaymentIntent(anyString(), anyString());

		try
		{
			service.prepareForCart(cart);
			org.junit.Assert.fail("Expected StripeGatewayException");
		}
		catch (final StripeGatewayException e)
		{
			assertTrue(e == down);
		}
		verify(lockService).release("pi_old");
		verify(gateway, never()).createPaymentIntent(any(), anyString());
		verify(authorizationService, never()).removeAuthorizations(any(), anyString());
	}

	private void assertPaymentInProgress()
	{
		try
		{
			service.prepareForCart(cart);
			org.junit.Assert.fail("Expected StripePaymentInProgressException");
		}
		catch (final StripePaymentInProgressException e)
		{
			assertTrue(e.getMessage().contains("pi_old"));
		}
	}

	@Test
	public void manualModeSetsManualCaptureOnlyForAllowedMethods()
	{
		when(config.getManualCaptureMethods()).thenReturn(Set.of("card", "bancontact"));
		when(gateway.createPaymentIntent(any(), anyString())).thenReturn(intent("pi_1", "requires_payment_method", 1200));

		service.prepareForCart(cart);

		final PaymentIntentCreateParams params = createdParams();
		final Map<String, Object> options = options(params);
		assertEquals(Map.of("capture_method", "manual"), options.get("card"));
		assertFalse(options.containsKey("bancontact"));
		assertFalse(options.containsKey("paypal"));
		assertEquals("automatic", params.toMap().get("capture_method"));
	}

	@Test
	public void automaticModeUsesAutomaticCapture()
	{
		when(config.getCaptureMode()).thenReturn(StripeCaptureMode.AUTOMATIC);
		when(gateway.createPaymentIntent(any(), anyString())).thenReturn(intent("pi_1", "requires_payment_method", 1200));

		service.prepareForCart(cart);

		final PaymentIntentCreateParams params = createdParams();
		assertEquals("automatic", params.toMap().get("capture_method"));
		assertTrue(options(params) == null || options(params).isEmpty());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void metadataContainsCartSiteUserCurrency()
	{
		when(gateway.createPaymentIntent(any(), anyString())).thenReturn(intent("pi_1", "requires_payment_method", 1200));

		service.prepareForCart(cart);

		final PaymentIntentCreateParams params = createdParams();
		final Map<String, String> metadata = (Map<String, String>) params.toMap().get("metadata");
		assertEquals("c1", metadata.get("cartCode"));
		assertEquals("electronics", metadata.get("baseSiteUid"));
		assertEquals("ana@example.com", metadata.get("userUid"));
		assertEquals("EUR", metadata.get("currencyIso"));
		assertEquals("SHOP", params.toMap().get("statement_descriptor_suffix"));
		assertNotNull(params.toMap().get("automatic_payment_methods"));
	}

	@Test
	public void releaseFundsRefundsSucceededIntent()
	{
		when(gateway.retrievePaymentIntent("pi_1")).thenReturn(captured(1000L, 0L));
		final StripePaymentIntentRecordModel record = new StripePaymentIntentRecordModel();
		when(recordDao.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(record));

		service.releaseFunds("pi_1", "test");

		verify(gateway).createRefund("pi_1", 1000L, "release-pi_1-refund-1000");
		verify(gateway, never()).cancelPaymentIntent(anyString(), anyString());
		assertEquals(StripePaymentIntentState.REFUNDED, record.getState());
	}

	@Test
	public void releaseFundsRefundsOnlyRemainingAmount()
	{
		when(gateway.retrievePaymentIntent("pi_1")).thenReturn(captured(1000L, 400L));
		final StripePaymentIntentRecordModel record = new StripePaymentIntentRecordModel();
		when(recordDao.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(record));

		service.releaseFunds("pi_1", "test");

		verify(gateway).createRefund("pi_1", 600L, "release-pi_1-refund-600");
		assertEquals(StripePaymentIntentState.REFUNDED, record.getState());
	}

	@Test
	public void releaseFundsOfFullyRefundedIntentDoesNotCallStripe()
	{
		when(gateway.retrievePaymentIntent("pi_1")).thenReturn(captured(1000L, 1000L));
		final StripePaymentIntentRecordModel record = new StripePaymentIntentRecordModel();
		when(recordDao.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(record));

		service.releaseFunds("pi_1", "test");

		verify(gateway, never()).createRefund(anyString(), anyLong(), anyString());
		verify(gateway, never()).cancelPaymentIntent(anyString(), anyString());
		assertEquals(StripePaymentIntentState.REFUNDED, record.getState());
	}

	@Test
	public void releaseFundsWithoutChargeDetailsRefundsTheReceivedAmount()
	{
		final PaymentIntent withoutCharge = intent("pi_1", "succeeded", 1000);
		withoutCharge.setAmountReceived(1000L);
		when(gateway.retrievePaymentIntent("pi_1")).thenReturn(withoutCharge);
		when(recordDao.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(new StripePaymentIntentRecordModel()));

		service.releaseFunds("pi_1", "test");

		verify(gateway).createRefund("pi_1", 1000L, "release-pi_1-refund-1000");
	}

	private PaymentIntent captured(final long received, final long refunded)
	{
		final PaymentIntent intent = intent("pi_1", "succeeded", received);
		intent.setAmountReceived(received);
		final Charge charge = new Charge();
		charge.setId("ch_1");
		charge.setAmountRefunded(refunded);
		intent.setLatestChargeObject(charge);
		return intent;
	}

	@Test
	public void releaseFundsCancelsAuthorizedIntent()
	{
		when(gateway.retrievePaymentIntent("pi_1")).thenReturn(intent("pi_1", "requires_capture", 1000));
		final StripePaymentIntentRecordModel record = new StripePaymentIntentRecordModel();
		when(recordDao.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(record));

		service.releaseFunds("pi_1", "test");

		verify(gateway).cancelPaymentIntent("pi_1", "release-pi_1-cancel");
		verify(gateway, never()).createRefund(anyString(), anyLong(), anyString());
		assertEquals(StripePaymentIntentState.CANCELED, record.getState());
	}

	@Test
	public void unchangedCartKeepsConfirmedIntent()
	{
		cartHasIntent("pi_1");
		when(gateway.retrievePaymentIntent("pi_1")).thenReturn(intent("pi_1", "requires_capture", 1200));

		final StripeIntentData data = service.prepareForCart(cart);

		assertEquals("pi_1", data.getPaymentIntentId());
		verify(gateway, never()).cancelPaymentIntent(anyString(), anyString());
		verify(gateway, never()).createRefund(anyString(), anyLong(), anyString());
		verify(gateway, never()).createPaymentIntent(any(), anyString());
		verify(gateway, never()).updatePaymentIntent(anyString(), any(), anyString());
	}

	@Test
	public void canceledIntentIsReplaced()
	{
		cartHasIntent("pi_old");
		when(gateway.retrievePaymentIntent("pi_old")).thenReturn(intent("pi_old", "canceled", 1200));
		when(gateway.createPaymentIntent(any(), anyString())).thenReturn(intent("pi_new", "requires_payment_method", 1200));

		final StripeIntentData data = service.prepareForCart(cart);

		assertEquals("pi_new", data.getPaymentIntentId());
		assertEquals("pi_new", ((StripePaymentInfoModel) cart.getPaymentInfo()).getStripePaymentIntentId());
		verify(authorizationService).removeAuthorizations(cart, "pi_old");
		verify(lockService, never()).tryAcquire(anyString());
	}

	@Test
	public void processingIntentWithChangedCartThrows()
	{
		cartHasIntent("pi_1");
		when(recordDao.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(new StripePaymentIntentRecordModel()));
		final de.hybris.platform.core.model.order.payment.PaymentInfoModel info = cart.getPaymentInfo();
		when(gateway.retrievePaymentIntent("pi_1")).thenReturn(intent("pi_1", "processing", 1000));

		try
		{
			service.prepareForCart(cart);
			org.junit.Assert.fail("Expected StripePaymentInProgressException");
		}
		catch (final StripePaymentInProgressException e)
		{
			assertTrue(info == cart.getPaymentInfo());
		}
		verify(gateway, never()).cancelPaymentIntent(anyString(), anyString());
		verify(gateway, never()).createPaymentIntent(any(), anyString());
		verify(modelService, never()).save(any(StripePaymentIntentRecordModel.class));
	}

	@Test
	public void missingRecordIsCreated()
	{
		cartHasIntent("pi_1");
		when(gateway.retrievePaymentIntent("pi_1")).thenReturn(intent("pi_1", "requires_capture", 1200));

		service.prepareForCart(cart);

		final ArgumentCaptor<StripePaymentIntentRecordModel> captor = ArgumentCaptor
				.forClass(StripePaymentIntentRecordModel.class);
		verify(modelService).save(captor.capture());
		assertEquals("pi_1", captor.getValue().getPaymentIntentId());
		assertEquals(StripePaymentIntentState.AUTHORIZED, captor.getValue().getState());
		assertEquals(Long.valueOf(1200), captor.getValue().getAmount());
	}
}
