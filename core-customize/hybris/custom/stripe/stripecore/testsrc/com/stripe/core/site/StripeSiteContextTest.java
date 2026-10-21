package com.stripe.core.site;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
import de.hybris.platform.servicelayer.session.SessionExecutionBody;
import de.hybris.platform.servicelayer.session.SessionService;
import de.hybris.platform.site.BaseSiteService;
import de.hybris.platform.task.RetryLaterException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class StripeSiteContextTest
{
	private SessionService sessionService;
	private BaseSiteService baseSiteService;
	private StripeSiteContext siteContext;
	private BaseSiteModel site;
	private final List<BaseSiteModel> sitesSeenByWork = new ArrayList<>();
	private BaseSiteModel currentSite;

	@Before
	public void setUp()
	{
		sessionService = mock(SessionService.class);
		baseSiteService = mock(BaseSiteService.class);
		when(sessionService.executeInLocalView(any(SessionExecutionBody.class))).thenAnswer(invocation -> {
			final BaseSiteModel outside = currentSite;
			try
			{
				return invocation.<SessionExecutionBody> getArgument(0).execute();
			}
			finally
			{
				currentSite = outside;
			}
		});
		when(baseSiteService.getCurrentBaseSite()).thenAnswer(invocation -> currentSite);
		org.mockito.Mockito.doAnswer(invocation -> {
			currentSite = invocation.getArgument(0);
			return null;
		}).when(baseSiteService).setCurrentBaseSite(any(BaseSiteModel.class), org.mockito.ArgumentMatchers.anyBoolean());

		site = new BaseSiteModel();
		site.setUid("site-a");
		when(baseSiteService.getBaseSiteForUID("site-a")).thenReturn(site);

		siteContext = new StripeSiteContext();
		siteContext.setSessionService(sessionService);
		siteContext.setBaseSiteService(baseSiteService);
	}

	@Test
	public void workRunsInTheSiteOfTheUidAndTheSessionKeepsItsSite()
	{
		final String result = siteContext.runInSite("site-a", () -> {
			sitesSeenByWork.add(baseSiteService.getCurrentBaseSite());
			return "done";
		});

		assertEquals("done", result);
		assertEquals(List.of(site), sitesSeenByWork);
		verify(baseSiteService).setCurrentBaseSite(site, false);
		assertEquals("the outer session has no site again", null, currentSite);
	}

	@Test
	public void workRunsInTheGivenSite()
	{
		siteContext.runInSite(site, () -> sitesSeenByWork.add(baseSiteService.getCurrentBaseSite()));

		assertEquals(List.of(site), sitesSeenByWork);
	}

	@Test
	public void checkedExceptionOfTheWorkReachesTheCaller()
	{
		final RetryLaterException retry = new RetryLaterException("later");
		try
		{
			siteContext.runInSite(site, () -> {
				throw retry;
			});
			fail("RetryLaterException expected");
		}
		catch (final RetryLaterException e)
		{
			assertSame(retry, e);
		}
	}

	@Test
	public void unknownOrBlankSiteRunsTheWorkInTheCurrentSession()
	{
		assertEquals("a", siteContext.runInSite("unknown", () -> "a"));
		assertEquals("b", siteContext.runInSite("", () -> "b"));
		assertEquals("c", siteContext.runInSite((BaseSiteModel) null, () -> "c"));

		verify(sessionService, never()).executeInLocalView(any(SessionExecutionBody.class));
	}
}
