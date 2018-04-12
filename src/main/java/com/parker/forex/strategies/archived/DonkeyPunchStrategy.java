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
import com.dukascopy.api.IContext;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;

/**
 * A Donchian channel-based trading strategy.
 */
public class DonkeyPunchStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final String NAME = "DONKY_PUNCH";
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS Z");
    
    static {
        DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IContext context;
    
    // Stats
    private int wins;
    private int losses;
    
    @Configurable(value = "Instrument")
    private Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Lot Size")
    private double baseLotSize = 0.01;
    
    @Configurable(value = "Period")
    private Period period = Period.FOUR_HOURS;
    
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
    
    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
    	if (this.instrument.equals(instrument) && this.period.equals(period)) {
    		
    		
    		
    	}
    }
    
    public void onMessage(IMessage message) throws JFException {
        if (message.getOrder().getInstrument().equals(instrument)) {
            IOrder order = message.getOrder();
            
            if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
                // Log when an order is filled
                log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + order.getOrderCommand() + 
                        " order @ $" + order.getOpenPrice() + ".");
            } else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
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
    }
    
    public void onStop() throws JFException {
        closeAllOrders();
        log("Total Equity: $" + context.getAccount().getEquity());
        
        int totalTrades = wins + losses;
        log("Total Trades: " + totalTrades + " [" + wins + " wins / " + losses + " losses]");
        log("%Win: " + round((wins * 100.0) / (wins + losses), 1) + "%");
        
        log("Strategy stopped.");
    }
    
    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }
    
    public void onAccount(IAccount account) throws JFException {
    }
}