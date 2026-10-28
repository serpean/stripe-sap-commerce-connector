package com.stripe.core.dao.impl;

import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.customer.dao.StripeCustomerDao;
import com.stripe.core.dao.StripeWebhookEventDao;
import com.stripe.core.model.StripeCustomerModel;
import com.stripe.core.model.StripeOrderPlacementLockModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.model.StripeWebhookEventModel;
import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.user.CustomerModel;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.servicelayer.ServicelayerTransactionalTest;
import de.hybris.platform.servicelayer.exceptions.ModelSavingException;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.user.UserService;
import de.hybris.platform.task.TaskModel;
import org.junit.Test;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@IntegrationTest
public class StripeRecordsIntegrationTest extends ServicelayerTransactionalTest
{
	@Resource
	private ModelService modelService;
	@Resource
	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	@Resource
	private StripeWebhookEventDao stripeWebhookEventDao;
	@Resource
	private StripeCustomerDao stripeCustomerDao;
	@Resource
	private UserService userService;
	@Resource
	private CommonI18NService commonI18NService;

	private StripePaymentIntentRecordModel newRecord(final String paymentIntentId)
	{
		final StripePaymentIntentRecordModel record = modelService.create(StripePaymentIntentRecordModel.class);
		record.setPaymentIntentId(paymentIntentId);
		return record;
	}

	@Test(expected = ModelSavingException.class)
	public void duplicatePaymentIntentIdIsRejected()
	{
		modelService.save(newRecord("pi_dup"));
		modelService.save(newRecord("pi_dup"));
	}

	@Test(expected = ModelSavingException.class)
	public void duplicateLockIsRejected()
	{
		for (int i = 0; i < 2; i++)
		{
			final StripeOrderPlacementLockModel lock = modelService.create(StripeOrderPlacementLockModel.class);
			lock.setPaymentIntentId("pi_lock");
			modelService.save(lock);
		}
	}

	@Test(expected = ModelSavingException.class)
	public void duplicateEventIdIsRejected()
	{
		for (int i = 0; i < 2; i++)
		{
			final StripeWebhookEventModel event = modelService.create(StripeWebhookEventModel.class);
			event.setEventId("evt_dup");
			modelService.save(event);
		}
	}

	@Test
	public void findsRecordByPaymentIntentId()
	{
		final StripePaymentIntentRecordModel record = newRecord("pi_find");
		record.setCartCode("cart1");
		modelService.save(record);

		final Optional<StripePaymentIntentRecordModel> found = stripePaymentIntentRecordDao.findByPaymentIntentId("pi_find");

		assertTrue(found.isPresent());
		assertEquals("cart1", found.get().getCartCode());
		assertFalse(stripePaymentIntentRecordDao.findByPaymentIntentId("pi_missing").isPresent());
	}

	@Test
	public void findsRecordByOrder()
	{
		final OrderModel order = newOrder("order-find");
		final StripePaymentIntentRecordModel record = newRecord("pi_order");
		record.setOrder(order);
		modelService.save(record);

		final Optional<StripePaymentIntentRecordModel> found = stripePaymentIntentRecordDao.findByOrder(order);

		assertTrue(found.isPresent());
		assertEquals("pi_order", found.get().getPaymentIntentId());
	}

	@Test
	public void findsEventByEventId()
	{
		final StripeWebhookEventModel event = modelService.create(StripeWebhookEventModel.class);
		event.setEventId("evt_find");
		modelService.save(event);

		assertTrue(stripeWebhookEventDao.findByEventId("evt_find").isPresent());
		assertFalse(stripeWebhookEventDao.findByEventId("evt_missing").isPresent());
		assertEquals(Integer.valueOf(0), event.getAttempts());
	}

	@Test
	public void findsScheduledTaskOfEvent()
	{
		final StripeWebhookEventModel scheduled = modelService.create(StripeWebhookEventModel.class);
		scheduled.setEventId("evt_task");
		final StripeWebhookEventModel orphan = modelService.create(StripeWebhookEventModel.class);
		orphan.setEventId("evt_orphan");
		modelService.saveAll(scheduled, orphan);
		final TaskModel task = modelService.create(TaskModel.class);
		task.setRunnerBean("stripeWebhookEventTaskRunner");
		task.setContextItem(scheduled);
		task.setExecutionDate(Date.from(Instant.now().plus(Duration.ofDays(1))));
		modelService.save(task);

		assertTrue(stripeWebhookEventDao.hasScheduledTask(scheduled));
		assertFalse(stripeWebhookEventDao.hasScheduledTask(orphan));
	}

	@Test
	public void orderCloneDoesNotTouchRecords()
	{
		final OrderModel order = newOrder("order-clone");
		final StripePaymentIntentRecordModel record = newRecord("pi_clone");
		record.setOrder(order);
		modelService.save(record);

		final OrderModel clone = modelService.clone(order);
		clone.setCode("order-clone-history");
		modelService.save(clone);

		assertTrue(stripePaymentIntentRecordDao.findByPaymentIntentId("pi_clone").isPresent());
	}

	private StripeCustomerModel newStripeCustomer(final CustomerModel customer, final String accountKey, final String stripeId)
	{
		final StripeCustomerModel model = modelService.create(StripeCustomerModel.class);
		model.setCustomer(customer);
		model.setAccountKey(accountKey);
		model.setStripeCustomerId(stripeId);
		return model;
	}

	@Test
	public void stripeCustomerIsUniquePerAccount()
	{
		final CustomerModel customer = modelService.create(CustomerModel.class);
		customer.setUid("stripe-cust-test@example.com");
		customer.setName("Stripe Customer Test");
		modelService.save(customer);
		modelService.save(newStripeCustomer(customer, "default", "cus_1"));

		try
		{
			modelService.save(newStripeCustomer(customer, "default", "cus_2"));
			fail("Second StripeCustomer for the same customer and account key must fail");
		}
		catch (final ModelSavingException expected)
		{
			// expected
		}

		modelService.save(newStripeCustomer(customer, "other", "cus_3"));

		assertEquals("cus_1", stripeCustomerDao.find(customer, "default").orElseThrow().getStripeCustomerId());
		assertEquals("cus_3", stripeCustomerDao.find(customer, "other").orElseThrow().getStripeCustomerId());
		assertEquals("other", stripeCustomerDao.findByStripeCustomerId("cus_3").orElseThrow().getAccountKey());
		assertFalse(stripeCustomerDao.find(customer, "missing").isPresent());
		assertFalse(stripeCustomerDao.findByStripeCustomerId("cus_missing").isPresent());
	}

	private OrderModel newOrder(final String code)
	{
		final UserModel user = userService.getAdminUser();
		final CurrencyModel currency = commonI18NService.getCurrentCurrency();
		final OrderModel order = modelService.create(OrderModel.class);
		order.setCode(code);
		order.setUser(user);
		order.setCurrency(currency);
		order.setDate(new Date());
		modelService.save(order);
		return order;
	}
}
