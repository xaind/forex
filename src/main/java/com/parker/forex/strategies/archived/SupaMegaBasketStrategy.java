package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

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

public class SupaMegaBasketStrategy implements IStrategy {

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
    
    volatile private double riskRatio = 2.0;
    volatile private double baseLotSize = 0.01;
    volatile private double baseLossAmount = 100;
    
    volatile private int wins;
    volatile private int losses;
    volatile private int lossCounter;
    volatile private int maxLossCounter;
    volatile private int maxLossLimit = 3;
    volatile private double lowestEquity = Double.MAX_VALUE;
    
    volatile private Basket basket;
    volatile Map<Integer, Integer> lossFrequencyMap = new HashMap<Integer, Integer>();
    
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
        basket = new Basket(
            Instrument.EURUSD,
            Instrument.USDJPY,
            Instrument.AUDUSD,
            Instrument.GBPUSD,
            Instrument.USDCHF
            //Instrument.USDCAD
        );
        
        log("Basket has been initialized.");
    }
    
    private void updateLossFrequency() {
        Integer lossFrequency = lossFrequencyMap.get(lossCounter);
        if (lossFrequency == null) {
            lossFrequency = Integer.valueOf(0);
        }
        lossFrequencyMap.put(lossCounter, lossFrequency + 1);
    }
        
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    @Override
    public void onStart(IContext context) throws JFException {
        this.context = context;
        log("Starting strategy SUPA_MEGA_BASKET.");        
        setupBasket();
    }
    
    @Override
    public void onStop() throws JFException {
        basket.close();
        
        log("Total Equity: $" + round(context.getAccount().getEquity(), 2));
        log("Lowest Equity: $" + round(lowestEquity, 2));
        
        log("Strategy SUPA_MEGA_BASKET stopped.");
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
    }

    @Override
    public void onTick(Instrument instrument, ITick tick) throws JFException {    
        InstrumentInfo info = basket.instruments.get(instrument);
        if (info != null) { 
            currentTime = tick.getTime();
            if (info.previousPrice == 0) {
                info.previousPrice = info.currentPrice = tick.getBid();
            } else {
                info.currentPrice = tick.getBid();
                basket.check();
            }
        }
    }
    
    @Override
    public void onAccount(IAccount account) throws JFException {
    }

    //*************************************************************************************************************************************************************
    // Inner classes.
    //*************************************************************************************************************************************************************
    private class Basket {
        
        volatile Map<Instrument, InstrumentInfo> instruments = new TreeMap<Instrument, InstrumentInfo>();
        
        public Basket(Instrument... instruments) {
            for (Instrument instrument : instruments) {
                this.instruments.put(instrument, new InstrumentInfo(instrument));
            }
        }
  
        public void open() throws JFException {
            close();
            log("Opening orders for basket.", true);
            
            for (InstrumentInfo info : instruments.values()) {
                placeOrder(info);
            }
        }
        
        private void placeOrder(InstrumentInfo info) throws JFException {
            String label = info.instrument.name() + "_" + orderId++;
            
            double lotSize = baseLotSize * Math.pow(riskRatio + 1, lossCounter);
            if (lotSize < 0.001) {
                lotSize = 0.001;
            }
            
            OrderCommand orderCommand = OrderCommand.BUY;
            if (info.getPriceDiff() < 0) {
                orderCommand = OrderCommand.SELL;
            }
            
            IOrder order = context.getEngine().submitOrder(label, info.instrument, orderCommand, lotSize);
            order.waitForUpdate(State.FILLED);
            
            info.order = order;
            info.previousPrice = info.currentPrice;
        }
        
        public synchronized void check() throws JFException {
            double profitAmount = 0;
            double virtualProfit = 0;
            
            for (InstrumentInfo info : instruments.values()) {
                if (info.order != null && (State.OPENED.equals(info.order.getState()) || State.FILLED.equals(info.order.getState()))) {
                    profitAmount += (info.order.getProfitLossInUSD() - info.order.getCommission());
                } else {
                    virtualProfit += Math.abs(info.getPriceDiff() / baseLotSize);
                }
            }

            double targetAmount = baseLossAmount * Math.pow(riskRatio + 1, lossCounter);
            
            if (profitAmount == 0) {
                if (virtualProfit > targetAmount) {
                    open();
                }
            } else if (profitAmount > targetAmount) {
                updateLossFrequency();
                lossCounter = 0;
                wins++;
                open();
            } else if (profitAmount < (-1.0 * riskRatio * targetAmount)) {
                losses++;
                lossCounter++;
                updateLossFrequency();
                
                if (lossCounter > maxLossCounter) {
                    maxLossCounter = lossCounter;
                }
                
                if (lossCounter >= maxLossLimit) {
                    lossCounter = 0;
                }
                open();
            }
        }
        
        public void close() throws JFException {
            double profitLoss = 0;
            double profitLossPips = 0;
            double commission = 0;
            
            log("Closing basket.", true);
            
            for (InstrumentInfo info : instruments.values()) {
                IOrder order = info.order;
                if (order != null && (State.OPENED.equals(order.getState()) || State.FILLED.equals(order.getState()))) {
                    order.close();
                    order.waitForUpdate(State.CLOSED);
                    
                    profitLoss += order.getProfitLossInUSD();
                    profitLossPips += order.getProfitLossInPips();
                    commission += order.getCommission();
                    
                    info.order = null;
                }
            }
            
            if (profitLoss == 0) {
                return;
            }
            
            log("Profit/Loss: $" + round(profitLoss, 2) + " (" + round(profitLossPips, 1)  + ")");
            log("Total Trades: " + (wins + losses));
            
            if ((wins + losses) == 0) {
                log("Win%: 0.0% (0 wins, 0 losses)");
            } else {
                log("Win%: " + round(100.0 * wins / (wins + losses), 1) + "% (" + wins + " wins, " + losses + " losses)");
            }
            
            log("Comission: $" + round(commission, 2));
            log("Equity: $" + round(context.getAccount().getEquity(), 2));
            log("Loss Counter: " + lossCounter);
            log("Max Loss Counter: " + maxLossCounter);
            
            double equity = context.getAccount().getEquity();
            if (lowestEquity > equity) {
                lowestEquity = equity;
            }
        }
    }
    
    private class InstrumentInfo implements Comparable<InstrumentInfo> {
    
        volatile Instrument instrument;
        volatile double previousPrice;
        volatile double currentPrice;
        volatile IOrder order;
        
        public InstrumentInfo(Instrument instrument) {
            this.instrument = instrument;
        }    
        
        public double getPriceDiff() {
            if (previousPrice != 0) {
                return currentPrice - previousPrice;
            } else {
                return 0;
            }
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
