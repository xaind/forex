package com.parker.forex.strategies.archived;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.Filter;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IConsole;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine;
import com.dukascopy.api.IHistory;
import com.dukascopy.api.IIndicators;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.OfferSide;
import com.dukascopy.api.Period;

/**
 * Determines buy and sell trigger points based on the crossover of a fast and slow exponential moving average with positive
 * ADX confirmation.
 */
public class MadManStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final String NAME = "MADMAN";
    private static final double BASE_LOT_SIZE = 0.001;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS Z");
    
    static {
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IAccount account;
    private IEngine engine;
    private IHistory history;
    private IIndicators indicators;
    private IConsole console;
    
    private int orderCounter = 1;
    private long previousBarTime;
    
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Equity per Trade (%)")
    public double equityPerTradePct = 2.0;
    
    @Configurable(value = "Slippage Pips")
    public int slippagePips = 0;
    
    @Configurable(value = "Take Profit Pips")
    public int takeProfitPips = 11;
    
    @Configurable(value = "Stop Loss Pips")
    public int stopLossPips = 7;
    
    @Configurable(value = "Bar Period")
    public Period barPeriod = Period.FIFTEEN_MINS;
    
    @Configurable(value = "Fast EMA Period")
    public int fastEmaPeriod = 20;
    
    @Configurable(value = "Slow EMA Period")
    public int slowEmaPeriod = 50;
    
    @Configurable(value = "ADX Period")
    public int adxPeriod = 14;
    
    @Configurable(value = "ADX Lower Threshold")
    public double adxLowerThreshold = 15.0;
    
    @Configurable(value = "ADX Upper Threshold")
    public double adxUpperThreshold = 30.0;
    
    @Configurable(value = "Stop Loss Buffer (Pips)")
    public int stopLossBuffer = 3;
    
    @Configurable(value = "Risk/Return Ratio (1:n)")
    public double riskReturnRatio = 1;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message) {
        console.getOut().println(message);
    }
    
    private boolean hasOpenPosition() throws JFException {
        return !engine.getOrders(instrument).isEmpty();
    }
    
    private void closePosition() throws JFException {
        for (IOrder order : engine.getOrders(instrument)) {
            if (order.getLabel().contains(NAME)) {
                order.close();
            }
        }
    }
    
    private double getLotSize() throws JFException {
        double lotSize = (account.getBaseEquity() * equityPerTradePct / 100.0) ;
        lotSize = Math.round(lotSize / 1000.0) * BASE_LOT_SIZE;
        
        if (lotSize == 0) {
            log("Lot size is zero. No more trades can be placed. [equity=" + account.getBaseEquity() + ",equityPerTradePct=" + equityPerTradePct + "]");
            onStop();
            System.exit(0);
        } else {
            log("About to place order with lot size: " + lotSize);
        }
        
        return lotSize;
    }
    
    private IOrder buy(double askPrice, long time) throws JFException {
        //double stopPrice = getPreciseValue(askPrice - (instrument.getPipValue() * stopLossPips));
        //double takeProfitPrice = getPreciseValue(askPrice + (instrument.getPipValue() * takeProfitPips));
        
    	double stopPrice = getPreciseValue(getStopLoss(time, true));
    	if (stopPrice < (askPrice - (instrument.getPipValue() * 10))) {
    		return null;
    	}
    	
    	double takeProfitPrice = getPreciseValue(askPrice + ((askPrice - stopPrice) * riskReturnRatio) - (instrument.getPipValue() * 0));
    	//double takeProfitPrice = getPreciseValue(askPrice + (tickData.getInstrument().getPipValue() * 20));
        
        IOrder order = engine.submitOrder(getNextOrderId(), instrument, IEngine.OrderCommand.BUY, getLotSize()); //, 0, slippagePips, stopPrice, takeProfitPrice);
        
        order.waitForUpdate(State.FILLED);
        order.setStopLossPrice(stopPrice);
        order.setTakeProfitPrice(takeProfitPrice);
        
        return order;
    }
    
    private IOrder sell(double bidPrice, long time) throws JFException {
        //double stopPrice = getPreciseValue(bidPrice + (instrument.getPipValue() * stopLossPips));
        //double takeProfitPrice = getPreciseValue(bidPrice - (instrument.getPipValue() * takeProfitPips));
        
        double stopPrice = getPreciseValue(getStopLoss(time, false));
        if (stopPrice > (bidPrice + (instrument.getPipValue() * 10))) {
        	return null;
        }
        
        double takeProfitPrice = getPreciseValue(bidPrice - ((stopPrice - bidPrice) * riskReturnRatio) + (instrument.getPipValue() * 0));
        //double takeProfitPrice = getPreciseValue(bidPrice - (tickData.getInstrument().getPipValue() * 20));
        
        IOrder order = engine.submitOrder(getNextOrderId(), instrument, IEngine.OrderCommand.SELL, getLotSize()); //, 0, slippagePips, stopPrice, takeProfitPrice);
        
        order.waitForUpdate(State.FILLED);
        order.setStopLossPrice(stopPrice);
        order.setTakeProfitPrice(takeProfitPrice);
        
        return order;
    }
    
    private String getNextOrderId() {
        return NAME + "_" + instrument.name().replace("/", "") + "_" + (orderCounter++);
    }
    
    private double getPreciseValue(double value) {
        return getPreciseValue(value, 5);
    }
    
    private double getPreciseValue(double value, int precision) {
        return Double.parseDouble(String.format("%." + precision + "f", value));
    }
    
    private boolean isUpwardXover(double[] fastEma, double[] slowEma) {
        return fastEma[0] < slowEma[0] && fastEma[1] > slowEma[1];
    }
    
    private boolean isDownwardXover(double[] fastEma, double[] slowEma) {
        return fastEma[0] > slowEma[0] && fastEma[1] < slowEma[1];
    }
    
    private boolean isAdxValid(double[] adx) {
        return adx[1] >= adxLowerThreshold && adx[1] <= adxUpperThreshold && (adx[1] -  adx[0] > 0);
    }
    
    private boolean isDirectionValid(IEngine.OrderCommand orderCommand) throws JFException {
        IBar previousBar = history.getBar(instrument, barPeriod, OfferSide.BID, 1);
        
        if (IEngine.OrderCommand.BUY.equals(orderCommand)) {
            return previousBar.getOpen() < previousBar.getClose();
        } else {
            return previousBar.getOpen() > previousBar.getClose();
        }
    }
    
//    private boolean isClearBehind(IEngine.OrderCommand orderCommand, Long barTime) throws JFException {
//        List<IBar> previousBars = history.getBars(instrument, barPeriod, OfferSide.BID, Filter.ALL_FLATS, lookbackPeriod, barTime, 0);
//        
//        if (IEngine.OrderCommand.BUY.equals(orderCommand)) {
//            IBar currentBar = previousBars.get(previousBars.size() - 1);
//            for (IBar bar : previousBars) {
//                if (!bar.equals(currentBar) && bar.getHigh() - currentBar.getHigh() > lookbackThreshold) {
//                    return false;
//                }
//            }
//        } else {
//            IBar currentBar = previousBars.get(previousBars.size() - 1);
//            for (IBar bar : previousBars) {
//                if (!bar.equals(currentBar) && currentBar.getLow() - bar.getLow() > lookbackThreshold) {
//                    return false;
//                }
//            }
//        }
//        
//        return true;
//    }
    
    private double getStopLoss(long time, boolean isLong) throws JFException {
    	double lowestLow = Double.MAX_VALUE;
    	double currentLow = Double.MAX_VALUE;
    	double highestHigh = 0.0;
    	double currentHigh = 0.0;
    	
    	// Get the tick bars and reverse the list so we scan backwards
    	//List<ITickBar> tickBars = history.getTickBars(instrument, OfferSide.BID, TickBarSize.valueOf(100), 50, time, 0);
    	
    	long barTime = history.getPreviousBarStart(barPeriod, time);
    	List<IBar> bars = history.getBars(instrument, barPeriod, OfferSide.BID, Filter.ALL_FLATS, 50, barTime, 0);
    	Collections.reverse(bars);
    	
    	// Set initial scan direction
    	boolean scanDown = isLong ? true : false;
    		
    	// Determine the highest high and lowest low
		for (IBar bar : bars) {
			if (scanDown) {
    			if (currentLow >= bar.getLow()) {
    				currentLow = bar.getLow();
    			} else {
    				scanDown = false;
    				if (lowestLow > currentLow) {
    					lowestLow = currentLow;
    					break;
    				}
    			}
			} else {
    			if (currentHigh <= bar.getHigh()) {
    				currentHigh = bar.getHigh();
    			} else {
    				scanDown = true;
    				if (highestHigh < currentHigh) {
    					highestHigh = currentHigh;
    					break;
    				}
    			}
			}
		}
    	
		// Subtract some pips from the lowest low or add 2 pips to the highest high
		if (isLong) {
			return lowestLow - (instrument.getPipValue() * stopLossBuffer);
		} else {
			return highestHigh + (instrument.getPipValue() * stopLossBuffer);
		}
    }
    
    private void logOrder(IOrder order, double[] adx) throws JFException {
        if (order != null) {
            order.waitForUpdate(State.FILLED);
            log(order.getLabel() + " @ " + DATE_FORMAT.format(new Date(order.getFillTime())) + ": Placed " + order.getOrderCommand() + " order for " + 
                    (IEngine.OrderCommand.BUY.equals(order.getOrderCommand()) ? "Upwards" : "Downwards") + " Xover @ $" + 
                    getPreciseValue(order.getOpenPrice()) + " (adx=" + getPreciseValue(adx[1]) + ",delta=" + (adx[1] - adx[0]) + ".");
        }
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (instrument.equals(this.instrument)) {
            long barTime = history.getPreviousBarStart(barPeriod, tick.getTime());
            
            if (previousBarTime != barTime) {
                previousBarTime = barTime;
                
                // Calculate indicator values
                double[] fastEma = indicators.ema(instrument, barPeriod, OfferSide.BID, IIndicators.AppliedPrice.CLOSE, fastEmaPeriod, Filter.ALL_FLATS, 2, barTime, 0);
                double[] slowEma = indicators.ema(instrument, barPeriod, OfferSide.BID, IIndicators.AppliedPrice.CLOSE, slowEmaPeriod, Filter.ALL_FLATS, 2, barTime, 0);
                double[] adx = indicators.adx(instrument, barPeriod, OfferSide.BID, adxPeriod, Filter.ALL_FLATS, 2, barTime, 0);
                
                // Check the ADX values
                if (!hasOpenPosition() && isAdxValid(adx)) {
                    IOrder order = null;
                    
                    // Next check for crossovers
                    if (isUpwardXover(fastEma, slowEma) && 
                    		//isClearBehind(IEngine.OrderCommand.BUY, barTime) &&
                    		isDirectionValid(IEngine.OrderCommand.BUY)) {
                        closePosition();
                        order = buy(tick.getBid(), tick.getTime());
                    } else if (isDownwardXover(fastEma, slowEma) && 
                    		//isClearBehind(IEngine.OrderCommand.SELL, barTime) && 
                    		isDirectionValid(IEngine.OrderCommand.SELL)) {
                        closePosition();
                        order = sell(tick.getAsk(), tick.getTime());
                    }
                    
                    logOrder(order, adx);
                }
            }
        }
    }
    
    public void onStart(IContext context) throws JFException {
        account = context.getAccount();
        engine = context.getEngine();
        indicators = context.getIndicators();
        history = context.getHistory();
        console = context.getConsole();
        
        // Subscribe an instrument
        Set<Instrument> instruments = new HashSet<Instrument>();
        instruments.add(instrument);                     
        context.setSubscribedInstruments(instruments, true);
        
        log("Started MADMAN strategy using " + instrument + ".");
    }

    public void onStop() throws JFException {
        closePosition();
        log("Strategy stopped.");
    }
    
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException { 
    }
    
    public void onMessage(IMessage message) throws JFException {
        if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
            IOrder order = message.getOrder();
            log(order.getLabel() + " @ " + DATE_FORMAT.format(new Date(order.getCloseTime())) + ": Closed " + order.getOrderCommand() + " order for " + order.getProfitLossInPips() + 
                    " pip " + (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ".");
        }
    }

    public void onAccount(IAccount account) throws JFException {
    }
}