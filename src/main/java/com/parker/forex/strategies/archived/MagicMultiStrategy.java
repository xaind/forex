package com.parker.forex.strategies.archived;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;

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
 * dynamically calculated based on the current profitability. Take profit pips are also dynamically calculated
 * based on the average daily range of the instrument.
 */
public class MagicMultiStrategy implements IStrategy {
    
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
    private volatile int orderCounter;
    
    private volatile int winCounter;
    private volatile int lossCounter;
    private volatile double lossLots;
    private volatile double openLots;
    
    private volatile PrintWriter printWriter;
    
    private double startingEquity;
    private double maxEquity;
    private double maxDrawDown;
    private long startTime;
    private long endTime;
    
    private final Map<Instrument, InstrumentInfo> instrumentInfos = Collections.synchronizedMap(new HashMap<Instrument, InstrumentInfo>());
    private final Map<InstrumentInfo, OrderCommand> queuedOrders = Collections.synchronizedMap(new HashMap<InstrumentInfo, OrderCommand>());
    
    @Configurable(value = "Base Trade Amount ($)")
    public final double baseTradeAmount = 50.0;
    
    @Configurable(value = "Max Total Open Lots")
    public final double maxTotalOpenLots = 0.1;
    
    @Configurable(value = "Open Lots Multiplier")
    public final double openLotsMultiplier = 0.5;
    
    @Configurable(value = "Loss Lots Multiplier")
    public final double lossLotsMultiplier = 1.0;
    
    @Configurable(value = "ADR Multiplier")
    public final double adrMultiplier = 1.0;
    
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
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private double getLotSize(InstrumentInfo instrumentInfo) throws JFException {
    	double lotSize = instrumentInfo.getBaseLotSize();
        if (openLots >= maxTotalOpenLots) {
        	log("*** MAX PENDING LOT SIZE HIT (" + round(maxTotalOpenLots, 3) + ") ***");
        } else if ((openLots * openLotsMultiplier) < lossLots && lossLots >= instrumentInfo.getBaseLotSize()) {
    		lotSize = round((lossLots / instrumentInfos.size() * lossLotsMultiplier) + instrumentInfo.getBaseLotSize(), 3);
    		if (lotSize > maxTotalOpenLots) {
    			lotSize = instrumentInfo.getBaseLotSize();
    		}
        }
        
   		return lotSize;
    }
    
    private void placeOrder(InstrumentInfo instrumentInfo, OrderCommand orderCommand)  throws JFException {
    	Instrument instrument = instrumentInfo.instrument;
        instrumentInfo.update();
        
        String label = getName() + "_" + instrument.name() + "_" + (++orderCounter);
        IOrder order = context.getEngine().submitOrder(label, instrument, orderCommand, getLotSize(instrumentInfo), 0, 0);
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
        double margin = instrument.getPipValue() * instrumentInfo.getTakeProfitPips();
        int negator = order.isLong() ? 1 : -1;
        
        order.setTakeProfitPrice(round(openPrice + (negator * margin), instrument.getPipScale()));
        order.setStopLossPrice(round(openPrice - (negator * margin), instrument.getPipScale()));
        
		log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + instrument + " " + order.getOrderCommand() 
			+ " order" + " at $" + order.getOpenPrice() + ". [lots=" + round(order.getAmount(), 3) + ", equity=$" + round(context.getAccount().getEquity(), 2) + ", comm=$" 
			+ round(order.getCommissionInUSD(), 2) + ", pendingLotSize=" + round(openLots, 3) + "]");
    }
    
    private void onOrderClosed(IOrder order) throws JFException {
    	Instrument instrument = order.getInstrument();
    	OrderCommand orderCommand = order.getOrderCommand();
    	InstrumentInfo instrumentInfo = instrumentInfos.get(instrument);
    	
    	openLots -= order.getAmount();
    	
        if (order.getProfitLossInPips() >= 0) {
            winCounter++;
            lossLots = Math.max(lossLots - order.getAmount(), 0);
            placeOrder(instrumentInfo, orderCommand);
        } else {            
            // Always trade in the current direction of the price
            lossCounter++;
           	lossLots += order.getAmount();
            
            // Delay the next trade after a loss to account for volatility
            orderCommand = OrderCommand.BUY.equals(orderCommand) ? OrderCommand.SELL : OrderCommand.BUY;
            queuedOrders.put(instrumentInfo, orderCommand);
        }
        
        log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCloseTime())) + ": Closed " + instrument + " " +  order.getOrderCommand() + " order @ $" 
        		+ round(order.getClosePrice(), 4) + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") " 
        		+ (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [lots=" + round(order.getAmount(), 3) + ", equity=$" + round(context.getAccount().getEquity(), 2) 
        		+ ", comm=$" + round(order.getCommissionInUSD(), 2) + ", lossLotSize=" + round(lossLots, 3) + "]");
        
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
	        Instrument.GBPUSD,
	        Instrument.USDJPY,
	        Instrument.EURJPY,
	        Instrument.EURGBP,
	        Instrument.GBPJPY,
	        //Instrument.AUDNZD,
	        //Instrument.USDCAD,
	        //Instrument.AUDUSD,
	        //Instrument.AUDJPY,
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
       	log("Max Pending Lots: " + round(maxTotalOpenLots, 3));
       	log("Pending Lot Size Multiplier: " + round(openLotsMultiplier, 1));
       	log("Loss Lot Size Multiplier: " + round(lossLotsMultiplier, 1));
       	log("ADR Multiplier: " + round(adrMultiplier, 1));
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
    
    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }
    
    public void onBar(Instrument inst, Period period, IBar askBar, IBar bidBar) throws JFException {
    	if (Period.ONE_HOUR.equals(period)) {
	    	if (!started) {
	    		started = true;
	    		placeInitialOrders();
	    	} else if (!queuedOrders.isEmpty()) {
	    		placeQueuedOrders();
	    	}
    	}
    }

	private void placeQueuedOrders() throws JFException {
		synchronized (queuedOrders) {
			for (Entry<InstrumentInfo, OrderCommand> entry : queuedOrders.entrySet()) {
				placeOrder(entry.getKey(), entry.getValue());
			}
			queuedOrders.clear();
		}
	}

	private void placeInitialOrders() throws JFException {
		for (InstrumentInfo instrumentInfo : instrumentInfos.values()) {
			IBar bar = context.getHistory().getBar(instrumentInfo.getInstrument(), Period.ONE_HOUR, OfferSide.ASK, 1);
			OrderCommand orderCommand = OrderCommand.BUY;
			if (bar.getClose() < bar.getOpen()) {
				orderCommand = OrderCommand.SELL;
			}
			placeOrder(instrumentInfo, orderCommand);
		}
	}
    
    public void onAccount(IAccount account) throws JFException {
    }
    
    //----------------------------------------------------------------------------------------------------------
    // Inner classes
    //----------------------------------------------------------------------------------------------------------
    private class InstrumentInfo {
    	
    	private Instrument instrument;
    	private double baseLotSize;
    	private int takeProfitPips;
    	
		public InstrumentInfo(Instrument instrument) {
    		this.instrument = instrument;
    	}
    	
    	private void update() throws JFException {
    		IHistory history = context.getHistory();
    		
    		long time = history.getStartTimeOfCurrentBar(instrument, Period.DAILY);
    		List<IBar> bars = history.getBars(instrument, Period.DAILY, OfferSide.ASK, Filter.WEEKENDS, 60, time, 0);
    		
    		List<Double> ranges = new ArrayList<>();
    		for (IBar bar : bars) {
    			ranges.add((bar.getHigh() - bar.getLow()) / instrument.getPipValue());
    		}
    		
    		Collections.sort(ranges);
    		ranges.removeAll(ranges.subList(0, 10));
    		ranges.removeAll(ranges.subList(ranges.size() - 10, ranges.size()));
    		
    		double totalPips = 0;
    		for (Double range : ranges) {
    			totalPips += range;
    		}
    		
    		this.takeProfitPips = (int) (totalPips / ranges.size() * adrMultiplier);
    		this.baseLotSize = Math.max(baseTradeAmount * 0.01 / takeProfitPips, 0.001); 
    	}
    	
    	public int getTakeProfitPips() {
			return takeProfitPips;
		}

		public Instrument getInstrument() {
			return instrument;
		}

		public double getBaseLotSize() {
			return baseLotSize;
		}
    }
    
}
