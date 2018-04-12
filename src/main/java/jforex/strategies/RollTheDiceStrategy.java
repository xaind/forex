package jforex.strategies;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
 * 
 * @author Xaind, 2016.
 * @version 1.0
 */
public class RollTheDiceStrategy implements IStrategy {
    
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS");
    private static final SimpleDateFormat DATE_FORMAT_MONTH = new SimpleDateFormat("MMMMM yyyy");
    
    static {
        DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
        DATE_FORMAT_MONTH.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    volatile IContext context;
    volatile int orderCounter;
    
    volatile int winCounter;
    volatile int lossCounter;
    volatile int maxConsecutiveLossCounter;
    
    final ConcurrentMap<Instrument, InstrumentInfo> pairs = new ConcurrentHashMap<>();;
    
    @Configurable(value = "Base Lot Size")
    public final double baseLotSize = 0.005;
     
    @Configurable(value = "Martingale Factor")
    public final double martingaleFactor = 1.0;
    
    @Configurable(value = "Max Consecutive Losses")
    public final double maxConsecutiveLosses = 3;
    
    @Configurable(value = "Take Profit Pips")
    public final double takeProfitPips = 37;
    
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private String getName() {
        return "ROLL_THE_DICE";
    }
    
    private void log(String message) {
        context.getConsole().getOut().println(message);
    }
    
    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private double getLotSize(InstrumentInfo info) throws JFException {
        // Calculate the current lot size based on the current number of consecutive losses
        return round(Math.pow(1 + martingaleFactor, info.consecutiveLossCounter) * baseLotSize, 3);
    }
    
    private void placeOrder(InstrumentInfo info, OrderCommand orderCommand)  throws JFException {
        if (hasOpenOrder(info.instrument)) return;                           
        String label = getName() + "_" + (++orderCounter);
        context.getEngine().submitOrder(label, info.instrument, orderCommand, getLotSize(info), 0, 0);
    }
 
    private boolean hasOpenOrder(Instrument instrument) throws JFException {
        for (IOrder order : context.getEngine().getOrders(instrument)) {
            if (!State.CLOSED.equals(order.getState()) && !State.CANCELED.equals(order.getState())) {
                return true;
            }
        }
        return false;
    }
    
    private void onOrderCancelled(IMessage message) throws JFException {    
        log("Error executing order: " + message.getContent());          
    }
    
    private void onOrderFilled(IOrder order) throws JFException {
    	InstrumentInfo info = pairs.get(order.getInstrument());
    	
        // Set the take profit and stop loss prices
        double openPrice = order.getOpenPrice();
        double margin = info.instrument.getPipValue() * takeProfitPips;
        int negator = order.isLong() ? 1 : -1;
        
        order.setTakeProfitPrice(round(openPrice + (negator * margin), info.instrument.getPipScale()));
        order.setStopLossPrice(round(openPrice - (negator * margin * martingaleFactor), info.instrument.getPipScale()));    
    }
    
    private void onOrderClosed(IOrder order) throws JFException {
    	InstrumentInfo info = pairs.get(order.getInstrument());
    	
    	synchronized (info) {
    		log(order.getLabel() + " @ " + DATE_FORMAT_LONG.format(new Date(order.getCloseTime())) + ": Closed " + info.instrument + " " + 
    				order.getOrderCommand() + " order" + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") " + 
    				(order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [equity=$" + context.getAccount().getEquity() + ", comm=$" + 
    				order.getCommissionInUSD() + ", consecutiveLosses=" + info.consecutiveLossCounter + "]");
	        
	        OrderCommand orderCommand = order.getOrderCommand();
	        
	        if (order.getProfitLossInPips() >= 0) {
	            winCounter++;
	            info.consecutiveLossCounter = 0;
	            placeOrder(info, orderCommand);
	        } else {            
	            // Always trade in the current direction of the price
	            lossCounter++;
	            info.consecutiveLossCounter++;
	            
	            if (info.consecutiveLossCounter >= maxConsecutiveLosses) {
            		log("*** MAX CONSECUTIVE LOSSES HIT ***");
            		maxConsecutiveLossCounter++;
            		info.consecutiveLossCounter = 0;
            	}
            	orderCommand = OrderCommand.BUY.equals(orderCommand) ? OrderCommand.SELL : OrderCommand.BUY;
	        }
	        
	        //placeOrder(info, orderCommand);
    	}
    }
    
    //*****************************************************************************************************************
    // Public Methods - Implementation of the IStrategy interface
    //*****************************************************************************************************************   
    public void onStart(IContext context) throws JFException {
        this.context = context;        
        
        pairs.put(Instrument.EURUSD, new InstrumentInfo(Instrument.EURUSD));
        pairs.put(Instrument.GBPUSD, new InstrumentInfo(Instrument.GBPUSD));
        pairs.put(Instrument.EURGBP, new InstrumentInfo(Instrument.EURGBP));
        pairs.put(Instrument.AUDUSD, new InstrumentInfo(Instrument.AUDUSD));
        pairs.put(Instrument.USDJPY, new InstrumentInfo(Instrument.USDJPY));
        pairs.put(Instrument.AUDJPY, new InstrumentInfo(Instrument.AUDJPY));
        //pairs.put(Instrument.USDCHF, new InstrumentInfo(Instrument.USDCHF));
        //pairs.put(Instrument.USDCAD, new InstrumentInfo(Instrument.USDCAD));
        //pairs.put(Instrument.NZDUSD, new InstrumentInfo(Instrument.NZDUSD));
        
        pairs.put(Instrument.EURJPY, new InstrumentInfo(Instrument.EURJPY));
        pairs.put(Instrument.EURAUD, new InstrumentInfo(Instrument.EURAUD));
        pairs.put(Instrument.GBPAUD, new InstrumentInfo(Instrument.GBPAUD));
        pairs.put(Instrument.GBPJPY, new InstrumentInfo(Instrument.GBPJPY));
//        pairs.put(Instrument.EURCHF, new InstrumentInfo(Instrument.EURCHF));
//        pairs.put(Instrument.GBPCHF, new InstrumentInfo(Instrument.GBPCHF));
//        pairs.put(Instrument.AUDNZD, new InstrumentInfo(Instrument.AUDNZD));
//        pairs.put(Instrument.AUDCAD, new InstrumentInfo(Instrument.AUDCAD));
//        
//        pairs.put(Instrument.CADJPY, new InstrumentInfo(Instrument.CADJPY));
//        pairs.put(Instrument.NZDJPY, new InstrumentInfo(Instrument.NZDJPY));
//        pairs.put(Instrument.CHFJPY, new InstrumentInfo(Instrument.CHFJPY));
//        pairs.put(Instrument.EURCAD, new InstrumentInfo(Instrument.EURCAD));
//        pairs.put(Instrument.EURNZD, new InstrumentInfo(Instrument.EURNZD));
        
       	context.setSubscribedInstruments(pairs.keySet());        
        
        log("Started the " + getName() + " strategy using " + pairs.size() + " instruments.");
    }

    public void onMessage(IMessage message) throws JFException {
    	InstrumentInfo info = pairs.get(message.getOrder().getInstrument());
    	
        if (info != null) {
            IOrder order = message.getOrder();
            
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
        log("Max Loss Count: " + maxConsecutiveLossCounter);
        
        log(getName() + " strategy stopped.");
    }
    
    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }
    
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
    	InstrumentInfo info = pairs.get(instrument);
    	
        if (info != null && Period.ONE_HOUR.equals(period) && !hasOpenOrder(instrument)) {
            IBar bar = context.getHistory().getBar(instrument, Period.FIVE_MINS, OfferSide.BID, 0);
            OrderCommand orderCommand = OrderCommand.BUY;
            if (bar != null && bar.getClose() < bar.getOpen()) {
                orderCommand = OrderCommand.SELL;
            }
            
            placeOrder(info, orderCommand);
        }
    }

    public void onAccount(IAccount account) throws JFException {
    }
    
    private static class InstrumentInfo {
    	
    	final Instrument instrument;
    	volatile int consecutiveLossCounter;
    	
    	InstrumentInfo(Instrument instrument) {
    		this.instrument = instrument;
    	}
    }
}
