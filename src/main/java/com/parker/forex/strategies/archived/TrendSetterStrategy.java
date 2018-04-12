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
import com.dukascopy.api.IEngine;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;

/**
 * Opens a positions at the start of each period based on the direction of the last period. Closes at the end of the period or at the
 * predetermined stop loss.
 */
public class TrendSetterStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final String NAME = "TREND_SETTER";
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS Z");
    
    static {
        DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IContext context;
    private int orderCounter = 1;
    
    private int wins;
    private int losses;
    private double profitPips;
    private double lossPips;
    
    //*****************************************************************************************************************
    // Configurable Fields
    //*****************************************************************************************************************
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Lot Size")
    public double lotSize = 0.05;
    
    @Configurable(value = "Slippage")
    public double slippage = 1;
    
    @Configurable(value = "Period")
    public Period period = Period.DAILY;
    
    @Configurable(value = "Min Period Range (Pips)")
    public int minPeriodRange = 0;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message) {
        context.getConsole().getOut().println(message);
    }
    
    private void closeAllPositions() throws JFException {
        for (IOrder order : context.getEngine().getOrders(instrument)) {
            if (order.getLabel().contains(NAME)) {
                order.close();
            }
        }
    }
    
    private String getNextOrderId() {
        return NAME + "_" + instrument.name().replace("/", "") + "_" + (orderCounter++);
    }
    
    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private IOrder placeOrder(OrderCommand orderCommand) throws JFException {
        return context.getEngine().submitOrder(getNextOrderId(), instrument, orderCommand, lotSize, 0, slippage);
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (instrument.equals(instrument) && this.period.equals(period)) {
            closeAllPositions();
            double barSize = bidBar.getClose() - bidBar.getOpen();
            
            if (Math.abs(barSize) > minPeriodRange) {
                placeOrder(barSize > 0 ? IEngine.OrderCommand.BUY : IEngine.OrderCommand.SELL);
            }
        }
    }

    public void onMessage(IMessage message) throws JFException {
        if (message.getOrder().getInstrument().equals(instrument)) {
            IOrder order = message.getOrder();
            
            if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
                log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + order.getOrderCommand() + 
                        " order @ $" + order.getOpenPrice() + ".");
                
            } else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
                if (order.getProfitLossInPips() > 0) {
                    wins++;
                    profitPips += order.getProfitLossInPips();
                } else if (order.getProfitLossInPips() < 0) {
                    losses++;
                    lossPips += (order.getProfitLossInPips() * -1);
                }
                
                log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCloseTime())) + ": Closed " + order.getOrderCommand() + " order" +
                        " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") " + (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + 
                        ". [equity=$" + context.getAccount().getEquity() + "]");
            }
        }
    }

    public void onStart(IContext context) throws JFException {
        this.context = context;
        
        context.setSubscribedInstruments(Collections.singleton(instrument), true);
        log("Started the " + NAME + " strategy using " + instrument + ".");
    }

    public void onStop() throws JFException {
        closeAllPositions();
        log("Total Equity: $" + context.getAccount().getEquity());
        
        int totalTrades = wins + losses;
        log("Total Trades: " + totalTrades + " [" + wins + " wins / " + losses + " losses => " + (totalTrades == 0 ? 0: round((wins * 100.0 / totalTrades), 0)) + "%]");
        log("Profit Pips: " + round(profitPips, 1) + ", Loss Pips: " + round(lossPips, 1));
        
        log("Strategy stopped.");
    }
    
    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }
    
    public void onAccount(IAccount account) throws JFException {
    }
}