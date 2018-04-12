package com.parker.forex.strategies.archived;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

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

/**
 * A trend-following strategy that trades multiple currency pairs simultaneously. Lot sizes for each trade are 
 * dynamically calculated based on the current profitability. Take profit pips are calculated based on the average 
 * daily range of the instrument with stop losses placed asymmetrically as specified by the risk/reward ratio.
 */
public class MagicMultiStrategy4 implements IStrategy {
    
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS");
    private static final SimpleDateFormat DATE_FORMAT_MEDIUM = new SimpleDateFormat("yyyyMMddHHmm");
    private static final SimpleDateFormat DATE_FORMAT_MONTH = new SimpleDateFormat("MMMMM yyyy");
    
    private static final long MILLIS_IN_DAY = 1000 * 60 * 60 * 24;
    
    static {
        DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
        DATE_FORMAT_MONTH.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private volatile boolean started;
    private volatile IContext context;
    
    private volatile int winCounter;
    private volatile int lossCounter;
    private volatile double openLots;
    private volatile double lossAmount;
    
    private volatile PrintWriter printWriter;
    
    private volatile double startingEquity;
    private volatile double maxEquity;
    private volatile double maxDrawDown;
    private volatile long startTime;
    private volatile long endTime;
    
    private final Map<Instrument, InstrumentInfo> instrumentInfos = Collections.synchronizedMap(new HashMap<Instrument, InstrumentInfo>());
    
    @Configurable(value = "Base Trade Amount ($)")
    public final double baseTradeAmount = 10.0;
    
    @Configurable(value = "Take Profit ADR Ratio")
    public final double takeProfitAdrRatio = 1.5;
    
    @Configurable(value = "Stop Loss ADR Ratio")
    public final double stopLossAdrRatio = 3.0;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private String getName() {
        return "MAGIC_MULTI";
    }
    
    private void logLine() {
    	log("-------------------------------------------------------------------");
    }
    
    private void log(String message) {
        context.getConsole().getOut().println(message);
        printWriter.println(message);
    }
    
    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.CEILING).doubleValue();
    }
    
    private double getLotSize(InstrumentInfo instrumentInfo, double amount) throws JFException {
    	return Math.max(round(instrumentInfo.getLotsForAmount(amount), 3), 0.001);
    }
    
    private void placeOrder(InstrumentInfo instrumentInfo, OrderCommand orderCommand, double amount)  throws JFException {
    	Instrument instrument = instrumentInfo.instrument;
        String label = getName() + "_" + instrument.name() + "_" + (++instrumentInfo.orderCounter);
        instrumentInfo.update();
        IOrder order = context.getEngine().submitOrder(label, instrument, orderCommand, getLotSize(instrumentInfo, amount), 0, 0);
        openLots += order.getAmount(); 
    }
 
    private void onOrderCancelled(IMessage message) throws JFException {    
        log("Error executing order: " + message.getContent());          
    }
    
    private void onOrderFilled(IOrder order) throws JFException {
    	Instrument instrument = order.getInstrument();
    	InstrumentInfo instrumentInfo = instrumentInfos.get(instrument);
    	
        // Set the take profit and stop loss prices
        double openPrice = order.getOpenPrice();
        double takeProfit = instrument.getPipValue() * instrumentInfo.getTakeProfitPips();
        double stopLoss = instrument.getPipValue() * instrumentInfo.getStopLossPips();
        int negator = order.isLong() ? 1 : -1;
        
        order.setTakeProfitPrice(round(openPrice + (negator * takeProfit), instrument.getPipScale()));
        order.setStopLossPrice(round(openPrice - (negator * stopLoss), instrument.getPipScale()));
        
		log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + instrument + " " + order.getOrderCommand() 
			+ " order" + " at $" + order.getOpenPrice() + ". [lots=" + round(order.getAmount(), 3) + ", equity=$" + round(context.getAccount().getEquity(), 2) 
			+ ", openLots=" + round(openLots, 3) + ", adr=" + instrumentInfo.avgDailyPips + "]");
    }
    
    private synchronized void onOrderClosed(IOrder order) throws JFException {
    	Instrument instrument = order.getInstrument();
    	OrderCommand orderCommand = order.getOrderCommand();
    	InstrumentInfo instrumentInfo = instrumentInfos.get(instrument);
    	
    	openLots -= order.getAmount();
    	double tradeAmount = baseTradeAmount + lossAmount;
    	lossAmount = Math.max(-order.getProfitLossInUSD(), 0);
    	
        if (order.getProfitLossInPips() >= 0) {
        	instrumentInfo.addDuration(order.getCloseTime(), order.getCloseTime() - order.getCreationTime());
            winCounter++;
        } else {            
        	instrumentInfo.addDuration(order.getCloseTime(), order.getCreationTime() - order.getCloseTime());
            lossCounter++;
        }

        log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCloseTime())) + ": Closed " + instrument + " " +  order.getOrderCommand() + " order @ $" 
        		+ round(order.getClosePrice(), 4) + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") " 
        		+ (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [lots=" + round(order.getAmount(), 3) + ", equity=$" + round(context.getAccount().getEquity(), 2) 
        		+ ", maxEquity=$" + round(maxEquity, 2) + ", loss=$" + round(lossAmount, 2) + "]");
        
       	
        if (order.getProfitLossInPips() < 0) {
    		orderCommand = OrderCommand.BUY.equals(orderCommand) ? OrderCommand.SELL : OrderCommand.BUY;
    	} 
        
        placeOrder(instrumentInfo, orderCommand, tradeAmount);
        updateDrawDown();
    }
    
    private void updateDrawDown() {
    	double equity = context.getAccount().getEquity();
    	
        if (maxEquity < equity) {
        	maxEquity = equity;
        }
        
        if (maxDrawDown < maxEquity - equity) {
        	maxDrawDown = maxEquity - equity;
        }
    }
    
    //*****************************************************************************************************************
    // Public Methods - Implementation of the IStrategy interface
    //*****************************************************************************************************************   
    public void onStart(IContext context) throws JFException {
        this.context = context;        
        
        List<Instrument> instruments = Arrays.asList(new Instrument[] {
        	Instrument.EURUSD,
            Instrument.GBPJPY,
            Instrument.NZDCHF,
            Instrument.CADCHF,
        });
        
       	context.setSubscribedInstruments(new HashSet<Instrument>(instruments));        
       	
       	try {
       		printWriter = new PrintWriter("mm" + DATE_FORMAT_MEDIUM.format(new Date()) + ".txt");
       	} catch (Exception e) {
       		// Checked exceptions suck!
       		throw new RuntimeException(e);
       	}
       	
       	startingEquity = context.getAccount().getBaseEquity();
       			
       	logLine();
       	log("Back-testing run for the " + getName() + " strategy.");
       	logLine();
       	log("Run Date: " + DATE_FORMAT_LONG.format(new Date()));
       	log("Starting Equity: $" + round(startingEquity, 2));
       	log("Leverage: " + round(context.getAccount().getLeverage(), 0) + ":1");
       	log("Max Lots: " + round(startingEquity * context.getAccount().getLeverage() / 1e5, 2));
       	log("Base Trade Amount: $" + round(baseTradeAmount, 2));
       	log("Take Profit ADR Ratio: " + round(takeProfitAdrRatio, 1));
       	log("Stop Loss ADR Ratio: " + round(stopLossAdrRatio, 1));
       	logLine();
       	
       	log("Instruments:");
       	for (Instrument instrument : instruments) {
       		instrumentInfos.put(instrument, new InstrumentInfo(instrument));
       		log(instrument.name());
       	}
       	
       	logLine();
        log("Started the " + getName() + " strategy using " + instruments.size() + " instruments.");
    }

    public void onMessage(IMessage message) throws JFException {
    	IOrder order = message.getOrder();
    	if (order != null) {
    		if (order.getCloseTime() > 0) {
    			if (startTime == 0) {
    				startTime = order.getFillTime();
    			}
    			endTime = order.getCloseTime();
    		}
    		
	        if (State.CANCELED.equals(order.getState())) {
	            onOrderCancelled(message);
	        } else if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
	            onOrderFilled(order);                
	        } else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {                
	            onOrderClosed(order);
	        }
    	}
    }

    public void onStop() throws JFException {
    	// Output instrument stats
    	Map<Long, Long> durations = new TreeMap<>();
    	for (InstrumentInfo info : instrumentInfos.values()) {
    		outputStats(info.instrument.toString(), info.durations.values());
    		durations.putAll(info.durations);
    	}
    	
    	// Calculate total stats
    	outputStats("All Instruments", durations.values());
    	
    	logLine();
        log("Total Equity: $" + context.getAccount().getEquity());
        
        int totalTrades = winCounter + lossCounter;
        log("Total Trades: " + totalTrades);
        log("Win%: " + (totalTrades == 0 ? 0: round((winCounter * 100.0 / totalTrades), 0)) + "% (" + winCounter + " wins / " + lossCounter + " losses)");
        
        double days = (endTime - startTime) / MILLIS_IN_DAY;
        double roi = ((context.getAccount().getEquity() - startingEquity) / startingEquity) * 100.0 / (days / 365.0);
        log("ROI%: " + round(roi, 1) + "%pa");
        log("Max Draw Down: $" + round(maxDrawDown, 2));
        
        printWriter.close();
    }

	private void outputStats(String instrument, Collection<Long> durations) {
		logLine();
		log(instrument);
		
		long totalDuration = 0;
		int totalWins = 0;
		
		List<Integer> outcomes = new ArrayList<>();
		
		for (Long duration : durations) {
			outcomes.add((int) Math.signum(duration));
			totalDuration += Math.abs(duration);
			if (duration > 0) { 
				totalWins++;
			}
		}
		
		int lastOutcome = 0;
		int winWins = 0;
		int winLosses = 0;
		int lossWins = 0;
		int lossLosses = 0;
		
		int consecWins = 0;
		int consecLosses = 0;
		int maxConsecWins = 0;
		int maxConsecLosses = 0;
		
		for (Integer outcome : outcomes) {
			if (lastOutcome != 0) {
				switch (lastOutcome + outcome) {
				case 2:
					winWins++;
					consecWins++;
					
					if (consecWins > maxConsecWins) {
						maxConsecWins = consecWins;
					}
					
					break;
				case -2:
					lossLosses++;
					consecLosses++;
					
					if (consecLosses > maxConsecLosses) {
						maxConsecLosses = consecLosses;
					}
					break;
				case 0:
					if (lastOutcome > 0) {
						winLosses++;
					} else {
						lossWins++;
					}

					consecWins = 0;
					consecLosses = 0;
					break;
				} 
			}
			
			lastOutcome = outcome;
		}
		
		int trades = outcomes.size() > 0 ? outcomes.size() : 0;
		
		log("Total Trades: " + durations.size());
		log("Avg Duration: " + round((1.0 * totalDuration / trades) / MILLIS_IN_DAY, 1) + " days");
		log("Total Wins: " + totalWins + " (" + round(100.0 * totalWins / trades, 1) + "%)");
		log("Max Consecutive Wins: " + maxConsecWins);
		log("Max Consecutive Losses: " + maxConsecLosses);
		log("---> WinWins: " + winWins + " (" + round(100.0 * winWins / trades, 1) + "%)");
		log("---> WinLosses: " + winLosses + " (" + round(100.0 * winLosses / trades, 1) + "%)");
		log("---> LossWins: " + lossWins + " (" + round(100.0 * lossWins / trades, 1) + "%)");
		log("---> LossLosses: " + lossLosses + " (" + round(100.0 * lossLosses / trades, 1) + "%)");
	}
    
    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }
    
    public void onBar(Instrument inst, Period period, IBar askBar, IBar bidBar) throws JFException {
    	if (!started) {
    		started = true;
    		placeInitialOrders(baseTradeAmount);
    	}
    }

	private void placeInitialOrders(double amount) throws JFException {
		for (InstrumentInfo instrumentInfo : instrumentInfos.values()) {
			placeOrder(instrumentInfo, instrumentInfo.getOrderCommand(), amount);
		}
	}
    
    public void onAccount(IAccount account) throws JFException {
    }
    
    //----------------------------------------------------------------------------------------------------------
    // Inner classes
    //----------------------------------------------------------------------------------------------------------
    private class InstrumentInfo {
    	
    	private int orderCounter;
    	
    	private Instrument instrument;
    	private int takeProfitPips;
    	private int stopLossPips;
    	private int avgDailyPips;
    	private Map<Long, Long> durations = new HashMap<>();
    	
		public InstrumentInfo(Instrument instrument) {
    		this.instrument = instrument;
    	}
    	
    	private void update() throws JFException {
    		// First get the latest price data
    		List<Double> ranges = new ArrayList<>();
    		for (IBar bar : getHistoryBars(Period.DAILY, 60)) {
    			ranges.add((bar.getHigh() - bar.getLow()) / instrument.getPipValue());
    		}
    		
    		Collections.sort(ranges);
    		ranges.removeAll(ranges.subList(0, 10));
    		ranges.removeAll(ranges.subList(ranges.size() - 10, ranges.size()));
    		
    		// Calculate the average daily pips
    		double totalPips = 0;
    		for (Double range : ranges) {
    			totalPips += range;
    		}
    		double avgDailyRange = totalPips / ranges.size();

    		this.takeProfitPips = (int) (avgDailyRange * takeProfitAdrRatio);
    		this.stopLossPips = (int) (avgDailyRange * stopLossAdrRatio);
    		this.avgDailyPips = (int) avgDailyRange;
    	}
    	
    	protected List<IBar> getHistoryBars(Period period, int number) throws JFException {
    		IHistory history = context.getHistory();    		
    		long time = history.getStartTimeOfCurrentBar(instrument, Period.DAILY);
    		return history.getBars(instrument, period, OfferSide.ASK, Filter.WEEKENDS, number, time, 0);	
    	}
    	
    	public OrderCommand getOrderCommand() throws JFException {
    		List<IBar> bars = getHistoryBars(Period.DAILY, 3);
    		IBar lastBar = bars.get(bars.size() - 1);
    		return lastBar.getClose() - lastBar.getOpen() > 0 ? OrderCommand.BUY : OrderCommand.SELL;
    	}
    	
    	public double getLotsForAmount(double amount) {
    		if (amount <= 0) {
    			return 0;
    		}
    		return round(Math.max(amount * 0.01 / takeProfitPips, 0.001), 3);
    	}
    	
    	public int getTakeProfitPips() {
			return takeProfitPips;
		}
    	
    	public int getStopLossPips() {
    		return stopLossPips;
    	}
    	
    	public void addDuration(long closeTime, long duration) {
    		durations.put(closeTime, duration);
    	}
    }
}
