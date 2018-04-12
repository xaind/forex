package com.parker.forex.strategies.archived;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IConsole;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;
import com.dukascopy.api.feed.ITickBar;

/**
 * Determines buy and sell trigger points based on the correlation of tick bars at different count intervals. If all bars are
 * in the same direction than an entry is triggered in the same direction. This strategy also uses custom take profit and stop
 * loss points.
 */
public class FrenchTicklerStrategy implements IStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final String NAME = "FRENCH_TICKLER";
    private static final double BASE_LOT_SIZE = 0.01; // $1
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS Z");
    
    static {
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    // Enum for candle direction
    private enum Direction {UPWARDS, DOWNWARDS, SIDEWAYS};
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IAccount account;
    private IEngine engine;
    private IConsole console;
    
    private int orderCounter = 1;
    private int consecutiveLossCount;
    private int bailouts;
    
    private Map<Instrument, TickData> tickDataMap;
    
    @Configurable(value = "Instruments")
    public Set<Instrument> instruments;
    
    @Configurable(value = "Equity per Trade (%)")
    public double equityPerTradePct = 2.0;
    
    @Configurable(value = "Slippage Pips")
    public int slippagePips = 1;
    
//    @Configurable(value = "Take Profit Pips")
//    public int takeProfitPips = 20;
//    
//    @Configurable(value = "Stop Loss Pips")
//    public int stopLossPips = 20;
    
    @Configurable(value = "EMA Period")
    public int emaPeriod = 26;
    
    @Configurable(value = "Stop Loss Buffer (Pips)")
    public int stopLossBuffer = 5;
    
    @Configurable(value = "Risk/Return Ratio (1:n)")
    public double riskReturnRatio = 2;
    
    @Configurable(value = "Consecutive Loss Limit")
    public int consecutiveLossLimit = 10;
    
    @Configurable(value = "Martingale Ratio")
    public double martingaleRatio = 2;
    
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
    
    private double getLotSize(double stopLossPips) throws JFException {
//        double lotSize = (account.getBaseEquity() * equityPerTradePct / 100.0) ;
//        lotSize = Math.round(lotSize / 1000.0) * BASE_LOT_SIZE;
//        
//        if (lotSize == 0) {
//            log("Lot size is zero. No more trades can be placed. [equity=" + account.getBaseEquity() + ",equityPerTradePct=" + equityPerTradePct + "]");
//            onStop();
//            System.exit(0);
//        }
        
    	double lotSize = BASE_LOT_SIZE * Math.pow(martingaleRatio, consecutiveLossCount);
    	//double lotSize = BASE_LOT_SIZE + (consecutiveLossCount * 5);
    	lotSize = getPreciseValue(lotSize / stopLossPips, 3);
    	
    	if (lotSize == 0) {
    		lotSize = 0.001;
    	}
    	
        return lotSize; 
        
        //return lotSize;
    }
    
    private IOrder buy(TickData tickData, double askPrice) throws JFException {
    	double stopPrice = getPreciseValue(getStopLoss(tickData, true));
    	if (stopPrice < (askPrice - (tickData.getInstrument().getPipValue() * 25))) {
    		return null;
    	}
    	
    	double takeProfitPrice = getPreciseValue(askPrice + ((askPrice - stopPrice) * riskReturnRatio) - (tickData.getInstrument().getPipValue() * 0));
    	//double takeProfitPrice = getPreciseValue(askPrice + (tickData.getInstrument().getPipValue() * 20));
        
    	double lotSize = getLotSize((askPrice - stopPrice) / tickData.getInstrument().getPipValue());
        IOrder order = engine.submitOrder(getNextOrderId(tickData.getInstrument()), tickData.getInstrument(), IEngine.OrderCommand.BUY, lotSize);
        
        order.waitForUpdate(State.FILLED);
        order.setStopLossPrice(stopPrice);
        order.setTakeProfitPrice(takeProfitPrice);
        
        log(order.getLabel() + " [SL=$" + stopPrice + ", TP=$" + takeProfitPrice + "]");
        
        return order;
    }
    
    private IOrder sell(TickData tickData, double bidPrice) throws JFException {
        double stopPrice = getPreciseValue(getStopLoss(tickData, false));
        if (stopPrice > (bidPrice + (tickData.getInstrument().getPipValue() * 25))) {
        	return null;
        }
        
        double takeProfitPrice = getPreciseValue(bidPrice - ((stopPrice - bidPrice) * riskReturnRatio) + (tickData.getInstrument().getPipValue() * 0));
        //double takeProfitPrice = getPreciseValue(bidPrice - (tickData.getInstrument().getPipValue() * 20));
        
        double lotSize = getLotSize((stopPrice - bidPrice) / tickData.getInstrument().getPipValue()); 
        IOrder order = engine.submitOrder(getNextOrderId(tickData.getInstrument()), tickData.getInstrument(), IEngine.OrderCommand.SELL, lotSize);
        
        order.waitForUpdate(State.FILLED);
        order.setStopLossPrice(stopPrice);
        order.setTakeProfitPrice(takeProfitPrice);
        
        log(order.getLabel() + " [SL=$" + stopPrice + ", TP=$" + takeProfitPrice + "]");
        
        return order;
    }
    
    private double getStopLoss(TickData tickData, boolean isLong) throws JFException {
    	double lowestLow = Double.MAX_VALUE;
    	double currentLow = Double.MAX_VALUE;
    	double highestHigh = 0.0;
    	double currentHigh = 0.0;
    	
    	// Get the tick bars and reverse the list so we scan backwards
    	List<ITickBar> tickBars = tickData.getTickBars(TickBar.T100, 50);
    	Collections.reverse(tickBars);
    	
    	// Set initial scan direction
    	boolean scanDown = isLong ? true : false;
    		
    	// Determine the highest high and lowest low
		for (ITickBar tickBar : tickBars) {
			if (scanDown) {
    			if (currentLow >= tickBar.getLow()) {
    				currentLow = tickBar.getLow();
    			} else {
    				scanDown = false;
    				if (lowestLow > currentLow) {
    					lowestLow = currentLow;
    				}
    			}
			} else {
    			if (currentHigh <= tickBar.getHigh()) {
    				currentHigh = tickBar.getHigh();
    			} else {
    				scanDown = true;
    				if (highestHigh < currentHigh) {
    					highestHigh = currentHigh;
    				}
    			}
			}
		}
    	
		// Subtract some pips from the lowest low or add 2 pips to the highest high
		if (isLong) {
			return lowestLow - (tickData.getInstrument().getPipValue() * stopLossBuffer);
		} else {
			return highestHigh + (tickData.getInstrument().getPipValue() * stopLossBuffer);
		}
    }
    
    private String getNextOrderId(Instrument instrument) {
        return NAME + "_" + instrument.name().replace("/", "") + "_" + (orderCounter++);
    }
    
    private double getPreciseValue(double value) {
        return getPreciseValue(value, 5);
    }
    
    private double getPreciseValue(double value, int precision) {
        return Double.parseDouble(String.format("%." + precision + "f", value));
    }
    
    private Direction getDirection(ITickBar tickBar) throws JFException {
        if (tickBar.getOpen() == tickBar.getClose()) {
            return Direction.SIDEWAYS;
        } else if (tickBar.getOpen() < tickBar.getClose()) {
            return Direction.UPWARDS;
        } else {
            return Direction.DOWNWARDS;
        }
    }
    
//    private boolean isOuterTickBarRangeValid(TickData tickData) {
//        ITickBar tickBar = tickData.getTickBar(TickBar.T5000);
//        return Math.abs(tickBar.getHigh() - tickBar.getLow()) < (tickData.getInstrument().getPipValue() * 30);
//    }
    
//    private boolean isDirectionContinuous(TickData tickData) {
//        List<ITickBar> tickBars = tickData.getTickBars(TickBar.T100, 3);
//        
//        ITickBar tickBar1 = tickBars.get(0);
//        ITickBar tickBar2 = tickBars.get(1);
//        ITickBar tickBar3 = tickBars.get(2);
//        
//        int signum1 = (int) Math.signum(tickBar1.getOpen() - tickBar1.getClose());
//        int signum2 = (int) Math.signum(tickBar2.getOpen() - tickBar2.getClose());
//        int signum3 = (int) Math.signum(tickBar3.getOpen() - tickBar3.getClose());
//        
//        return  signum1 != 0 && signum1 == signum2 && signum1 == signum3;
//    }
    
    private boolean isPriceOnEma(TickData tickData, Direction direction) throws JFException {
        List<ITickBar> tickBars = tickData.getTickBars(TickBar.T100, 50);
        double currentEmaPrice = calculateCurrentEmaPrice(tickBars);
        ITickBar tickBar = tickBars.get(tickBars.size() - 1);
        
        // Check that the last 100 tick bar contains the last EMA price
//        return (tickBar.getLow() < currentEmaPrice && tickBar.getHigh() > currentEmaPrice);
        
        // The current price must be on the correct side of the current EMA price
        if (Direction.UPWARDS.equals(direction)) {
            return (tickBar.getOpen() < currentEmaPrice && tickBar.getClose() > currentEmaPrice);
        } else if (Direction.DOWNWARDS.equals(direction)) {
            return (tickBar.getOpen() > currentEmaPrice && tickBar.getClose() < currentEmaPrice);
        } else {
            return false;
        }
    }
    
    private double calculateCurrentEmaPrice(List<ITickBar> tickBars) {
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
        
        return ema[ema.length - 1];
    }
    
    private void logOrder(IOrder order) throws JFException {
        if (order != null) {
            //order.waitForUpdate(State.FILLED);
            String durations = tickDataMap.get(order.getInstrument()).getTickBarDurations();
            log(order.getLabel() + " @ " + DATE_FORMAT.format(new Date(order.getFillTime())) + ": Placed " + order.getOrderCommand() + " order @ $" + 
                    getPreciseValue(order.getOpenPrice()) + ". " + durations);
        }
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (instruments.contains(instrument)) {
            TickData tickData = tickDataMap.get(instrument);
            tickData.addTick(tick);
            
            // Execute every 100 ticks once the tick queue is full
            int tickCount = tickData.getCurrentTickCount();
            if (tickData.isQueueFull() && tickCount % TickBar.T100 == 0) {
                
                // Reset the tick counter for this instrument
                tickData.resetTickCount();
                
                //closeOverDueOrders(tick.getTime());
                
                // Determine if the 10000, 5000, 2500, 1000, 500 and 100 tick bars are all heading in the same direction
                Direction direction = getDirection(tickData.getTickBar(TickBar.T5000));
                
                if (!hasOpenPosition(instrument) &&
                         !Direction.SIDEWAYS.equals(direction) && 
                        //direction.equals(getDirection(tickData.getTickBar(5000))) && 
                        direction.equals(getDirection(tickData.getTickBar(TickBar.T2500))) && 
                        direction.equals(getDirection(tickData.getTickBar(TickBar.T1000))) && 
                        direction.equals(getDirection(tickData.getTickBar(TickBar.T500))) && 
                        direction.equals(getDirection(tickData.getTickBar(TickBar.T100))) && 
                        //isOuterTickBarRangeValid(tickData) &&
                        //isDirectionContinuous(tickData) &&
                        isPriceOnEma(tickData, direction)
                        ) {
                    
                    IOrder order = null;
                    
                    // If we are currently in a losing position then close it and open a new position
//                    List<IOrder> orders = engine.getOrders(instrument);
//                    if (!orders.isEmpty() && orders.get(0).getProfitLossInPips() > 0) {
//                        return;
//                    } else {
//                        closePosition(instrument);
//                    }
                    
                    if (Direction.UPWARDS.equals(direction)) {
                        order = buy(tickData, tick.getAsk());
                    } else {
                        order = sell(tickData, tick.getBid());
                    }
                    
                    logOrder(order);
                }
            }
            
            tickData.incrementTickCount();
        }
    }
    
    public void onStart(IContext context) throws JFException {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"));

        account = context.getAccount();
        engine = context.getEngine();
        console = context.getConsole();
        
        // Subscribe an instrument
        instruments = new HashSet<Instrument>();
        instruments.add(Instrument.EURUSD);
//        instruments.add(Instrument.USDJPY);
//        instruments.add(Instrument.USDCHF);
//        instruments.add(Instrument.AUDUSD);
//        instruments.add(Instrument.GBPUSD);
//        instruments.add(Instrument.USDCAD);
//        instruments.add(Instrument.NZDUSD);
//        instruments.add(Instrument.AUDJPY);
//        instruments.add(Instrument.EURJPY);
//        instruments.add(Instrument.AUDNZD);
//        instruments.add(Instrument.GBPAUD);
        
        context.setSubscribedInstruments(instruments, true);
        
        // Initialize the tick counters
        tickDataMap = new HashMap<Instrument, TickData>();
        String description = "Started the " + NAME + " strategy using ";
        
        for (Iterator<Instrument> iterator = instruments.iterator(); iterator.hasNext();) {
            Instrument instrument = iterator.next();
            tickDataMap.put(instrument, new TickData(instrument, TickBar.T5000));
            
            description += instrument;
            if (iterator.hasNext()) {
                description += ", ";
            }
        }
        
        log(description + ".");
    }

    public void onStop() throws JFException {
        closeAllPositions();
        log("Total Equity: $" + account.getEquity() + ", Total Bailouts: " + bailouts);
        log("Strategy stopped.");
    }
    
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException { 
    }
    
    public void onMessage(IMessage message) throws JFException {
        if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
            IOrder order = message.getOrder();
            
        	if (order.getProfitLossInPips() >= 0) {
        		consecutiveLossCount = 0;
        	} else if (consecutiveLossCount >= consecutiveLossLimit) {
        		log("*********************** " + consecutiveLossLimit + " CONSECUTIVE LOSSES ************************");
        		bailouts++;
        		consecutiveLossCount = 0;
        	} else {
        		consecutiveLossCount++;
        	}
        	
            log(order.getLabel() + " @ " + DATE_FORMAT.format(new Date(order.getCloseTime())) + ": Closed " + order.getOrderCommand() + " order for " + order.getProfitLossInPips() + 
                    " pip " + (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + " (commission=$" + order.getCommissionInUSD() + ").");
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
        private LimitedQueue<ITick> tickQueue;
        
        //*****************************************************************************************************************
        // Constructor & Life-Cycle Methods
        //*****************************************************************************************************************
        public TickData(Instrument instrument, int tickQueueLimit) {
            this.instrument = instrument;
            tickQueue = new LimitedQueue<ITick>(tickQueueLimit);
        }
        
        //*****************************************************************************************************************
        // Private Methods
        //*****************************************************************************************************************
        private String formatTickBarDuration(ITickBar tickBar) {
        	long delta = tickBar.getEndTime() - tickBar.getTime();
        	long mins = (int)(delta / 60000);
        	double secs = (delta % 60000) / 1000.0;
            return mins + ":" + secs;
        }
        
        private ITickBar createTickBar(int startIndex, int endIndex) {
            TickBar tickBar = new TickBar();
            List<ITick> subList = tickQueue.subList(startIndex, endIndex);

            tickBar.setOpen(subList.get(0).getBid());
            tickBar.setClose(subList.get(subList.size() - 1).getBid());
            
            double high = 0.0;
            double low = Double.MAX_VALUE;
            long volume = 0;
            
            for (ITick tick : subList) {
                if (tick.getBid() > high) {
                    high = tick.getBid();
                }
                
                if (tick.getBid() < low) {
                    low = tick.getBid();
                }
                
                volume += tick.getBidVolume();
            }
            
            tickBar.setStartTime(tickQueue.get(startIndex).getTime());
            tickBar.setEndTime(tickQueue.getLast().getTime());
            
            tickBar.setHigh(high);
            tickBar.setLow(low);
            tickBar.setVolume(volume);
            tickBar.setFormedElementCount(endIndex - startIndex);
            
            return tickBar;
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
        
        public void addTick(ITick tick) {
            tickQueue.add(tick);
        }
        
        public boolean isQueueFull() {
            return tickQueue.isFull();
        }
        
        public ITickBar getTickBar(int size) {
            int startIndex  = tickQueue.size() - size;
            return createTickBar(startIndex, tickQueue.size());
        }
        
        public List<ITickBar> getTickBars(int size, int number) {
            List<ITickBar> tickBars = new ArrayList<ITickBar>();
            int queueSize = tickQueue.size();
            
            for (int i = number; i > 0; i--) {
                int startIndex = queueSize - (i * size);
                ITickBar tickBar = createTickBar(startIndex, startIndex + size);
                tickBars.add(tickBar);
            }
            
            return tickBars;
        }
        
        public String getTickBarDurations() {
            StringBuilder result = new StringBuilder();
            
            //result.append("[T10000=");
            //result.append(formatTickBarDuration(getTickBar(TickBar.T10000)));
            result.append("[T5000=");
            result.append(formatTickBarDuration(getTickBar(TickBar.T5000)));
            result.append(", T2500=");
            result.append(formatTickBarDuration(getTickBar(TickBar.T2500)));
            result.append(", T1000=");
            result.append(formatTickBarDuration(getTickBar(TickBar.T1000)));
            result.append(", T500=");
            result.append(formatTickBarDuration(getTickBar(TickBar.T500)));
            result.append(", T100=");
            result.append(formatTickBarDuration(getTickBar(TickBar.T100)));
            result.append("]");
            
            return result.toString();
        }
    }
    
    /**
     * Tick bar implementation.
     */
    public class TickBar implements ITickBar {

        //*****************************************************************************************************************
        // Static Fields
        //*****************************************************************************************************************
        public static final int T10000 = 10000;
        public static final int T5000 = 5000;
        public static final int T2500 = 2500;
        public static final int T1000 = 1000;
        public static final int T500 = 500;
        public static final int T100 = 100;
        
        //*****************************************************************************************************************
        // Instance Fields
        //*****************************************************************************************************************
        private long startTime; 
        private long endTime;
        private long volume;
        private long elementCount;
        
        private double open;
        private double high;
        private double low;
        private double close;
        
        //*****************************************************************************************************************
        // Public Methods
        //*****************************************************************************************************************
        @Override
        public long getEndTime() {
            return endTime;
        }

        @Override
        public long getFormedElementsCount() {
            return elementCount;
        }

        @Override
        public double getOpen() {
            return open;
        }

        @Override
        public double getClose() {
            return close;
        }

        @Override
        public double getLow() {
            return low;
        }

        @Override
        public double getHigh() {
            return high;
        }

        @Override
        public double getVolume() {
            return volume;
        }

        @Override
        public long getTime() {
            return startTime;
        }
        
        public long getStartTime() {
            return startTime;
        }

        public void setStartTime(long startTime) {
            this.startTime = startTime;
        }

        public long getElementCount() {
            return elementCount;
        }

        public void setFormedElementCount(long elementCount) {
            this.elementCount = elementCount;
        }

        public void setEndTime(long endTime) {
            this.endTime = endTime;
        }

        public void setVolume(long volume) {
            this.volume = volume;
        }

        public void setOpen(double open) {
            this.open = open;
        }

        public void setHigh(double high) {
            this.high = high;
        }

        public void setLow(double low) {
            this.low = low;
        }

        public void setClose(double close) {
            this.close = close;
        }
    }
    
    /**
     * A queue with fixed size that automatically removed the eldest entry if the queue is full
     * when a new entry is added.
     */
    public class LimitedQueue<E> extends LinkedList<E> {

        //*****************************************************************************************************************
        // Static Fields
        //*****************************************************************************************************************
        private static final long serialVersionUID = 4021517722147518306L;
        
        //*****************************************************************************************************************
        // Instance Fields
        //*****************************************************************************************************************
        private int limit;

        //*****************************************************************************************************************
        // Constructor & Life-Cycle Methods
        //*****************************************************************************************************************
        public LimitedQueue(int limit) {
            this.limit = limit;
        }

        //*****************************************************************************************************************
        // Public Methods
        //*****************************************************************************************************************
        @Override
        public boolean add(E o) {
            super.add(o);
            while (size() > limit) { super.remove(); }
            return true;
        }
        
        public boolean isFull() {
            return size() == limit;
        }
    }
}