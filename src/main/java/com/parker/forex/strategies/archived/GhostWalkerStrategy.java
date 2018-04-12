package com.parker.forex.strategies.archived;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine.OrderCommand;
import com.parker.forex.CustomStrategy;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;

/**
 * Determines the range of the previous day and then monitors breaks of these prices. Stop orders will be set for a second break.
 */
public class GhostWalkerStrategy implements CustomStrategy {
    
    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final String NAME = "GHOST_WALKER";
    
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS Z");
    private static final SimpleDateFormat DATE_FORMAT_MONTH = new SimpleDateFormat("MMMMM yyyy");
    
    static {
    	DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
    	DATE_FORMAT_MONTH.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IContext context;
    private int orderCounter = 1;
    
    private int winCounter;
    private int lossCounter;
    private int consecutiveLossCounter;
    private int maxConsecutiveLosses;
    
    private String currentMonth;
    private int monthWinCounter;
    private int monthLossCounter;
    private int monthMaxConsecutiveLosses;
    private double previousEquity;
    
    private PrintWriter logFile;
    private Map<Integer, Integer> consecutiveLossFrequency = new HashMap<Integer, Integer>();
    
    //*****************************************************************************************************************
    // Configurable Fields
    //*****************************************************************************************************************
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.USDCHF;
    
	@Configurable(value = "TP/SL Pips")
	public int pips = 150;
	
	@Configurable(value = "Martingale Offset")
	public int martingaleOffset = 0;
	
	@Configurable(value = "Martingale Multiplier")
	public double martingaleMultiplier = 2.1;
	
	@Configurable(value = "Consecutive Loss Limit")
	public int consecutiveLossLimit = 7;
	
	//*****************************************************************************************************************
	// Constructor & Life-Cycle Methods
	//*****************************************************************************************************************
	public GhostWalkerStrategy() {
	}
	
	public GhostWalkerStrategy(Instrument instrument, int pips, int martingaleOffset, int consecutiveLossLimit) {
		this.instrument = instrument;
		this.pips = pips;
		this.martingaleOffset = martingaleOffset;
		this.consecutiveLossLimit = consecutiveLossLimit;
	}
	
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message) {
        context.getConsole().getOut().println(message);
        logFile.println(message);
    }
    
    private void closeAllPositions() throws JFException {
        for (IOrder order : context.getEngine().getOrders(instrument)) {
            if (order.getLabel().contains(NAME)) {
                order.close();
            }
        }
    }
    
    private double getLotSize() {
    	// Base trading amount is 0.1% of current equity - base lot size is calculated using this amount
    	double tradeAmount  = context.getAccount().getEquity() * 0.001;
    	double baseLotSize = tradeAmount / 10000.0;
    	
    	if (baseLotSize < 0.001) {
    		baseLotSize = 0.001;
    	}
    	
    	baseLotSize = BigDecimal.valueOf(baseLotSize).setScale(3, RoundingMode.DOWN).doubleValue();
    	
    	double lotSize = Math.pow(martingaleMultiplier, consecutiveLossCounter + martingaleOffset) * baseLotSize;
    	lotSize =  round(lotSize, 3);
    	
    	return lotSize;
    }
    
    private String getLabel() {
        return NAME + "_" + instrument.name().replace("/", "") + "_" + (orderCounter++);
    }
    
    private double round(double value, int precision) {
    	return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private void placeTrade(OrderCommand orderCommand)  throws JFException {
    	context.getEngine().submitOrder(getLabel(), instrument, orderCommand, getLotSize(), 0, 1);
    }
	
    private double logMonthly() {
		double currentEquity = context.getAccount().getEquity();
		double monthlyProfitLoss = currentEquity - previousEquity;
		int totalTrades = monthWinCounter + monthLossCounter;
		
		log(currentMonth + ": $" + round(monthlyProfitLoss, 2) + " " + (monthlyProfitLoss > 0 ? "PROFIT" : "LOSS") + ", Max Consecutive Losses: " + monthMaxConsecutiveLosses);
		log("Monthly Trades: " + totalTrades + ", Win%: " + (totalTrades == 0 ? 0: round((monthWinCounter * 100.0 / totalTrades), 0)) + "% (" + monthWinCounter + " wins / " + 
					monthLossCounter + " losses)");
		log("************************************************************************************************");
		
		return currentEquity;
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    public String getName() {
    	return "GHOST_WALKER";
    }
    
    public String getDescription() {
    	return getName() + "_" + instrument.name() + "_" + pips;
    }
    
    public Instrument getInstrument() {
    	return instrument;
    }
    
    public void onStart(IContext context) throws JFException {
        this.context = context;
        
        try {
        	logFile = new PrintWriter(getDescription().toLowerCase() + ".log");
		} catch (FileNotFoundException fnfe) {
			throw new RuntimeException("Could not create log file.", fnfe);
		}
        
        context.setSubscribedInstruments(Collections.singleton(instrument), true);
        log("Started the " + NAME + " strategy using " + instrument + ".");
        
        placeTrade(OrderCommand.BUY);
    }

    public void onMessage(IMessage message) throws JFException {
    	if (message.getOrder().getInstrument().equals(instrument)) {
    		IOrder order = message.getOrder();
    		
    		if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
    			double openPrice = order.getOpenPrice();
    			double margin = instrument.getPipValue() * pips;
    			int negator = order.isLong() ? 1 : -1;
    			
   				order.setTakeProfitPrice(round(openPrice + (negator * margin), 4));
   				order.setStopLossPrice(round(openPrice - (negator * margin), 4));
    			
	            log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + order.getOrderCommand() + 
	            		" order @ $" + order.getOpenPrice() + " [lotSize=" + order.getAmount() + ", consecutiveLosses=" + consecutiveLossCounter + 
	            		", martingale=" + round(Math.pow(martingaleMultiplier, consecutiveLossCounter), 2) + "]");
	            
    		} else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
	    		// Log the order outcome
    			log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCloseTime())) + ": Closed " + 
    					order.getOrderCommand() + " order" + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") " + 
    					(order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [equity=$" + context.getAccount().getEquity() + "]");
    			
    			OrderCommand orderCommand = null;
    			
    			// Collate stats
	        	if (order.getProfitLossInPips() > 0) {
	        		winCounter++;
	        		monthWinCounter++;
	        		
	        		if (maxConsecutiveLosses < consecutiveLossCounter) {
	        			maxConsecutiveLosses = consecutiveLossCounter;
	        			monthMaxConsecutiveLosses = maxConsecutiveLosses;
	        		}
	        		
	        		Integer count = consecutiveLossFrequency.get(consecutiveLossCounter);
	        		if (count == null) {
	        			count = Integer.valueOf(0);
	        		}
	        		consecutiveLossFrequency.put(consecutiveLossCounter, count + 1);
	        		consecutiveLossCounter = 0;
	        		
	        		orderCommand = order.isLong() ? OrderCommand.BUY : OrderCommand.SELL;
	        		
	        		
	        	} else if (order.getProfitLossInPips() < 0) {
	        		lossCounter++;
	        		consecutiveLossCounter++;
	        		monthLossCounter++;
	        		
	        		orderCommand = order.isLong() ? OrderCommand.SELL : OrderCommand.BUY;
	        		
	        		if (consecutiveLossCounter > consecutiveLossLimit) {
	        			consecutiveLossCounter = 0;
	        			log("Consecutive loss limit has been reached. Resetting loss counter.");
	        		}
	        	}
	        	
	        	placeTrade(orderCommand);
	        	
	        	// Collate end-of-month stats
	        	String orderMonth = DATE_FORMAT_MONTH.format(new Date(order.getCreationTime()));
	        	
	        	if (currentMonth == null) {
	        		currentMonth = orderMonth;
	        		previousEquity = context.getAccount().getEquity();
	        	} else if (!currentMonth.equals(orderMonth)) {
	        		previousEquity = logMonthly();
	        		
	        		monthWinCounter = 0;
	        		monthLossCounter = 0;
	        		monthMaxConsecutiveLosses = 0;
	        		
	        		currentMonth = orderMonth;
	        	}
    		}
    	}
    }
    
    public void onStop() throws JFException {
    	closeAllPositions();
    	logMonthly();
    	
        log("Total Equity: $" + context.getAccount().getEquity());
        
        int totalTrades = winCounter + lossCounter;
        log("Total Trades: " + totalTrades);
        log("Win%: " + (totalTrades == 0 ? 0: round((winCounter * 100.0 / totalTrades), 0)) + "% (" + winCounter + " wins / " + lossCounter + " losses)");
        log("Max Consecutive Losses: " + maxConsecutiveLosses);
        
        log("Consecutive Loss Distribution");
        for (Entry<Integer, Integer> entry : consecutiveLossFrequency.entrySet()) {
        	log(entry.getKey() + ": " + entry.getValue());
        }
        
        log("Strategy stopped.");
        logFile.close();
    }
    
    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }
    
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
    }
    
    public void onAccount(IAccount account) throws JFException {
    }
}