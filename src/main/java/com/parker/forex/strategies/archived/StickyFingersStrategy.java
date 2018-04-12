package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.Filter;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IConsole;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine;
import com.dukascopy.api.IHistory;
import com.dukascopy.api.IIndicators;
import com.dukascopy.api.IIndicators.AppliedPrice;
import com.dukascopy.api.IIndicators.MaType;
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
 * Determines buy and sell trigger points based on RSI and Stochastic Oscillator levels.
 */
public class StickyFingersStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final String NAME = "STICKY_FINGERS";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS Z");
    
    static {
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IAccount account;
    private IEngine engine;
    private IHistory history;
    private IIndicators indicators;
    private IConsole console;
    
    private int orderCounter = 1;
    private TickData tickData;
    private int wins;
    private int losses;
    
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Equity per Trade (%)")
    public double equityPerTradePct = 2.0;
    
    @Configurable(value = "Lot Size")
    public double lotSize = 0.1;
    
    @Configurable(value = "Slippage Pips")
    public int slippagePips = 1;
    
    @Configurable(value = "Bar Period")
    public Period barPeriod = Period.FIFTEEN_MINS;
    
    @Configurable(value = "Slow EMA Period")
    public int slowEmaPeriod = 150;
    
    @Configurable(value = "RSI Lower Threshold")
    public double rsiLowerThreshold = 20.0;
    
    @Configurable(value = "RSI Upper Threshold")
    public double rsiUpperThreshold = 80.0;
    
    @Configurable(value = "Stochastic Lower Threshold")
    public double stochasticLowerThreshold = 30.0;
    
    @Configurable(value = "Stochastic Upper Threshold")
    public double stochasticUpperThreshold = 70.0;
    
    @Configurable(value = "Stop Loss (Pips)")
    public int stopLossPips = 50;
    
    @Configurable(value = "Risk/Return Ratio (1:n)")
    public double riskReturnRatio = 0.5;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message) {
        console.getOut().println(message);
    }
    
    private boolean hasOpenPosition(Instrument instrument) throws JFException {
        return !engine.getOrders(instrument).isEmpty();
    }
    
    private void closeAllPositions() throws JFException {
        for (IOrder order : engine.getOrders()) {
            if (IOrder.State.OPENED.equals(order.getState()) || IOrder.State.FILLED.equals(order.getState())) {
                order.close();
            }
        }
    }
    
    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private IOrder buy(Instrument instrument, ITick tick) throws JFException {
        double lastAskPrice = tick.getAsk();
        double stopPrice = round(lastAskPrice - (instrument.getPipValue() * stopLossPips), 4);
        double takeProfitPrice = round(lastAskPrice + ((lastAskPrice - stopPrice) * riskReturnRatio), 4);
        
        IOrder order = engine.submitOrder(getNextOrderId(instrument), instrument, IEngine.OrderCommand.BUY, lotSize);
        
        order.waitForUpdate(State.FILLED);
        order.setStopLossPrice(stopPrice);
        order.setTakeProfitPrice(takeProfitPrice);
        
        return order;
    }
    
    private IOrder sell(Instrument instrument, ITick tick) throws JFException {
        return engine.submitOrder(getNextOrderId(instrument), instrument, IEngine.OrderCommand.SELL, lotSize);
    }
    
//    private double getStopLoss(Instrument instrument, ITick tick, boolean isLong) throws JFException {
//        double lowestLow = Double.MAX_VALUE;
//        double currentLow = Double.MAX_VALUE;
//        double highestHigh = 0.0;
//        double currentHigh = 0.0;
//        
//        
//        // calculate pivot points for support and resistance
//        
//        
//        // Get the tick bars and reverse the list so we scan backwards
//        long barTime = history.getPreviousBarStart(barPeriod, tick.getTime());
//        List<IBar> bars = history.getBars(instrument, barPeriod, isLong ? OfferSide.ASK : OfferSide.BID, Filter.ALL_FLATS, 50, barTime, 0);
//        Collections.reverse(bars);
//        
//        // Set initial scan direction
//        boolean scanDown = isLong ? true : false;
//            
//        // Determine the highest high and lowest low
//        for (IBar tickBar : bars) {
//            if (scanDown) {
//                if (currentLow >= tickBar.getLow()) {
//                    currentLow = tickBar.getLow();
//                } else {
//                    scanDown = false;
//                    if (lowestLow > currentLow) {
//                        lowestLow = currentLow;
//                        break;
//                    }
//                }
//            } else {
//                if (currentHigh <= tickBar.getHigh()) {
//                    currentHigh = tickBar.getHigh();
//                } else {
//                    scanDown = true;
//                    if (highestHigh < currentHigh) {
//                        highestHigh = currentHigh;
//                        break;
//                    }
//                }
//            }
//        }
//        
//        // Subtract some pips from the lowest low or add 2 pips to the highest high
//        if (isLong) {
//            //return getPreciseValue(lowestLow - (instrument.getPipValue() * stopLossBuffer));
//            return tick.getBid() - 0.0015;
//        } else {
//            //return getPreciseValue(highestHigh + (instrument.getPipValue() * stopLossBuffer));
//            return tick.getAsk() + 0.0015;
//        }
//    }
    
    private String getNextOrderId(Instrument instrument) {
        return NAME + "_" + instrument.name().replace("/", "") + "_" + (orderCounter++);
    }
    
    private boolean isPriceAboveSlowEma(Instrument instrument, double emaPrice, ITick tick) throws JFException {
        long barTime = history.getPreviousBarStart(barPeriod, tick.getTime());
        List<IBar> bars = history.getBars(instrument, barPeriod, OfferSide.ASK, Filter.ALL_FLATS, 1, barTime, 0);
        return bars.get(0).getClose() > emaPrice && tick.getAsk() > emaPrice; 
    }
    
    private boolean isPriceBelowSlowEma(Instrument instrument, double emaPrice, ITick tick) throws JFException {
        long barTime = history.getPreviousBarStart(barPeriod, tick.getTime());
        List<IBar> bars = history.getBars(instrument, barPeriod, OfferSide.BID, Filter.ALL_FLATS, 1, barTime, 0);
        return bars.get(0).getHigh() < emaPrice && tick.getBid() < emaPrice; 
    }
    
    private boolean isStochasticLowCross(double[][] stochastic) {
        return stochastic[0][0] < stochastic[1][0] && stochastic[0][1] > stochastic[1][1] && 
                stochastic[0][1] <= stochasticLowerThreshold && stochastic[1][1] <= stochasticLowerThreshold;
    }
    
    private boolean isStochasticHighCross(double[][] stochastic) {
        return stochastic[0][0] > stochastic[1][0] && stochastic[0][1] < stochastic[1][1] &&
                stochastic[0][1] >= stochasticUpperThreshold && stochastic[1][1] >= stochasticLowerThreshold;
    }
    
    private boolean isRsiHigh(double[] rsi) {
        return rsi[0] >= rsiUpperThreshold || rsi[1] >= rsiUpperThreshold;
    }
    
    private boolean isRsiLow(double[] rsi) {
        return rsi[0] <= rsiLowerThreshold || rsi[1] <= rsiLowerThreshold;
    }  
      
    private void logOrder(IOrder order) throws JFException {
        if (order != null) {
            order.waitForUpdate(State.FILLED);
            log(order.getLabel() + " @ " + DATE_FORMAT.format(new Date(order.getFillTime())) + ": Placed " + order.getOrderCommand() + 
                    " order @ $" + round(order.getOpenPrice(), 4) + ".");
        }
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (this.instrument.equals(instrument)) {
            
            long barTime = history.getPreviousBarStart(barPeriod, tick.getTime());
            long previousBarTime = tickData.getPreviousBarTime();
            
            if (previousBarTime != barTime) {
                tickData.setPreviousBarTime(barTime);
                
                // Calculate indicator values
                double[] ema = indicators.ema(instrument, barPeriod, OfferSide.BID, AppliedPrice.CLOSE, slowEmaPeriod, Filter.ALL_FLATS, 1, barTime, 0);
                double[] rsi = indicators.rsi(instrument, barPeriod, OfferSide.BID, AppliedPrice.CLOSE, 3, Filter.ALL_FLATS, 2, barTime, 0);
                double[][] stochastic = indicators.stoch(instrument, barPeriod, OfferSide.BID, 6, 3, MaType.SMA, 3, MaType.SMA, Filter.ALL_FLATS, 2, barTime, 0);
                
                // Check the indicators
                IOrder order = null;
                if (!hasOpenPosition(instrument)) {
                    if (isPriceBelowSlowEma(instrument, ema[0], tick) && isRsiHigh(rsi) && isStochasticHighCross(stochastic)) {
//                        order = sell(instrument, tick);
                        order = buy(instrument, tick);
                    } else if (isPriceAboveSlowEma(instrument, ema[0], tick) && isRsiLow(rsi) && isStochasticLowCross(stochastic)) {
//                        order = buy(instrument, tick);
                        order = sell(instrument, tick);
                    }
                }
                    
                logOrder(order);
            }
        }
    }
    
    public void onStart(IContext context) throws JFException {
        account = context.getAccount();
        engine = context.getEngine();
        console = context.getConsole();
        history = context.getHistory();
        indicators = context.getIndicators();
        
        // Subscribe an instrument
        context.setSubscribedInstruments(Collections.singleton(instrument), true);
        tickData = new TickData(instrument);
        
        log("Started the " + NAME + " strategy using " + instrument.name() + ".");
    }

    public void onStop() throws JFException {
        closeAllPositions();
        log("Total Equity: $" + account.getEquity());
        log("Win%: " + round(wins * 100.0 / (wins + losses), 1) + " [" + wins + " wins / " + losses + " losses]");
        log("Strategy stopped.");
    }
    
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException { 
    }
    
    public void onMessage(IMessage message) throws JFException {
        IOrder order = message.getOrder();
        if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
            int multiplier = order.isLong() ? 1 : -1;
            double stopLossPrice = round(order.getOpenPrice() - (instrument.getPipValue() * stopLossPips * multiplier), 4);
            double takeProfitPrice = round(order.getOpenPrice() + (instrument.getPipValue() * stopLossPips * riskReturnRatio * multiplier), 4);
            
            order.setTakeProfitPrice(takeProfitPrice);
            order.setStopLossPrice(stopLossPrice);
            
        } else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
            if (order.getProfitLossInPips() >= 0) {
                wins++;
            } else {
                losses++;
            }
            
            log(order.getLabel() + " @ " + DATE_FORMAT.format(new Date(order.getCloseTime())) + ": Closed " + order.getOrderCommand() + " order for " + order.getProfitLossInPips() + 
                    " pip " + (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ".");
        }
    }

    public void onAccount(IAccount account) throws JFException {
    }
    
    //********************************************************************************************************************************
    // Inner Classes
    //********************************************************************************************************************************
    /**
     * Holds tick state for each instrument.
     */
    public class TickData {

        //*****************************************************************************************************************
        // Instance Fields
        //*****************************************************************************************************************
        private Instrument instrument;
        private int currentTickCount;
        private long previousBarTime;
        
        //*****************************************************************************************************************
        // Constructor & Life-Cycle Methods
        //*****************************************************************************************************************
        public TickData(Instrument instrument) {
            this.instrument = instrument;
        }
        
        //*****************************************************************************************************************
        // Public Methods
        //*****************************************************************************************************************
        public Instrument getInstrument() {
            return instrument;
        }
        
        public int getCurrentTickCount() {
            return currentTickCount;
        }
        
        public void resetTickCount() {
            currentTickCount = 0;
        }
        
        public void incrementTickCount() {
            currentTickCount++;
        }
        
        public long getPreviousBarTime() {
            return previousBarTime;
        }
        
        public void setPreviousBarTime(long previousBarTime) {
            this.previousBarTime = previousBarTime;
        }
    }
}