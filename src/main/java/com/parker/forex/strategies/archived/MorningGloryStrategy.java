package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IIndicators.AppliedPrice;
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
 * Determines a day's buy/sell trigger prices based on the the range of the early morning trading. Also uses trailing stops and
 * time based close.
 */
public class MorningGloryStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final String NAME = "MORNING_GLORY";
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS Z");
    
    static {
        DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IContext context;
    private Calendar calendar;
    private double buyTriggerPrice;
    private double sellTriggerPrice;
    private double currentStopLossPrice;
    
    private int orderCounter = 1;
    private int winCounter;
    private int lossCounter;
    
    
    //*****************************************************************************************************************
    // Configurable Fields
    //*****************************************************************************************************************
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Trade %")
    public double tradePct = 2.0;
    
    @Configurable(value = "Period")
    public Period period = Period.FIFTEEN_MINS;
    
    @Configurable(value = "SMA Periods")
    public int smaPeriods = 20;
    
    @Configurable(value = "Stop Loss Lookback Periods")
    public int stopLossLookbackPeriods = 3;
    
    @Configurable(value = "Initial Range Periods")
    public int initialRangePeriods = 4;
    
    @Configurable(value = "Start Hour")
    public int startHour = 8;
    
    @Configurable(value = "End Hour")
    public int endHour= 12;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message) {
        context.getConsole().getOut().println(message);
    }
    
    private IOrder getOpenPosition() throws JFException {
        for (IOrder order : context.getEngine().getOrders(instrument)) {
            if (order.getLabel().contains(NAME) && State.FILLED.equals(order.getState())) {
                return order;
            }
        }
        return null;
    }
    
    private void closePositions() throws JFException {
        for (IOrder order : context.getEngine().getOrders(instrument)) {
            if (order.getLabel().startsWith(NAME)) {
                order.close();
            }
        }
    }
    
    private double getLotSize(OrderCommand command, double price) {
        // Normalize the lot size based on the stop loss pips - this effectively enforces the same $ amount per trade
        double tradeAmount = context.getAccount().getEquity() * tradePct / 100.0;
        if (tradeAmount == 0) {
            tradeAmount = 100;
        }

        double pipRange = 0;
        if (command.isLong()) {
            pipRange = Math.abs((price - sellTriggerPrice) / instrument.getPipValue());
        } else {
            pipRange = Math.abs((buyTriggerPrice - price) / instrument.getPipValue());
        }
        
        return round(tradeAmount / pipRange / 100.0, 3);
    }
    
    private String getNextOrderId() {
        return NAME + "_" + instrument.name().replace("/", "") + "_" + (orderCounter++);
    }
    
    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private IOrder placeOrder(OrderCommand command, double price) throws JFException {
        IOrder order = context.getEngine().submitOrder(getNextOrderId(), instrument, command, getLotSize(command, price));
        order.waitForUpdate(State.FILLED);
        return order;
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (this.instrument.equals(instrument) && this.period.equals(period)) {
            
            // Set the current time
            calendar.setTimeInMillis(bidBar.getTime());
            int dow = calendar.get(Calendar.DAY_OF_WEEK);
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            
            if (Calendar.SATURDAY != dow && Calendar.SUNDAY != dow && hour >= startHour && hour < endHour) {
                if (hour == startHour) {
                    // Set the trigger prices
                    double[] minMax = context.getIndicators().minMax(instrument, period, OfferSide.BID, AppliedPrice.CLOSE, 4, 1);
                    buyTriggerPrice = minMax[1];
                    sellTriggerPrice = minMax[0];
                }
                
                IOrder order = getOpenPosition();
                
                if (order == null) {
                    // Determine whether to open a position
                    double sma = context.getIndicators().sma(instrument, period, OfferSide.BID, AppliedPrice.CLOSE, smaPeriods, 1);
                    
                    if (bidBar.getClose() > buyTriggerPrice && bidBar.getClose() > sma) {
                        placeOrder(OrderCommand.BUY, bidBar.getClose());
                    } else if (bidBar.getClose() < sellTriggerPrice && bidBar.getClose() < sma) {
                        placeOrder(OrderCommand.SELL, bidBar.getClose());
                    }
                } else {
                    // Check if we need to adjust the stop loss
                    double[] minMax = context.getIndicators().minMax(instrument, period, OfferSide.BID, AppliedPrice.CLOSE, 3, 1);
                    boolean adjustedSL = false;
                    
                    if (order.isLong() && order.getOpenPrice() > minMax[0] && minMax[0] > currentStopLossPrice) {
                        order.setStopLossPrice(minMax[0]);
                        currentStopLossPrice = minMax[0];
                        adjustedSL = true;
                    } else if (!order.isLong() && order.getOpenPrice() < minMax[1] && minMax[1] < currentStopLossPrice) {
                        order.setStopLossPrice(minMax[1]);
                        currentStopLossPrice = minMax[1];
                        adjustedSL = true;
                    }

                    if (adjustedSL) {
                        log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(bidBar.getTime())) + ": Adjusted SL to $" + currentStopLossPrice);
                    }
                }
            } else {
                // Close any position that is still open outside the trading window
                closePositions();
                currentStopLossPrice = 0;
            }
        }
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }
    
    public void onStart(IContext context) throws JFException {
        this.context = context;
        calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        
        context.setSubscribedInstruments(Collections.singleton(instrument), true);
        log("Started the " + NAME + " strategy using " + instrument + ".");
    }

    public void onStop() throws JFException {
        closePositions();
        log("Total Equity: $" + context.getAccount().getEquity());
        
        int totalTrades = winCounter + lossCounter;
        log("Total Trades: " + totalTrades + " [" + winCounter + " wins / " + lossCounter + " losses => " + (totalTrades == 0 ? 0: round((winCounter * 100.0 / totalTrades), 0)) + "%]");
        
        log("Strategy stopped.");
    }
    
    public void onMessage(IMessage message) throws JFException {
        if (message.getOrder().getInstrument().equals(instrument)) {
            IOrder order = message.getOrder();
            
            if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
                int stopLossPips = 0;
                if (order.isLong()) {
                    stopLossPips = (int) ((order.getOpenPrice() - sellTriggerPrice) / instrument.getPipValue());
                    currentStopLossPrice = sellTriggerPrice;
                } else {
                    stopLossPips = (int) ((buyTriggerPrice - order.getOpenPrice()) / instrument.getPipValue());
                    currentStopLossPrice = buyTriggerPrice;
                }
                
                order.setStopLossPrice(currentStopLossPrice);
                
                log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + order.getOrderCommand() + 
                        " order @ $" + order.getOpenPrice() + ". [SL=$" + currentStopLossPrice + " (" + stopLossPips + " pips)]");
                
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
                    msg += " for " + order.getClosePrice() + ", " + order.getProfitLossInPips() + " pips (US$" + order.getProfitLossInUSD() + ") " + (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + 
                            ". [equity=$" + context.getAccount().getEquity() + "]";
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