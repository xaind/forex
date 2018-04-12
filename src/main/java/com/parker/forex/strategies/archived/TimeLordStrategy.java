package com.parker.forex.strategies.archived;

import java.text.SimpleDateFormat;
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
 * Determines buy and sell trigger points based on the correlation of bars at different time intervals. If all bars are
 * in the same direction than an entry is triggered in the same direction.
 */
public class TimeLordStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final String NAME = "TIME_LORD";
    private static final double BASE_LOT_SIZE = 0.001;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS Z");
    
    static {
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    // Enum for candle direction
    private enum Direction {UPWARDS, DOWNWARDS, SIDEWAYS};
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IAccount account;
    private IEngine engine;
    private IHistory history;
    private IIndicators indicators;
    private IConsole console;
    
    private int orderCounter = 1;
    private long previousHourlyBarTime;
    
    @Configurable(value = "Instrument")
//    public Instrument instrument = Instrument.USDJPY;
    public Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Equity per Trade (%)")
    public double equityPerTradePct = 2.0;
    
    @Configurable(value = "Slippage Pips")
    public int slippagePips = 0;
    
    @Configurable(value = "Take Profit Pips")
    public int takeProfitPips = 25;
    
    @Configurable(value = "Stop Loss Pips")
    public int stopLossPips = 50;
    
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
        lotSize = 0.01;
        
        if (lotSize == 0) {
            log("Lot size is zero. No more trades can be placed. [equity=" + account.getBaseEquity() + ",equityPerTradePct=" + equityPerTradePct + "]");
            onStop();
            System.exit(0);
        } else {
            log("About to place order with lot size: " + lotSize);
        }
        
        return lotSize;
    }
    
    private IOrder buy(double askPrice) throws JFException {
        double stopPrice = getPreciseValue(askPrice - (instrument.getPipValue() * stopLossPips));
        double takeProfitPrice = getPreciseValue(askPrice + (instrument.getPipValue() * takeProfitPips));
        
        IOrder order = engine.submitOrder(getNextOrderId(), instrument, IEngine.OrderCommand.BUY, getLotSize()); //, 0, slippagePips, stopPrice, takeProfitPrice);
        
        order.waitForUpdate(State.FILLED);
        order.setStopLossPrice(stopPrice);
        order.setTakeProfitPrice(takeProfitPrice);
        
        return order;
    }
    
    private IOrder sell(double bidPrice) throws JFException {
        double stopPrice = getPreciseValue(bidPrice + (instrument.getPipValue() * stopLossPips));
        double takeProfitPrice = getPreciseValue(bidPrice - (instrument.getPipValue() * takeProfitPips));
        
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
    
    private Direction getDirection(Period period, long time) throws JFException {
    	long barTime = history.getPreviousBarStart(period, time);
        List<IBar> bars = history.getBars(instrument, period, OfferSide.BID, Filter.NO_FILTER, 1, barTime, 0);
        IBar bar = bars.get(0);
        
        if (bar.getOpen() == bar.getClose()) {
        	return Direction.SIDEWAYS;
        } else if (bar.getOpen() < bar.getClose()) {
        	return Direction.UPWARDS;
        } else {
        	return Direction.DOWNWARDS;
        }
    }
    
    private boolean isPreviousRangeValid(long time) throws JFException {
    	long barTime = history.getPreviousBarStart(Period.ONE_HOUR, time);
        List<IBar> bars = history.getBars(instrument, Period.ONE_HOUR, OfferSide.BID, Filter.NO_FILTER, 1, barTime, 0);
        IBar bar = bars.get(0);
        return Math.abs(bar.getHigh() - bar.getLow()) < (instrument.getPipValue() * 30);
    }
    
    private boolean isDirectionContinuous(long time) throws JFException {
    	long barTime = history.getPreviousBarStart(Period.FIVE_MINS, time);
        List<IBar> bars = history.getBars(instrument, Period.FIVE_MINS, OfferSide.BID, Filter.NO_FILTER, 3, barTime, 0);
        
        IBar bar1 = bars.get(0);
        IBar bar2 = bars.get(1);
        IBar bar3 = bars.get(2);
        
        int signum1 = (int) Math.signum(bar1.getOpen() - bar1.getClose());
        int signum2 = (int) Math.signum(bar2.getOpen() - bar2.getClose());
        int signum3 = (int) Math.signum(bar3.getOpen() - bar3.getClose());
        
        return  signum1 != 0 && signum1 == signum2 && signum1 == signum3;
    }
    
    private boolean isPriceValid(Direction direction, ITick tick) throws JFException {
    	long time = history.getPreviousBarStart(Period.FIVE_MINS, tick.getTime());
    	double[] lwma = indicators.lwma(instrument, Period.FIVE_MINS, OfferSide.BID, IIndicators.AppliedPrice.CLOSE, 8, Filter.NO_FILTER, 1, time, 0);
    	
        List<IBar> bars = history.getBars(instrument, Period.FIVE_MINS, OfferSide.BID, Filter.NO_FILTER, 1, time, 0);
        IBar bar = bars.get(0);
        
        // Check that the last M5 bar contains the LWMA
        return (bar.getLow() < lwma[0] && bar.getHigh() > lwma[0]);
        
    	// The current price must be on the correct side of the current EMA price
//    	if (Direction.UPWARDS.equals(direction)) {
//    		return (bar.getOpen() < lwma[0] && bar.getClose() > lwma[0]);
//    	} else if (Direction.DOWNWARDS.equals(direction)) {
//    		return (bar.getOpen() > lwma[0] && bar.getClose() < lwma[0]);
//    	} else {
//    		return false;
//    	}
    }
    
    private void logOrder(IOrder order) throws JFException {
        if (order != null) {
            order.waitForUpdate(State.FILLED);
            log(order.getLabel() + " @ " + DATE_FORMAT.format(new Date(order.getFillTime())) + ": Placed " + order.getOrderCommand() + " order @ $" + 
                    getPreciseValue(order.getOpenPrice()) + ".");
        }
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (instrument.equals(this.instrument)) {
        	long time = tick.getTime();
            long hourlyBarTime = history.getPreviousBarStart(Period.ONE_HOUR, time);
            
            // Only execute on the hour
            if (previousHourlyBarTime != hourlyBarTime) {
                previousHourlyBarTime = hourlyBarTime;
                
            	// If we are currently in a losing position then close it and open a new position
//            	List<IOrder> orders = engine.getOrders(instrument);
//            	if (!orders.isEmpty() && orders.get(0).getProfitLossInPips() < 0) {
//            		closePosition();
//            		orders.get(0).waitForUpdate(State.CLOSED);
//            	}
                
                // Determine if the H1, M30, M15 and M5 bars are all heading in the same direction
                Direction direction = getDirection(Period.ONE_HOUR, time);
                if (!hasOpenPosition() && isPreviousRangeValid(time) &&
                		isDirectionContinuous(time) &&
                		isPriceValid(direction, tick) &&
                		!Direction.SIDEWAYS.equals(direction) && 
                		direction.equals(getDirection(Period.THIRTY_MINS, time)) && 
                		direction.equals(getDirection(Period.FIFTEEN_MINS, time)) && 
                		//direction.equals(getDirection(Period.ONE_MIN, time)) && 
                		direction.equals(getDirection(Period.FIVE_MINS, time))) {
                	
                	IOrder order = null;
                	
                	// If we are currently in a losing position then close it and open a new position
//                	List<IOrder> orders = engine.getOrders(instrument);
//                	if (!orders.isEmpty() && orders.get(0).getProfitLossInPips() > 0) {
//                		return;
//                	} else {
//                		closePosition();
//                	}
                	
                	if (Direction.UPWARDS.equals(direction)) {
                		buy(tick.getAsk());
                	} else {
                		sell(tick.getBid());
                	}
                	
                	logOrder(order);
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
        
        log("Started TIME_LORD strategy using " + instrument + ".");
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