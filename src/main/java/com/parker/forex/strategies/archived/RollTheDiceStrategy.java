package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;

/**
 * Simple trend following strategy that uses a Martingale money management system across multiple currency pairs.
 * 
 * @author Xaind, 2015.
 * @version 1.0
 */
public class RollTheDiceStrategy implements IStrategy {
    
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS");
    private static final SimpleDateFormat DATE_FORMAT_MONTH = new SimpleDateFormat("MMMMM yyyy");
    
    static {
        DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
        DATE_FORMAT_MONTH.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    volatile IContext context;
    volatile int orderCounter;
    
    volatile int winCounter;
    volatile int lossCounter;
    volatile int consecutiveLossCounter;
    volatile int maxConsecutiveLossCounter;
    
    final ConcurrentMap<Instrument, InstrumentInfo> pairs = new ConcurrentHashMap<>();
    
    @Configurable(value = "Base Lot Size")
    public final double baseLotSize = 0.002;
     
    @Configurable(value = "Take Profit Pips")
    public final double takeProfitPips = 100;
    
    @Configurable(value = "Martingale Factor")
    public final double martingaleFactor = 1.5;
    
    @Configurable(value = "Max Consecutive Losses")
    public final double maxConsecutiveLosses = 8;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private String getName() {
        return "ROLL_THE_DICE";
    }
    
    private void log(String message) {
        context.getConsole().getOut().println(message);
    }
    
    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private double getLotSize(InstrumentInfo info) throws JFException {
        // Calculate the current lot size based on the current number of consecutive losses
        return round(Math.pow(1 + martingaleFactor, consecutiveLossCounter) * baseLotSize, 3);
    }
    
    private void placeOrder(InstrumentInfo info, OrderCommand orderCommand)  throws JFException {
        String label = getName() + "_" + (++orderCounter);
        context.getEngine().submitOrder(label, info.instrument, orderCommand, getLotSize(info), 0, 0);
    }
 
    private boolean hasOpenOrders() throws JFException {
        for (IOrder order : context.getEngine().getOrders()) {
			if (State.OPENED.equals(order.getState()) || State.FILLED.equals(order.getState())) {
				return true;
			}
		}
        return false;
    }  
    
    private void onOrderCancelled(IMessage message) throws JFException {    
        log("Error executing order: " + message.getContent());          
    }
    
    private void onOrderFilled(IOrder order) throws JFException {
    	InstrumentInfo info = pairs.get(order.getInstrument());
    	
        // Set the take profit and stop loss prices
        double openPrice = order.getOpenPrice();
        double margin = info.instrument.getPipValue() * takeProfitPips;
        int negator = order.isLong() ? 1 : -1;
        
        order.setTakeProfitPrice(round(openPrice + (negator * margin), info.instrument.getPipScale()));
        order.setStopLossPrice(round(openPrice - (negator * margin), info.instrument.getPipScale()));
        
		log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + info.instrument + " " + 
				order.getOrderCommand() + " order" + " at $" + order.getOpenPrice() + ". [equity=$" + context.getAccount().getEquity() + ", comm=$" + 
				order.getCommissionInUSD() + ", profitabilityIndex=" + info.getProfitabilityIndex() + ", weight=" + info.directionalWeight + 
				", avgDuration=" + info.avgDuration + "]");
    }
    
    private void onOrderClosed(IOrder order) throws JFException {
    	InstrumentInfo info = pairs.get(order.getInstrument());
    	
    	synchronized (info) {
    		log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCloseTime())) + ": Closed " + info.instrument + " " + 
    				order.getOrderCommand() + " order" + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") " + 
    				(order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [equity=$" + context.getAccount().getEquity() + ", comm=$" + 
    				order.getCommissionInUSD() + ", consecutiveLosses=" + consecutiveLossCounter + ", profitabilityIndex=" + info.getProfitabilityIndex() +
    				", weight=" + info.directionalWeight + ", avgDuration=" + info.avgDuration + "]");
	        
	        if (order.getProfitLossInPips() >= 0) {
	            winCounter++;
	            consecutiveLossCounter = 0;
	        } else {            
	            // Always trade in the current direction of the price
	            lossCounter++;
	            consecutiveLossCounter++;
	            
	            info.directionalWeight = 0;
	            
	            if (consecutiveLossCounter >= maxConsecutiveLosses) {
	            	log("*** MAX CONSECUTIVE LOSSES HIT ***");
	            	maxConsecutiveLossCounter++;
	            	consecutiveLossCounter = 0;
	            }
	        }
    	}
    }
    
    //*****************************************************************************************************************
    // Public Methods - Implementation of the IStrategy interface
    //*****************************************************************************************************************   
    public void onStart(IContext context) throws JFException {
        this.context = context;        
        
        pairs.put(Instrument.EURUSD, new InstrumentInfo(Instrument.EURUSD));
        pairs.put(Instrument.GBPUSD, new InstrumentInfo(Instrument.GBPUSD));
        pairs.put(Instrument.USDJPY, new InstrumentInfo(Instrument.USDJPY));
        pairs.put(Instrument.EURJPY, new InstrumentInfo(Instrument.EURJPY));
        pairs.put(Instrument.EURGBP, new InstrumentInfo(Instrument.EURGBP));
        pairs.put(Instrument.GBPJPY, new InstrumentInfo(Instrument.GBPJPY));
        
       	context.setSubscribedInstruments(pairs.keySet());        
        
        log("Started the " + getName() + " strategy using " + pairs.size() + " instruments.");
    }

    public void onMessage(IMessage message) throws JFException {
    	InstrumentInfo info = pairs.get(message.getOrder().getInstrument());
    	
        if (info != null) {
            IOrder order = message.getOrder();
            
            if (State.CANCELED.equals(order.getState())) {
                onOrderCancelled(message);
            } else if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
                onOrderFilled(order);                
            } else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {                
                onOrderClosed(order);
            }
        }
    }

    public void onStop() throws JFException { 
        log("Total Equity: $" + context.getAccount().getEquity());
        
        int totalTrades = winCounter + lossCounter;
        log("Total Trades: " + totalTrades);
        log("Win%: " + (totalTrades == 0 ? 0: round((winCounter * 100.0 / totalTrades), 0)) + "% (" + winCounter + " wins / " + lossCounter + " losses)");
        log("Max Loss Count: " + maxConsecutiveLossCounter);
        
        log(getName() + " strategy stopped.");
    }
    
    public void onTick(Instrument instrument, ITick tick) throws JFException {
    	InstrumentInfo info = pairs.get(instrument);
    	if (info != null) {
    		if (info.basePrice <= 0) {
    			info.basePrice = tick.getAsk();
    			info.baseTime = tick.getTime() / 1000.0;
    		} else if (tick.getAsk() - info.basePrice > (instrument.getPipValue() * takeProfitPips)) {
    			info.update(tick.getAsk(), tick.getTime(), 1);
    		} else if (tick.getAsk() - info.basePrice < (instrument.getPipValue() * takeProfitPips * -1.0)) {
    			info.update(tick.getAsk(), tick.getTime(), -1);
    		}
    	}
    	
    	if (!hasOpenOrders()) {
    		List<InstrumentInfo> infos = new ArrayList<>(pairs.values());
    		Collections.sort(infos);
    		InstrumentInfo bestInfo = infos.get(0);
    		
    		if (Math.abs(bestInfo.directionalWeight) > 2) {
    			String s = "";
    			for (InstrumentInfo i : infos) {
    				s += i.instrument + ":" + i.directionalWeight + ", ";
    			}
    			log(s + " (maxConsecutiveLosses=" + maxConsecutiveLossCounter + ")");
    			
    			placeOrder(bestInfo, bestInfo.directionalWeight > 0 ? OrderCommand.BUY : OrderCommand.SELL);
    		}
    	}
    }
    
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
    }

    public void onAccount(IAccount account) throws JFException {
    }
    
    //*****************************************************************************************************************
    // Static helper classes
    //***************************************************************************************************************** 
    private static class InstrumentInfo implements Comparable<InstrumentInfo> {
    	
    	final Instrument instrument;
    	volatile double basePrice;
    	volatile double baseTime;
    	volatile double avgDuration;
    	volatile int directionalWeight;
    	
    	InstrumentInfo(Instrument instrument) {
    		this.instrument = instrument;
    	}

    	public void update(double price, long time, int indexor) {
			double duration = (time - baseTime) / 1000.0;
			int index  = Math.abs(directionalWeight);
			avgDuration = ((avgDuration * index) + duration) / (index + 1);  
						
			if ((int)Math.signum(index) != (int)Math.signum(directionalWeight)) {
				directionalWeight = 0;
			} else {
				directionalWeight += indexor;
			}
						
			basePrice = price;
			baseTime = time;
    	}
    	
    	public double getProfitabilityIndex() {
    		return Math.abs(directionalWeight);
    	}
    	
		@Override
		public int compareTo(InstrumentInfo info) {
			return (int)(info.getProfitabilityIndex() - this.getProfitabilityIndex());
		}
    }
}
