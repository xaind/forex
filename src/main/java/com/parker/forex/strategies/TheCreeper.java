package com.parker.forex.strategies;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
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
 * Performance-based strategy that monitors multiple concurrent strategies and opens th next order using the strategy
 * with the best current win percentage.
 * 
 * @since December 2018.
 */
public class TheCreeper implements IStrategy {

    private static final SimpleDateFormat DATE_FORMAT_SHORT = new SimpleDateFormat("yyyyMMdd");
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS");
    private static final SimpleDateFormat DATE_FORMAT_MONTH = new SimpleDateFormat("MMMMM yyyy");
    
    private static final long MILLIS_IN_YEAR = 31_536_000_000L;
    private static final long MILLIS_IN_DAY = 86_400_000L;
    
    static {
        DATE_FORMAT_SHORT.setTimeZone(TimeZone.getTimeZone("GMT"));
        DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
        DATE_FORMAT_MONTH.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    // *****************************************************************************************************************
    // Instance Fields
    // *****************************************************************************************************************
    volatile IContext context;
    volatile int orderCounter;

    volatile int winCounter;
    volatile int lossCounter;
    
    volatile double totalProfit;
    volatile double totalLockedProfit;
    volatile double maxDrawdown;
    
    volatile int consecutiveLossCounter;
    volatile int maxConsecutiveLossCounter;
    
    volatile long startTime;
    volatile long endTime;
    volatile double startEquity;
    
    volatile List<InstrumentStrategy> strategies;

    @Configurable(value = "Base Trade Amount")
    public final double baseTradeAmount = 1.0;
    
    @Configurable(value = "Max Trade Amount")
    public final double maxTradeAmount = 500.0;
    
    @Configurable(value = "Virtual Result Limit")
    public final int virtualResultLimit = 20;
    
    // *****************************************************************************************************************
    // Private Methods
    // *****************************************************************************************************************
    private void init(IContext context) {
        this.context = context;
        
        Set<Instrument> instruments = new HashSet<>();
        
        instruments.add(Instrument.EURUSD);
        instruments.add(Instrument.USDJPY);
        instruments.add(Instrument.GBPUSD);
        
        instruments.add(Instrument.EURJPY);
        instruments.add(Instrument.EURGBP);
        instruments.add(Instrument.GBPJPY);
        
        context.setSubscribedInstruments(instruments);

        instrumentInfos = new ArrayList<>();
        
        for (Instrument instrument : instruments) {
            instrumentInfos.add(new InstrumentInfo(instrument, OrderCommand.BUY, atrMultiplier));
            instrumentInfos.add(new InstrumentInfo(instrument, OrderCommand.SELL, atrMultiplier));
        }
        
        log("\nStarted the " + getName() + " strategy using " + instrumentInfos.size() + " instruments.");
    }
    
    private void checkAndTrade(Instrument instrument, Period period, long time) throws JFException {
        if (Period.DAILY.equals(period)) {
            List<InstrumentInfo> instrumentInfos = getInstrumentInfos(instrument);
        
            if (!instrumentInfos.isEmpty()) {
                if (startTime == 0) {
                    // If we haven't started yet then place orders for all instruments
                    startTime = time;
                    startEquity = context.getAccount().getEquity();
                    
                    for (InstrumentInfo nextInstrumentInfo : instrumentInfos) {
                        placeOrder(nextInstrumentInfo);
                    }
                } else {
                    // If an instrument does not have an open order then place one
                    for (InstrumentInfo instrumentInfo : instrumentInfos) {
                        if (!hasOpenPosition(instrumentInfo)) {
                            placeOrder(instrumentInfo);
                        }
                    }
                }
            }
        }
    }
    
    private String getName() {
        return "THE_CREEPER";
    }

    private void log(String label, long time, String message) {
        log(label + " @ " + DATE_FORMAT_LONG.format(new Date(time)) + ": " + message); 
    }
    
    private void log(String message) {
        context.getConsole().getOut().println(message);
    }

    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private double getTradeAmount(InstrumentInfo instrumentInfo) {
        double tradeAmount = this.baseTradeAmount;
        
        // Increase the trade amount for the best instrument if we are under the locked profit amount
        if (this.totalProfit < this.totalLockedProfit) {
            double lossMultiplier = getLossMultiplier(instrumentInfo);
            tradeAmount = (this.totalLockedProfit - this.totalProfit) * lossMultiplier + tradeAmount;
        }
        
        return tradeAmount;
    }
    
    private synchronized double getLossMultiplier(InstrumentInfo instrumentInfo) {
        Collections.sort(this.instrumentInfos);
        List<InstrumentInfo> profitableInstruments = new ArrayList<>();
        
        // Determine what instruments are profitable
        for (InstrumentInfo info : this.instrumentInfos) {
            if (info.winPct() - 0.5 > 0) {
                profitableInstruments.add(info);
            }
        }
        
        // If this instrument if profitable OR if no instruments are profitable then allocate the full loss
        if (profitableInstruments.contains(instrumentInfo) || profitableInstruments.isEmpty()) {
            return 1.0;
        } else {
            // Otherwise this instrument is not profitable so do not allocate any loss
            return 0;
        }
    }
    
    private boolean hasOpenPosition(InstrumentInfo instrumentInfo) throws JFException {
        for (IOrder order : context.getEngine().getOrders(instrumentInfo.instrument)) {
            if (!State.CLOSED.equals(order.getState()) && !State.CANCELED.equals(order.getState()) 
                    && order.getOrderCommand().equals(instrumentInfo.orderCommand)) {
                return true;
            }
        }
        return false;
    }
    
    private InstrumentInfo getInstrumentInfo(IOrder order) {
        for (InstrumentInfo instrumentInfo : getInstrumentInfos(order.getInstrument())) {
            if (instrumentInfo.orderCommand.equals(order.getOrderCommand())) {
                return instrumentInfo;
            }
        }
        return null;
    }
    
    private List<InstrumentInfo> getInstrumentInfos(Instrument instrument) {
        List<InstrumentInfo> infos = new ArrayList<>();
        for (InstrumentInfo instrumentInfo : instrumentInfos) {
            if (instrumentInfo.instrument.equals(instrument)) {
                infos.add(instrumentInfo);
            }
        }
        return infos;
    }
    
    private double getLotSize(InstrumentInfo instrumentInfo) throws JFException {
        Instrument instrument = instrumentInfo.instrument;
        
        // Calculate the take profit pips based on the daily true range
        double atr = context.getIndicators().atr(instrument, Period.DAILY, OfferSide.BID, 30, 1);
        int takeProfitPips = (int) (atr * instrumentInfo.atrMultiplier / instrument.getPipValue());
        
        instrumentInfo.takeProfitPips = takeProfitPips;
        
        // Calculate the lot size
        double tradeAmount = getTradeAmount(instrumentInfo);
        return Math.max(round((tradeAmount / takeProfitPips) * 0.01, 3), 0.001);
    }

    private void placeOrder(InstrumentInfo instrumentInfo) throws JFException {
        String label = getName() + "_" + (++orderCounter);
        double lotSize = getLotSize(instrumentInfo);
        context.getEngine().submitOrder(label, instrumentInfo.instrument, instrumentInfo.orderCommand, lotSize, 0, 0);
    }

    private void handleMessage(IMessage message) throws JFException {
        IOrder order = message.getOrder();
        if (State.CANCELED.equals(order.getState())) {
            onOrderCancelled(message);
        } else if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
            onOrderFilled(order);
        } else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
            onOrderClosed(order);
        }
    }
    
    private void onOrderCancelled(IMessage message) throws JFException {
        IOrder order = message.getOrder();
        log(order.getLabel(), order.getCreationTime(), "Order has been cancelled: " + order.getInstrument() + " " + order.getOrderCommand() + " for " + order.getAmount() 
            + " lots. (" + message.getContent() + ")");
    }

    private void onOrderFilled(IOrder order) throws JFException {
        Instrument instrument = order.getInstrument();
        InstrumentInfo instrumentInfo = getInstrumentInfo(order);
        
        // Set the take profit and stop loss prices
        double openPrice = order.getOpenPrice();
        double margin = instrument.getPipValue() * instrumentInfo.takeProfitPips;
        int negator = order.isLong() ? 1 : -1;

        order.setTakeProfitPrice(round(openPrice + (negator * margin), instrument.getPipScale()));
        order.setStopLossPrice(round(openPrice - (negator * margin), instrument.getPipScale()));

        log(order.getLabel(), order.getFillTime(), "Filled " + instrumentInfo + " " + order.getOrderCommand() + " for " + order.getAmount() + " lots. " + instrumentInfo.getProfitStatus());
        endTime = order.getFillTime();
    }

    private void onOrderClosed(IOrder order) throws JFException {
        double profit = order.getProfitLossInUSD() - order.getCommissionInUSD();
        
        InstrumentInfo instrumentInfo = getInstrumentInfo(order);
        instrumentInfo.updateProfit(order);
        updateStats(profit);
        
        if (profit < 0) {
            lossCounter++;
            consecutiveLossCounter++;
            
            if (consecutiveLossCounter > maxConsecutiveLossCounter) {
                maxConsecutiveLossCounter = consecutiveLossCounter;
            }
        } else {
            winCounter++;
            consecutiveLossCounter = 0;
        }
        
        log(order.getLabel(), order.getCloseTime(), "Closed " + instrumentInfo + " " + order.getOrderCommand() + " of " + order.getAmount() + " lots for $" + round(profit, 2) + " " 
                + (profit >= 0 ? "PROFIT" : "LOSS") + ". " + instrumentInfo.getProfitStatus());
        
        double winPct = 1.0 * this.winCounter / (this.winCounter + this.lossCounter) * 100.0;
        log("Overall: totalProfit=$" + round(this.totalProfit, 2) + ", totalLockedProfit=$" + round(this.totalLockedProfit, 2)  
                + ", winPct=" + round(winPct, 1) + "%, equity=$" + round(context.getAccount().getEquity(), 2) + "]");
        
        placeOrder(instrumentInfo);
    }
    
    private void updateStats(double profit) {
        this.totalProfit += profit;
        if (totalProfit > totalLockedProfit) {
            totalLockedProfit = totalProfit;
        } else {
            double diff = this.totalLockedProfit - this.totalProfit;
            if (diff > maxDrawdown) {
                maxDrawdown = totalLockedProfit - totalProfit;
            }
            
            if (diff > this.maxTradeAmount) {
                log("Reducing total locked profit by $" + round(diff, 2) + ". " + getProfitStatus());
                this.totalLockedProfit = this.totalProfit;
            }
        }
    }
    
    private void outputStats() {
        log("--------------------------------------------------------------------------------------------------");
        log("Strategy: " + getName() + " (" + DATE_FORMAT_MONTH.format(new Date(startTime)) + " to " + DATE_FORMAT_MONTH.format(new Date(endTime)) + ")");
        log("Parameters: TradeAmount=" + round(baseTradeAmount, 2) + "%, MaxTradeAmount=" + round(maxTradeAmount, 2) + "%");
        
        double equity = context.getAccount().getEquity();
        double roi = (equity - startEquity) / startEquity * 100.0;
        roi = roi / ((endTime - startTime) / (1.0 * MILLIS_IN_YEAR));
        
        log("Total Equity: $" + round(equity, 2) + " (initial=$" + round(startEquity, 2) + ", profit=$" + round(equity - startEquity, 2) + ", roi=" + round(roi, 1) + "%pa)");

        int totalTrades = winCounter + lossCounter;
        double winPct = (totalTrades == 0 ? 0 : round((winCounter * 100.0 / totalTrades), 0));
        log("Total Trades: " + totalTrades + " (" + winCounter + " wins/" + lossCounter + " losses, win%=" + winPct + ")");
        
        log("Max Drawdown: $" + round(maxDrawdown, 2) + " (consecutiveLosses=" + maxConsecutiveLossCounter + ")");
        log(getName() + " strategy stopped.");
    }
    
    String getProfitStatus() {
        return "[profit=$" + round(totalProfit, 2) + ", lockedProfit=$" + round(totalLockedProfit, 2) + ", equity=$" 
                + round(context.getAccount().getEquity(), 2) + "]";
    }
    
    // *****************************************************************************************************************
    // Public Methods - Implementation of the IStrategy interface
    // *****************************************************************************************************************
    public void onStart(IContext context) throws JFException {
        init(context);
    }

    public void onMessage(IMessage message) throws JFException {
        handleMessage(message);
    }

    public void onStop() throws JFException {
        outputStats();
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        checkAndTrade(instrument, period, askBar.getTime());
    }

    public void onAccount(IAccount account) throws JFException {
    }
    
    static InstrumentStrategy createStrategy(Instrument instrument, StrategyType strategyType, int takeProfitPips) {
        InstrumentStrategy strategy = new InstrumentStrategy();
        strategy.instrument = instrument;
        strategy.strategyType = strategyType;
        strategy.takeProfitPips = takeProfitPips;
        strategy.orderCommand = strategy.virtualOrderCommand = OrderCommand.BUY;
        return strategy;
    }
    
    // *****************************************************************************************************************
    // Static helper classes
    // *****************************************************************************************************************
    enum StrategyType {
        TREND, RANGE, BUY, SELL;
    }
    
    static class InstrumentStrategy implements Comparable<InstrumentStrategy> {

        Instrument instrument;
        StrategyType strategyType;
        OrderCommand orderCommand;
        int takeProfitPips;
        
        OrderCommand virtualOrderCommand;
        double virtualOrderPrice;
        long virtualOrderTime;
        List<Boolean> virtualResults = new ArrayList<>();
        List<Long> virtualOrderDurations = new ArrayList<>();

        void onTick(ITick tick) {
            double price = OrderCommand.BUY.equals(this.virtualOrderCommand) ? tick.getAsk() : tick.getBid();
            long time = tick.getTime();
            
            if (virtualOrderPrice == 0) {
                virtualOrderPrice = price;
                virtualOrderTime = time;
            } else if (Math.abs(price - virtualOrderPrice) > takeProfitPips) {
                if (OrderCommand.BUY.equals(virtualOrderCommand) && price > virtualOrderPrice ||
                        OrderCommand.SELL.equals(virtualOrderCommand) && price < virtualOrderPrice) {
                    virtualResults.add(true);
                    
                    if (StrategyType.RANGE.equals(strategyType)) {
                        switchOrderCommand();
                    }
                } else {
                    virtualResults.add(false);
                    if (StrategyType.TREND.equals(strategyType)) {
                        switchOrderCommand();
                    }
                }
                
                if (virtualResults.size() > virtualResultLimit) {
                    virtualResults = virtualResults.subList(0, virtualResultLimit);
                }
                
                virtualOrderDurations.add(time - virtualOrderTime);
                
                if (virtualOrderDurations.size() > virtualResultLimit) {
                    virtualOrderDurations = virtualOrderDurations.subList(0, virtualResultLimit);
                }
                
                virtualOrderPrice = price;
                virtualOrderTime = time;
            }
        }
        
        void switchOrderCommand() {
            this.virtualOrderCommand = OrderCommand.BUY.equals(this.virtualOrderCommand) ? OrderCommand.SELL : OrderCommand.BUY;
        }
        
        void updateProfit(IOrder order) {
            double orderProfit = order.getProfitLossInUSD() - order.getCommissionInUSD();
            this.profit += orderProfit;
            
            if (orderProfit >= 0) {
                this.wins++;
                this.results.add(true);
            } else {
                this.losses++;
                this.results.add(false);
            }
            
            if (this.results.size() > winTradeLimit) {
                this.results.remove(0);
            }
            
            this.totalTradeTime += 1.0 * (order.getCloseTime() - order.getFillTime()) / MILLIS_IN_DAY;
        }
        
        String getProfitStatus() {
            return "[profit=$" + round(this.profit, 2) + ", win%=" + round(winPct() * 100, 1) + "%, avgTradeFreq=" + round(this.avgTradeFrequency(), 1) + "/day]";
        }
        
        double winPct() {
            if (this.virtualResults.size() > virtualResultLimit) {
                return 0;
            }
            
            int wins = 0;
            for (Boolean result : this.virtualResults) {
                if (result) {
                    wins++;
                }
            }
            return 1.0 * wins / this.virtualResults.size(); 
        }
        
        double avgOrderDuration() {
            if (this.virtualOrderDurations.size() < virtualResultLimit) {
                return 0;
            } else {
                return virtualOrderDurations.stream()
                        .mapToDouble(d -> d)
                        .average()
                        .getAsDouble();
            }
        }
        
        @Override
        public String toString() {
            return this.instrument + " (" + this.orderCommand + ")";
        }

        @Override
        public int compareTo(InstrumentStrategy strategy) {
            return (int)(strategy.winPct() - this.winPct());
        }
    }
}
