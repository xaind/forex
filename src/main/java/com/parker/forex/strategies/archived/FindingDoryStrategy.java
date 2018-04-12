package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.TreeMap;

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
import com.dukascopy.api.Period;

/**
 * Determines order entry triggers based on the price deviation between correlated 
 * currency pairs. Price deviation determination is based on normalized price difference
 * with dynamic calculation of optimal trigger point.
 */
public class FindingDoryStrategy implements IStrategy {

    //********************************************************************s*********************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final DateFormat LOG_DF = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");   
	private static final DateFormat PERIOD_DF = new SimpleDateFormat("yyyyMMddHHmm");  
    
    static {
    	LOG_DF.setTimeZone(TimeZone.getTimeZone("GMT"));
    	PERIOD_DF.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IContext context;
	private long currentTime;
    
    private int orderCounter = 1;
    private int wins;
    private int losses;
	
	private double totalProfitLoss;
	private double totalCommission;
	private double totalProfitLossPips;
	
	private volatile InstrumentInfo info1;
	private volatile InstrumentInfo info2;
    	
    @Configurable(value = "Instrument 1")
    public Instrument instrument1 = Instrument.AUDUSD;
	
	@Configurable(value = "Instrument 2")
    public Instrument instrument2 = Instrument.NZDUSD;
    
    @Configurable(value = "Lot Size")
    public double lotSize = 0.01;
    
	@Configurable(value = "Stop Loss (Pips)")
    public int stopLossPips = 50;
    
	@Configurable(value = "Check Period")
    public Period checkPeriod = Period.ONE_HOUR;
	
	@Configurable(value = "Sample Intervals")
    public int intervals = 100;
    
	@Configurable(value = "Close Order Std Deviation Multiplier")
    public double deviationLimitClose = 0.01;	
	
    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message) {
        log(message, currentTime);
    }
    
    private void log(String message, boolean addLine) {
        if (addLine) {
            log("------------------------------------------------------------------------------------------------------------");
        }
        log(message, currentTime);
    }
    
    private void log(String message, long time) {
        if (time > 0) {
            message = LOG_DF.format(new Date(time)) + ": " + message;
        }
        context.getConsole().getInfo().println(message);
    }

    private static double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    } 

	private List<IOrder> getOpenOrders() throws JFException {
		List<IOrder> orders = new ArrayList<IOrder>();
		for (IOrder order : context.getEngine().getOrders()) {
			if ((order.getInstrument().equals(instrument1) || order.getInstrument().equals(instrument2)) &&
					(State.OPENED.equals(order.getState()) || State.FILLED.equals(order.getState()))) {
				orders.add(order);
			}
		}
		return orders;
	}
	
	private void checkOrderOpen(CorrelationStatus correlationStatus) throws JFException {
		if (correlationStatus.openSignal != 0) {
			log(correlationStatus.toString(), true);
		}
		
		if (correlationStatus.openSignal > 0) {
			placeOrder(info1.instrument, OrderCommand.SELL);
			placeOrder(info2.instrument, OrderCommand.BUY);
		} else if (correlationStatus.openSignal < 0) {
			placeOrder(info1.instrument, OrderCommand.BUY);
			placeOrder(info2.instrument, OrderCommand.SELL);
		 }			
	}
	
	private void checkOrderClose(CorrelationStatus correlationStatus) throws JFException {		
		if (!getOpenOrders().isEmpty()) {
			if ((correlationStatus != null && Math.abs(correlationStatus.lastDiff) < deviationLimitClose)) {
				log("Closing orders due to gap reduction.");
				closeOrders();
			}
			//else if (stopLossPips > 0 && getCurrentProfitLossPips() < (-1 * stopLossPips)) {
			//	log("Closing orders due to stop loss.");
			//	closeOrders();
			//}	
		}
	}
	
	private CorrelationStatus getCorrelationStatus() {				
		double[] normalizedPrices1 = calculateNormalizedPrices(info1.prices.values().toArray(new Double[intervals]));
		double[] normalizedPrices2 = calculateNormalizedPrices(info2.prices.values().toArray(new Double[intervals]));		

		Double[] normalizedPriceDiffs = new Double[normalizedPrices1.length];
		for (int i = 0; i < normalizedPrices1.length; i++) {
			normalizedPriceDiffs[i] = normalizedPrices1[i] - normalizedPrices2[i];
		}
		
		double mean = calculateMean(normalizedPriceDiffs);
		double stdDev = calcuateStdDev(normalizedPriceDiffs, mean);		
		double triggerPoint = 1.5 * stdDev; //calculateTriggerPoint(normalizedPriceDiffs, stdDev);
				
		int openSignal = 0;
		double lastDiff = normalizedPriceDiffs[normalizedPriceDiffs.length - 1];
		
		if (Math.abs(lastDiff) >= triggerPoint) {
			openSignal = (int) Math.signum(lastDiff);
		}
		
		return new CorrelationStatus(lastDiff, triggerPoint, openSignal);	
	}
	
	private double calculateMean(Double[] prices) {
		double total = 0;		
		for (double price : prices) {
			total += price;			
		}		
		return total / prices.length;
	}
	
	private double calcuateStdDev(Double[] prices, double mean) {
		double total = 0;
		for (double price : prices) {
			total += Math.pow(price - mean, 2);		
		}		
		return Math.sqrt(total / (prices.length - 1));
	}
	
	private double[] calculateNormalizedPrices(Double[] prices) {
		double mean = calculateMean(prices);	
		double stdDev = calcuateStdDev(prices, mean);
		
		double[] normalizedPrices = new double[prices.length];
		int index = 0;		
		for (double price : prices) {
			normalizedPrices[index++] = (price - mean) / stdDev;		
		}		
		return normalizedPrices;	
	}
	
    private IOrder placeOrder(Instrument instrument, OrderCommand orderCommand) throws JFException {
        String label = instrument.name() + "_" + orderCounter++;  
        IOrder order = context.getEngine().submitOrder(label, instrument, orderCommand, lotSize);
        order.waitForUpdate(State.FILLED);	
		return order;
    }

	private void closeOrders() throws JFException {
		double profitLoss = 0;
		double profitLossPips = 0;
		double commission = 0;
		
		for (IOrder order : getOpenOrders()) {						
			order.close();
			order.waitForUpdate(State.CLOSED);
			
			profitLoss += order.getProfitLossInAccountCurrency();
			profitLossPips += order.getProfitLossInPips();
			commission += order.getCommission();				
		}

		totalProfitLoss += profitLoss;
		totalCommission += commission;
		totalProfitLossPips += profitLossPips;
			
		if (wins == 0 && losses == 0) wins++;
		
		log("Total Trades: " + (wins + losses) + " (" + wins + " wins, " + losses + " losses)");
		log("Win%: " + round(wins * 100.0 / (wins + losses), 1));
		log("Profit/Loss: $" + round(profitLoss, 2) + " (" + round(profitLossPips, 1)  + ")");
		log("Commission: $" + round(commission, 2));
		log("Equity: $" + round(context.getAccount().getEquity(), 2));
		log("", true);
	}
		
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    @Override
    public void onStart(IContext context) throws JFException {
        this.context = context;  
		info1 = new InstrumentInfo(instrument1, intervals);
		info2 = new InstrumentInfo(instrument2, intervals);
    }
    
    @Override
    public void onStop() throws JFException {
        currentTime = context.getHistory().getLastTick(Instrument.GBPUSD).getTime();
		closeOrders();
		
		log("Total Profit/Loss: $" + round(totalProfitLoss, 2) + " (" + round(totalProfitLossPips, 1)  + ")");
		log("Total Commission: $" + round(totalCommission, 2));
		log("Final Equity: $" + round(context.getAccount().getEquity(), 2));
		
        log("Strategy stopped.");
    }

    @Override
    public void onMessage(IMessage message) throws JFException {
        IOrder order = message.getOrder();  
        if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
            log(order.getInstrument() + ": Filled " + order.getOrderCommand() + " order " + order.getLabel() + " @ $" + order.getOpenPrice(), order.getFillTime());    
        } else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
            log(order.getInstrument() + ": Closed " + order.getOrderCommand() + " order " + order.getLabel() + " for $" + round(order.getProfitLossInAccountCurrency(), 2) +
                    " (" + order.getProfitLossInPips() + " pips)", order.getCloseTime());   
            
            if (order.getProfitLossInPips() > 0) {
            	wins++;
            } else {
            	losses++;
            }
        }
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar bidBar, IBar askBar) throws JFException {
		if (checkPeriod.equals(period) && (instrument.equals(info1.instrument) || instrument.equals(info2.instrument))) {
			currentTime = bidBar.getTime();
			
			// Set the current time
			Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
			calendar.setTimeInMillis(currentTime);
			int dow = calendar.get(Calendar.DAY_OF_WEEK);
			int hour = calendar.get(Calendar.HOUR_OF_DAY);
			
			// Only trade between Monday 12am and Saturday 6am GMT
			boolean isTradingWindow = dow >= Calendar.MONDAY && (dow < Calendar.SATURDAY || (dow == Calendar.SATURDAY && hour <= 6));
			
			String time = PERIOD_DF.format(new Date(bidBar.getTime()));
			CorrelationStatus correlationStatus = null;
			
			synchronized (checkPeriod) {
				// Check whether to open the trades
				if (isTradingWindow) {																
				
					if (info1.instrument.equals(instrument)) {						
						info1.addPrice(time, askBar.getClose());			
					} else if (info2.instrument.equals(instrument)) {						
						info2.addPrice(time, askBar.getClose());		
					}
					
					if (info1.prices.size() >= intervals && info1.hasInterval(time) && info2.hasInterval(time)) {
						correlationStatus = getCorrelationStatus();											
						if (getOpenOrders().isEmpty()) {
							checkOrderOpen(correlationStatus);				
						}						
					}
				}				 
						
				// Check whether to close the trades				
				checkOrderClose(correlationStatus);							
			}
		}
    }

    @Override
    public void onTick(Instrument instrument, ITick tick) throws JFException {        
    }
    
    @Override
    public void onAccount(IAccount account) throws JFException {
    }
	
	private static class InstrumentInfo {
	
		private volatile Instrument instrument;
		private volatile int intervals;
		private volatile SortedMap<String, Double> prices = new TreeMap<String, Double>();
		
		public InstrumentInfo(Instrument instrument, int intervals) {
			this.instrument = instrument;
			this.intervals = intervals;
		}
	
		public void addPrice(String time, double price) {			
			synchronized (prices) {
				prices.put(time, price);				
				if (prices.size() > intervals) {
					prices.remove(prices.firstKey());
				}
			}
		}	
		
		public boolean hasInterval(String time) {
			return prices.containsKey(time);		
		}
	}
	
	private static class CorrelationStatus {
	
		private double lastDiff;
		private double triggerPoint;
		private int openSignal;	
	
		public CorrelationStatus(double lastDiff, double triggerPoint, int openSignal) {
			this.lastDiff = lastDiff;
			this.triggerPoint = triggerPoint;
			this.openSignal = openSignal;
		}
		
		public String toString() {
			return "Last Normalized Price Difference = " + round(lastDiff, 4) + ", Trigger Point = " + 
					round(triggerPoint, 4) + ", Open Signal = " + openSignal;
		}
	}
}
