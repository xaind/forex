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
 * Simple trend following strategy that uses a Martingale money management
 * system across multiple currency pairs.
 * 
 * @author Xaind, 2015.
 * @version 1.0
 */
public class YlemStrategy implements IStrategy {

    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS");
    private static final SimpleDateFormat DATE_FORMAT_MONTH = new SimpleDateFormat("MMMMM yyyy");

    static {
        DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
        DATE_FORMAT_MONTH.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    // *****************************************************************************************************************
    // Instance Fields
    // *****************************************************************************************************************
    volatile IContext context;
    volatile int orderCounter;
    volatile boolean armed;
    volatile boolean hasOrder;
    volatile long waitTime;

    volatile int winCounter;
    volatile int lossCounter;
    volatile int consecutiveLossCounter;
    volatile int maxConsecutiveLossCounter;

    final ConcurrentMap<Instrument, InstrumentInfo> instruments = new ConcurrentHashMap<>();

    @Configurable(value = "Base Trade Amount")
    public final double baseTradeAmount = 5;
    
    @Configurable(value = "Min Index")
    public final double minIndex = 3;
    
    @Configurable(value = "Max Size")
    public final int maxSize = 50;
    
    @Configurable(value = "Min Size")
    public final int minSize = 10;

    @Configurable(value = "Martingale Factor")
    public final double martingaleFactor = 2.0;

    @Configurable(value = "Max Consecutive Losses")
    public final double maxConsecutiveLosses = 10;

    // *****************************************************************************************************************
    // Private Methods
    // *****************************************************************************************************************
    private String getName() {
        return "YLEM";
    }

    private void log(String message) {
        context.getConsole().getOut().println(message);
    }

    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }

    private double getLotSize(InstrumentInfo info) throws JFException {
        // Calculate the current lot size based on the current number of consecutive losses
        double tradeAmount = Math.pow(1 + martingaleFactor, consecutiveLossCounter) * baseTradeAmount;
        return Math.max(round((tradeAmount / info.size) * 0.001, 3), 0.001);
    }

    private void placeOrder(InstrumentInfo info) throws JFException {
        String label = getName() + "_" + (++orderCounter);
        context.getEngine().submitOrder(label, info.instrument, info.orderCommand, getLotSize(info), 0, 0);
    }

    private void onOrderCancelled(IMessage message) throws JFException {
        log("Error executing order: " + message.getContent());
        waitTime = System.currentTimeMillis() + 600000;
        hasOrder = false;
        checkAndOrder();
    }

    private void onOrderFilled(IOrder order) throws JFException {
        InstrumentInfo info = instruments.get(order.getInstrument());

        // Set the take profit and stop loss prices
        double openPrice = order.getOpenPrice();
        double margin = info.instrument.getPipValue() * info.size;
        int negator = order.isLong() ? 1 : -1;

        order.setTakeProfitPrice(round(openPrice + (negator * margin), info.instrument.getPipScale()));
        order.setStopLossPrice(round(openPrice - (negator * margin), info.instrument.getPipScale()));

        log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + info.instrument + " " + order.getOrderCommand()
                + " order" + " at $" + order.getOpenPrice() + ". [equity=$" + context.getAccount().getEquity() + ", comm=$" + order.getCommissionInUSD() 
                + ", lots=" + order.getAmount() + "]");
    }

    private void onOrderClosed(IOrder order) throws JFException {
        log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCloseTime())) + ": Closed " + order.getInstrument() +  " "
                + order.getOrderCommand() + " order" + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") "
                + (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [equity=$" + context.getAccount().getEquity() + ", comm=$"
                + order.getCommissionInUSD() + ", consecutiveLosses=" + (consecutiveLossCounter + 1) + "]");

        if (order.getProfitLossInPips() >= 0) {
            winCounter++;
            consecutiveLossCounter = 0;
        } else {
            lossCounter++;
            consecutiveLossCounter++;

            if (consecutiveLossCounter >= maxConsecutiveLosses) {
                log("*** MAX CONSECUTIVE LOSSES HIT ***");
                maxConsecutiveLossCounter++;
                consecutiveLossCounter = 0;
            }
        }
        
        waitTime = System.currentTimeMillis() + 600000;
        hasOrder = false;
        checkAndOrder();
    }
    
    private InstrumentInfo getBestInstrument() {
        List<InstrumentInfo> infos = new ArrayList<>(instruments.values());
        Collections.sort(infos);
        return infos.get(0);
    }
    
    private void checkAndOrder() throws JFException {
        synchronized (instruments) {
            if (!hasOrder && System.currentTimeMillis() > waitTime) {
                InstrumentInfo bestInstrument = getBestInstrument();
                if (bestInstrument.getBestIndex().index > minIndex) {
                    waitTime = 0;
                    hasOrder = true;
                    placeOrder(bestInstrument);
                }
            }
        }
    }

    // *****************************************************************************************************************
    // Public Methods - Implementation of the IStrategy interface
    // *****************************************************************************************************************
    public void onStart(IContext context) throws JFException {
        this.context = context;

        instruments.put(Instrument.EURUSD, new InstrumentInfo(Instrument.EURUSD));
        instruments.put(Instrument.EURGBP, new InstrumentInfo(Instrument.EURGBP));
        instruments.put(Instrument.EURJPY, new InstrumentInfo(Instrument.EURJPY));
        instruments.put(Instrument.GBPUSD, new InstrumentInfo(Instrument.GBPUSD));
        instruments.put(Instrument.GBPJPY, new InstrumentInfo(Instrument.GBPJPY));
        instruments.put(Instrument.USDJPY, new InstrumentInfo(Instrument.USDJPY));

        context.setSubscribedInstruments(instruments.keySet());

        log("\nStarted the " + getName() + " strategy using " + instruments.size() + " instruments.");
    }

    public void onMessage(IMessage message) throws JFException {
        InstrumentInfo info = instruments.get(message.getOrder().getInstrument());

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
        log("Win%: " + (totalTrades == 0 ? 0 : round((winCounter * 100.0 / totalTrades), 0)) + "% (" + winCounter + " wins / " + lossCounter + " losses)");
        log("Max Loss Count: " + maxConsecutiveLossCounter);

        log(getName() + " strategy stopped.");
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
        InstrumentInfo info = instruments.get(instrument);
        if (info != null) {
            if (info.basePrice <= 0) {
                info.update(tick.getAsk());
            }
        }
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (armed && Period.ONE_HOUR.equals(period)) {
            checkAndOrder();
        }
    }

    public void onAccount(IAccount account) throws JFException {
    }

    // *****************************************************************************************************************
    // Static helper classes
    // *****************************************************************************************************************
    private class InstrumentInfo implements Comparable<InstrumentInfo> {

        final Instrument instrument;
        volatile double basePrice;
        OrderCommand orderCommand;
        int size;
        
        volatile List<Index> indexes = new ArrayList<>();

        InstrumentInfo(Instrument instrument) {
            this.instrument = instrument;
            
            // Initialize the trend indexes
            for (int size = minSize; size <= maxSize; size += 10) {
                indexes.add(new TrendIndex(size, instrument.getPipValue()));
                indexes.add(new RangeIndex(size, instrument.getPipValue()));
            }
        }

        public void update(double price) {
            for (Index index : indexes) {
                index.update(price);
            }
        }
        
        public Index getBestIndex() {
            Index bestIndex = null;
            for (Index index : indexes) {
                if (bestIndex == null || Math.abs(bestIndex.index) < Math.abs(index.index)) {
                    bestIndex = index;
                }
            }

            orderCommand = bestIndex.orderCommand;
            size = bestIndex.size;
            
            return bestIndex;
        }
        
        @Override
        public int compareTo(InstrumentInfo info) {
            return info.getBestIndex().index - this.getBestIndex().index;
        }
    }
    
    private abstract class Index {
        
        int size;
        double pip;
        double basePrice;
        int index;
        OrderCommand orderCommand;
        
        Index(int size, double pip) {
            this.size = size;
            this.pip = pip;
        }
        
        abstract void update(double price);
    }
    
    private class TrendIndex extends Index {
        
        public TrendIndex(int size, double pip) {
            super(size, pip);
        }
        
        void update(double price) {
            if (basePrice == 0) {
                basePrice = price;
                return;
            }
            
            double diff = size * pip;
            if (price - basePrice > diff) {
                index = index >= 0 ? index + 1 : 0;
                basePrice = price;
            } else if (basePrice - price > diff) {
                index = index <= 0 ? index - 1 : 0;
                basePrice = price;
            }
            
            if (Math.abs(index) > minIndex) {
                armed = true;
            }
            
            orderCommand = index > 0 ? OrderCommand.BUY : OrderCommand.SELL;
        }
    }
    
    private class RangeIndex extends Index {
        
        int lastDirection;
        
        RangeIndex(int size, double pip) {
            super(size, pip);
        }
        
        void update(double price) {
            if (basePrice == 0) {
                basePrice = price;
                return;
            }
            
            double diff = size * pip;
            if (price - basePrice > diff) {
                index = lastDirection < 0 ? index + 1 : 0;
                lastDirection = 1;
                basePrice = price;
            } else if (basePrice - price > diff) {
                index = lastDirection > 0 ? index - 1 : 0;
                lastDirection = -1;
                basePrice = price;
            }
            
            if (index > minIndex) {
                armed = true;
            }
            
            orderCommand = lastDirection < 0 ? OrderCommand.BUY : OrderCommand.SELL;
        }
    }
}
