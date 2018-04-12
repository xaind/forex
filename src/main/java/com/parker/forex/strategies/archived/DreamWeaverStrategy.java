package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.INewsFilter.Currency;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;

public class DreamWeaverStrategy implements IStrategy {

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
    private long currentTime;
    private boolean initialized;
    
    private double martingaleFactor = 2;
    private double baseLotSize = 0.001;
    
    private double lowestEquity = Double.MAX_VALUE;
    
    private List<Basket> currentBaskets;
    
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

    private void setupBaskets() {
        currentBaskets = new ArrayList<Basket>();
        
        currentBaskets.add(new Basket(Currency.EUR,
                new InstrumentInfo(Instrument.EURGBP, OrderCommand.BUY),
                new InstrumentInfo(Instrument.EURJPY, OrderCommand.BUY),
                new InstrumentInfo(Instrument.EURUSD, OrderCommand.BUY),
                new InstrumentInfo(Instrument.EURAUD, OrderCommand.BUY),
                new InstrumentInfo(Instrument.EURCAD, OrderCommand.BUY),
                new InstrumentInfo(Instrument.EURNZD, OrderCommand.BUY),
                new InstrumentInfo(Instrument.EURCHF, OrderCommand.BUY)
        ));
        
        currentBaskets.add(new Basket(Currency.GBP,
                new InstrumentInfo(Instrument.EURGBP, OrderCommand.SELL),
                new InstrumentInfo(Instrument.GBPJPY, OrderCommand.BUY),
                new InstrumentInfo(Instrument.GBPUSD, OrderCommand.BUY),
                new InstrumentInfo(Instrument.GBPAUD, OrderCommand.BUY),
                new InstrumentInfo(Instrument.GBPCAD, OrderCommand.BUY),
                new InstrumentInfo(Instrument.GBPNZD, OrderCommand.BUY),
                new InstrumentInfo(Instrument.GBPCHF, OrderCommand.BUY)
        ));
        
        currentBaskets.add(new Basket(Currency.JPY,
                new InstrumentInfo(Instrument.EURJPY, OrderCommand.SELL),
                new InstrumentInfo(Instrument.GBPJPY, OrderCommand.SELL),
                new InstrumentInfo(Instrument.USDJPY, OrderCommand.SELL),
                new InstrumentInfo(Instrument.AUDJPY, OrderCommand.SELL),
                new InstrumentInfo(Instrument.CADJPY, OrderCommand.SELL),
                new InstrumentInfo(Instrument.NZDJPY, OrderCommand.SELL),
                new InstrumentInfo(Instrument.CHFJPY, OrderCommand.SELL)
        ));
        
        currentBaskets.add(new Basket(Currency.USD,
                new InstrumentInfo(Instrument.EURUSD, OrderCommand.SELL),
                new InstrumentInfo(Instrument.GBPUSD, OrderCommand.SELL),
                new InstrumentInfo(Instrument.USDJPY, OrderCommand.BUY),
                new InstrumentInfo(Instrument.USDCHF, OrderCommand.BUY),
                new InstrumentInfo(Instrument.USDCAD, OrderCommand.BUY),
                new InstrumentInfo(Instrument.NZDUSD, OrderCommand.SELL),
                new InstrumentInfo(Instrument.AUDUSD, OrderCommand.SELL)
        ));
        
        currentBaskets.add(new Basket(Currency.AUD,
                new InstrumentInfo(Instrument.AUDCHF, OrderCommand.BUY),
                new InstrumentInfo(Instrument.AUDJPY, OrderCommand.SELL),
                new InstrumentInfo(Instrument.AUDUSD, OrderCommand.BUY),
                new InstrumentInfo(Instrument.USDJPY, OrderCommand.BUY),
                new InstrumentInfo(Instrument.EURAUD, OrderCommand.SELL),
                new InstrumentInfo(Instrument.AUDCAD, OrderCommand.BUY),
                new InstrumentInfo(Instrument.AUDNZD, OrderCommand.BUY),
                new InstrumentInfo(Instrument.GBPAUD, OrderCommand.SELL)
        ));
        
        currentBaskets.add(new Basket(Currency.CHF,
                new InstrumentInfo(Instrument.AUDCHF, OrderCommand.SELL),
                new InstrumentInfo(Instrument.CHFJPY, OrderCommand.BUY),
                new InstrumentInfo(Instrument.EURCHF, OrderCommand.SELL),
                new InstrumentInfo(Instrument.GBPCHF, OrderCommand.SELL),
                new InstrumentInfo(Instrument.CADCHF, OrderCommand.SELL),
                new InstrumentInfo(Instrument.NZDCHF, OrderCommand.SELL),
                new InstrumentInfo(Instrument.USDCHF, OrderCommand.SELL)
        ));
        
        currentBaskets.add(new Basket(Currency.CAD,
                new InstrumentInfo(Instrument.AUDCAD, OrderCommand.SELL),
                new InstrumentInfo(Instrument.CADJPY, OrderCommand.BUY),
                new InstrumentInfo(Instrument.EURCAD, OrderCommand.SELL),
                new InstrumentInfo(Instrument.GBPCAD, OrderCommand.SELL),
                new InstrumentInfo(Instrument.NZDCAD, OrderCommand.SELL),
                new InstrumentInfo(Instrument.CADCHF, OrderCommand.BUY),
                new InstrumentInfo(Instrument.USDCAD, OrderCommand.SELL)
                ));
        
        currentBaskets.add(new Basket(Currency.NZD,
                new InstrumentInfo(Instrument.AUDNZD, OrderCommand.SELL),
                new InstrumentInfo(Instrument.NZDJPY, OrderCommand.BUY),
                new InstrumentInfo(Instrument.EURNZD, OrderCommand.SELL),
                new InstrumentInfo(Instrument.GBPNZD, OrderCommand.SELL),
                new InstrumentInfo(Instrument.NZDCAD, OrderCommand.BUY),
                new InstrumentInfo(Instrument.NZDCHF, OrderCommand.BUY),
                new InstrumentInfo(Instrument.NZDUSD, OrderCommand.BUY)
                ));
        
        log(currentBaskets.size() + " baskets have been initialized.");
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    @Override
    public void onStart(IContext context) throws JFException {
        this.context = context;
        log("Starting strategy DREAM_WEAVER.");        
        setupBaskets();
    }
    
    @Override
    public void onStop() throws JFException {
        for (Basket basket : currentBaskets) {
            basket.close();
        }
        
        log("Total Equity: $" + round(context.getAccount().getEquity(), 2));
        log("Lowest Equity: $" + round(lowestEquity, 2));
        
        log("Strategy DREAM_WEAVER stopped.");
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
        if (Instrument.EURUSD.equals(instrument) && Period.ONE_MIN.equals(period)) {            
            for (Basket basket : currentBaskets) {
                if (!initialized) {
                    basket.open();                   
                } else {
                    //basket.check();
                }
            }
            initialized = true;
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
    private class Basket implements Comparable<Basket> {
        
        Currency currency;
        double profitPips;
        double totalProfit;
        private int lossCounter;
        private int maxLossCounter;
        private OrderCommand orderCommand = OrderCommand.SELL;
        
        List<InstrumentInfo> instruments = new ArrayList<InstrumentInfo>();
        
        public Basket(Currency currency, InstrumentInfo... instruments) {
            this.currency = currency;
            this.instruments.addAll(Arrays.asList(instruments));
        }
        
        public void open() throws JFException {
        	close();
            log("Opening orders for basket: " + currency, true);
            
            for (InstrumentInfo info : instruments) {
                info.order = placeOrder(info);
            }
        }
        
        private IOrder placeOrder(InstrumentInfo instrumentInfo) throws JFException {
            String label = currency.name().substring(0, 3) + "_" + instrumentInfo.instrument.name() + "_" + orderId++;
            
            // Check if we need to reverse the order command
            OrderCommand executionCommand = instrumentInfo.orderCommand;
            if (OrderCommand.SELL.equals(orderCommand)) {
                executionCommand = OrderCommand.BUY.equals(instrumentInfo.orderCommand) ? OrderCommand.SELL : OrderCommand.BUY;
            }
            
            double lotSize = baseLotSize * Math.pow(martingaleFactor, lossCounter);
            if (lotSize < 0.001) {
                lotSize = 0.001;
            }
            
            IOrder order = context.getEngine().submitOrder(label, instrumentInfo.instrument, executionCommand, lotSize);
            order.waitForUpdate(State.FILLED);
            return order;
        }
        
        public void close() throws JFException {
            double profitLoss = 0;
            double profitLossPips = 0;
            double commission = 0;
            
            log("Closing basket for: " + currency, true);
            
            for (InstrumentInfo info : instruments) {
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
            
            log("Profit/Loss: $" + round(profitLoss, 2) + " (" + round(profitLossPips, 1)  + ")");
            log("Total Profit/Loss: $" + round(totalProfit, 2) + " (" + round(profitLossPips, 1)  + ")");
            log("Comission: $" + round(commission, 2));
            log("Equity: $" + round(context.getAccount().getEquity(), 2));
            log("Loss Counter: " + lossCounter);
            log("Max Loss Counter: " + maxLossCounter);
            
            double equity = context.getAccount().getEquity();
            if (lowestEquity > equity) {
                lowestEquity = equity;
            }
        }
        
        @Override
        public int compareTo(Basket basket) {
            return (int) (basket.profitPips - profitPips);
        }
    }
    
    private class InstrumentInfo implements Comparable<InstrumentInfo> {
    
        Instrument instrument;
        OrderCommand orderCommand;
        double profitPips;
        IOrder order;
        
        public InstrumentInfo(Instrument instrument, OrderCommand orderCommand) {
            this.instrument = instrument;
            this.orderCommand = orderCommand;
        }     
        
        @Override
        public int compareTo(InstrumentInfo info) {
            return (int) (info.profitPips - this.profitPips);
        }
    }
}
