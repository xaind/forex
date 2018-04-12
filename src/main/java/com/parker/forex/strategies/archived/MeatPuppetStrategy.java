package com.parker.forex.strategies.archived;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.Filter;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IConsole;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine;
import com.dukascopy.api.IHistory;
import com.dukascopy.api.IIndicators;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.OfferSide;
import com.dukascopy.api.Period;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

/**
 * Determines buy and sell trigger points based on the crossover of a fast and slow exponential moving average. Postions
 * are closed on the either the fast EMA turning points or on the next crossover.
 */
public class MeatPuppetStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy hh:mm:ss a");
    
    static {
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IEngine engine;
    private IHistory history;
    private IIndicators indicators;
    private IConsole console;
    
    private int orderCounter;
    private long previousBarTime;
    
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Lot Amount (Million)")
    public double amount = 0.001;
    
    @Configurable(value = "Slippage Pips")
    public int slippagePips = 0;
    
    @Configurable(value = "Stop Loss Pips")
    public int stopLossPips = 5;
    
    @Configurable(value = "Bar Period")
    public Period barPeriod = Period.FIFTEEN_MINS;
    
    @Configurable(value = "Fast EMA Period")
    public int fastEmaPeriod = 10;
    
    @Configurable(value = "Slow EMA Period")
    public int slowEmaPeriod = 50;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message) {
        console.getOut().println(message);
    }
    
    private void closePosition() throws JFException {
        for (IOrder order : engine.getOrders(instrument)) {
            order.close();
        }
    }
    
    private String buy(double askPrice) throws JFException {
        String orderId = getNextOrderId();
        double stopPrice = Double.parseDouble(String.format("%.5f", askPrice - (instrument.getPipValue() * stopLossPips)));
        double takeProfitPrice = Double.parseDouble(String.format("%.5f", askPrice + (instrument.getPipValue() * 150)));
        engine.submitOrder(orderId, instrument, IEngine.OrderCommand.BUY, amount, 0, slippagePips, stopPrice, takeProfitPrice);
        return orderId;
    }
    
    private String sell(double bidPrice) throws JFException {
        String orderId = getNextOrderId();
        double stopPrice = Double.parseDouble(String.format("%.5f", bidPrice + (instrument.getPipValue() * stopLossPips)));
        double takeProfitPrice = Double.parseDouble(String.format("%.5f", bidPrice - (instrument.getPipValue() * 150)));
        IOrder order = engine.submitOrder(orderId, instrument, IEngine.OrderCommand.SELL, amount);
        order.waitForUpdate(State.FILLED);
        order.setStopLossPrice(stopPrice);
        order.setTakeProfitPrice(takeProfitPrice);
        return orderId;
    }
    
    private String getNextOrderId() {
        return instrument.name().replace("/", "") + (orderCounter++);
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (instrument.equals(this.instrument)) {
            long barTime = history.getPreviousBarStart(barPeriod, tick.getTime());
            
            // Sanity check to prevent us processing the same bar more than once
            if (previousBarTime == barTime) {
                return;
            }
            previousBarTime = barTime;
            
            // Calculate EMAs
            double[] fastEmaAsk = indicators.ema(instrument, barPeriod, OfferSide.ASK, IIndicators.AppliedPrice.MEDIAN_PRICE, fastEmaPeriod, Filter.ALL_FLATS, 3, barTime, 0);
            double[] slowEmaAsk = indicators.ema(instrument, barPeriod, OfferSide.ASK, IIndicators.AppliedPrice.MEDIAN_PRICE, slowEmaPeriod, Filter.ALL_FLATS, 2, barTime, 0);
            
            // Asking prices
            double previousFastAskPrice = fastEmaAsk[1];
            double currentFastAskPrice = fastEmaAsk[2];
            double previousSlowAskPrice = slowEmaAsk[0];
            double currentSlowAskPrice = slowEmaAsk[1];
            
            String orderId = null;
            
            // Check for crossovers
            if (previousFastAskPrice < previousSlowAskPrice && currentFastAskPrice > currentSlowAskPrice) {
                closePosition();
                orderId = buy(tick.getBid());
                log(orderId + " @ " + DATE_FORMAT.format(new Date(tick.getTime())) + ": Placed BUY order for Upwards Xover @ $" + String.format("%.5f", tick.getBid()) + ".");
            } else if (previousFastAskPrice > previousSlowAskPrice && currentFastAskPrice < currentSlowAskPrice) {
                closePosition();
                orderId = sell(tick.getAsk());
                log(orderId + " @ " + DATE_FORMAT.format(new Date(tick.getTime())) + ": Placed SELL order for Downwards Xover @ $" + String.format("%.5f", tick.getAsk()) + ".");
            } 
        }
    }
    
    public void onStart(IContext context) throws JFException {
        engine = context.getEngine();
        indicators = context.getIndicators();
        history = context.getHistory();
        console = context.getConsole();

        // Subscribe an instrument
        Set<Instrument> instruments = new HashSet<Instrument>();
        instruments.add(instrument);                     
        context.setSubscribedInstruments(instruments, true);
        
        log("Strategy started using " + instrument + ".");
    }

    public void onStop() throws JFException {
        log("Strategy stopped.");
    }
    
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException { 
    }
    
    public void onMessage(IMessage message) throws JFException {
    }

    public void onAccount(IAccount account) throws JFException {
    }
}