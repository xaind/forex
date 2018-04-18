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
 * system that will only kick in under certain conditions.
 * 
 * @author Xaind, 2017.
 * @version 1.0
 */
public class SimpleMartingaleStrategy implements IStrategy {

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
    volatile boolean started;
    volatile int orderCounter;

    volatile int winCounter;
    volatile int lossCounter;
    volatile int consecutiveWinCounter;
    volatile int consecutiveLossCounter;
    volatile int maxConsecutiveLossCounter;
    
    volatile double takeProfitPips = 50;
    volatile double totalProfit;
    volatile double lockedProfit;

    final Instrument instrument = Instrument.EURUSD;

    @Configurable(value = "Base Trade Amount")
    public final double baseTradeAmount = 25;

    // *****************************************************************************************************************
    // Private Methods
    // *****************************************************************************************************************
    private String getName() {
        return "SIMPLE_MARTINGALE";
    }

    private void log(String message) {
        context.getConsole().getOut().println(message);
    }

    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }

    private double getLotSize() throws JFException {
        double tradeAmount = baseTradeAmount;
        if (totalProfit < lockedProfit) {
            tradeAmount = lockedProfit - totalProfit;
        }
        
        return Math.max(round((tradeAmount / takeProfitPips) * 0.01, 3), 0.001);
    }

    private void placeOrder(OrderCommand orderCommand) throws JFException {
        String label = getName() + "_" + (++orderCounter);
        context.getEngine().submitOrder(label, instrument, orderCommand, getLotSize(), 0, 0);
    }

    private void onOrderCancelled(IMessage message) throws JFException {
        log("Error executing order: " + message.getContent());
        placeOrder(OrderCommand.BUY);
    }

    private void onOrderFilled(IOrder order) throws JFException {
        // Set the take profit and stop loss prices
        double openPrice = order.getOpenPrice();
        double margin = instrument.getPipValue() * takeProfitPips;
        int negator = order.isLong() ? 1 : -1;

        order.setTakeProfitPrice(round(openPrice + (negator * margin), instrument.getPipScale()));
        order.setStopLossPrice(round(openPrice - (negator * margin), instrument.getPipScale()));

        log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + instrument + " " + order.getOrderCommand()
                + " order" + " at $" + order.getOpenPrice() + ". [equity=$" + context.getAccount().getEquity() + ", comm=$" + order.getCommissionInUSD() 
                + ", lots=" + order.getAmount() + "]");
    }

    private void onOrderClosed(IOrder order) throws JFException {
        log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCloseTime())) + ": Closed " + order.getInstrument() +  " "
                + order.getOrderCommand() + " order" + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") "
                + (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [profit=$" + round(totalProfit, 2) + " ($" + round(lockedProfit, 2)
                + " locked), equity=$" + context.getAccount().getEquity() + ", comm=$"
                + order.getCommissionInUSD() + ", consecutiveLosses=" + (consecutiveLossCounter + 1) + "]");

        OrderCommand orderCommand = order.getOrderCommand();
        totalProfit += (order.getProfitLossInUSD() - order.getCommissionInUSD());
        
        if (order.getProfitLossInPips() >= 0) {
            winCounter++;
            consecutiveWinCounter++;
            consecutiveLossCounter = 0;
            
            if (consecutiveWinCounter > 2) {
                lockedProfit += baseTradeAmount;
            }
        } else {
            lossCounter++;
            consecutiveLossCounter++;
            consecutiveWinCounter = 0;
            
            if (consecutiveLossCounter > maxConsecutiveLossCounter) {
                maxConsecutiveLossCounter = consecutiveLossCounter;
            }
            
            orderCommand = OrderCommand.BUY.equals(orderCommand) ? OrderCommand.SELL : OrderCommand.BUY;
        }
        
        placeOrder(orderCommand);
    }
    
    // *****************************************************************************************************************
    // Public Methods - Implementation of the IStrategy interface
    // *****************************************************************************************************************
    public void onStart(IContext context) throws JFException {
        this.context = context;
        context.setSubscribedInstruments(Collections.singleton(instrument));

        log("\nStarted the " + getName() + " strategy using " + instrument + ".");
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
        log("Total Equity: $" + context.getAccount().getEquity());

        int totalTrades = winCounter + lossCounter;
        log("Total Trades: " + totalTrades);
        log("Win%: " + (totalTrades == 0 ? 0 : round((winCounter * 100.0 / totalTrades), 0)) + "% (" + winCounter + " wins / " + lossCounter + " losses)");
        log("Max Loss Count: " + maxConsecutiveLossCounter);

        log(getName() + " strategy stopped.");
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (!started && Period.ONE_HOUR.equals(period)) {
            started = true;
            placeOrder(OrderCommand.BUY);
        }
    }

    public void onAccount(IAccount account) throws JFException {
    }
}
