package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IContext;
import com.dukascopy.api.ICurrency;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFCurrency;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;

public class PowerOfOneStrategy implements IStrategy {

    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final DateFormat DF = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        
    static {
        DF.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    volatile IContext context;
    volatile long currentTime;
    volatile int orderId = 0;
    volatile int priceHistorySize;
    
    volatile Period checkPeriod = Period.ONE_HOUR;
    volatile Period lookbackPeriod = Period.DAILY;
    
    volatile double baseLotSize = 0.01;
    volatile double profitLimit = 100.0;
    volatile double lossLimit = -100.0;
    volatile double stopLossPips = 50;
    
    volatile int tradableInstrumentThreshold = 6;
    volatile int tradablePipThreshold = 50;
    
    volatile private int wins;
    volatile private int losses;   
    volatile private double currentDrawDown;
    volatile private double maxDrawDown;
    volatile private double totalProfit;
    volatile private double totalCommission;
    
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

    private void setupBasket() {
        basket = new Basket(JFCurrency.getInstance("USD"),
            Instrument.EURUSD,
            Instrument.USDCHF,
            Instrument.USDJPY,
            Instrument.USDCAD,
            Instrument.AUDUSD,
            Instrument.GBPUSD,
            Instrument.NZDUSD
        );
        
        log(basket.primaryCurrency + " basket has been initialized.");
    }
        
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    @Override
    public void onStart(IContext context) throws JFException {
        this.context = context;
        log("Starting strategy POWER_OF_ONE."); 
        priceHistorySize = (int)(lookbackPeriod.getInterval() / checkPeriod.getInterval());
        setupBasket();
    }
    
    @Override
    public void onStop() throws JFException {
        basket.close("End of strategy");
        
        log("Results for " + basket.primaryCurrency + " basket", true);
        log("Total Profit: $" + round(totalProfit, 2));        
        log("Total Commission: $" + round(totalCommission, 2));        
        log("Total Equity: $" + round(context.getAccount().getEquity(), 2));
        log("Max Draw Down: $" + round(maxDrawDown, 2));        
        log("Total Trades: " + (wins + losses));
        
        if ((wins + losses) == 0) {
            log("Win%: 0.0% (0 wins, 0 losses)");
        } else {
            log("Win%: " + round(100.0 * wins / (wins + losses), 1) + "% (" + wins + " wins, " + losses + " losses)");
        }
            
        log("Strategy POWER_OF_ONE stopped.");
    }

    @Override
    public void onMessage(IMessage message) throws JFException {
        IOrder order = message.getOrder();  
        if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
            log(order.getInstrument() + ": Filled " + order.getOrderCommand() + " order " + order.getLabel() + " @ $" + order.getOpenPrice(), order.getFillTime());    
        } else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
            log(order.getInstrument() + ": Closed " + order.getOrderCommand() + " order " + order.getLabel() + " for $" + round(order.getProfitLossInAccountCurrency(), 2) +
                    " (" + order.getProfitLossInPips() + " pips)", order.getCloseTime());    
        }
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar bidBar, IBar askBar) throws JFException {
    	if (Instrument.EURUSD.equals(instrument) && checkPeriod.equals(period)) {
    		basket.update();
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
    enum BasketStatus {TRACKING, TRADING};
    
    private class Basket {
        
        volatile ICurrency primaryCurrency;
    	volatile BasketStatus status = BasketStatus.TRACKING;
    	volatile Map<Instrument, InstrumentInfo> instruments = new TreeMap<Instrument, InstrumentInfo>();
        
        public Basket(ICurrency primaryCurrency, Instrument... instruments) {
            this.primaryCurrency = primaryCurrency;
            for (Instrument instrument : instruments) {
                this.instruments.put(instrument, new InstrumentInfo(primaryCurrency, instrument));
            }
        }
  
        public synchronized void update() throws JFException {
        	int tradeIndex = 0;
        	
        	for (InstrumentInfo info : instruments.values()) {
        		ITick lastTick = context.getHistory().getLastTick(info.instrument);
        		info.addPrice(lastTick.getAsk());    		
                tradeIndex += info.getTradeIndex();
        	}
        	
        	if (BasketStatus.TRACKING.equals(status)) {
        		if (Math.abs(tradeIndex) > tradableInstrumentThreshold) {
        			open();
        			status = BasketStatus.TRADING;
        		}
        	} else {
        		double profit = getProfit();        		
        		
        		if (profit < lossLimit) {
        			close("Loss limit reached.");
        		} else if (profit > profitLimit) {
        			close("Profit limit reached.");
        		} else if (isAllClosed()) {
            	    close("All trades have hit the stop loss.");   
        		}      
        	}
        }
        
        public boolean isAllClosed() {
        	for (InstrumentInfo info : instruments.values()) {
        		if (info.order != null && !State.CLOSED.equals(info.order.getState())) {
        			return false;
        		}
        	}
        	return true;
        }
        
        public synchronized void open() throws JFException {
            log("Opening orders for " + primaryCurrency + " basket.", true);
            for (InstrumentInfo info : instruments.values()) {
            	if (info.isTradable()) {
            		placeOrder(info);
            	}
            }
        }
        
        private synchronized void placeOrder(InstrumentInfo info) throws JFException {
            String label = info.instrument.name() + "_" + orderId++;
            OrderCommand orderCommand = info.getOrderCommand();
            
            IOrder order = context.getEngine().submitOrder(label, info.instrument, orderCommand, baseLotSize);
            order.waitForUpdate(State.FILLED);
            
            // Set stop loss
            int negator = -1;
            if (OrderCommand.SELL.equals(orderCommand)) {
            	negator = 1;
            }
            double stopLossPrice = round(order.getOpenPrice() + (negator * stopLossPips * info.instrument.getPipValue()), info.instrument.getPipScale()); 
            order.setStopLossPrice(stopLossPrice);
            
            info.order = order;
        }
        
        public synchronized double getProfit() throws JFException {
            double profit = 0;
            for (InstrumentInfo info : instruments.values()) {
                if (info.order != null) {
                    profit += (info.order.getProfitLossInUSD() - info.order.getCommission());
                }
            }
            return profit;
        }
        
        public synchronized void close(String message) throws JFException {
            double profitLoss = 0;
            double profitLossPips = 0;
            double commission = 0;
            
            log("Closing " + primaryCurrency + " basket: " + message, true);
            
            for (InstrumentInfo info : instruments.values()) {
                IOrder order = info.order;
                if (order != null) {
                	// Close any open orders
                	if (State.OPENED.equals(order.getState()) || State.FILLED.equals(order.getState())) {
                		order.close();
                		order.waitForUpdate(State.CLOSED);
                	}
                    
                    profitLoss += order.getProfitLossInUSD();
                    profitLossPips += order.getProfitLossInPips();
                    commission += order.getCommission();
                    
                    info.order = null;
                }
            }
            
            if (profitLoss != 0) {
            	log("Profit/Loss: $" + round(profitLoss, 2) + " (" + round(profitLossPips, 1)  + ")");            
            	log("Comission: $" + round(commission, 2));
            	log("Equity: $" + round(context.getAccount().getEquity(), 2));
            }
                
            if (profitLoss < 0) {
                currentDrawDown += (-1 * profitLoss);
                if (maxDrawDown < currentDrawDown) {
                    maxDrawDown = currentDrawDown;
                }
                losses++;
            } else {
                currentDrawDown = 0;
                wins++;
            }
            
            totalCommission += commission;
            totalProfit += profitLoss;
            
            status = BasketStatus.TRACKING;
        }
    }   
    
    private class InstrumentInfo implements Comparable<InstrumentInfo> {
    
        volatile Instrument instrument;
        volatile OrderCommand orderCommand;
        volatile List<Double> prices = new CopyOnWriteArrayList<Double>();
        volatile IOrder order;
        
        public InstrumentInfo(ICurrency primaryCurrency, Instrument instrument) {
            this.instrument = instrument;
            
            if (primaryCurrency.equals(instrument.getPrimaryJFCurrency())) {
                this.orderCommand = OrderCommand.BUY;
            } else {
                this.orderCommand = OrderCommand.SELL;
            }
        }    
        
        public void addPrice(Double price) {
        	prices.add(price);
        	if (prices.size() > priceHistorySize) {
        		prices.remove(0);
        	}
        }
        
        public double getPipMovement() {
            return (prices.get(prices.size() - 1) - prices.get(0)) * Math.pow(10, instrument.getPipScale());
        }
        
        public boolean isTradable() {
            return Math.abs(getPipMovement()) > tradablePipThreshold;
        }
        
        public boolean isTradableBuy() {
           double pipMovement = getPipMovement();
           return (OrderCommand.BUY.equals(orderCommand) && pipMovement > 0) || (OrderCommand.SELL.equals(orderCommand) && pipMovement < 0); 
        }
        
        public int getTradeIndex() {
        	if (prices.size() >= priceHistorySize) {            
                if (Math.abs(getPipMovement()) > tradablePipThreshold) {
                    return isTradableBuy() ? 1 : -1;
                };
        	}
        	return 0;
        }
        
        public OrderCommand getOrderCommand() {
        	return isTradableBuy() ? orderCommand : (OrderCommand.BUY.equals(orderCommand) ? OrderCommand.SELL : OrderCommand.BUY);        
        }
        
        @Override
        public int compareTo(InstrumentInfo info) {
            if (order != null && info.order != null) {
                return (int) (info.order.getProfitLossInUSD() - this.order.getProfitLossInUSD());
            } else {
                return 0;
            }
        }
    }
}
