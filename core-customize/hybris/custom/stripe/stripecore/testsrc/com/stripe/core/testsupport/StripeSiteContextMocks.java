package com.stripe.core.testsupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.stripe.core.site.StripeSiteContext;
import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
import org.mockito.ArgumentMatchers;
import org.mockito.stubbing.Answer;

/**
 * A mock {@link StripeSiteContext} that runs the work at once, so a test can check which site was asked for.
 */
public final class StripeSiteContextMocks
{
	private StripeSiteContextMocks()
	{
	}

	public static StripeSiteContext direct()
	{
		final StripeSiteContext siteContext = mock(StripeSiteContext.class);
		final Answer<Object> runWork = invocation -> invocation.<StripeSiteContext.SiteWork<?, ?>> getArgument(1).run();
		try
		{
			doAnswer(runWork).when(siteContext).runInSite(ArgumentMatchers.<BaseSiteModel> any(), any());
			doAnswer(runWork).when(siteContext).runInSite(ArgumentMatchers.<String> any(), any());
		}
		catch (final Exception e)
		{
			throw new IllegalStateException(e);
		}
		return siteContext;
	}
}
