/*
 * Copyright (c) 2015 Shane Parker. All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, is 
 * NOT permitted without prior consent and authorization from the copyright holder.
 */
package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.Filter;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IContext;
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

public class DualityStrategy implements IStrategy {

    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
    
    private static final String STRATEGY_NAME = "DUALITY";
    private static final long MILLIS_IN_DAY = 1000 * 60 * 60 * 24;
    private static final long MILLIS_IN_WEEK = MILLIS_IN_DAY * 7;
    
    static {
        DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    enum TradeType {TRENDING, RANGING;}

    // *****************************************************************************************************************
    // Instance Fields
    // *****************************************************************************************************************
    private IContext context;
    private int orderCounter;
        
    private double lotSize;
    private int takeProfitPips;
    
    private int trendWinCounter;
    private int rangeWinCounter;
    private int trendLossCounter;
    private int rangeLossCounter;
    
    private int trendPips;
    private double trendProfit;
    private double trendCommission;
    
    private int rangePips;
    private double rangeProfit;
    private double rangeCommission;
    
    private double maxDrawdown;
    private double currentDrawdown;
    
    private double openingEquity;
    private long startTime;
    
    private int trendConsecutiveLossCounter;
    private int rangeConsecutiveLossCounter;
    
    @Configurable("Instrument")
    public Instrument instrument = Instrument.EURUSD;
    
    @Configurable("% of Equity per Trade")
    public double tradePct = 0.1;
    
    @Configurable("Risk Reward Ratio")
    public double riskReward = 4.0;
    
    @Configurable("Range Multiplier")
    public double adrMultiplier = 1.0;
    
    @Configurable("Martingale Factor")
    public double martingaleFactor = 1.0;
    
    @Configurable("Max Consecutive Losses")
    public int maxConsecutiveLosses = 0;
    
    // *****************************************************************************************************************
    // Constructor
    // *****************************************************************************************************************
    public DualityStrategy() {
    }
    
    public DualityStrategy(Instrument instrument, double adrMultiplier, double riskReward) {
        this.instrument = instrument;
        this.adrMultiplier = adrMultiplier;
        this.riskReward = riskReward;
    }
    
    // *****************************************************************************************************************
    // Private Methods
    // *****************************************************************************************************************
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

    private double getLotSize(TradeType tradeType) throws JFException {
        int consecutiveLossCounter = trendConsecutiveLossCounter;
        if (TradeType.RANGING.equals(tradeType)) {
            consecutiveLossCounter = rangeConsecutiveLossCounter;
        }
        
        if (consecutiveLossCounter == 0) {
            double tradeAmount = context.getAccount().getBalance() * tradePct / 100.0;
            updateTakeProfit();
            lotSize = tradeAmount / (takeProfitPips * 100.0);
            
            if (lotSize < 0.001) {
                lotSize = 0.001;
            }
        }
        
        return round(Math.pow(1 + martingaleFactor, consecutiveLossCounter) * lotSize, 3);
    }
    
    private void updateTakeProfit() throws JFException {
        IHistory history = context.getHistory();
        
        long time = history.getStartTimeOfCurrentBar(instrument, Period.DAILY);
        List<IBar> bars = history.getBars(instrument, Period.DAILY, OfferSide.ASK, Filter.WEEKENDS, 60, time, 0);
        
        double totalPips = 0;
        for (IBar bar : bars) {
            totalPips += (bar.getHigh() - bar.getLow()) / instrument.getPipValue();
        }
        
        takeProfitPips = (int) (totalPips / bars.size() * adrMultiplier);
    }
    
    private synchronized void placeOrder(OrderCommand orderCommand, TradeType tradeType) throws JFException {
        String label = STRATEGY_NAME + "_" + instrument.name() + "_" + (++orderCounter) + "_" + tradeType;
        context.getEngine().submitOrder(label, instrument, orderCommand, getLotSize(tradeType), 0, 0);
    }

    private void onOrderCancelled(IMessage message) throws JFException {
        IOrder order = message.getOrder();
        log(getTradeType(order) + " order " + instrument + " " + order.getOrderCommand() + " " + getOrderId(order) + " " + order.getState() + ".");
    }

    private synchronized void onOrderFilled(IOrder order) throws JFException {
        // Set the take profit and stop loss prices
        double openPrice = order.getOpenPrice();
        double margin = instrument.getPipValue() * takeProfitPips;
        int negator = order.isLong() ? 1 : -1;

        order.setTakeProfitPrice(round(openPrice + (negator * margin), instrument.getPipScale()));
        order.setStopLossPrice(round(openPrice - (negator * margin * riskReward), instrument.getPipScale()));
    }

    private synchronized void onOrderClosed(IOrder order) throws JFException {        
        OrderCommand orderCommand = order.getOrderCommand();
        TradeType tradeType = getTradeType(order);
        
        if (TradeType.RANGING.equals(tradeType)) {
            rangeProfit += order.getProfitLossInUSD();
            rangeCommission += order.getCommissionInUSD();
            rangePips += order.getProfitLossInPips();
        } else {
            trendProfit += order.getProfitLossInUSD();
            trendCommission += order.getCommissionInUSD();
            trendPips += order.getProfitLossInPips();
        }
        
        if (order.getProfitLossInPips() >= 0) {
            if (currentDrawdown > maxDrawdown) {
                maxDrawdown = currentDrawdown;
                currentDrawdown = 0;
            }
            
            if (TradeType.RANGING.equals(tradeType)) {
                orderCommand = OrderCommand.SELL.equals(orderCommand) ? OrderCommand.BUY : OrderCommand.SELL;
                rangeWinCounter++;
                rangeConsecutiveLossCounter = 0;
            } else {
                trendWinCounter++;
                trendConsecutiveLossCounter = 0;
            }
        } else {
            currentDrawdown += Math.abs(order.getProfitLossInUSD());
            
            if (TradeType.TRENDING.equals(tradeType)) {
                orderCommand = OrderCommand.SELL.equals(orderCommand) ? OrderCommand.BUY : OrderCommand.SELL;
                trendLossCounter++;
                trendConsecutiveLossCounter++;
                if (trendConsecutiveLossCounter >= maxConsecutiveLosses) {
                    trendConsecutiveLossCounter = 0;
                }
            } else {
                rangeLossCounter++;
                rangeConsecutiveLossCounter++;
                if (rangeConsecutiveLossCounter >= maxConsecutiveLosses) {
                    rangeConsecutiveLossCounter = 0;
                }
            }
        }

        log("Closed " + instrument + " " + order.getOrderCommand() + " " + tradeType + " order " + getOrderId(order) + " for " + order.getProfitLossInPips() 
                + " pip (US$" + order.getProfitLossInUSD() + ") " + (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [open=$" 
                + round(order.getOpenPrice(), instrument.getPipScale()) + ", close=$" + round(order.getClosePrice(), instrument.getPipScale()) 
                + ", equity=$" + context.getAccount().getEquity() + ", comm=$" + order.getCommissionInUSD() + ", lots=" + round(order.getAmount(), 3) 
                + "]", order.getCloseTime());
        
        placeOrder(orderCommand, tradeType);
    }
    
    private TradeType getTradeType(IOrder order) {
        String[] parts = order.getLabel().split("_");
        return TradeType.valueOf(parts[3]);
    }
    
    private int getOrderId(IOrder order) {
        String[] parts = order.getLabel().split("_");
        return Integer.valueOf(parts[2]);
    }
    
    // *****************************************************************************************************************
    // Public Methods - Implementation of the IStrategy interface
    // *****************************************************************************************************************
    public void onStart(IContext context) throws JFException {
        this.context = context;
        log("Started the " + STRATEGY_NAME + " strategy.");
        
        openingEquity = context.getAccount().getBalance();
        startTime = context.getHistory().getTimeOfLastTick(instrument);
    }

    public void onStop() throws JFException {
        long endTime = context.getHistory().getTimeOfLastTick(instrument);
        int trendTrades = trendWinCounter + trendLossCounter;
        int rangeTrades = rangeWinCounter + rangeLossCounter;
        int overallWinCounter = trendWinCounter + rangeWinCounter;      
        int totalTrades = trendTrades + rangeTrades;
        
        double netTrendProfit = trendProfit - trendCommission;
        double trendPaRoi = (netTrendProfit / openingEquity) / ((endTime - startTime) / MILLIS_IN_DAY) * 36500.0;
        
        double netRangeProfit = rangeProfit - rangeCommission;
        double rangePaRoi = (netRangeProfit / openingEquity) / ((endTime - startTime) / MILLIS_IN_DAY) * 36500.0;
        
        double netProfit = netTrendProfit + netRangeProfit;
        double paRoi = (netProfit / openingEquity) / ((endTime - startTime) / MILLIS_IN_DAY) * 36500.0;
        double commission = trendCommission + rangeCommission;
        
        log("Total Trades: " + totalTrades + " (" + round(1.0 * totalTrades / ((endTime - startTime) / MILLIS_IN_WEEK), 1) + "/week)");
        log("Total Wins: " + overallWinCounter + " (" + round(100.0 * overallWinCounter / totalTrades, 1) + "%)");
        log("Profit: $" + round(netProfit, 2) + " (pips=" + (trendPips + rangePips) + ", openingBalance=$" + round(openingEquity, 2) + ", " + round(paRoi, 1) + "% pa)");
        log("Commission: $" + round(commission, 2) + " (" + round(commission / netProfit * 100.0, 2) + "% of profit)");
        log("Drawdown: $" + round(maxDrawdown, 2) + " (" + round(maxDrawdown / openingEquity * 100.0, 2) + "% of opening balance)");
        log("------");
        log("Trend Trades: " + trendTrades + " (" + round(1.0 * trendTrades / ((endTime - startTime) / MILLIS_IN_WEEK), 1) + "/week)");
        log("Trend Wins: " + trendWinCounter + " (" + round(100.0 * trendWinCounter / trendTrades, 1) + "%)");
        log("Trend Profit: $" + round(netTrendProfit, 2) + " (pips=" + trendPips + ", " + round(trendPaRoi, 1) + "% pa)");
        log("Trend Commission: $" + round(trendCommission, 2) + " (" + round(trendCommission / trendProfit * 100.0, 2) + "% of trend profit)");
        log("------");
        log("Range Trades: " + rangeTrades + " (" + round(1.0 * rangeTrades / ((endTime - startTime) / MILLIS_IN_WEEK), 1) + "/week)");
        log("Range Wins: " + rangeWinCounter + " (" + round(100.0 * rangeWinCounter / rangeTrades, 1) + "%)");
        log("Range Profit: $" + round(netRangeProfit, 2) + " (pips=" + rangePips + ", " + round(rangePaRoi, 1) + "% pa)");
        log("Range Commission: $" + round(rangeCommission, 2) + " (" + round(rangeCommission / rangeProfit * 100.0, 2) + "% of range profit)");
        log("-----------------------------------------------------------------------------");        
        
        log(STRATEGY_NAME + " strategy stopped.");
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

    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (orderCounter == 0) {
             placeOrder(OrderCommand.BUY, TradeType.TRENDING);
             placeOrder(OrderCommand.BUY, TradeType.RANGING);
        }
    }
    
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
    }

    public void onAccount(IAccount account) throws JFException {
    }
}
