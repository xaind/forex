package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;

/**
 * A grid-based trading strategy using a fixed grid size but with no fixed take profit. Once a grid level is reached
 * a trailing stop is used to counteract strong trends.
 */
public class KissTheSkyStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final String NAME = "KISS_THE_SKY";
    private static final int SLIPPAGE = 1;
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS Z");
    
    static {
        DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IContext context;
    private long orderId;
    
    // Stats
    private int wins;
    private int losses;
    
    @Configurable(value = "Instrument")
    private Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Lot Size")
    private double baseLotSize = 0.01;
    
    @Configurable(value = "Grid Size")
    private int gridSize = 10;
    
    @Configurable(value = "Stop Loss Pips")
    private int stopLossPips = 8;
    
    @Configurable(value = "Min Profit Pips")
    private int minProfitPips = 7;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message) {
        context.getConsole().getOut().println(message);
    }
    
    private void closeAllOrders() throws JFException {
        for (IOrder order : context.getEngine().getOrders(instrument)) {
            order.close();
        }
    }
    
    private String getLabel(Instrument instrument) {
        return NAME + "_" + instrument.name() + "_" + (++orderId);
    }
        
    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private IOrder placeTrade(OrderCommand orderCommand)  throws JFException {
        return context.getEngine().submitOrder(getLabel(instrument), instrument, orderCommand, baseLotSize, 0, SLIPPAGE);
    }
    
    private void placeOrders() throws JFException {
        placeTrade(OrderCommand.BUY);
        placeTrade(OrderCommand.SELL);
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public void onMessage(IMessage message) throws JFException {
        if (message.getOrder().getInstrument().equals(instrument)) {
            IOrder order = message.getOrder();
            int multiplier = order.isLong() ? 1 : -1;
            
            if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
                // Set the take profit price and stop loss
                order.setTakeProfitPrice(order.getOpenPrice() + (instrument.getPipValue() * gridSize * multiplier));
                order.setStopLossPrice(order.getOpenPrice() - (instrument.getPipValue() * stopLossPips * multiplier));
                   
                // Log when an order is filled
                log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + order.getOrderCommand() + 
                        " order @ $" + order.getOpenPrice() + ".");
                
            } else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
            	boolean isFirstOrder = false;
            	
            	// When the first order is closed by hitting its stop loss adjust the stop loss on the remaining order to protect profits
        		if (order.getProfitLossInPips() < 0) {
        			List<IOrder> openOrders = context.getEngine().getOrders(instrument);
        			
        			if (!openOrders.isEmpty()) {
        				IOrder openOrder = openOrders.get(0);
        				openOrder.setStopLossPrice(openOrder.getOpenPrice() + (instrument.getPipValue() * minProfitPips * multiplier * -1));
        				isFirstOrder = true;
        			}
        		}

            	if (!isFirstOrder) {
            		// Safeguard
            		closeAllOrders();
            		
	            	String msg = "************ CASHED IN FOR A ";
	                if (order.getProfitLossInPips() >= stopLossPips) {
	                    msg +="WIN"; 
	                    wins++;
	                } else {
	                    msg +="LOSS";
	                    losses++;
	                }
	                log(msg + " ************");
	                
	                // Start again
	                placeOrders();
            	}
                
                // Log the order outcome on close
                log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCloseTime())) + ": Closed " + 
                        order.getOrderCommand() + " order" + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") " + 
                        (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [equity=$" + context.getAccount().getEquity() + "]");
            }
        }
    }
    
    public void onStart(IContext context) throws JFException {
        this.context = context;
        context.setSubscribedInstruments(Collections.singleton(instrument));
        log("Started " + NAME + " strategy using " + instrument + ".");
        
        placeOrders();
    }
    
    public void onStop() throws JFException {
        //closeAllOrders();
        log("Total Equity: $" + context.getAccount().getEquity());
        
        int totalTrades = wins + losses;
        log("Total Trades: " + totalTrades + " [" + wins + " wins / " + losses + " losses]");
        log("%Win: " + round((wins * 100.0) / (wins + losses), 1) + "%");
        
        log("Strategy stopped.");
    }
    
    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }
    
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
    }
    
    public void onAccount(IAccount account) throws JFException {
    }
}