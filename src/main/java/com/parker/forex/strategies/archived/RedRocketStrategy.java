package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IConsole;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine;
import com.dukascopy.api.IEngine.OrderCommand;
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
import com.dukascopy.api.PriceRange;
import com.dukascopy.api.feed.IRenkoBar;

/**
 * A continuously trading strategy where subsequent entry and exit points based on the order open price relative to fixed 
 * stop loss and take profit prices. Although trading is continuous it will only occur between set hours of the day. 
 */
public class RedRocketStrategy implements IStrategy {
	
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final String NAME = "RED_ROCKET";
    private static final int SLIPPAGE = 1;
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
    private IHistory history;
    
    private long orderId = 1;
    private IOrder currentOrder;
    private boolean marketIsOpen;
    private int winCounter;
    private int lossCounter;
    
    @Configurable(value = "Instrument")
    private Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Lot Size")
    private double lotSize = 0.01;
    
    @Configurable(value = "Stop Loss Pips")
    private double stopLossPips = 50;
    
    @Configurable(value = "Risk/Reward Ratio (1:n)")
    private double riskRewardRatio = 1;
    
    @Configurable(value = "Opening Hour (GMT)")
    private int openingHour = 7;
    
    @Configurable(value = "Closing Hour (GMT)")
    private double closingHour = 18;

    //*****************************************************************************************************************
	// Private Methods
	//*****************************************************************************************************************
    private void log(String message) {
        console.getOut().println(message);
    }
    
    private void closeOrder() throws JFException {
    	if (currentOrder != null) {
    		currentOrder.close();
    		currentOrder = null;
    	}
    }
    
    private String getLabel(Instrument instrument) {
        return NAME + "_" + instrument.name() + "_" + (orderId++);
    }
        
    private double round(double value, int precision) {
    	return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private void placeTrade(OrderCommand orderCommand)  throws JFException {
    	// Only place new trades when the market is open
    	//if (marketIsOpen) {
    		currentOrder = engine.submitOrder(getLabel(instrument), instrument, orderCommand, lotSize, 0, SLIPPAGE);
    	//}
    }
    
    //*****************************************************************************************************************
	// Public Methods
	//*****************************************************************************************************************
    public void onTick(Instrument instrument, ITick tick) throws JFException {
    	if (this.instrument.equals(instrument)) {
    		
    		if (currentOrder != null && State.FILLED.equals(currentOrder.getState())) {
	            if (currentOrder.isLong()) {
	            	if (tick.getBid() >= (currentOrder.getOpenPrice() + (instrument.getPipValue() * stopLossPips * riskRewardRatio))) {
	            		// Take profit and open a new LONG trade
	            		closeOrder();
	            		placeTrade(OrderCommand.BUY);
	            	} else if (tick.getBid() < (currentOrder.getOpenPrice() - (instrument.getPipValue() * stopLossPips))) {
	            		// Cut losses and open a new SHORT trade
	            		closeOrder();
	            		placeTrade(OrderCommand.SELL);
	            	}
	            } else {
	            	if (tick.getBid() <= (currentOrder.getOpenPrice() - (instrument.getPipValue() * stopLossPips * riskRewardRatio))) {
	            		// Take profit and open a new SHORT trade
	            		closeOrder();
	            		placeTrade(OrderCommand.SELL);
	            	} else if (tick.getBid() > (currentOrder.getOpenPrice() + (instrument.getPipValue() * stopLossPips))) {
	            		// Cut losses and open a new LONG trade
	            		closeOrder();
	            		placeTrade(OrderCommand.BUY);
	            	}
	            }
    		}
    	}
    }
    
    @SuppressWarnings("deprecation")
	public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (this.instrument.equals(instrument) && Period.ONE_HOUR.equals(period) && !marketIsOpen) {
        	
        	Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        	calendar.setTimeInMillis(askBar.getTime());
        	
        	// Update the market open flag - we only trade Monday to Friday during the specified hours
        	//marketIsOpen = calendar.get(Calendar.DAY_OF_WEEK) >= Calendar.MONDAY && calendar.get(Calendar.DAY_OF_WEEK) <= Calendar.FRIDAY &&
        	//		calendar.get(Calendar.HOUR_OF_DAY) >= openingHour && calendar.get(Calendar.HOUR_OF_DAY) <= closingHour;
        			
        	//if (!marketIsOpen) {// && calendar.get(Calendar.HOUR_OF_DAY) == openingHour) {
        		// Ensure any order from the previous day is closed
        		closeOrder();
        		
        		// Start trading for the day - determine which way to go based on the previous Renko bar
        		IRenkoBar previousRenkoBar = history.getRenkoBar(instrument, OfferSide.BID, PriceRange.FIVE_PIPS, 1);
        		if (previousRenkoBar.getOpen() < previousRenkoBar.getClose()) {
        			placeTrade(OrderCommand.BUY);
        		} else {
        			placeTrade(OrderCommand.SELL);
        		}
        	//}
        	
        	marketIsOpen = true;
        }
    }
    
    public void onMessage(IMessage message) throws JFException {
    	if (message.getOrder().getInstrument().equals(instrument)) {
    		IOrder order = message.getOrder();
    		
    		if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
    			
	            log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + order.getOrderCommand() + 
	            		" order @ $" + order.getOpenPrice() + ".");
	            
    		} else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
	    		// Log the order outcome
	        	if (order.getProfitLossInPips() > 0) {
	        		winCounter++;
	        	} else if (order.getProfitLossInPips() < 0) {
	        		lossCounter++;
	        	}
	        	
	        	log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCloseTime())) + ": " + order.getOrderCommand() + " " + 
	        			order.getOrderCommand() + " order" + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") " + 
	        			(order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [equity=$" + account.getEquity() + "]");
    		}
    	}
    }
    
    public void onStart(IContext context) throws JFException {
        this.engine = context.getEngine();
        this.account = context.getAccount();
        this.console = context.getConsole();
        this.history = context.getHistory();
        
        context.setSubscribedInstruments(Collections.singleton(instrument));
        log("Started " + NAME + " strategy using " + instrument + ".");
    }
    
    public void onStop() throws JFException {
    	closeOrder();
        log("Total Equity: $" + account.getEquity());
        
        int totalTrades = winCounter + lossCounter;
        log("Total Trades: " + totalTrades + " [" + winCounter + " wins / " + lossCounter + " losses => " + (totalTrades == 0 ? 0: round((winCounter * 100.0 / totalTrades), 0)) + "%]");
        
        log("Strategy stopped.");
    }
    
    public void onAccount(IAccount account) throws JFException {
    }
}