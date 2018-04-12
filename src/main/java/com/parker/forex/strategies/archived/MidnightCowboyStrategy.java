package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IConsole;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;

/**
 * Determines the range of the previous day and then monitors breaks of these prices. Stop orders will be set for a second break.
 */
public class MidnightCowboyStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final String NAME = "MIDNIGHT_COWBOY";
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS Z");
    
    static {
    	DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IAccount account;
    private IEngine engine;
    private IConsole console;
    
    private int orderCounter = 1;
    private int winCounter;
    private int lossCounter;
    
    private SetupDetails buySetup;
    private SetupDetails sellSetup;
    
    //*****************************************************************************************************************
    // Configurable Fields
    //*****************************************************************************************************************
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Trade Amount ($)")
    public double tradeAmount = 50.0;
    
    @Configurable(value = "Slippage")
    public double slippage = 1;
    
	@Configurable(value = "Stop Loss Buffer (Pips)")
	public int stopLossBuffer = 2;
	
	@Configurable(value = "Entry Buffer (Pips)")
	public int entryBuffer = 10;
	
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message) {
        console.getOut().println(message);
    }
    
    private IOrder getOpenPosition() throws JFException {
    	for (IOrder order : engine.getOrders(instrument)) {
    		if (order.getLabel().contains(NAME) && State.FILLED.equals(order.getState())) {
    			return order;
    		}
    	}
    	return null;
    }
    
    private void closeAllPendingPositions() throws JFException {
        for (IOrder order : engine.getOrders(instrument)) {
            if (order.getLabel().contains(NAME) && !State.FILLED.equals(order.getState())) {
                order.close();
            }
        }
    }
    
    private void closeAllPositions() throws JFException {
        for (IOrder order : engine.getOrders(instrument)) {
            if (order.getLabel().contains(NAME)) {
                order.close();
            }
        }
    }
    
    private double getLotSize(double stopLossPips) {
    	// Normalize the lot size based on the stop loss pips - this effectively enforces the same $ amount per trade
    	return round(tradeAmount / stopLossPips / 100.0, 3);
    }
    
    private String getNextOrderId() {
        return NAME + "_" + instrument.name().replace("/", "") + "_" + (orderCounter++);
    }
    
    private double round(double value) {
        return round(value, 5);
    }
    
    private double round(double value, int precision) {
    	return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private void logOrder(IOrder order) throws JFException {
        if (order != null) {
            log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCreationTime())) + ": Placed " + order.getOrderCommand() + " order @ $" + 
                    round(order.getOpenPrice()) + ". [RP=$" + (order.isLong() ? buySetup.getRangePrice() : sellSetup.getRangePrice()) + ", EP=$" + 
            		order.getOpenPrice() + ", SL=$" + order.getStopLossPrice() + ", TP=$" + order.getTakeProfitPrice() + "]");
        }
    }
    
	private void updateBuySetupInfo(IBar bidBar) throws JFException {
		if (buySetup != null) {
			if (buySetup.isArmed()) {
				// Create a new stop order if the price dips below the current stop loss price 
//				if (bidBar.getLow() < buySetup.getStopLossPrice()) {
//					closePosition(buySetup.getLabel());
//					buySetup.setStopLossPrice(bidBar.getLow());
//					placeBuyStopOrder();
//				}
			} else {
				// Determine the entry and stop loss prices
				if (buySetup.getStopLossPrice() > 0) {
					// Check for the turning point of the retracement
					if (buySetup.getStopLossPrice() > bidBar.getLow()) {
						buySetup.setStopLossPrice(bidBar.getLow());
					} else {
						// Place the stop order
						placeBuyStopOrder();
					}
				} else if (buySetup.getEntryPrice() > 0) {
					// Check for the turning point of the entry price
					if (buySetup.getEntryPrice() < bidBar.getHigh()) {
						buySetup.setEntryPrice(bidBar.getHigh());
					} else{
						buySetup.setStopLossPrice(bidBar.getLow());
					}
				} else if (buySetup.getRangePrice() < bidBar.getHigh()) {
					// Check for a crossing of the range price
					buySetup.setEntryPrice(bidBar.getHigh());
				}
			}
		}
	}
	
	private void updateSellSetupInfo(IBar askBar) throws JFException {
		if (sellSetup != null) {
			if (sellSetup.isArmed()) {
				// Create a new stop order if the price moves above the current stop loss price 
//				if (askBar.getHigh() > sellSetup.getStopLossPrice()) {
//					closePosition(sellSetup.getLabel());
//					sellSetup.setStopLossPrice(askBar.getHigh());
//					placeSellStopOrder();
//				}
			} else {
				if (sellSetup.getStopLossPrice() > 0) {
					// Check for the turning point of the retracement
					if (sellSetup.getStopLossPrice() < askBar.getHigh()) {
						sellSetup.setStopLossPrice(askBar.getHigh());
					} else {
						// Place the stop order
						placeSellStopOrder();
					}
				} else if (sellSetup.getEntryPrice() > 0) {
					// Check for the turning point of the entry price
					if (sellSetup.getEntryPrice() > askBar.getLow()) {
						sellSetup.setEntryPrice(askBar.getLow());
					} else{
						sellSetup.setStopLossPrice(askBar.getHigh());
					}
				} else if (sellSetup.getRangePrice() > askBar.getLow()) {
					// Check for a crossing of the range price
					sellSetup.setEntryPrice(askBar.getLow());
				}
			}
		}
	}
	
	private IOrder placeBuyStopOrder() throws JFException {
		double entryPrice = buySetup.getBufferedEntryPrice();
		double stopPrice = buySetup.getBufferedStopLossPrice();
		double lotSize = getLotSize((entryPrice - stopPrice) / instrument.getPipValue());
		
		lotSize = 0.05;
		stopPrice = entryPrice - (instrument.getPipValue() * 20);
		double takeProfitPrice = entryPrice + (instrument.getPipValue() * 60);
		
		IOrder order = engine.submitOrder(getNextOrderId(), instrument, IEngine.OrderCommand.BUYSTOP, lotSize, entryPrice, slippage, stopPrice, takeProfitPrice);
	    order.waitForUpdate(State.CREATED, State.OPENED, State.FILLED);
		logOrder(order);
		
		buySetup.setArmed(true);
		
		return order;
	}
	
	private IOrder placeSellStopOrder() throws JFException {
		double entryPrice = sellSetup.getBufferedEntryPrice();
		double stopPrice = sellSetup.getBufferedStopLossPrice();
		double lotSize = getLotSize((stopPrice - entryPrice) / instrument.getPipValue());
		
		lotSize = 0.1;
		stopPrice = entryPrice + (instrument.getPipValue() * 10);
		double takeProfitPrice = entryPrice - (instrument.getPipValue() * 20);
		
		IOrder order = engine.submitOrder(getNextOrderId(), instrument, IEngine.OrderCommand.SELLSTOP, lotSize, entryPrice, slippage, stopPrice, takeProfitPrice);
		order.waitForUpdate(State.CREATED, State.OPENED, State.FILLED);
		logOrder(order);
		
		sellSetup.setArmed(true);
		
		return order;
	}
	
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (instrument.equals(instrument)) {
        	IOrder order = getOpenPosition();
        	
        	if (order == null) {
	        	// At the start of a new day re-evaluate our position
	        	if (Period.DAILY.equals(period)) {
	        		closeAllPendingPositions();
	        		
	        		// Create new setup info
	        		buySetup = new SetupDetails(bidBar.getHigh());
	        		sellSetup = new SetupDetails(askBar.getLow());
	    		}
	        	
	        	// Update the setups
	        	if (Period.FIVE_MINS.equals(period)) {
	        		updateBuySetupInfo(bidBar); 
	        		updateSellSetupInfo(askBar);
	        	}
        	
        	} else if (Period.TEN_SECS.equals(period)) {
        		// Update the stop loss
       			//updateStopLoss(order, askBar.getClose(), bidBar.getClose());
        	}
        }
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }
    
    public void onStart(IContext context) throws JFException {
        account = context.getAccount();
        engine = context.getEngine();
        console = context.getConsole();
        
        context.setSubscribedInstruments(Collections.singleton(instrument), true);
        log("Started the " + NAME + " strategy using " + instrument + ".");
    }

    public void onStop() throws JFException {
    	closeAllPositions();
        log("Total Equity: $" + account.getEquity());
        
        int totalTrades = winCounter + lossCounter;
        log("Total Trades: " + totalTrades + " [" + winCounter + " wins / " + lossCounter + " losses => " + (totalTrades == 0 ? 0: round((winCounter * 100.0 / totalTrades), 0)) + "%]");
        
        log("Strategy stopped.");
    }
    
    public void onMessage(IMessage message) throws JFException {
    	if (message.getOrder().getInstrument().equals(instrument)) {
    		IOrder order = message.getOrder();
    		
    		if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
    			// Log order filled
    			double stopLossPrice = 0.0;
    			double stopLossPips = 0.0;
    			
	            // Cancel any pending order and set the actual entry prices
	            closeAllPendingPositions();
	            
	            if (order.isLong()) {
	            	//buySetup.setActualEntryPrice(order.getOpenPrice());
	            	stopLossPrice = buySetup.getBufferedStopLossPrice();
	            	stopLossPips = round((order.getOpenPrice() - stopLossPrice) / instrument.getPipValue(), 1);
	            	sellSetup = null;
	            } else {
	            	sellSetup.setEntryPrice(order.getOpenPrice());
	            	stopLossPrice = sellSetup.getBufferedStopLossPrice();
	            	stopLossPips = round((stopLossPrice - order.getOpenPrice()) / instrument.getPipValue(), 1);
	            	buySetup = null;
	            }
	            
	            log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + order.getOrderCommand() + 
	            		" order @ $" + order.getOpenPrice() + ". [SL=$" + stopLossPrice + ", (" + stopLossPips + " pips)]");
	            
    		} else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
	    		// Log the order outcome
	        	String action = "Closed";
	        	if (IOrder.State.CANCELED.equals(order.getState())) {
	        		action = "Cancelled";
	        	} else {
	        		buySetup = null;
	        		sellSetup = null;
	        	}
	        	
	        	if (order.getProfitLossInPips() > 0) {
	        		winCounter++;
	        	} else if (order.getProfitLossInPips() < 0) {
	        		lossCounter++;
	        	}
	        	
	        	String msg = order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCloseTime())) + ": " + action + " " + order.getOrderCommand() + " order";
	        	if ("Closed".equals(action)) {
	        		msg += " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") " + (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + 
	        				". [equity=$" + account.getEquity() + "]";
	        	} else {
	        		msg += ".";
	        	}
	        	
	            log(msg);
    		}
    	}
    }
    
    public void onAccount(IAccount account) throws JFException {
    }
    
    /**
     * Holds info related to a position setup.
     */
    private class SetupDetails {
    	
    	private double rangePrice;
    	private double entryPrice;
    	private double stopLossPrice;
    	private boolean armed;
    	
    	public SetupDetails(double rangePrice) {
    		this.rangePrice = rangePrice;
    	}
    	
    	public double getRangePrice() {
    		return round(rangePrice);
    	}
    	
    	public double getEntryPrice() {
    		return round(entryPrice);
    	}
    	
    	public void setEntryPrice(double entryPrice) {
    		this.entryPrice = entryPrice;
    	}
    	
//    	public double getActualEntryPrice() {
//    		return round(actualEntryPrice);
//    	}
//    	
//    	public void setActualEntryPrice(double actualEntryPrice) {
//    		this.actualEntryPrice = actualEntryPrice;
//    	}
    	
    	public double getBufferedEntryPrice() {
    		if (stopLossPrice < entryPrice) {
        		return round(entryPrice + (instrument.getPipValue() * entryBuffer));
        	} else {
        		return round(entryPrice - (instrument.getPipValue() * entryBuffer));
        	}
    	}
    	
    	public double getStopLossPrice() {
    		return round(stopLossPrice);
    	}
    	
    	public void setStopLossPrice(double stopLossPrice) {
    		this.stopLossPrice = stopLossPrice;
    	}
    	
        public void setArmed(boolean armed) {
        	this.armed = armed;
        }
        
        public boolean isArmed() {
        	return armed;
        }
        
        public double getBufferedStopLossPrice() {
        	if (stopLossPrice < entryPrice) {
        		return round(stopLossPrice - (instrument.getPipValue() * stopLossBuffer));
        	} else {
        		return round(stopLossPrice + (instrument.getPipValue() * stopLossBuffer));
        	}
        }
    }  
}