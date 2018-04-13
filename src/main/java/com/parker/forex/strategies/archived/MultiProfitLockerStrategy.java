package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * A constant order type strategy that either always buys or always sells. It continuously sets a profit target
 * and will increase lot sizes when the current profit drops below this target.
 *
 * @author Xaind, 2017.
 * @version 1.0
 */
public class MultiProfitLockerStrategy implements IStrategy {

    private static final SimpleDateFormat DATE_FORMAT_SHORT = new SimpleDateFormat("yyyyMMdd");
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS");
    private static final SimpleDateFormat DATE_FORMAT_MONTH = new SimpleDateFormat("MMMMM yyyy");
    
    private static final long MILLIS_IN_YEAR = 31_536_000_000L;
    
    private static final List<String> NON_TRADEABLE_DATES = Arrays.asList(new String[]{
            "20171224",
            "20170813"
    });

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

    volatile double totalProfit;
    volatile double lockedProfit;

    volatile int winCounter;
    volatile int lossCounter;
    
    volatile int consecutiveLossCounter;
    volatile int maxConsecutiveLossCounter;
    
    volatile long startTime;
    volatile long endTime;
    volatile double startEquity;
    
    volatile Set<Instrument> instruments;
    volatile Map<Instrument, Integer> pipMap = new HashMap<>();

    @Configurable(value = "Base Trade Amount")
    public final double baseTradeAmount = 1;
    
    @Configurable(value = "Max Trade Amount")
    public final double maxTradeAmount = 2000;
    
    @Configurable(value = "ATR Multiplier (Take Profit Pips)")
    public final double atrMultiplier = 0.5;
    
    @Configurable(value = "Order Command (Buy/Sell)")
    public final OrderCommand orderCommand = OrderCommand.SELL;

    // *****************************************************************************************************************
    // Private Methods
    // *****************************************************************************************************************
    private String getName() {
        return "MULTI_PROFIT_LOCKER";
    }

    private void log(String message) {
        context.getConsole().getOut().println(message);
    }

    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }

    private boolean hasOpenPosition(Instrument instrument) throws JFException {
        for (IOrder order : context.getEngine().getOrders(instrument)) {
            if (!State.CLOSED.equals(order.getState()) && !State.CANCELED.equals(order.getState())) {
                return true;
            }
        }
        return false;
    }
    
    private boolean checkTime(long time) {
        String date = DATE_FORMAT_SHORT.format(new Date(time));
        return !NON_TRADEABLE_DATES.contains(date);
    }
    
    private double getLotSize(Instrument instrument) throws JFException {
        double tradeAmount = baseTradeAmount;
        
        // Increase the trade amount if we are under the locked profit amount
        if (totalProfit < lockedProfit) {
            tradeAmount = (lockedProfit - totalProfit) / instruments.size() + baseTradeAmount;
            tradeAmount = Math.min(tradeAmount, maxTradeAmount);
        }
        
        // Calculate the take profit pips based on the daily true range
        double atr = context.getIndicators().atr(instrument, Period.DAILY, OfferSide.BID, 30, 1);
        int takeProfitPips = (int) (atr * atrMultiplier / instrument.getPipValue());
        
        pipMap.put(instrument, takeProfitPips);
        
        // Caclulate the lot size
        return Math.max(round((tradeAmount / takeProfitPips) * 0.01, 3), 0.001);
    }

    private void placeOrder(Instrument instrument) throws JFException {
        String label = getName() + "_" + (++orderCounter);
        double lotSize = getLotSize(instrument);
        context.getEngine().submitOrder(label, instrument, orderCommand, lotSize, 0, 0);
    }

    private void onOrderCancelled(IMessage message) throws JFException {
        IOrder order = message.getOrder();
        log("Order has been cancelled: " + order.getInstrument() + " " + order.getOrderCommand() + " for " + order.getAmount() 
            + " lots. ("  + message.getContent() + ")");
    }

    private void onOrderFilled(IOrder order) throws JFException {
        Instrument instrument = order.getInstrument();
        
        // Set the take profit and stop loss prices
        double openPrice = order.getOpenPrice();
        double margin = instrument.getPipValue() * pipMap.get(instrument);
        int negator = order.isLong() ? 1 : -1;

        order.setTakeProfitPrice(round(openPrice + (negator * margin), instrument.getPipScale()));
        order.setStopLossPrice(round(openPrice - (negator * margin), instrument.getPipScale()));

        log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + instrument + " " + order.getOrderCommand()
                + " of " + order.getAmount() + " lots at $" + order.getOpenPrice() + ". [totalProfit=$" + round(totalProfit, 2) + ", lockedProfit=$" 
                + round(lockedProfit, 2) + "]");
        
        endTime = order.getFillTime();
    }

    private void onOrderClosed(IOrder order) throws JFException {
        Instrument instrument = order.getInstrument();
        
        double profit = order.getProfitLossInUSD() - order.getCommissionInUSD();
        totalProfit += profit;
        
        // Recalculate locked profit
        if (totalProfit > lockedProfit) {
            lockedProfit = totalProfit;
        }
        
        log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCloseTime())) + ": Closed " + instrument + " " + order.getOrderCommand()
            + " of " + order.getAmount() + " lots for $" + round(profit, 2) + " " + (profit >= 0 ? "PROFIT" : "LOSS") + ". [totalProfit=$" + round(totalProfit, 2)
            + ", lockedProfit=$" + round(lockedProfit, 2) + "]");
        
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
        
        if (checkTime(order.getCloseTime())) {
            placeOrder(instrument);
        }
    }
    
    // *****************************************************************************************************************
    // Public Methods - Implementation of the IStrategy interface
    // *****************************************************************************************************************
    public void onStart(IContext context) throws JFException {
        this.context = context;
        
        instruments = new HashSet<>();
//        instruments.add(Instrument.EURUSD);
//        instruments.add(Instrument.USDJPY);
//        instruments.add(Instrument.GBPUSD);
//        
//        instruments.add(Instrument.EURJPY);
        instruments.add(Instrument.EURGBP);
//        instruments.add(Instrument.GBPJPY);
        
        context.setSubscribedInstruments(instruments);

        log("\nStarted the " + getName() + " strategy using " + instruments.size() + " instruments.");
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
        double equity = context.getAccount().getEquity();
        
        log("--------------------------------------------------------------------------------------------------");
        log("Strategy: " + getName());
        log("Parameters: Mode=" + orderCommand + ", BaseTradeAmount=$" + round(baseTradeAmount, 2) + ", MaxTradeAmount=$" + round(maxTradeAmount, 2));
        
        double roi = (equity - startEquity) / startEquity * 100.0;
        roi = roi / ((endTime - startTime) / (1.0 * MILLIS_IN_YEAR));
        
        log("Total Equity: $" + round(equity, 2) + " (ROI%: " + round(roi, 1) + "%pa)");

        int totalTrades = winCounter + lossCounter;
        log("Total Trades: " + totalTrades);
        log("Win%: " + (totalTrades == 0 ? 0 : round((winCounter * 100.0 / totalTrades), 0)) + "% (" + winCounter + " wins / " + lossCounter + " losses)");
        log("Max Consecutive Losses: " + maxConsecutiveLossCounter);
        
        log(getName() + " strategy stopped.");
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (startTime == 0 && Period.DAILY.equals(period) && checkTime(askBar.getTime())) {
            startTime = askBar.getTime();
            startEquity = context.getAccount().getEquity();
            
            for (Instrument nextInstrument : instruments) {
                placeOrder(nextInstrument);
            }
        }
        
        if (startTime > 0 && Period.DAILY.equals(period) && instruments.contains(instrument) 
                && !hasOpenPosition(instrument) && checkTime(askBar.getTime())) {
            placeOrder(instrument);
        }
    }

    public void onAccount(IAccount account) throws JFException {
    }
}
