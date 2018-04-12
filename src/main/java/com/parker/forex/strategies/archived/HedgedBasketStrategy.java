package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

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

public class HedgedBasketStrategy implements IStrategy {

    //********************************************************************s*********************************************
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
    private boolean isOpen;

    private int checkHour = 8;
    private double baseLotSize = 0.01;
    
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

    private IOrder placeOrder(InstrumentInfo instrumentInfo, OrderCommand orderCommand) throws JFException {
        String label = orderCommand + "_" + instrumentInfo.instrument.name() + "_" + orderId++;
        IOrder order = context.getEngine().submitOrder(label, instrumentInfo.instrument, orderCommand, baseLotSize);
        order.waitForUpdate(State.FILLED);
        return order;
    }
    
    private void setupBaskets() {
        currentBaskets = new ArrayList<Basket>();
        
        currentBaskets.add(new Basket(OrderCommand.BUY,
                new InstrumentInfo(Instrument.GBPUSD),
                new InstrumentInfo(Instrument.EURGBP),
                new InstrumentInfo(Instrument.EURAUD),
                new InstrumentInfo(Instrument.AUDUSD),
                new InstrumentInfo(Instrument.GBPAUD)
        ));
        
        currentBaskets.add(new Basket(OrderCommand.SELL,
                new InstrumentInfo(Instrument.GBPUSD),
                new InstrumentInfo(Instrument.EURGBP),
                new InstrumentInfo(Instrument.EURAUD),
                new InstrumentInfo(Instrument.AUDUSD),
                new InstrumentInfo(Instrument.GBPAUD)
        ));
        
//        currentBaskets.add(new Basket(OrderCommand.SELL,
//                new InstrumentInfo(Instrument.GBPUSD),
//                new InstrumentInfo(Instrument.EURGBP),
//                new InstrumentInfo(Instrument.GBPJPY),
//                new InstrumentInfo(Instrument.USDCHF),
//                new InstrumentInfo(Instrument.NZDUSD),
//                new InstrumentInfo(Instrument.AUDJPY),
//                new InstrumentInfo(Instrument.EURJPY)
//        ));
    
//        currentBaskets.add(new Basket(OrderCommand.BUY,
//                new InstrumentInfo(Instrument.EURUSD),
//                new InstrumentInfo(Instrument.USDJPY),
//                new InstrumentInfo(Instrument.AUDUSD),
//                new InstrumentInfo(Instrument.NZDJPY),
//                new InstrumentInfo(Instrument.GBPCHF),
//                new InstrumentInfo(Instrument.CHFJPY),
//                new InstrumentInfo(Instrument.EURCHF)
//        ));
        
        log(currentBaskets.size() + " baskets have been initialized.");
    }
    
    private void processBaskets() throws JFException {
        log("Updating baskets...", true);
        double profitPips = 0;
        
        for (Basket basket : currentBaskets) {
            basket.update();
            basket.output();
            
            profitPips += basket.profitPips;
            
            if (!isOpen) {
                basket.open();
            }
        }
 
        log("Overall Profit Pips: " + round(profitPips, 1), true);
    }
    
    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    @Override
    public void onStart(IContext context) throws JFException {
        this.context = context;
        setupBaskets();
    }
    
    @Override
    public void onStop() throws JFException {
        currentTime = context.getHistory().getLastTick(Instrument.GBPUSD).getTime();
        for (Basket basket : currentBaskets) {
            basket.close();
        }
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
        }
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar bidBar, IBar askBar) throws JFException {
        if (Instrument.EURUSD.equals(instrument) && Period.ONE_HOUR.equals(period)) {
            currentTime = bidBar.getTime();
            
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
            calendar.setTimeInMillis(bidBar.getTime());
            
            if (Calendar.MONDAY <= calendar.get(Calendar.DAY_OF_WEEK) && Calendar.FRIDAY >= calendar.get(Calendar.DAY_OF_WEEK)
                    && calendar.get(Calendar.HOUR_OF_DAY) == checkHour) {
                processBaskets();
                isOpen = true;
            } else {
                //checkStop();
            }
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
        
        OrderCommand orderCommand;
        double profitPips;
        
        List<InstrumentInfo> instruments = new ArrayList<InstrumentInfo>();
        
        public Basket(OrderCommand orderCommand, InstrumentInfo... instruments) {
            this.orderCommand = orderCommand;
            this.instruments.addAll(Arrays.asList(instruments));
        }
        
        public void update() throws JFException {
            profitPips = 0;
            
            for (InstrumentInfo info : instruments) {
                ITick lastTick = context.getHistory().getLastTick(info.instrument);
                if (info.lastPrice == 0) {
                    info.lastPrice = lastTick.getBid();
                    info.profitPips = 0;
                } else {
                  double currentPrice = lastTick.getBid();
                  
                  if (OrderCommand.BUY.equals(orderCommand)) {
                      info.profitPips = (currentPrice - info.lastPrice) / info.instrument.getPipValue();
                  } else {
                      info.profitPips = (info.lastPrice - currentPrice) / info.instrument.getPipValue();
                  }
                  
                  info.lastPrice = currentPrice;
                  profitPips += info.profitPips;
                }
            }
            
            Collections.sort(instruments);
        }
        
        public void output() {
            log("Basket: " + orderCommand, true);
            
            double totalProfitPips = 0;
            for (InstrumentInfo info : instruments) {
                log(info.instrument.name() + " - " + orderCommand + ", Profit = " +  round(info.profitPips, 1) + " pips");
                totalProfitPips += info.profitPips;
            }
            log("Total Profit: " + round(totalProfitPips, 1) + " pips");
        }
        
        public void open() throws JFException {
            for (InstrumentInfo info : instruments) {
                info.order = placeOrder(info, orderCommand);
            }
        }
        
        public void close() throws JFException {
            int orderCount = 0;
            double profitLoss = 0;
            double profitLossPips = 0;
            double commission = 0;
            
            for (InstrumentInfo info : instruments) {
                IOrder order = info.order;
                if (order != null && (State.OPENED.equals(order.getState()) || State.FILLED.equals(order.getState()))) {
                    order.close();
                    order.waitForUpdate(State.CLOSED);
                    
                    profitLoss += order.getProfitLossInAccountCurrency();
                    profitLossPips += order.getProfitLossInPips();
                    commission += order.getCommission();
                    orderCount++;
                    
                    info.order = null;
                }
            }
            
            if (orderCount > 0) {
                log("Closing basket for: " + orderCommand, true);
                log("Profit/Loss: $" + round(profitLoss, 2) + " (" + round(profitLossPips, 1)  + ")");
                log("Comission: $" + round(commission, 2));
                log("Equity: $" + round(context.getAccount().getEquity(), 2));
            }
        }
        
        @Override
        public int compareTo(Basket basket) {
            return (int) (basket.profitPips - profitPips);
        }
    }
    
    private class InstrumentInfo implements Comparable<InstrumentInfo> {
    
        Instrument instrument;
        double lastPrice;
        double profitPips;
        IOrder order;
        
        public InstrumentInfo(Instrument instrument) {
            this.instrument = instrument;
        }     
        
        @Override
        public int compareTo(InstrumentInfo info) {
            return (int) (info.profitPips - this.profitPips);
        }
    }
}
