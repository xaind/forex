package com.parker.forex.utils;

import com.dukascopy.api.system.ITesterClient;
import com.dukascopy.api.system.TesterFactory;

/**
 * Utility methods for JFX object management.
 */
public final class JfxUtils {

	private JfxUtils(){}
	
	public static void compile(String source) throws Exception {
		ITesterClient client = TesterFactory.getDefaultInstance();
        client.compileStrategy(source, false);
	}
}
