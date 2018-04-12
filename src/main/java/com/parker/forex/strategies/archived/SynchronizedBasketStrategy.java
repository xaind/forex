package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import com.dukascopy.api.Filter;
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

public class SynchronizedBasketStrategy implements IStrategy {

    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final DateFormat DF = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
    private static int orderId = 0;
    
    static {
        DF.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IContext context;
    volatile private long currentTime;
    volatile private double baseLotSize = 0.001;
    volatile private double tradeAmount = 10;
    volatile private double maxLossAmount = 1000;
    volatile int lookbackIntervals = 10; 
    volatile double rangeMultiplier = 0.5; 
    
    volatile private int wins;
    volatile private int losses;
    volatile private double profitAmount;
    volatile private double profitPips;
    volatile private double commission;
    
    volatile private Basket basket;
    
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
            message = DF.format(new Date(time)) + ": " + message;
        }
        context.getConsole().getInfo().println(message);
    }

    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    } 

	private void onOrderCancelled(IMessage message) throws JFException {
		IOrder order = message.getOrder();
		log("Order " + order.getLabel() + " " + order.getOrderCommand() + " " + order.getState() + ".");
	}

	private synchronized void onOrderFilled(IOrder order) throws JFException {
		// Set the take profit and stop loss prices
		InstrumentInfo info = basket.instruments.get(order.getInstrument());
		double openPrice = order.getOpenPrice();
		double margin = order.getInstrument().getPipValue() * info.takeProfitPips;
		int negator = order.isLong() ? 1 : -1;

		order.setTakeProfitPrice(round(openPrice + (negator * margin), order.getInstrument().getPipScale()));
		order.setStopLossPrice(round(openPrice - (negator * margin), order.getInstrument().getPipScale()));
		
		log("Filled " + order.getInstrument() + " " + order.getOrderCommand() + " order for $" + round(order.getOpenPrice(), order.getInstrument().getPipScale())
			+ ", equity=$" + context.getAccount().getEquity() + ", lots=" + round(order.getAmount(), 3) + "]", order.getFillTime());
	}

	private synchronized void onOrderClosed(IOrder order) throws JFException {		
		currentTime = order.getCloseTime();
		
		profitAmount += order.getProfitLossInUSD();
		commission += order.getCommissionInUSD();
		profitPips += order.getProfitLossInPips();
		
		log("Closed " + order.getInstrument() + " " + order.getOrderCommand() + " order for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") "
				+ (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [open=$" + round(order.getOpenPrice(), order.getInstrument().getPipScale()) 
				+ ", close=$" + round(order.getClosePrice(), order.getInstrument().getPipScale()) + ", equity=$" + context.getAccount().getEquity() 
				+ ", comm=$" + order.getCommissionInUSD() + ", lots=" + round(order.getAmount(), 3) + "]", order.getCloseTime());
		
		InstrumentInfo info = basket.instruments.get(order.getInstrument());
    	
		if (order.getProfitLossInPips() >= 0) {
			wins++;			
		} else {
			losses++;	
			
			if (!basket.restarting) {
				basket.lossCounter++;
				info.orderCommand = order.getOrderCommand().isShort() ? OrderCommand.BUY : OrderCommand.SELL;
			}
		}

		if (basket.restarting) {
			return;
		}
		
		// If we've hit our target profit amount for the round then close all orders and start again
		double roundProfit = basket.getRoundProfit();
		if (roundProfit > tradeAmount) {
			log("Profit target hit!", true);
			basket.restart();
		} else if (roundProfit < (-1 * maxLossAmount)) {
			// Time to bail out
			log("Maximum loss amount reached!", true);
			basket.restart();
		} else if (order.getProfitLossInPips() >= 0) {
			basket.placeOrder(info);
		} else if (basket.lossCounter % basket.instruments.size() == 0) {
			log("Round has been lost. Let's try again! (Loss Counter = " + basket.lossCounter + ")", true);
			basket.nextRound();
		}
	}
	
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    @Override
    public void onStart(IContext context) throws JFException {
        this.context = context;
        log("Starting strategy SYNCHRONIZED_BASKET.");        

        basket = new Basket(
            Instrument.EURUSD,
            Instrument.GBPUSD,
            Instrument.AUDJPY,
            Instrument.AUDJPY,
            Instrument.EURJPY
            //Instrument.EURCHF,
            //Instrument.AUDUSD,
            //Instrument.USDCAD,
            //Instrument.AUDNZD
        );
            
        log("Basket has been initialized.");
        basket.open();
    }
    
	public void onStop() throws JFException {
		int trades = wins + losses;
		log("Total Trades: " + trades);
		
		if (trades == 0) {
			trades = 1;
		}
		
		double netProfit = profitAmount - commission;
		
		log("Wins: " + wins + " (" + round(100.0 * wins / trades, 1) + "%)");
		log("Profit: $" + round(netProfit, 2) + " (pips=" + profitPips + ")");
		log("Commission: $" + round(commission, 2) + " (" + round(commission / profitAmount * 100.0, 2) + "% of profit)");
		log("-----------------------------------------------------------------------------");		
		
		log("SYNCHRONIZED_BASKET strategy stopped.");
	}
	
	public void onMessage(IMessage message) throws JFException {
		IOrder order = message.getOrder();
		if (State.CANCELED.equals(order.getState())) {
			onOrderCancelled(message);
		} else if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
			onOrderFilled(order);
		} else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
			onOrderClosed(order);
		}
	}

    @Override
    public void onBar(Instrument instrument, Period period, IBar bidBar, IBar askBar) throws JFException {
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
        
    	volatile int lossCounter;
    	volatile boolean restarting;
    	volatile List<IOrder> roundOrders = new ArrayList<IOrder>();
        volatile Map<Instrument, InstrumentInfo> instruments = new TreeMap<Instrument, InstrumentInfo>();
        
        public Basket(Instrument... instruments) throws JFException {
            for (Instrument instrument : instruments) {
            	InstrumentInfo info = new InstrumentInfo(instrument);
            	this.instruments.put(instrument, info);
            }
        }
        
        public synchronized void setDirection(InstrumentInfo info) throws JFException {
        	ITick lastTick = context.getHistory().getLastTick(info.instrument);
        	long prevBarTime = context.getHistory().getPreviousBarStart(Period.ONE_HOUR, lastTick.getTime());
        	
        	// Determine the price direction
        	List<IBar> bars = context.getHistory().getBars(info.instrument, Period.ONE_HOUR, OfferSide.ASK, Filter.WEEKENDS, lookbackIntervals, prevBarTime, 0);
        	Collections.reverse(bars);
        	double lastAsk = lastTick.getAsk();
        	OrderCommand orderCommand = null;
        	
        	for (IBar bar : bars) {
        		if (Math.abs(lastAsk - bar.getClose()) > info.takeProfitPips) {
        			orderCommand = Math.signum(lastAsk - bar.getClose()) > 0 ? OrderCommand.BUY : OrderCommand.SELL;
        			break;
        		}
        	}
        	
        	if (orderCommand == null) {
        		if (info.orderCommand == null) {
        			orderCommand = Math.signum(lastAsk - bars.get(0).getClose()) > 0 ? OrderCommand.BUY : OrderCommand.SELL;
        		} else {
        			orderCommand = info.orderCommand;
        		}
        	}
        	
        	info.orderCommand = orderCommand;
        }
        
        public synchronized void setTakeProfit(InstrumentInfo info) throws JFException {
        	// First calculate the daily range so we can get out TP
        	ITick lastTick = context.getHistory().getLastTick(info.instrument);
        	long prevBarTime = context.getHistory().getPreviousBarStart(Period.DAILY, lastTick.getTime());
        	List<IBar> bars = context.getHistory().getBars(info.instrument, Period.DAILY, OfferSide.ASK, Filter.WEEKENDS, lookbackIntervals, prevBarTime, 0);

        	double totalRange = 0;
        	for (IBar bar : bars) {
        		totalRange += bar.getHigh() - bar.getClose();
        	}
        	
        	double takeProfit = (totalRange / lookbackIntervals) * rangeMultiplier;
        	info.takeProfitPips = (int)(takeProfit * Math.pow(10, info.instrument.getPipScale()));
        }
  
        public synchronized void open() throws JFException {
            log("Opening orders for initial Round " + (lossCounter / instruments.size() + 1), true);
            for (InstrumentInfo info : instruments.values()) {
            	setTakeProfit(info);
           		setDirection(info);
                placeOrder(info);
            }
        }
        
        public synchronized void nextRound() throws JFException {
        	log("Opening orders for Round " + (lossCounter / instruments.size() + 1), true);
        	for (InstrumentInfo info : instruments.values()) {
        		setDirection(info);
        		placeOrder(info);
        	}
        }
        
        public synchronized void restart() throws JFException {
        	restarting = true;
        	close();
        	open();
        	restarting = false;
        }
        
        public double getRoundProfit() {
        	double profitAmount = 0;
        	for (IOrder order : roundOrders) {
        		profitAmount += order.getProfitLossInUSD();
        	}
        	return profitAmount;
        }
        
        private synchronized void placeOrder(InstrumentInfo info) throws JFException {
            String label = info.instrument.name() + "_" + orderId++;  
            double lotSize = round(Math.pow(2, this.lossCounter / instruments.size()) * baseLotSize, 3);
            
            IOrder order = context.getEngine().submitOrder(label, info.instrument, info.orderCommand, lotSize);
            order.waitForUpdate(State.FILLED);
            roundOrders.add(order);
        }
        
        public void close() throws JFException {
            double profitLoss = 0;
            double profitLossPips = 0;
            double commission = 0;
            
            log("Closing basket.", true);
            
            for (IOrder order : roundOrders) {
                if (order != null && (State.OPENED.equals(order.getState()) || State.FILLED.equals(order.getState()))) {
                    order.close();
                    order.waitForUpdate(State.CLOSED);
                    
                    profitLoss += order.getProfitLossInUSD();
                    profitLossPips += order.getProfitLossInPips();
                    commission += order.getCommission();
                }
            }
            
            roundOrders.clear();
            lossCounter = 0;
            
            log("Profit/Loss: $" + round(profitLoss, 2) + " (" + round(profitLossPips, 1)  + ")");
            log("Total Trades: " + (wins + losses));
            
            if ((wins + losses) == 0) {
                log("Win%: 0.0% (0 wins, 0 losses)");
            } else {
                log("Win%: " + round(100.0 * wins / (wins + losses), 1) + "% (" + wins + " wins, " + losses + " losses)");
            }
            
            log("Comission: $" + round(commission, 2));
            log("Equity: $" + round(context.getAccount().getEquity(), 2));
        }
    }
    
    private class InstrumentInfo {
    
        volatile Instrument instrument;
        volatile int takeProfitPips;
        volatile OrderCommand orderCommand;
        
        public InstrumentInfo(Instrument instrument) {
            this.instrument = instrument;
        }
    }
}
