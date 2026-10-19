package com.stripe.sampledata.setup;

import de.hybris.platform.commerceservices.setup.SetupImpexService;
import de.hybris.platform.core.initialization.SystemSetup;
import de.hybris.platform.core.initialization.SystemSetup.Process;
import de.hybris.platform.core.initialization.SystemSetup.Type;


/**
 * Imports the reference sample data that enables Stripe on the electronics store. Runs after the electronics store
 * and Spartacus sample data, because this extension requires them.
 */
@SystemSetup(extension = "stripesampledata")
public class StripeSampleDataSystemSetup
{
	private static final String STORE_IMPEX = "/stripesampledata/import/sampledata/stores/electronics/store.impex";

	private SetupImpexService setupImpexService;

	@SystemSetup(type = Type.PROJECT, process = Process.ALL)
	public void importStripeSampleData()
	{
		setupImpexService.importImpexFile(STORE_IMPEX, true);
	}

	public void setSetupImpexService(final SetupImpexService setupImpexService)
	{
		this.setupImpexService = setupImpexService;
	}
}
