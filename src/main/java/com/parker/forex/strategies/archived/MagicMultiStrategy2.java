package com.parker.forex.strategies.archived;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
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
import com.dukascopy.api.OfferSide;
import com.dukascopy.api.Period;

/**
 * Simple trend following strategy that uses a Martingale money management system across multiple currency pairs.
 */
public class MagicMultiStrategy2 implements IStrategy {
    
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS");
    private static final SimpleDateFormat DATE_FORMAT_MEDIUM = new SimpleDateFormat("yyyyMMddHHmm");
    private static final SimpleDateFormat DATE_FORMAT_MONTH = new SimpleDateFormat("MMMMM yyyy");
    
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
    private volatile double lossLotSize;
    private volatile double pendingLotSize;
    
    private volatile PrintWriter printWriter;
    
    private final Set<Instrument> instruments = Collections.synchronizedSet(new HashSet<Instrument>());
    private final Map<Instrument, OrderCommand> queuedOrders = Collections.synchronizedMap(new HashMap<Instrument, OrderCommand>());
    
    @Configurable(value = "Base Lot Size")
    public final double baseLotSize = 0.001;
     
    @Configurable(value = "Take Profit Pips")
    public final double takeProfitPips = 50;
    
    @Configurable(value = "Max Loss Size")
    public final double maxLossLotSize = 0.1;
    
    @Configurable(value = "Pending Lot Size Multiplier")
    public final double pendingLotSizeMultiplier = 0.5;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private String getName() {
        return "MAGIC_MULTI";
    }
    
    private void log(String message) {
        context.getConsole().getOut().println(message);
    }
    
    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private double getLotSize() throws JFException {
    	if ((pendingLotSize * pendingLotSizeMultiplier) < lossLotSize && lossLotSize >= baseLotSize) {
    		return round((lossLotSize * pendingLotSizeMultiplier) + baseLotSize, 3);
    	} else {
    		return baseLotSize;
    	}
    }
    
    private void placeOrder(Instrument instrument, OrderCommand orderCommand)  throws JFException {
        String label = getName() + "_" + instrument.name() + "_" + (++orderCounter);
        IOrder order = context.getEngine().submitOrder(label, instrument, orderCommand, getLotSize(), 0, 0);
        pendingLotSize += order.getAmount(); 
    }
 
    private void onOrderCancelled(IMessage message) throws JFException {    
        log("Error executing order: " + message.getContent());          
    }
    
    private void onOrderFilled(IOrder order) throws JFException {
    	Instrument instrument = order.getInstrument();
    	
        // Set the take profit and stop loss prices
        double openPrice = order.getOpenPrice();
        double margin = instrument.getPipValue() * takeProfitPips;
        int negator = order.isLong() ? 1 : -1;
        
        order.setTakeProfitPrice(round(openPrice + (negator * margin), instrument.getPipScale()));
        order.setStopLossPrice(round(openPrice - (negator * margin), instrument.getPipScale()));
        
		log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getFillTime())) + ": Filled " + instrument + " " + order.getOrderCommand() 
			+ " order" + " at $" + order.getOpenPrice() + ". [lots=" + order.getAmount() + ", equity=$" + context.getAccount().getEquity() + ", comm=$" 
			+ order.getCommissionInUSD() + ", pendingLotSize=" + round(pendingLotSize, 3) + "]");
    }
    
    private void onOrderClosed(IOrder order) throws JFException {
    	Instrument instrument = order.getInstrument();
    	OrderCommand orderCommand = order.getOrderCommand();
    	
    	pendingLotSize -= order.getAmount();
    	
        if (order.getProfitLossInPips() >= 0) {
            winCounter++;
            lossLotSize = Math.max(lossLotSize - order.getAmount(), 0);
            placeOrder(instrument, orderCommand);
        } else {            
            // Always trade in the current direction of the price
            lossCounter++;
           	lossLotSize += order.getAmount();
            
            if (lossLotSize >= maxLossLotSize) {
            	log("*** MAX LOSS SIZE HIT (" + maxLossLotSize + ") ***");
            	lossLotSize = maxLossLotSize;
            }
            
            orderCommand = OrderCommand.BUY.equals(orderCommand) ? OrderCommand.SELL : OrderCommand.BUY;
            queuedOrders.put(instrument, orderCommand);
        }
        
        log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCloseTime())) + ": Closed " + instrument + " " +  order.getOrderCommand() + " order @ $" 
        		+ round(order.getClosePrice(), 4) + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") " 
        		+ (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [lots=" + order.getAmount() + ", equity=$" + context.getAccount().getEquity() 
        		+ ", comm=$" + order.getCommissionInUSD() + ", lossLotSize=" + round(lossLotSize, 3) + "]");
        
    }
    
    //*****************************************************************************************************************
    // Public Methods - Implementation of the IStrategy interface
    //*****************************************************************************************************************   
    public void onStart(IContext context) throws JFException {
        this.context = context;        
        
        instruments.add(Instrument.EURUSD);
        instruments.add(Instrument.GBPUSD);
        instruments.add(Instrument.USDJPY);
        instruments.add(Instrument.EURJPY);
        instruments.add(Instrument.EURGBP);
        instruments.add(Instrument.AUDNZD);
        instruments.add(Instrument.USDCAD);
        //instruments.add(Instrument.GBPJPY);
        
       	context.setSubscribedInstruments(instruments);        
        
       	try {
       		printWriter = new PrintWriter("mm" + DATE_FORMAT_MEDIUM.format(new Date()) + ".txt");
       	} catch (Exception e) {
       		// Checked exceptions suck!
       		throw new RuntimeException(e);
       	}
       	
       	printWriter.println("-------------------------------------------------------------------");
       	printWriter.println("Backtesting run for " + getName() + " strategy.");
       	printWriter.println("-------------------------------------------------------------------");
       	printWriter.println("Run Date: " + DATE_FORMAT_LONG.format(new Date()));
       	printWriter.println();
       	
        log("Started the " + getName() + " strategy using " + instruments.size() + " instruments.");
    }

    public void onMessage(IMessage message) throws JFException {
    	IOrder order = message.getOrder();
    	if (order != null) {
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
        log("Total Equity: $" + context.getAccount().getEquity());
        
        int totalTrades = winCounter + lossCounter;
        log("Total Trades: " + totalTrades);
        log("Win%: " + (totalTrades == 0 ? 0: round((winCounter * 100.0 / totalTrades), 0)) + "% (" + winCounter + " wins / " + lossCounter + " losses)");
        
        log(getName() + " strategy stopped.");
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
			for (Entry<Instrument, OrderCommand> entry : queuedOrders.entrySet()) {
				placeOrder(entry.getKey(), entry.getValue());
			}
			queuedOrders.clear();
		}
	}

	private void placeInitialOrders() throws JFException {
		for (Instrument instrument : instruments) {
			IBar bar = context.getHistory().getBar(instrument, Period.ONE_HOUR, OfferSide.ASK, 1);
			OrderCommand orderCommand = OrderCommand.BUY;
			if (bar.getClose() < bar.getOpen()) {
				orderCommand = OrderCommand.SELL;
			}
			placeOrder(instrument, orderCommand);
		}
	}
    
    public void onAccount(IAccount account) throws JFException {
    }
}
