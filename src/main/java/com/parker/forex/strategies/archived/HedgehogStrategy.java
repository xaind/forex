package com.parker.forex.strategies.archived;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IConsole;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine;
import com.dukascopy.api.IHistory;
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
 * Places simultaneous buy and sell orders with appropriate stop loss and take profit orders.
 */
public class HedgehogStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final String NAME = "HEDGEHOG";
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
    private IConsole console;
    
    private int orderCounter = 1;
    private long previousBarTime;
    
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Equity per Trade (%)")
    public double equityPerTradePct = 2.0;
    
    @Configurable(value = "Slippage Pips")
    public int slippagePips = 0;
    
    @Configurable(value = "Bar Period")
    public Period barPeriod = Period.FIFTEEN_MINS;
    
    @Configurable(value = "Take Profit Pips")
    public int takeProfitPips = 200;
    
    @Configurable(value = "Stop Loss Pips")
    public int stopLossPips = 10;
    
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
            //log("About to place order with lot size: " + lotSize);
        }
        
        return lotSize;
    }
    
    private boolean isTradingTime() {
    	GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
    	calendar.setTimeInMillis(previousBarTime);
    	
    	int hour = calendar.get(Calendar.HOUR_OF_DAY);
    	int minute = calendar.get(Calendar.MINUTE);
    	
    	return (hour >= 8 && hour <= 10) || ((hour >= 14 && minute >= 30) && (hour <= 16 && minute <= 30));
    }
    
    private IOrder buy(double askPrice) throws JFException {
        double stopPrice = getPreciseValue(askPrice - (instrument.getPipValue() * stopLossPips));
        
        IOrder order = engine.submitOrder(getNextOrderId(), instrument, IEngine.OrderCommand.BUY, getLotSize());
        
        order.waitForUpdate(State.FILLED);
        order.setStopLossPrice(stopPrice, OfferSide.BID, 10);
        //order.setTakeProfitPrice(takeProfitPrice);
        
        return order;
    }
    
    private IOrder sell(double bidPrice) throws JFException {
        double stopPrice = getPreciseValue(bidPrice + (instrument.getPipValue() * stopLossPips));
        
        IOrder order = engine.submitOrder(getNextOrderId(), instrument, IEngine.OrderCommand.SELL, getLotSize());
        
        order.waitForUpdate(State.FILLED);
        order.setStopLossPrice(stopPrice, OfferSide.ASK, 10);
        //order.setTakeProfitPrice(takeProfitPrice);
        
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
            long barTime = history.getPreviousBarStart(barPeriod, tick.getTime());
            
            // For each new day set today's buy/sell limits
            if (previousBarTime != barTime) {
                previousBarTime = barTime;

	            // Place both buy and sell order for each new bar
		        if (!hasOpenPosition() && isTradingTime()) {
		        	IOrder order = buy(tick.getBid());
		        	logOrder(order);
		        	
		        	order = sell(tick.getAsk());
		        	logOrder(order);
	            }
            }
        }
    }
    
    public void onStart(IContext context) throws JFException {
        account = context.getAccount();
        engine = context.getEngine();
        history = context.getHistory();
        console = context.getConsole();
        
        // Subscribe an instrument
        Set<Instrument> instruments = new HashSet<Instrument>();
        instruments.add(instrument);                     
        context.setSubscribedInstruments(instruments, true);
        
        log("Started HEDGEHOG strategy using " + instrument + ".");
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
            log(order.getLabel() + " @ " + DATE_FORMAT.format(new Date(order.getCloseTime())) + ": Closed " + order.getOrderCommand() + 
            		" order for " + order.getProfitLossInPips() + " pip " + (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ".");
        }
    }

    public void onAccount(IAccount account) throws JFException {
    }
}