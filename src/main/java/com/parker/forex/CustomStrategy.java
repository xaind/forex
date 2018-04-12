package com.parker.forex;

import com.dukascopy.api.IStrategy;
import com.dukascopy.api.Instrument;

/**
 * Adds a few enhancements to the base Strategy. 
 */
public interface CustomStrategy extends IStrategy {

	String getName();
	String getDescription();
	Instrument getInstrument();
}
