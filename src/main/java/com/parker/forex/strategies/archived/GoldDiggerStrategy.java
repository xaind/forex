package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

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
 * Simple trend following strategy that uses a Martingale money management system and accommodates multiple
 * currency pairs simultaneously.
 * 
 * @author Xaind, 2014.
 * @version 1.0
 */
public class GoldDiggerStrategy implements IStrategy {
    
    private static final String STRATEGY_NAME = "GOLD_DIGGER";
        
    private static final TimeZone GMT_TIME_ZONE = TimeZone.getTimeZone("GMT");
    
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
    private static final SimpleDateFormat DATE_FORMAT_MONTH = new SimpleDateFormat("MMMMM yyyy");
    private static final SimpleDateFormat DATE_FORMAT_CHECK = new SimpleDateFormat("HHmm");
    
    static {
        DATE_FORMAT_LONG.setTimeZone(GMT_TIME_ZONE);
        DATE_FORMAT_MONTH.setTimeZone(GMT_TIME_ZONE);
        DATE_FORMAT_MONTH.setTimeZone(GMT_TIME_ZONE);
        DATE_FORMAT_CHECK.setTimeZone(GMT_TIME_ZONE);
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IContext context;
    private int orderCounter;
    private double martingaleFactor = 1.0;
    private double tradePct = 0.05;
    private int maxConsecutiveLosses = 4;
    
    private Map<Instrument, InstrumentInfo> instruments;
        
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message) {
        log(message, 0);
    }
    
    private void log(String message, long time) {
        String date = "";
        if (time != 0) {
            date = DATE_FORMAT_LONG.format(new Date(time)) + " ";
        }
        context.getConsole().getOut().println(date + message);    
    }
    
    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private double getLotSize(InstrumentInfo info) throws JFException {
        if (info.consecutiveLossCounter == 0) {
            info.lotSize = round(tradePct / info.takeProfitPips, 3);
            if (info.lotSize < 0.001) {
                info.lotSize = 0.001;
            }
            
            info.lotSize = 0.01;
        }
        
        // Calculate the current lot size based on the current number of consecutive losses
        double lotSize = Math.pow(1 + martingaleFactor, info.consecutiveLossCounter) * info.lotSize;
        return round(lotSize, 3);
    }
    
    private void placeOrder(InstrumentInfo info)  throws JFException {
        String label = STRATEGY_NAME + "_" + info.instrument.name() + "_" + (++orderCounter);
        context.getEngine().submitOrder(label, info.instrument, info.nextOrderCommand, getLotSize(info), 0, 0);
    }
 
    private boolean hasOpenOrder(Instrument instrument) throws JFException {
        for (IOrder order : context.getEngine().getOrders(instrument)) {
            if (!State.CLOSED.equals(order.getState()) && !State.CANCELED.equals(order.getState())) {
                return true;
            }
        }
        return false;
    }  
    
    private void onOrderCancelled(IMessage message) throws JFException {    
        log("Error executing order: " + message.getContent(), message.getCreationTime());          
    }
    
    private void onOrderFilled(IOrder order) throws JFException {
        InstrumentInfo info = instruments.get(order.getInstrument());
        
        // Set the take profit and stop loss prices
        double openPrice = order.getOpenPrice();
        double margin = info.instrument.getPipValue() * info.takeProfitPips;
        int negator = order.isLong() ? 1 : -1;
        
        order.setTakeProfitPrice(round(openPrice + (negator * margin), info.instrument.getPipScale()));
        order.setStopLossPrice(round(openPrice - (negator * margin), info.instrument.getPipScale()));    
    }
    
    private void onOrderClosed(IOrder order) throws JFException {
        InstrumentInfo info = instruments.get(order.getInstrument());
        info.nextOrderCommand = order.getOrderCommand();
        
        info.equity += order.getProfitLossInUSD();
        info.commission += order.getCommissionInUSD();
        int consecutiveLosses = info.consecutiveLossCounter;
        
        if (order.getProfitLossInPips() >= 0) {
            info.winCounter++;
            info.consecutiveLossCounter = 0;
            info.nextOrderCommand = info.nextOrderCommand.isLong() ? OrderCommand.SELL : OrderCommand.BUY;
        } else {            
            // Always trade in the current direction of the price            
            info.lossCounter++;
            info.consecutiveLossCounter++;
            consecutiveLosses++;
                
            if (info.consecutiveLossCounter > info.maxConsecutiveLossCounter) {
                info.maxConsecutiveLossCounter = info.consecutiveLossCounter;
            }
            
            if (info.consecutiveLossCounter >= maxConsecutiveLosses) {
                info.outrightLossCounter++;
                info.consecutiveLossCounter = 0;
            }
        }
        
        log(order.getInstrument() + ": Closed " + order.getOrderCommand() + " order" + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") " + 
                (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [equity=$" + context.getAccount().getEquity() + ", comm=$" + order.getCommissionInUSD() + 
                ", lots=" + round(order.getAmount(), 3) + ", consecutiveLosses=" + consecutiveLosses + "]", order.getCloseTime());
                
        if (info.consecutiveLossCounter != consecutiveLosses && order.getProfitLossInPips() < 0) {
            log("*********** MAX CONSECUTIVE LOSSES HIT FOR " + info.instrument + " ***********", order.getCloseTime());
        }
        
        if (checkTime(order.getCloseTime())) {
            placeOrder(info);
        }
    }
    
    private boolean checkTime(long time) {
        return true;
    }
    
    private void logResults(InstrumentInfo info) {
        log("-----------------------------------------------------------------------------");
        
        if (info.instrument == null) {
            log("Overall Results");
            log("Total Equity: $" + round(info.equity, 2));
        } else {
            log("Results for Instrument: " + info.instrument);
            log("Rank: " + info.rank);
            log("Total Profit: $" + round(info.equity - info.commission, 2));
            log("Commission: $" + round(info.commission, 2));
        }
        
        int totalTrades = info.winCounter + info.lossCounter;
        
        log("Total Trades: " + totalTrades);
        
        if (totalTrades > 0) {
            log("Win%: " + (totalTrades == 0 ? 0: round((info.winCounter * 100.0 / totalTrades), 0)) + "% (" + info.winCounter + " wins / " + info.lossCounter + " losses)");
        } else {
            log("Win%: 0%");
        }

        if (info.winCounter > 0 || info.outrightLossCounter > 0) {
            log("Outright Win%: " + (totalTrades == 0 ? 0: round((info.winCounter * 100.0 / (info.winCounter + info.outrightLossCounter)), 0)) + "% (" + info.winCounter + " wins / " + info.outrightLossCounter + " outright losses)");
            log("Max Consecutive Losses: " + info.maxConsecutiveLossCounter);
        } else {
            log("Outright Win%: 0%");
        }
    }
    
    //*****************************************************************************************************************
    // Public Methods - Implementation of the IStrategy interface
    //*****************************************************************************************************************   
    public void onStart(IContext context) throws JFException {
        this.context = context;        
        instruments = new HashMap<Instrument, GoldDiggerStrategy.InstrumentInfo>();
        
        // Define the instrument parameters
        instruments.put(Instrument.USDJPY, new InstrumentInfo(Instrument.USDJPY));
       
        context.setSubscribedInstruments(instruments.keySet());
        
        log("Started the " + STRATEGY_NAME + " strategy using " + instruments.size() + " instruments.");
    }

    public void onMessage(IMessage message) throws JFException {
        IOrder order = message.getOrder();
        if (State.CANCELED.equals(order.getState())) {
            onOrderCancelled(message);
        } else if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
            onOrderFilled(order);                
        } else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {                
            onOrderClosed(order);
        }
    }

    public void onStop() throws JFException { 
        int winCounter = 0;
        int lossCounter = 0;
        int outrightLossCounter = 0;
        int maxConsecutiveLossCounter = 0;
        
        List<InstrumentInfo> infos = new ArrayList<InstrumentInfo>(this.instruments.values());
        Collections.sort(infos);
        
        int rankCounter = infos.size();
        
        for (InstrumentInfo info : infos) {
            info.rank = rankCounter--;
            winCounter += info.winCounter;
            lossCounter += info.lossCounter;
            outrightLossCounter += info.outrightLossCounter;
            
            if (maxConsecutiveLossCounter < info.maxConsecutiveLossCounter) {
                maxConsecutiveLossCounter = info.maxConsecutiveLossCounter;
            }
            
            logResults(info);
        }
        
        InstrumentInfo info = new InstrumentInfo(context.getAccount().getEquity(), winCounter, lossCounter, outrightLossCounter, maxConsecutiveLossCounter);
        logResults(info);
        
        log("-----------------------------------------------------------------------------");
        log(STRATEGY_NAME + " strategy stopped.");
    }
    
    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }
    
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        InstrumentInfo info = instruments.get(instrument);
        if (info != null && checkTime(bidBar.getTime()) && !hasOpenOrder(instrument)) {
            IBar bar = context.getHistory().getBar(info.instrument, Period.THIRTY_MINS, OfferSide.BID, 0);
            info.nextOrderCommand = OrderCommand.BUY;
            if (bar.getClose() < bar.getOpen()) {
                info.nextOrderCommand = OrderCommand.SELL;
            }
            placeOrder(info);
        }
    }

    public void onAccount(IAccount account) throws JFException {
    }
    
    //*******************************************************************************************************************
    // Inner Classes
    //********************************************************************************************************************
    class InstrumentInfo implements Comparable<InstrumentInfo> {
    
        Instrument instrument;
        int takeProfitPips = 50;
        double lotSize;      
        OrderCommand nextOrderCommand;
        
        int rank;
        double equity;
        double commission;
        int winCounter;
        int lossCounter;
        int outrightLossCounter;
        int consecutiveLossCounter;
        int maxConsecutiveLossCounter;
        
        InstrumentInfo(Instrument instrument) {
            this.instrument = instrument;
        }
        
        InstrumentInfo(double equity, int winCounter, int lossCounter, int outrightLossCounter, int maxConsecutiveLossCounter) {
            this.equity = equity;
            this.winCounter = winCounter;
            this.lossCounter = lossCounter;
            this.outrightLossCounter = outrightLossCounter;
            this.maxConsecutiveLossCounter = maxConsecutiveLossCounter;
        }

        @Override
        public int compareTo(InstrumentInfo info) {
            return (int)((this.equity - this.commission) - (info.equity - info.commission));
        }
    }
}
