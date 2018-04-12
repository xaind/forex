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
public class BallBreakerStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final String NAME = "BALL_BREAKER";
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
    
    //*****************************************************************************************************************
    // Configurable Fields
    //*****************************************************************************************************************
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Trade Amount ($)")
    public double tradeAmount = 50.0;
    
    @Configurable(value = "Slippage (Pips)")
    public double slippage = 1;
    
    @Configurable(value = "Stop Loss (Pips)")
    public int stopLossPips = 20;
    
    @Configurable(value = "Entry Buffer (Pips)")
    public int entryBuffer = 10;
    
    @Configurable(value = "Risk/Reward Ratio (1:n)")
    public int riskRewardRatio = 3;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message) {
        console.getOut().println(message);
    }
    
    private boolean hasOpenPosition() throws JFException {
        for (IOrder order : engine.getOrders(instrument)) {
            if (order.getLabel().contains(NAME) && State.FILLED.equals(order.getState())) {
                return true;
            }
        }
        return false;
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
    
    private double getLotSize() {
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
                    round(order.getOpenPrice()) + ". [EP=$" + order.getOpenPrice() + ", SL=$" + order.getStopLossPrice() + ", TP=$" + order.getTakeProfitPrice() + "]");
        }
    }

    private IOrder placeBuyStopOrder(double price) throws JFException {
        double entryPrice = round(price + (instrument.getPipValue() * entryBuffer));
        double stopPrice = round(entryPrice - (instrument.getPipValue() * stopLossPips));
        double takeProfitPrice = round(entryPrice + ((entryPrice - stopPrice) * riskRewardRatio));
        
        IOrder order = engine.submitOrder(getNextOrderId(), instrument, IEngine.OrderCommand.BUYSTOP, getLotSize(), entryPrice, slippage, stopPrice, takeProfitPrice);
        order.waitForUpdate(State.CREATED, State.OPENED, State.FILLED);
        logOrder(order);

        return order;
    }
    
    private IOrder placeSellStopOrder(double price) throws JFException {
        double entryPrice = round(price - (instrument.getPipValue() * entryBuffer));
        double stopPrice = round(entryPrice + (instrument.getPipValue() * stopLossPips));
        double takeProfitPrice = round(entryPrice - ((stopPrice - entryPrice) * riskRewardRatio));
        
        IOrder order = engine.submitOrder(getNextOrderId(), instrument, IEngine.OrderCommand.SELLSTOP, getLotSize(), entryPrice, slippage, stopPrice, takeProfitPrice);
        order.waitForUpdate(State.CREATED, State.OPENED, State.FILLED);
        logOrder(order);
        
        return order;
    }
   
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (instrument.equals(instrument) && Period.DAILY.equals(period) && !hasOpenPosition()) {
            closeAllPendingPositions();
            
            // Create new stop orders
            placeBuyStopOrder(askBar.getHigh());
            placeSellStopOrder(bidBar.getLow());
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
                // Cancel any pending order and set the actual entry prices
                closeAllPendingPositions();
                
                //log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + order.getOrderCommand() + 
                //        " order @ $" + order.getOpenPrice() + ". [SL=$" + order.getStopLossPrice() + ", (" + stopLossPips + " pips)]");
                
            } else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
                // Log the order outcome
                String action = "Closed";
                if (IOrder.State.CANCELED.equals(order.getState())) {
                    action = "Cancelled";
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
}