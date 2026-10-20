package com.stripe.core.payment.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.payment.StripeTransactionLookup;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

@UnitTest
public class DefaultStripePaymentIntentRecordServiceTest
{
	private static final String PI_ID = "pi_link";

	private StripePaymentIntentRecordDao recordDao;
	private StripeTransactionLookup lookup;
	private StripeGateway gateway;
	private FlexibleSearchService flexibleSearchService;
	private ModelService modelService;
	private DefaultStripePaymentIntentRecordService service;
	private OrderModel order;

	@Before
	public void setUp()
	{
		recordDao = mock(StripePaymentIntentRecordDao.class);
		lookup = mock(StripeTransactionLookup.class);
		gateway = mock(StripeGateway.class);
		flexibleSearchService = mock(FlexibleSearchService.class);
		modelService = mock(ModelService.class);
		final StripeAmountConverter converter = mock(StripeAmountConverter.class);

		final CurrencyModel currency = new CurrencyModel();
		currency.setIsocode("EUR");
		order = new OrderModel();
		order.setCode("order1");
		order.setCurrency(currency);
		when(converter.chargeAmountMinor(order)).thenReturn(1999L);
		when(modelService.create(StripePaymentIntentRecordModel.class)).thenAnswer(i -> new StripePaymentIntentRecordModel());

		service = new DefaultStripePaymentIntentRecordService();
		service.setStripePaymentIntentRecordDao(recordDao);
		service.setStripeTransactionLookup(lookup);
		service.setStripeGateway(gateway);
		service.setStripeAmountConverter(converter);
		service.setFlexibleSearchService(flexibleSearchService);
		service.setModelService(modelService);
	}

	@Test
	public void linkOrderKeepsKnownState()
	{
		final StripePaymentIntentRecordModel record = givenRecord(StripePaymentIntentState.AUTHORIZED);

		service.linkOrder(PI_ID, order);

		assertSame(order, record.getOrder());
		assertEquals(StripePaymentIntentState.AUTHORIZED, record.getState());
		verify(modelService).save(record);
		verify(gateway, never()).retrievePaymentIntent(anyString());
	}

	@Test
	public void createdRecordGetsStateFromPaymentIntent()
	{
		final StripePaymentIntentRecordModel record = givenRecord(StripePaymentIntentState.CREATED);
		final PaymentIntent intent = new PaymentIntent();
		intent.setStatus("succeeded");
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent);

		service.linkOrder(PI_ID, order);

		assertSame(order, record.getOrder());
		assertEquals(StripePaymentIntentState.CAPTURED, record.getState());
	}

	@Test
	public void gatewayFailureStillLinksOrder()
	{
		final StripePaymentIntentRecordModel record = givenRecord(StripePaymentIntentState.CREATED);
		when(gateway.retrievePaymentIntent(PI_ID)).thenThrow(new IllegalStateException("stripe down"));

		service.linkOrder(PI_ID, order);

		assertSame(order, record.getOrder());
		assertEquals(StripePaymentIntentState.CREATED, record.getState());
		verify(modelService).save(record);
	}

	@Test
	public void missingRecordIsCreatedForOrder()
	{
		when(recordDao.findByPaymentIntentId(PI_ID)).thenReturn(Optional.empty());
		final PaymentIntent intent = new PaymentIntent();
		intent.setStatus("requires_capture");
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent);

		service.linkOrder(PI_ID, order);

		final ArgumentCaptor<StripePaymentIntentRecordModel> saved = ArgumentCaptor
				.forClass(StripePaymentIntentRecordModel.class);
		verify(modelService).save(saved.capture());
		assertEquals(PI_ID, saved.getValue().getPaymentIntentId());
		assertSame(order, saved.getValue().getOrder());
		assertEquals(Long.valueOf(1999L), saved.getValue().getAmount());
		assertEquals("EUR", saved.getValue().getCurrencyIso());
		assertEquals(StripePaymentIntentState.AUTHORIZED, saved.getValue().getState());
	}

	@Test
	public void liveOrderIsFoundThroughAuthorizationTransaction()
	{
		final PaymentTransactionModel transaction = new PaymentTransactionModel();
		transaction.setOrder(order);
		when(lookup.findByPaymentIntentId(PI_ID)).thenReturn(Optional.of(transaction));

		assertSame(order, service.findLiveOrder(PI_ID).orElseThrow());

		verify(flexibleSearchService, never()).search(any(FlexibleSearchQuery.class));
	}

	private StripePaymentIntentRecordModel givenRecord(final StripePaymentIntentState state)
	{
		final StripePaymentIntentRecordModel record = new StripePaymentIntentRecordModel();
		record.setPaymentIntentId(PI_ID);
		record.setState(state);
		when(recordDao.findByPaymentIntentId(PI_ID)).thenReturn(Optional.of(record));
		return record;
	}
}
