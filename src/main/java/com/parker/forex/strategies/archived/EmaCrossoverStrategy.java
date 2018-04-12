package com.parker.forex.strategies.archived;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IConsole;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine;
import com.dukascopy.api.IHistory;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.OfferSide;
import com.dukascopy.api.Period;
import com.dukascopy.api.TickBarSize;
import com.dukascopy.api.feed.ITickBar;

/**
 * Determines buy and sell trigger points based on the crossing over of a fast
 * and slow exponential moving average.
 */
public class EmaCrossoverStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
	private IContext context;
    private IEngine engine;
    private IHistory history;
    private IConsole console;
    
    private int orderCounter;
    private long tickCounter;
    private int previousSignum;
    
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Lot Amount (Million)")
    public double amount = 0.01;
    
    @Configurable(value = "Slippage Pips")
    public int slippagePips = 0;
    
    @Configurable(value = "Take Profit Pips")
    public int takeProfitPips = 25;
    
    @Configurable(value = "Stop Loss Pips")
    public int stopLossPips = 50;
    
    @Configurable(value = "Slow EMA Period")
    public int slowEmaPeriod = 30;
    
    @Configurable(value = "Fast EMA Period")
    public int fastEmaPeriod = 10;
    
    @Configurable(value = "EMA Bar Size (Ticks)")
    public int emaBarSize = 50;
    
    @Configurable(value = "Delta Threshold")
    public double deltaThreshold = 0.00002;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message) {
        console.getOut().println(message);
    }
    
    //count open positions
    private void closeOpenPositions() throws JFException {
        for (IOrder order : engine.getOrders(instrument)) {
            if (IOrder.State.OPENED.equals(order.getState()) || IOrder.State.FILLED.equals(order.getState())) {
                order.close();
                log("Closed previous order: " + order.getLabel() + ", Equity: $" + context.getAccount().getEquity());
            }
        }
    }
    
    private double[] calculateEma(int emaPeriod, List<ITickBar> tickBars) {
        double[] ema = new double[tickBars.size()];
        int counter = 0;
        double multiplier = 2.0 / (emaPeriod + 1);
        
        for (ITickBar tickBar : tickBars) {
            if (counter == 0) {
                ema[counter] = tickBar.getClose(); 
            } else {
                ema[counter] = (tickBar.getClose() * multiplier) + (ema[counter - 1] * (1 - multiplier));  
            }
            counter++;
        }
        
        return ema;
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public void onStart(IContext context) throws JFException {
    	this.context = context;
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

    @SuppressWarnings("deprecation")
	public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (instrument.equals(this.instrument)) {
            tickCounter++;
            
            if (tickCounter % emaBarSize == 0) {
                tickCounter = 0;
                
                List<ITickBar> tickBars = history.getTickBars(instrument, OfferSide.BID, TickBarSize.valueOf(emaBarSize), 100, tick.getTime(), 0);
                
                double[] fastEma = calculateEma(fastEmaPeriod, tickBars);
                double[] slowEma = calculateEma(slowEmaPeriod, tickBars);
                
                if (previousSignum == 0) {
                    previousSignum = (int) Math.signum(fastEma[fastEma.length - 2] - slowEma[fastEma.length - 2]);
                }
                
                // Determine if we have a trigger
                IEngine.OrderCommand orderCommand = null;
                double currentDelta = fastEma[fastEma.length - 1] - slowEma[fastEma.length - 1];
                int currentSignum = (int) Math.signum(currentDelta);
                
                //log("Checking for trigger. [currentSignum=" + currentSignum + ",previousSignum=" + previousSignum + "]");
                
                if (Math.abs(currentDelta) >= deltaThreshold && previousSignum != 0 && currentSignum != previousSignum) {
                    if (currentSignum < 0) {
                        orderCommand = IEngine.OrderCommand.BUY;
                    } else {
                        orderCommand = IEngine.OrderCommand.SELL;
                    }
                    
                    previousSignum = currentSignum;
                }
                
                if (orderCommand != null) {
                    closeOpenPositions();
                    String orderId = instrument.name().replace("/", "") + (orderCounter++);
                    
                    // Submit the order
                    if (IEngine.OrderCommand.BUY.equals(orderCommand)) {
                        engine.submitOrder(orderId, instrument, orderCommand, amount, 0, slippagePips, tick.getBid() - (instrument.getPipValue() * stopLossPips), 
                                tick.getBid() + (instrument.getPipValue() * takeProfitPips));
                    } else {
                        engine.submitOrder(orderId, instrument, orderCommand, amount, 0, slippagePips, tick.getAsk() + (instrument.getPipValue() * stopLossPips), 
                                tick.getAsk() - (instrument.getPipValue() * takeProfitPips));
                    }
                    
                    log("Placed " + orderCommand.name() + " order.");
                }
            }
        }
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {       
    }
    
    public void onMessage(IMessage message) throws JFException {
    }

    public void onAccount(IAccount account) throws JFException {
    }
}