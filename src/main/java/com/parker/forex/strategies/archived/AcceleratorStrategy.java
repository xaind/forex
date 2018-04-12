package com.parker.forex.strategies.archived;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IConsole;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;

/**
 * Determines buy and sell trigger points based on the crossover of a fast and slow exponential moving average. Postions
 * are closed on the either the fast EMA turning points or on the next crossover.
 */
public class AcceleratorStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy hh:mm:ss.SSS a");
    
    static {
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IConsole console;
    
    private long tickCounter;
    private int velocityPeriods = 20;
    
    private ITick previousTick;
    //private double currentBidVelocity;
    //private double currentAskVelocity;
    private double currentAskDuration;
    private double currentAskTotal;
    
    //private double maxPositiveBidVelocity;
    //private double maxNegativeBidVelocity;
    //private Date maxPositiveBidTime;
    //private Date maxNegativeBidTime;
    
    private double maxPositiveAskVelocity;
    private double maxNegativeAskVelocity;
    private Date maxPositiveAskTime;
    private Date maxNegativeAskTime;
    
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Lot Amount")
    public double amount = 0.001;
    
    @Configurable(value = "Slippage Pips")
    public int slippagePips = 0;
    
    @Configurable(value = "Stop Loss Pips")
    public int stopLossPips = 5;
    
    @Configurable(value = "Bar Period")
    public Period barPeriod = Period.FIFTEEN_MINS;
    
    @Configurable(value = "Fast EMA Period")
    public int fastEmaPeriod = 10;
    
    @Configurable(value = "Slow EMA Period")
    public int slowEmaPeriod = 50;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message) {
        console.getOut().println(message);
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (instrument.equals(this.instrument)) {
        	if (previousTick != null) {
        		currentAskDuration = (tick.getTime() - previousTick.getTime()) / 1000.0;
        		currentAskTotal = (tick.getAsk() - previousTick.getAsk());
        		
        		if (tickCounter % velocityPeriods == 0) {
	        		double currentAskVelocity = currentAskTotal / currentAskDuration;
	        		//currentBidVelocity += (tick.getBid() - previousTick.getBid()) / duration;
	        			
	        		Date time = new Date(tick.getTime());
	        		
	        		if (currentAskVelocity > maxPositiveAskVelocity) {
	        			maxPositiveAskVelocity = currentAskVelocity;
	        			maxPositiveAskTime = time;
	        		} else if (currentAskVelocity < maxNegativeAskVelocity) {
	        			maxNegativeAskVelocity = currentAskVelocity;
	        			maxNegativeAskTime = time;
	        		}
	        		
	        		currentAskTotal = 0;
	        		currentAskDuration = 0;
	        		
	//        		if (currentBidVelocity > maxPositiveBidVelocity) {
	//        			maxPositiveBidVelocity = currentBidVelocity;
	//        			maxPositiveBidTime = time;
	//        		} else if (currentBidVelocity < maxNegativeBidVelocity) {
	//        			maxNegativeBidVelocity = currentBidVelocity;
	//        			maxNegativeBidTime = time;
	//        		}
	        		
	        		if (tickCounter % 100 == 0) {
	        			log("currentAskVelocity=" + String.format("%.3f", currentAskVelocity * 1000) + "pips/sec @ " + DATE_FORMAT.format(time)); //, currentBidVelocity=" + String.format("%.1f", currentBidVelocity * 1000) + "pips/sec");
	//        			log("maxPositiveAskVelocity=" + String.format("%.1f", maxPositiveAskVelocity * 1000) + "pips/sec @ " + DATE_FORMAT.format(maxPositiveAskTime) + ", maxNegativeAskVelocity=" + String.format("%.1f", maxNegativeAskVelocity * 1000) + "pips/sec @ " + DATE_FORMAT.format(maxNegativeAskTime));
	//        			log("maxPositiveBidVelocity=" + String.format("%.1f", maxPositiveBidVelocity * 1000) + "pips/sec @ " + DATE_FORMAT.format(maxPositiveBidTime) + ", maxNegativeBidVelocity=" + String.format("%.1f", maxNegativeBidVelocity * 1000) + "pips/sec @ " + DATE_FORMAT.format(maxNegativeBidTime));
	//        			
	        			tickCounter = 0;
	        		}
        		}
        			
        		
        		// Check for crossovers
//    			String orderId = null;
//        		if (previousFastAskPrice < previousSlowAskPrice && currentFastAskPrice > currentSlowAskPrice) {
//        			closePosition();
//        			orderId = buy(tick.getBid());
//        			log(orderId + " @ " + DATE_FORMAT.format(new Date(tick.getTime())) + ": Placed BUY order for Upwards Xover @ $" + String.format("%.5f", tick.getBid()) + ".");
//        		} else if (previousFastAskPrice > previousSlowAskPrice && currentFastAskPrice < currentSlowAskPrice) {
//        			closePosition();
//        			orderId = sell(tick.getAsk());
//        			log(orderId + " @ " + DATE_FORMAT.format(new Date(tick.getTime())) + ": Placed SELL order for Downwards Xover @ $" + String.format("%.5f", tick.getAsk()) + ".");
//        		} 
        	}
            
        	previousTick = tick;
        }
    }
    
    public void onStart(IContext context) throws JFException {
        console = context.getConsole();

        // Subscribe an instrument
        Set<Instrument> instruments = new HashSet<Instrument>();
        instruments.add(instrument);                     
        context.setSubscribedInstruments(instruments, true);
        
        log("Strategy started using " + instrument + ".");
    }

    public void onStop() throws JFException {
		log("maxPositiveAskVelocity=" + String.format("%.1f", maxPositiveAskVelocity * 1000) + "pips/sec @ " + DATE_FORMAT.format(maxPositiveAskTime) + ", maxNegativeAskVelocity=" + String.format("%.1f", maxNegativeAskVelocity * 1000) + "pips/sec @ " + DATE_FORMAT.format(maxNegativeAskTime));
		//log("maxPositiveBidVelocity=" + String.format("%.1f", maxPositiveBidVelocity * 1000) + "pips/sec @ " + DATE_FORMAT.format(maxPositiveBidTime) + ", maxNegativeBidVelocity=" + String.format("%.1f", maxNegativeBidVelocity * 1000) + "pips/sec @ " + DATE_FORMAT.format(maxNegativeBidTime));
		
        log("Strategy stopped.");
    }
    
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException { 
    }
    
    public void onMessage(IMessage message) throws JFException {
    }

    public void onAccount(IAccount account) throws JFException {
    }
}