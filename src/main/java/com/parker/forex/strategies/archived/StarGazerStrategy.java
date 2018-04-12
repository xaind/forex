package com.parker.forex.strategies.archived;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.Filter;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IConsole;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine;
import com.dukascopy.api.IHistory;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.OfferSide;
import com.dukascopy.api.Period;

/**
 * Determines buy and sell trigger points based on the presence of morning and evening star
 * candlestick formations.
 */
public class StarGazerStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy hh:mm:ss a");
    private static final int HISTORICAL_BARS = 50;
    
    static {
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IEngine engine;
    private IHistory history;
    private IConsole console;
    
    private int orderCounter;
    private long tickCounter;
    private long previousBarTime;
    
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Lot Amount (Million)")
    public double amount = 0.001;
    
    @Configurable(value = "Slippage Pips")
    public int slippagePips = 0;
    
    @Configurable(value = "Take Profit Pips")
    public int takeProfitPips = 10;
    
    @Configurable(value = "Stop Loss Pips")
    public int stopLossPips = 4;
    
    @Configurable(value = "Tick Bar Size (Ticks)")
    public int emaBarSize = 100;
    
    @Configurable(value = "Maximum Star Size (Pips)")
    public double starSize = 1.0;
    
    @Configurable(value = "Minimum Bounding Bar Size (Pips)")
    public double boundingBarSize = 1.0;
    
    @Configurable(value = "Enforce Star Direction")
    public boolean enforceStarDirection = false;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message) {
        console.getOut().println(message);
    }
    
    private boolean hasOpenPosition() throws JFException {
        return !engine.getOrders(instrument).isEmpty();
    }
    
//    private void processTickBars(Instrument instrument, ITick tick) throws JFException {
//        // Retrieve last 3 bars and determine if we have the beginning of a star formation
//        List<ITickBar> tickBars = history.getTickBars(instrument, OfferSide.BID, TickBarSize.valueOf(emaBarSize), HISTORICAL_BARS, tick.getTime(), 0);
//    
//        // Determine the previous high and low to gauge an indication of trend
//        double previousHigh = tickBars.get(0).getHigh();
//        double previousLow = tickBars.get(0).getLow();
//        
//        int counter = 0;
//        for (IBar bar : tickBars) {
//            // Ignore last 3 bars 
//            if (counter >= (tickBars.size() - 3)) {
//                continue;
//            } else if (bar.getHigh() > previousHigh) {
//                previousHigh = bar.getHigh();
//            } else if (bar.getLow() < previousLow) {
//                previousLow = bar.getLow();
//            }
//            
//            counter++;
//        }
//        
//        IBar leadingBar = tickBars.get(tickBars.size() - 3);
//        IBar starBar = tickBars.get(tickBars.size() - 2);
//        IBar trailingBar = tickBars.get(tickBars.size() - 1);
//        
//        if (isMorningStar(leadingBar, starBar, trailingBar, previousLow)) {
//            buy(tick.getAsk());
//        }
//        
//        tickBars = history.getTickBars(instrument, OfferSide.ASK, TickBarSize.valueOf(emaBarSize), HISTORICAL_BARS, tick.getTime(), 0);
//        
//        leadingBar = tickBars.get(tickBars.size() - 3);
//        starBar = tickBars.get(tickBars.size() - 2);
//        trailingBar = tickBars.get(tickBars.size() - 1);
//        
//        if (isEveningStar(leadingBar, starBar, trailingBar, previousHigh)) {
//            sell(tick.getBid());
//        }
//    }
    
    private void processTickBars(Instrument instrument, ITick tick) throws JFException {
        // Retrieve last 3 bars and determine if we have the beginning of a star formation
        long barTime = history.getPreviousBarStart(Period.ONE_HOUR, tick.getTime());
        
        // Sanity check to prevent us processing the same bar more than once
        if (previousBarTime == barTime) {
            return;
        }
        previousBarTime = barTime;
        
        List<IBar> bars = history.getBars(instrument, Period.ONE_HOUR, OfferSide.ASK, Filter.NO_FILTER, HISTORICAL_BARS, barTime, 0);

        // Determine the previous high and low to gauge an indication of trend
        double previousHigh = bars.get(0).getHigh();
        double previousLow = bars.get(0).getLow();
        
        int counter = 0;
        for (IBar bar : bars) {
            // Ignore last 3 bars 
            if (counter >= (bars.size() - 3)) {
                continue;
            } else if (bar.getHigh() > previousHigh) {
                previousHigh = bar.getHigh();
            } else if (bar.getLow() < previousLow) {
                previousLow = bar.getLow();
            }
            
            counter++;
        }
        
        IBar leadingBar = bars.get(bars.size() - 3);
        IBar starBar = bars.get(bars.size() - 2);
        IBar trailingBar = bars.get(bars.size() - 1);
        
        if (isMorningStar(leadingBar, starBar, trailingBar, previousLow, tick.getAsk())) {
            buy(tick.getAsk());
        }
        
        bars = history.getBars(instrument, Period.ONE_HOUR, OfferSide.BID, Filter.NO_FILTER, HISTORICAL_BARS, barTime, 0);
        
        leadingBar = bars.get(bars.size() - 3);
        starBar = bars.get(bars.size() - 2);
        trailingBar = bars.get(bars.size() - 1);
        
        if (isEveningStar(leadingBar, starBar, trailingBar, previousHigh, tick.getBid())) {
            sell(tick.getBid());
        }
    }
    
    private boolean isMorningStar(IBar leadingBar, IBar starBar, IBar trailingBar, double previousLow, double currentTickPrice) {
        boolean isMorningStar = false;
        if (starBar.getLow() < previousLow && // ensure there are no previous bars that are lower (crude trend check)
                currentTickPrice >= trailingBar.getClose() && // current price must be higher than trailing bar close
                leadingBar.getOpen() - leadingBar.getClose() >= (instrument.getPipValue() * boundingBarSize) && // first bar body is correct size and downwards
                leadingBar.getClose() > starBar.getOpen() && leadingBar.getClose() > starBar.getClose() && // star must be fully below the first bar 
                (!enforceStarDirection || starBar.getOpen() <= starBar.getClose()) && // ensure direction of star is upwards
                Math.abs(starBar.getOpen() - starBar.getClose()) <= (starSize * instrument.getPipValue()) && // star body is correct size
                starBar.getOpen() < trailingBar.getOpen() && starBar.getClose() < trailingBar.getOpen() && // star must be fully below the last bar
                trailingBar.getClose() - trailingBar.getOpen() >= (instrument.getPipValue() * boundingBarSize) && // last bar body is correct size and upwards
                //trailingBar.getClose() <= leadingBar.getOpen() && // trailing bar must not close higher than leading open
                Math.abs(trailingBar.getOpen() - trailingBar.getClose()) >= (Math.abs(leadingBar.getOpen() - leadingBar.getClose()) / 2)) { // last bar body must be smaller than first bar
            isMorningStar = true;
            logPriceDetails("Morning Star", leadingBar, starBar, trailingBar);
        }
        return isMorningStar;
    }
    
    private boolean isEveningStar(IBar leadingBar, IBar starBar, IBar trailingBar, double previousHigh, double currentTickPrice) {
        boolean isEveningStar = false;
        if (starBar.getHigh() > previousHigh && // ensure there are no previous bars that are higher (crude trend check)
                currentTickPrice <= trailingBar.getClose() && // current price must be higher than trailing bar close
                leadingBar.getClose() - leadingBar.getOpen() >= (instrument.getPipValue() * boundingBarSize) && // first bar body is correct size and upwards
                leadingBar.getClose() < starBar.getOpen() && leadingBar.getClose() < starBar.getClose() && // star must be fully above the first bar 
                (!enforceStarDirection || starBar.getOpen() >= starBar.getClose()) && // ensure direction of star is downwards
                Math.abs(starBar.getOpen() - starBar.getClose()) <= (starSize * instrument.getPipValue()) && // star body is correct size
                starBar.getOpen() > trailingBar.getOpen() && starBar.getClose() > trailingBar.getOpen() && // star must be fully above the last bar
                trailingBar.getOpen() - trailingBar.getClose() >= (instrument.getPipValue() * boundingBarSize) && // last bar body is correct size and downwards
                //trailingBar.getClose() >= leadingBar.getOpen() && // trailing bar must not close lower than leading open
                Math.abs(trailingBar.getOpen() - trailingBar.getClose()) >= (Math.abs(leadingBar.getOpen() - leadingBar.getClose()) / 2)) { // last bar body must be smaller than first bar
            isEveningStar = true;
            logPriceDetails("Evening Star", leadingBar, starBar, trailingBar);
        }
        return isEveningStar;
    }
    
    private void logPriceDetails(String type, IBar leadingBar, IBar starBar, IBar trailingBar) {
        log(type + " @ " + DATE_FORMAT.format(new Date(starBar.getTime())) + " [F-Open=" + leadingBar.getOpen() + ",F-Close=" + leadingBar.getClose() + ",body=" + formatBodySize(leadingBar) + 
                ",S-Open=" + starBar.getOpen() + ",S-Close=" + starBar.getClose()  + ",body=" + formatBodySize(starBar) +
                ",L-Open=" + trailingBar.getOpen() + ",L-Close=" + trailingBar.getClose() + ",body=" + formatBodySize(trailingBar));
    }
    
    private String formatBodySize(IBar tickbar) {
        return String.format("%.5f", Math.abs(tickbar.getOpen() - tickbar.getClose()));
    }
    
    private void buy(double askPrice) throws JFException {
        engine.submitOrder(getNextOrderId(), instrument, IEngine.OrderCommand.BUY, amount, 0, slippagePips, askPrice - (instrument.getPipValue() * stopLossPips), 
                askPrice + (instrument.getPipValue() * takeProfitPips));
        log("Placed BUY order @ $" + String.format("%.5f", askPrice) + ".");
    }
    
    private void sell(double bidPrice) throws JFException {
        engine.submitOrder(getNextOrderId(), instrument, IEngine.OrderCommand.SELL, amount, 0, slippagePips, bidPrice + (instrument.getPipValue() * stopLossPips), 
                bidPrice - (instrument.getPipValue() * takeProfitPips));
        log("Placed SELL order @ $" + String.format("%.5f", bidPrice) + ".");
    }
    
    private String getNextOrderId() {
        return instrument.name().replace("/", "") + (orderCounter++);
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (instrument.equals(this.instrument)) {
            tickCounter++;
            
            if (tickCounter % emaBarSize == 0) {
                tickCounter = 0;

                // Only check for triggers if we are not already in an open position
                if (!hasOpenPosition()) {
                    processTickBars(instrument, tick);
                }
            }
        }
    }

    public void onStart(IContext context) throws JFException {
        engine = context.getEngine();
        history = context.getHistory();
        console = context.getConsole();

        // Subscribe an instrument
        Set<Instrument> instruments = new HashSet<Instrument>();
        instruments.add(instrument);                     
        context.setSubscribedInstruments(instruments, true);
        
        log("Strategy started using " + instrument);
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