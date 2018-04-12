package com.parker.forex.strategies.archived;

import java.io.File;
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
 * currency pairs. Standard deviation calculations are based on direct price differences.
 */
public class FindingNemoStrategy implements IStrategy {

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
	
	private double totalProfitLoss;
	private double totalCommission;
	private double totalProfitLossPips;
	
	private volatile List<Correlation> correlations;
	private volatile List<InstrumentInfo> infos;
	
    @Configurable(value = "Lot Size")
    public double lotSize = 0.01;
    
	@Configurable(value = "Stop Loss (Pips)")
    public int stopLossPips = 20;
    
	@Configurable(value = "Check Period")
    public Period checkPeriod = Period.FIVE_MINS;
	
	@Configurable(value = "Sample Intervals")
    public int intervals = 50;
    
	@Configurable(value = "Open Order Std Deviation Multiplier")
    public double deviationLimitOpen = 1.0;	
	
	@Configurable(value = "Close Order Std Deviation Multiplier")
    public double deviationLimitClose = 0.5;	
	
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
    	if (Double.isNaN(value)) value = 0;
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    } 
	
    private IOrder placeOrder(Instrument instrument, OrderCommand orderCommand) throws JFException {
        String label = instrument.name() + "_" + orderCounter++;  
        IOrder order = context.getEngine().submitOrder(label, instrument, orderCommand, lotSize);
        order.waitForUpdate(State.FILLED);	
		return order;
    }

    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    @Override
    public void onStart(IContext context) throws JFException {
        this.context = context;  
        
        infos = new ArrayList<>();
        infos.add(new InstrumentInfo(Instrument.CADCHF));
        infos.add(new InstrumentInfo(Instrument.USDCHF));
        
		correlations = new ArrayList<>();
		correlations.add(new Correlation(infos.get(0), infos.get(1)));
		
		File filesDir = context.getFilesDir();
		filesDir.canExecute();
    }
    
    @Override
    public void onStop() throws JFException {
        currentTime = context.getHistory().getLastTick(infos.get(0).instrument).getTime();
        
        int wins = 0;
        int losses = 0;
        
        for (Correlation correlation : correlations) {
        	correlation.closeOrders();
        	wins += correlation.wins;
        	losses += correlation.losses;
        }
		
		if (wins == 0 && losses == 0) wins++;
		
		log("Total Trades: " + (wins + losses) + " (" + wins + " wins, " + losses + " losses)");
		log("Win%: " + round(wins * 100.0 / (wins + losses), 1));
		log("Total Profit/Loss: $" + round(totalProfitLoss, 2) + " (" + round(totalProfitLossPips, 1)  + " pips)");
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
            log(order.getInstrument() + ": Closed " + order.getOrderCommand() + " order " + order.getLabel() + " @ $" + order.getClosePrice() + " for $" + round(order.getProfitLossInAccountCurrency(), 2) +
                    " (" + order.getProfitLossInPips() + " pips)", order.getCloseTime());
            
            
            for (Correlation correlation : correlations) {
            	if (correlation.hasOrder(order)) {
            		if (Double.isNaN(correlation.currentProfit)) {
            			correlation.currentProfit = order.getProfitLossInUSD();
            		} else {
            			correlation.currentProfit += order.getProfitLossInUSD();
            			
            			if (correlation.currentProfit > 0) {
            				correlation.wins++;
            			} else {
            				correlation.losses++;
            			}
            			
            			correlation.currentProfit = Double.NaN;
            		}
            	}
            }
        }
    }
    
    protected boolean isTradingWindow(long time) {
		Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
		calendar.setTimeInMillis(time);
		int dow = calendar.get(Calendar.DAY_OF_WEEK);
		int hour = calendar.get(Calendar.HOUR_OF_DAY);
		
		// Only trade between Monday 12am and Saturday 6am GMT
		return dow >= Calendar.MONDAY && (dow < Calendar.SATURDAY || (dow == Calendar.SATURDAY && hour <= 6));
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar bidBar, IBar askBar) throws JFException {
		if (checkPeriod.equals(period)) {
			currentTime = bidBar.getTime();
			
			if (isTradingWindow(currentTime)) {
				String time = PERIOD_DF.format(new Date(bidBar.getTime()));
				
				// Add the price to the appropriate instrument info
				for (InstrumentInfo info : infos) {
					if (info.instrument.equals(instrument)) {
						info.addPrice(time, askBar.getClose());
						break;
					}
				}
				
				// Update the correlations that contain this instrument
				for (Correlation correlation : correlations) {
					if (correlation.hasTime(time)) {
						correlation.check(time);
					}
				}
			
			} else {
				for (Correlation correlation : correlations) {
					if (!correlation.getOpenOrders().isEmpty()) {
						//correlation.closeOrders();
					}
				}
			}
		}
    }

    @Override
    public void onTick(Instrument instrument, ITick tick) throws JFException {        
    }
    
    @Override
    public void onAccount(IAccount account) throws JFException {
    }
	
    /*****************************************************************************************************************
     * Inner classes. 
     * ***************************************************************************************************************
     */
    private class Correlation {
    	
    	private volatile InstrumentInfo info1;
    	private volatile InstrumentInfo info2;
    	private volatile double meanAtOpen;
    	private volatile int orderSide;
    	
    	private volatile double currentProfit =  Double.NaN;
        private volatile int wins;
        private volatile int losses;
    	
    	private volatile List<IOrder> orders = new ArrayList<>();
    	private volatile SortedMap<String, Double> meanPriceDiffs = new TreeMap<String, Double>();
    	
    	public Correlation(InstrumentInfo info1, InstrumentInfo info2) {
    		this.info1 = info1;
    		this.info2 = info2;
    	}
    	
		public void addMean(String time, double mean) {			
			synchronized (meanPriceDiffs) {
				meanPriceDiffs.put(time, mean);				
				if (meanPriceDiffs.size() > intervals) {
					meanPriceDiffs.remove(meanPriceDiffs.firstKey());
				}
			}
		}
		
		public double getMeanDrift() {
			double total = 0;
			for (double mean : meanPriceDiffs.values()) {
				total += mean;
			}
			
			double meanOfMeans = total / meanPriceDiffs.size();
			
			total = 0;
			
			// Calculate standard deviation
			for (double mean : meanPriceDiffs.values()) {
				total += Math.pow(mean - meanOfMeans, 2);		
			}		
			
			double stdDev = Math.sqrt(total / (meanPriceDiffs.size() - 1));	
			return (meanPriceDiffs.get(meanPriceDiffs.lastKey()) - meanOfMeans) / stdDev;
		}
		
		public boolean hasTime(String time) {
			return info1.prices.size() >= intervals && info1.hasInterval(time) && info2.hasInterval(time) && !meanPriceDiffs.containsKey(time);
		}
		
		public void check(String time) throws JFException {
			double deviation = calculateDeviation(time);											
			if (getOpenOrders().isEmpty()) {
				checkOrderOpen(deviation);				
			} else {
				checkOrderClose(deviation);
			}
		}
		
		public List<IOrder> getOpenOrders() {
			List<IOrder> openOrders = new ArrayList<IOrder>();
			for (IOrder order : orders) {
				if (State.OPENED.equals(order.getState()) || State.FILLED.equals(order.getState())) {
					openOrders.add(order);
				}
			}
			return openOrders;
		}
		
		private double calculateDeviation(String barTime) {				
			double total = 0;
			int size = info1.prices.size();
			double[] diffs = new double[size];
		
			int index = 0;
			for (String time : info1.prices.keySet()) {
				double price1 = info1.prices.get(time);
				double price2 = info2.prices.get(time);
					
				// Diffs are direct
				diffs[index] = price1 - price2;
				total += diffs[index];
				
				index++;
			}
			
			double mean = total / size;
			addMean(barTime, mean);
			
			// Calculate standard deviation
			total = 0;
			for (double diff : diffs) {
				total += Math.pow(diff - mean, 2);		
			}		
			double stdDev = Math.sqrt(total / (size - 1));
			
			total = 0;
			for (double diff : diffs) {
				total += Math.pow(diff - meanAtOpen, 2);		
			}		
			
			return (diffs[size - 1] - mean) / stdDev;			
		}
		
		private double getLastMean() {
			return meanPriceDiffs.get(meanPriceDiffs.lastKey());
		}
		
		private void checkOrderOpen(double deviation) throws JFException {	
			double lastMean = getLastMean();
			double meanDrift = getMeanDrift();
			
			if (deviation > deviationLimitOpen) {
				log("Opening orders. [deviation=" + round(deviation, 1) + ", LastMean=" + round(lastMean, 1) + ", MeanDeviation=" + round(meanDrift, 6) + ", Convergent]");
				orders.add(placeOrder(info1.instrument, OrderCommand.SELL));
				orders.add(placeOrder(info2.instrument, OrderCommand.BUY));
				meanAtOpen = lastMean;
				orderSide = 1;
			} else if (deviation < (-1 * deviationLimitOpen)) {
				log("Opening orders. [deviation=" + round(deviation, 1) + ", LastMean=" + round(lastMean, 1) + ", MeanDeviation=" + round(meanDrift, 6) + ", Divergent]");
				orders.add(placeOrder(info1.instrument, OrderCommand.BUY));
				orders.add(placeOrder(info2.instrument, OrderCommand.SELL));
				meanAtOpen = lastMean;
				orderSide = -1;
			 }			
		}
		
		private void checkOrderClose(double deviation) throws JFException {		
			double lastMean = getLastMean();
			
			if (Math.abs(deviation) < deviationLimitClose && ((orderSide < 0 && lastMean > meanAtOpen) || (orderSide > 0 && lastMean < meanAtOpen))) { 
			//if (Math.abs(deviation) < deviationLimitClose) {
				//log("Closing orders due to deviation limit. [deviation=" + (Double.isNaN(deviation) ? "NaN" : round(deviation, 1)) + ", MeanDiff=" + round(meanDiff, 1) + "]");
				log("Closing orders due to deviation limit. [deviation=" + (Double.isNaN(deviation) ? "NaN" : round(deviation, 1)) + ", LastMean=" + round(lastMean, 1) + "]");
				closeOrders();
			}
			//else if ((orderSide > 0 && meanDiff > 1) || (orderSide < 0 && meanDiff < -1)) {
			//	log("Closing orders due to excessive mean drift. [deviation=" + (Double.isNaN(deviation) ? "NaN" : round(deviation, 1)) + ", LastMean=" + round(lastMean, 1) + "]");
			//	closeOrders();
			//}
			//else if (currentTime - getOpenOrders().get(0).getFillTime() > (4 * 60 * 60000)) {
			//	log("Closing orders due to timeout.");
			//	closeOrders();
			//}
			//else if (getCurrentProfitLossPips() < (-1 * stopLossPips)) {
			//	log("Closing orders due to stop loss. [deviation=" + (Double.isNaN(deviation) ? "NaN" : round(deviation, 6)) + ", LastMean=" + round(lastMean, 6) + "]");
			//	closeOrders();
			//}
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
			totalProfitLossPips += (profitLossPips - commission);
				
			log("Profit/Loss: $" + round(profitLoss, 2) + " (" + round(profitLossPips, 1)  + ")");
			log("Commission: $" + round(commission, 2));
			log("Equity: $" + round(context.getAccount().getEquity(), 2));
			log("", true);
			
			orderSide = 0;
		}
		
		public boolean hasOrder(IOrder order) {
			for (IOrder nextOrder : orders) {
				if (nextOrder.equals(order)) {
					return true;
				}
			}
			return false;
		}
    }
    
	private class InstrumentInfo {
	
		private volatile Instrument instrument;
		private volatile SortedMap<String, Double> prices = new TreeMap<String, Double>();
		
		public InstrumentInfo(Instrument instrument) {
			this.instrument = instrument;
		}
	
		public void addPrice(String time, double price) {			
			synchronized (prices) {
				price = price * Math.pow(10, instrument.getPipScale());
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
}
