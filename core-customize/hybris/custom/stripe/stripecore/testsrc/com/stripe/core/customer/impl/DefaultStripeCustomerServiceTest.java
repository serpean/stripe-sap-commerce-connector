package com.stripe.core.customer.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.customer.dao.StripeCustomerDao;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripeCustomerModel;
import com.stripe.model.Customer;
import com.stripe.param.CustomerUpdateParams;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.commerceservices.enums.CustomerType;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.user.CustomerModel;
import de.hybris.platform.servicelayer.model.ModelService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

@UnitTest
public class DefaultStripeCustomerServiceTest
{
	private StripeGateway gateway;
	private StripeConfigurationService config;
	private StripeCustomerDao dao;
	private ModelService modelService;
	private DefaultStripeCustomerService service;
	private CustomerModel user;
	private CartModel cart;
	private final List<StripeCustomerModel> stored = new ArrayList<>();
	private String accountKey = "acct_1:test";
	private final java.util.Map<String, String> idsByKey = new java.util.HashMap<>();

	@Before
	public void setUp()
	{
		gateway = mock(StripeGateway.class);
		config = mock(StripeConfigurationService.class);
		dao = mock(StripeCustomerDao.class);
		modelService = mock(ModelService.class);
		when(config.isCustomerCreationEnabled()).thenReturn(true);
		when(gateway.retrieveAccountKey()).thenAnswer(i -> accountKey);
		when(gateway.createCustomer(any(), anyString())).thenAnswer(i -> {
			final Customer customer = new Customer();
			customer.setId(idsByKey.computeIfAbsent(i.getArgument(1), k -> "cus_" + (idsByKey.size() + 1)));
			return customer;
		});
		when(modelService.create(StripeCustomerModel.class)).thenAnswer(i -> new StripeCustomerModel());
		org.mockito.Mockito.doAnswer(i -> {
			final Object model = i.getArgument(0);
			if (model instanceof StripeCustomerModel && !stored.contains(model))
			{
				stored.add((StripeCustomerModel) model);
			}
			return null;
		}).when(modelService).save(any());
		org.mockito.Mockito.doAnswer(i -> {
			stored.remove(i.getArgument(0));
			return null;
		}).when(modelService).remove(any(Object.class));
		when(dao.find(any(), anyString())).thenAnswer(i -> stored.stream()
				.filter(s -> s.getAccountKey().equals(i.getArgument(1))).findFirst());

		service = new DefaultStripeCustomerService();
		service.setStripeGateway(gateway);
		service.setStripeConfigurationService(config);
		service.setStripeCustomerDao(dao);
		service.setModelService(modelService);

		user = mock(CustomerModel.class);
		when(user.getUid()).thenReturn("ana@example.com");
		when(user.getContactEmail()).thenReturn("ana.contact@example.com");
		when(user.getType()).thenReturn(null);
		cart = mock(CartModel.class);
		when(cart.getCode()).thenReturn("c1");
		when(cart.getUser()).thenReturn(user);
	}

	@Test
	public void registeredCustomerIsCreatedOnceAndReused()
	{
		final Optional<String> first = service.customerIdFor(cart);
		final Optional<String> second = service.customerIdFor(cart);

		assertEquals(Optional.of("cus_1"), first);
		assertEquals(first, second);
		verify(gateway, times(1)).createCustomer(any(), anyString());
		assertEquals(1, stored.size());
		assertEquals("cus_1", stored.get(0).getStripeCustomerId());
		assertEquals("acct_1:test", stored.get(0).getAccountKey());
	}

	@Test
	public void registeredCustomerUsesStableKeyAndContactEmail()
	{
		when(user.getPk()).thenReturn(de.hybris.platform.core.PK.fromLong(123L));

		service.customerIdFor(cart);

		verify(gateway).createCustomer(any(), eq("customer-123-acct_1:test"));
		final ArgumentCaptor<CustomerUpdateParams> params = ArgumentCaptor.forClass(CustomerUpdateParams.class);
		verify(gateway).updateCustomer(eq("cus_1"), params.capture(), anyString());
		assertEquals("ana.contact@example.com", params.getValue().getEmail());
	}

	@Test
	public void registeredEmailFallsBackToUid()
	{
		when(user.getContactEmail()).thenReturn(null);

		service.customerIdFor(cart);

		final ArgumentCaptor<CustomerUpdateParams> params = ArgumentCaptor.forClass(CustomerUpdateParams.class);
		verify(gateway).updateCustomer(eq("cus_1"), params.capture(), anyString());
		assertEquals("ana@example.com", params.getValue().getEmail());
	}

	@Test
	public void separateCustomerPerAccount()
	{
		service.customerIdFor(cart);
		accountKey = "acct_2:test";
		final Optional<String> other = service.customerIdFor(cart);

		assertEquals(Optional.of("cus_2"), other);
		verify(gateway, times(2)).createCustomer(any(), anyString());
		assertEquals(2, stored.size());
	}

	@Test
	public void guestGetsOneCustomerPerCart()
	{
		when(user.getType()).thenReturn(CustomerType.GUEST);
		when(user.getUid()).thenReturn("guid-1|guest@example.com");

		service.customerIdFor(cart);
		service.customerIdFor(cart);

		verify(gateway, times(2)).createCustomer(any(), eq("customer-cart-c1"));
		assertTrue(stored.isEmpty());
		verify(modelService, never()).save(any(StripeCustomerModel.class));
	}

	@Test
	public void guestCustomerEmailIsUpdatedWhenKnown()
	{
		when(user.getType()).thenReturn(CustomerType.GUEST);
		when(user.getUid()).thenReturn("guid-1");
		when(user.getContactEmail()).thenReturn(null);

		service.customerIdFor(cart);
		verify(gateway, never()).updateCustomer(anyString(), any(), anyString());

		when(user.getContactEmail()).thenReturn("guest@example.com");
		service.customerIdFor(cart);

		final ArgumentCaptor<CustomerUpdateParams> params = ArgumentCaptor.forClass(CustomerUpdateParams.class);
		final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		verify(gateway).updateCustomer(eq("cus_1"), params.capture(), key.capture());
		assertEquals("guest@example.com", params.getValue().getEmail());
		assertTrue(key.getValue().startsWith("customer-email-cus_1-"));
		assertFalse(key.getValue().contains("guest@example.com"));
	}

	@Test
	public void guestEmailComesFromUidAfterPipe()
	{
		when(user.getType()).thenReturn(CustomerType.GUEST);
		when(user.getUid()).thenReturn("guid-1|from.uid@example.com");

		service.customerIdFor(cart);

		final ArgumentCaptor<CustomerUpdateParams> params = ArgumentCaptor.forClass(CustomerUpdateParams.class);
		verify(gateway).updateCustomer(eq("cus_1"), params.capture(), anyString());
		assertEquals("from.uid@example.com", params.getValue().getEmail());
	}

	@Test
	public void changedEmailUpdatesRegisteredCustomer()
	{
		service.customerIdFor(cart);
		when(user.getContactEmail()).thenReturn("new@example.com");

		service.customerIdFor(cart);

		final ArgumentCaptor<CustomerUpdateParams> params = ArgumentCaptor.forClass(CustomerUpdateParams.class);
		verify(gateway, times(2)).updateCustomer(eq("cus_1"), params.capture(), anyString());
		assertEquals("new@example.com", params.getAllValues().get(1).getEmail());
		service.customerIdFor(cart);
		verify(gateway, times(2)).updateCustomer(eq("cus_1"), any(), anyString());
	}

	@Test
	public void disabledReturnsEmpty()
	{
		when(config.isCustomerCreationEnabled()).thenReturn(false);

		assertFalse(service.customerIdFor(cart).isPresent());
		verify(gateway, never()).createCustomer(any(), anyString());
		verify(gateway, never()).retrieveAccountKey();
	}

	@Test
	public void replacingRegisteredCustomerRemovesStoredOneAndCreatesAnother()
	{
		service.customerIdFor(cart);
		final StripeCustomerModel old = stored.get(0);

		final String replacement = service.replaceCustomer(cart, "cus_1");

		assertEquals("cus_2", replacement);
		verify(modelService).remove(old);
		verify(gateway).createCustomer(any(), org.mockito.ArgumentMatchers.endsWith("-replaces-cus_1"));
	}

	@Test
	public void deletedStoredCustomerIsReplacedOnEmailSync()
	{
		service.customerIdFor(cart);
		when(user.getContactEmail()).thenReturn("new@example.com");
		when(gateway.updateCustomer(eq("cus_1"), any(), anyString()))
				.thenThrow(new StripeGatewayException("missing", "resource_missing", false, false, null));

		final Optional<String> id = service.customerIdFor(cart);

		assertEquals(Optional.of("cus_2"), id);
		verify(gateway).createCustomer(any(), org.mockito.ArgumentMatchers.endsWith("-replaces-cus_1"));
		assertEquals(1, stored.size());
		assertEquals("cus_2", stored.get(0).getStripeCustomerId());
	}

	@Test
	public void emailUpdateFailureDoesNotLoseCustomerId()
	{
		when(gateway.updateCustomer(anyString(), any(), anyString()))
				.thenThrow(new StripeGatewayException("down", "api_error", true, false, null));

		assertEquals(Optional.of("cus_1"), service.customerIdFor(cart));
		assertEquals(1, stored.size());
	}

	@Test
	public void concurrentFirstCreateReusesStoredRow()
	{
		final StripeCustomerModel other = new StripeCustomerModel();
		other.setAccountKey("acct_1:test");
		other.setStripeCustomerId("cus_other");
		when(dao.find(any(), anyString())).thenReturn(Optional.empty()).thenReturn(Optional.of(other));
		org.mockito.Mockito.doThrow(new IllegalStateException("unique violation")).when(modelService)
				.save(any(StripeCustomerModel.class));

		assertEquals(Optional.of("cus_other"), service.customerIdFor(cart));
	}
}
