package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
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

public class BasketCaseStrategy implements IStrategy {

    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final DateFormat DF = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
	private static final long MILLIS_IN_DAY = 1000 * 3600 * 24;
	private static final long MILLIS_IN_HOUR = 1000 * 3600;
    private static int orderId = 0;
    
    static {
        DF.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IContext context;
	private Basket basket;

    //*****************************************************************************************************************
    // Configurable Fields
    //*****************************************************************************************************************
    @Configurable(value = "Lot Size")
    public double lotSize = 0.01;
    
    @Configurable(value = "Trailing Stop (Pips)")
    public int trailingStop = 20;
    
    @Configurable(value = "Max Orders per Basket")
    public int maxOrders = 100;

    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message, long time) {
    	if (time > 0) {
    		message = DF.format(new Date(time)) + ": " + message;
    	}
        context.getConsole().getInfo().println(message);
    }

    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    } 

    private IOrder placeOrder(Instrument instrument, OrderCommand orderCommand) throws JFException {
        String label = instrument.name() + "_" + orderCommand.toString() + "_" + orderId++;
        IOrder order = context.getEngine().submitOrder(label, instrument, orderCommand, lotSize);
        order.waitForUpdate(State.FILLED);
        
        int negator = 1;
        if (OrderCommand.SELL.equals(orderCommand)) {
        	negator = -1;
        }
        
        order.setStopLossPrice(order.getOpenPrice() - (trailingStop * instrument.getPipValue() * negator), OfferSide.BID, trailingStop);
        return order;
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    @Override
    public void onStart(IContext context) throws JFException {
        this.context = context;
		basket = new Basket();
		
		basket.addInstrument(Instrument.GBPUSD, OrderCommand.BUY);
		basket.addInstrument(Instrument.EURGBP, OrderCommand.BUY);
		basket.addInstrument(Instrument.GBPCHF, OrderCommand.BUY);
		basket.addInstrument(Instrument.CHFJPY, OrderCommand.BUY);
		basket.addInstrument(Instrument.AUDJPY, OrderCommand.BUY);
		basket.addInstrument(Instrument.EURJPY, OrderCommand.BUY);
		basket.addInstrument(Instrument.USDCHF, OrderCommand.BUY);
		
		basket.addInstrument(Instrument.AUDUSD, OrderCommand.SELL);
		basket.addInstrument(Instrument.CADJPY, OrderCommand.SELL);
		basket.addInstrument(Instrument.USDJPY, OrderCommand.SELL);
		basket.addInstrument(Instrument.EURUSD, OrderCommand.SELL);
		basket.addInstrument(Instrument.EURCHF, OrderCommand.SELL);
		basket.addInstrument(Instrument.GBPJPY, OrderCommand.SELL);
		basket.addInstrument(Instrument.USDCAD, OrderCommand.SELL); 
    }
    
    @Override
    public void onStop() throws JFException {
    	long time = context.getHistory().getLastTick(Instrument.GBPUSD).getTime();
    	basket.close(time);
    	log("Strategy stopped.", time);
    }

    @Override
    public void onMessage(IMessage message) throws JFException {
        if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
            IOrder order = message.getOrder();  
            log(order.getInstrument() + ": Filled order " + order.getLabel() + " @ " + order.getOpenPrice(), order.getFillTime());    
        }
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar bidBar, IBar askBar) throws JFException {
        if (Instrument.GBPUSD.equals(instrument) && Period.ONE_HOUR.equals(period)) {
    		basket.process(bidBar.getTime());
        }
    }

    @Override
    public void onTick(Instrument instrument, ITick tick) throws JFException {		
    }
    
    @Override
    public void onAccount(IAccount account) throws JFException {
    }

    //*************************************************************************************************************************************************************
    // Inner classes.
    //*************************************************************************************************************************************************************
    private class Basket {
    	
    	long id;
    	long startTime;
    	boolean primed;
    	boolean hammertime;
    	OrderCommand topAnchor;
    	OrderCommand bottomAnchor;
    	long currentTime;
    	
    	List<InstrumentInfo> instruments = new ArrayList<InstrumentInfo>();
    	
    	private void sort() throws JFException {
    		for (InstrumentInfo info : instruments) {
    			ITick lastTick = context.getHistory().getLastTick(info.instrument);
    			if (info.startPrice == 0) {
    				info.startPrice = lastTick.getBid();
    			} else {
    				info.profitPips = (lastTick.getBid() - info.startPrice) / info.instrument.getPipValue();
    			}
    		}
    		
    		Collections.sort(instruments);    		
    		int counter = 1;
    				
    		log("------------------------------------------------------------------------------------------------------------", currentTime);
    		for (InstrumentInfo info : instruments) {
    			info.previousRank = info.currentRank;
    			info.currentRank = counter++;
    			
    			if (primed) {
    				log(String.format("%02d", info.currentRank) + " " + info.instrument.name() + " (" + round(info.profitPips, 1) + " pips)", currentTime);
    			}
    		}
    		log("------------------------------------------------------------------------------------------------------------", currentTime);
    	}
    	
    	private boolean isPrimed() {
    		if (!primed && startTime < (currentTime - MILLIS_IN_HOUR)) {
    			primed = true;
    			OrderCommand profitType = null;
    			
    			for (InstrumentInfo info : instruments) {
    				if (profitType == null) {
    					profitType = info.orderCommand;
    				}
    				
    				if (!profitType.equals(info.orderCommand) && info.currentRank <= (instruments.size() / 2)) {
    					primed = false;
    					break;
    				}
    			}
    			
    			if (primed) {
    				topAnchor = instruments.get(0).orderCommand;
    				bottomAnchor = instruments.get(instruments.size() - 1).orderCommand;
    			}
    		}
    		return primed;	
    	}
    	
    	private void checkForBorderJumpers() throws JFException {
    		if (isPrimed() && getOrders().size() < maxOrders) {
    			int border = instruments.size() / 2;
    			for (InstrumentInfo info : instruments) {
    				if (info.order == null && info.currentRank <= border && info.previousRank > border) {
    					// Positive jumper
    					log("Postive border jumper detected: " + info.instrument.name() + " (" + info.previousRank + " -> " + info.currentRank + ")", currentTime); 
    					info.order = placeOrder(info.instrument, info.orderCommand);
    				} else if (info.order == null && info.currentRank > border && info.previousRank <= border) {
    					// Negative jumper
    					log("Negative border jumper detected: " + info.instrument.name() + " (" + info.previousRank + " -> " + info.currentRank + ")", currentTime); 
    					OrderCommand orderCommand = OrderCommand.BUY.equals(info.orderCommand) ? OrderCommand.SELL : OrderCommand.BUY;
    					info.order = placeOrder(info.instrument, orderCommand);
    				}
    			}
    		}
    	}
    	
    	private void checkForHammerTime() throws JFException {
    		OrderCommand allInType = null;
    		if (!instruments.get(0).orderCommand.equals(topAnchor) || !instruments.get(instruments.size() - 1).orderCommand.equals(bottomAnchor)) {
    			allInType = bottomAnchor;
    			this.hammertime = true;
    		}
    		
    		if (hammertime) {
	    		log("HAMMERTIME -- All in for a " + bottomAnchor, currentTime);
	    		
	    		for (InstrumentInfo info : instruments) {
	    			if (info.order == null) {
	    				info.order = placeOrder(info.instrument, allInType);
	    			} else if (!info.order.getOrderCommand().equals(allInType)) {
	    				info.order.close();
	    				info.order = placeOrder(info.instrument, allInType);
	    			}
	    		}
    		}
    	}
    	
    	private List<IOrder> getOrders() {
    		List<IOrder> orders = new ArrayList<IOrder>();
    		for (InstrumentInfo info : instruments) {
    			if (info.order != null) {
    				orders.add(info.order);
    			}
    		}
    		return orders;
    	}
    	
    	private void checkForReset() {
    		if (!hammertime) {
    			return;
    		}
    		
    		int orderCount = 0;
    		int closedOrderCount = 0;
    		
    		for (IOrder order : getOrders()) {
    			if (State.CLOSED.equals(order.getState()) || State.CANCELED.equals(order.getState())) {
    				closedOrderCount++;
    			}
    			orderCount++;
    		}
    		
    		if (closedOrderCount == orderCount) {
    			reset();
    		}
    	}
    	
    	private void reset() {
            // Collate the results
    		double profitLoss = 0;
    		double commission = 0;
    		
    		for (InstrumentInfo info : instruments) {
    			if (info.order != null) {
    				profitLoss += info.order.getProfitLossInAccountCurrency();
    				commission += info.order.getCommission();
    			}
    			info.reset();
    		}
    		
          log("------------------------------------------------------------------------------------------------------------", currentTime);                
          log("Basket: " + (id++) + " started @ " + DF.format(new Date(startTime)), currentTime);
          log("Profit/Loss: $" + round(profitLoss, 2), currentTime);
          log("Comission: $" + round(commission, 2), currentTime);
          log("Equity: $" + round(context.getAccount().getEquity(), 2), currentTime);
          log("Duration: " + round(1.0 * currentTime / MILLIS_IN_DAY, 1) + " days", currentTime);
          log("------------------------------------------------------------------------------------------------------------", currentTime);                
          
          this.startTime = 0;
          this.primed = false;
          this.hammertime = false;
          this.topAnchor = null;
          this.bottomAnchor = null;
    	}
    	
    	public void addInstrument(Instrument instrument, OrderCommand orderCommand) {
    		instruments.add(new InstrumentInfo(instrument, orderCommand));
    	}
    	
    	public void close(long time) throws JFException {
    		for (IOrder order : getOrders()) {
    			if (State.OPENED.equals(order.getState()) || State.FILLED.equals(order.getState())) {
    				order.close();
    				order.waitForUpdate(State.CLOSED);
    			}
    		}
    		
    		this.currentTime = time;
    		reset();
    	}
    	
    	public void process(long time) throws JFException {
    		this.currentTime = time;
    		if (startTime == 0) {
    			startTime = time;
    		}
    		
    		sort();
    		checkForBorderJumpers();
    		checkForHammerTime();
    		checkForReset();
    	}
    }
    
    private class InstrumentInfo implements Comparable<InstrumentInfo> {
    
        Instrument instrument;
        OrderCommand orderCommand;
        double startPrice;
        int previousRank;
		int currentRank;
		double profitPips;
        IOrder order;
        
        public InstrumentInfo(Instrument instrument, OrderCommand orderCommand) {
            this.instrument = instrument;
            this.orderCommand = orderCommand;
        }     

		@Override
		public int compareTo(InstrumentInfo info) {
			return (int) (this.profitPips - info.profitPips);
		}
		
		public void reset() {
			this.startPrice = 0;
			this.previousRank = 0;
			this.currentRank = 0;
			this.profitPips = 0;
			this.order = null;
		}
	}
}
