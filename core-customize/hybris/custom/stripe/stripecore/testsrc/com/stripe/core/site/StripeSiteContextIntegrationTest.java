package com.stripe.core.site;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.stripe.Stripe;
import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.enums.StripeWebhookEventState;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.model.StripeWebhookEventModel;
import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
import de.hybris.platform.servicelayer.ServicelayerTransactionalTest;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskRunner;
import de.hybris.platform.task.TaskService;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.annotation.Resource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The background paths read the Stripe settings of the site of their data (here: the site of the PaymentIntent
 * record), not the global settings, also when the session has no base site.
 */
@IntegrationTest
public class StripeSiteContextIntegrationTest extends ServicelayerTransactionalTest
{
	private static final String SITE_UID = "stripe-it-site";
	private static final String GRACE_KEY = "stripe.webhook.graceperiod.seconds." + SITE_UID;
	private static final String SECRET_KEY = "stripe.secret.key." + SITE_UID;

	@Resource
	private StripeSiteContext stripeSiteContext;
	@Resource
	private StripeConfigurationService stripeConfigurationService;
	@Resource
	private ConfigurationService configurationService;
	@Resource
	private ModelService modelService;
	@Resource
	private TaskService taskService;
	@Resource
	private FlexibleSearchService flexibleSearchService;
	@Resource(name = "stripeWebhookEventTaskRunner")
	private TaskRunner<TaskModel> stripeWebhookEventTaskRunner;

	@Before
	public void setUp()
	{
		final BaseSiteModel site = modelService.create(BaseSiteModel.class);
		site.setUid(SITE_UID);
		modelService.save(site);
		configurationService.getConfiguration().setProperty(GRACE_KEY, "7");
		configurationService.getConfiguration().setProperty(SECRET_KEY, "sk_test_site_only_placeholder");
	}

	@After
	public void tearDown()
	{
		configurationService.getConfiguration().clearProperty(GRACE_KEY);
		configurationService.getConfiguration().clearProperty(SECRET_KEY);
	}

	@Test
	public void siteSettingsAreReadInTheSiteOnly()
	{
		assertEquals(Duration.ofSeconds(7),
				stripeSiteContext.runInSite(SITE_UID, () -> stripeConfigurationService.getWebhookGracePeriod()));
		assertEquals("sk_test_site_only_placeholder",
				stripeSiteContext.runInSite(SITE_UID, () -> stripeConfigurationService.getSecretKey()));
		assertTrue("the session outside keeps the global value",
				!Duration.ofSeconds(7).equals(stripeConfigurationService.getWebhookGracePeriod()));
	}

	@Test
	public void webhookRunnerUsesTheGracePeriodOfTheSiteOfTheRecord() throws Exception
	{
		final Duration wait = graceWaitOfRunnerFor(SITE_UID);

		assertTrue("waits the 7 s of the site, not the global period: " + wait,
				wait.compareTo(Duration.ofSeconds(8)) < 0 && wait.compareTo(Duration.ZERO) > 0);
	}

	@Test
	public void webhookRunnerUsesTheGlobalGracePeriodForARecordOfAnotherSite() throws Exception
	{
		final Duration wait = graceWaitOfRunnerFor("stripe-it-other-site");

		assertTrue("waits the global period: " + wait, wait.compareTo(Duration.ofSeconds(8)) > 0);
	}

	private Duration graceWaitOfRunnerFor(final String recordSiteUid) throws Exception
	{
		final String paymentIntentId = "pi_site_" + System.nanoTime();
		final StripePaymentIntentRecordModel record = modelService.create(StripePaymentIntentRecordModel.class);
		record.setPaymentIntentId(paymentIntentId);
		record.setCartCode("cart-" + paymentIntentId);
		record.setBaseSiteUid(recordSiteUid);
		record.setCurrencyIso("EUR");
		record.setAmount(1000L);
		record.setState(StripePaymentIntentState.CREATED);
		modelService.save(record);

		final StripeWebhookEventModel event = modelService.create(StripeWebhookEventModel.class);
		event.setEventId("evt_site_" + System.nanoTime());
		event.setType("payment_intent.succeeded");
		event.setState(StripeWebhookEventState.RECEIVED);
		event.setPayload("{\"id\":\"" + event.getEventId() + "\",\"object\":\"event\",\"api_version\":\""
				+ Stripe.API_VERSION + "\",\"created\":" + Instant.now().getEpochSecond()
				+ ",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"" + paymentIntentId
				+ "\",\"object\":\"payment_intent\",\"status\":\"succeeded\"}}}");
		modelService.save(event);
		final TaskModel task = modelService.create(TaskModel.class);
		task.setRunnerBean("stripeWebhookEventTaskRunner");
		task.setContextItem(event);
		task.setExecutionDate(Date.from(Instant.now().plus(Duration.ofDays(365))));
		modelService.save(task);

		final Instant before = Instant.now();
		stripeWebhookEventTaskRunner.run(taskService, task);

		final FlexibleSearchQuery query = new FlexibleSearchQuery(
				"SELECT {pk} FROM {Task} WHERE {contextItem} = ?event AND {pk} <> ?task");
		query.addQueryParameter("event", event);
		query.addQueryParameter("task", task);
		final List<TaskModel> next = flexibleSearchService.<TaskModel> search(query).getResult();
		assertEquals("the runner schedules one task for the end of the grace period", 1, next.size());
		return Duration.between(before, next.get(0).getExecutionDate().toInstant());
	}
}
