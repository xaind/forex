package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

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
import com.dukascopy.api.OfferSide;
import com.dukascopy.api.Period;

/**
 * Simple trend following strategy that uses a Martingale money management system.
 * 
 * @author Xaind, 2013.
 * @version 1.0
 */
public class JizzLobberStrategy implements IStrategy {
    
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS Z");
    private static final SimpleDateFormat DATE_FORMAT_MONTH = new SimpleDateFormat("MMMMM yyyy");
    
    static {
        DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
        DATE_FORMAT_MONTH.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IContext context;
    private int orderCounter;
    
    private int winCounter;
    private int lossCounter;
    private int consecutiveLossCounter;
    private int maxConsecutiveLossCounter;
    
    // Configurable fields
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.GBPAUD;
    
    @Configurable(value = "Take Profit Pips")
    private int takeProfitPips = 50;
    
    @Configurable(value = "Martingale Factor")
    public double martingaleFactor = 1.1;
    
    @Configurable(value = "Max Consecutive Losses")
    public double maxConsecutiveLosses = 7;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private String getName() {
        return "JIZZLOBBER";
    }
    
    private void log(String message) {
        context.getConsole().getOut().println(message);
    }
    
    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private double getLotSize() throws JFException {
        // Calculate the current lot size based on the current number of consecutive losses
        double lotSize = Math.pow(1 + martingaleFactor, consecutiveLossCounter) * 0.001;
        return round(lotSize, 3);
    }
    
    private void placeOrder(OrderCommand orderCommand)  throws JFException {
        if (hasOpenOrder()) return;                           
        String label = getName() + "_" + (++orderCounter);
        context.getEngine().submitOrder(label, instrument, orderCommand, getLotSize(), 0, 0);
    }
 
    private boolean hasOpenOrder() throws JFException {
        for (IOrder order : context.getEngine().getOrders(instrument)) {
            if (!State.CLOSED.equals(order.getState()) && !State.CANCELED.equals(order.getState())) {
                return true;
            }
        }
        return false;
    }  
    
    private void onOrderCancelled(IMessage message) throws JFException {    
        log("Error executing order: " + message.getContent());          
    }
    
    private void onOrderFilled(IOrder order) throws JFException {
        //log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + order.getOrderCommand() + 
        //        " order @ $" + order.getOpenPrice() + " [lotSize=" + order.getAmount() + ", consecutiveLosses=" + consecutiveLossCounter + 
        //        ", riskRatio=" + martingaleFactor + "]");
        
        // Set the take profit and stop loss prices
        double openPrice = order.getOpenPrice();
        double margin = instrument.getPipValue() * takeProfitPips;
        int negator = order.isLong() ? 1 : -1;
        
        order.setTakeProfitPrice(round(openPrice + (negator * margin), instrument.getPipScale()));
        order.setStopLossPrice(round(openPrice - (negator * margin), instrument.getPipScale()));    
    }
    
    private void onOrderClosed(IOrder order) throws JFException {
        log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCloseTime())) + ": Closed " + 
                order.getOrderCommand() + " order" + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") " + 
                (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [equity=$" + context.getAccount().getEquity() + ", comm=$" + 
                order.getCommissionInUSD() + ", consecutiveLosses=" + consecutiveLossCounter + "]");
        
        OrderCommand orderCommand = order.getOrderCommand();
        
        if (order.getProfitLossInPips() >= 0) {
            winCounter++;
            consecutiveLossCounter = 0;
        } else {            
            // Always trade in the current direction of the price
            orderCommand = orderCommand.isLong() ? OrderCommand.SELL : OrderCommand.BUY;
            lossCounter++;
            consecutiveLossCounter++;
            
            if (consecutiveLossCounter > maxConsecutiveLossCounter) {
                maxConsecutiveLossCounter = consecutiveLossCounter;
            }
            
            if (consecutiveLossCounter >= maxConsecutiveLosses) {
                consecutiveLossCounter = 0;
                log("*** MAX CONSECUTIVE LOSSES HIT ***");
            }
        }
        
        placeOrder(orderCommand);
    }
    
    //*****************************************************************************************************************
    // Public Methods - Implementation of the IStrategy interface
    //*****************************************************************************************************************   
    public void onStart(IContext context) throws JFException {
        this.context = context;        
        Set<Instrument> instruments = new HashSet<Instrument>();
        instruments.add(instrument);
        context.setSubscribedInstruments(instruments);        
        
        log("Started the " + getName() + " strategy using " + instrument + ".");
    }

    public void onMessage(IMessage message) throws JFException {
        if (message.getOrder().getInstrument().equals(instrument)) {
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
        log("Max Consecutive Losses: " + maxConsecutiveLossCounter);
        
        log(getName() + " strategy stopped.");
    }
    
    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }
    
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (this.instrument.equals(instrument) && Period.FIVE_MINS.equals(period) && !hasOpenOrder()) {
            IBar bar = context.getHistory().getBar(instrument, Period.FIVE_MINS, OfferSide.BID, 0);
            OrderCommand orderCommand = OrderCommand.BUY;
            if (bar.getClose() < bar.getOpen()) {
                orderCommand = OrderCommand.SELL;
            }
            placeOrder(orderCommand);
        }
    }

    public void onAccount(IAccount account) throws JFException {
    }
}
