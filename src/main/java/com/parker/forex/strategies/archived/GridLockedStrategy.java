package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
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

public class GridLockedStrategy implements IStrategy {

    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final DateFormat DF = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
    private static int groupCounter = 0;
    
    static {
        DF.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    enum Direction {UP, DOWN;}
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IContext context;
    private Instrument instrument = Instrument.EURUSD;
    private double tradePct = 2;
    private int gridSize = 10;
    private Period period = Period.ONE_HOUR;
    
    private int startHour = 21;
    
    private GridGroup gridUp;
    private GridGroup gridDown;
    
    private List<GridGroup> gridGroups = new ArrayList<GridGroup>();

    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    } 
    
    private double getLotSize() {
        double equity = context.getAccount().getEquity();
        double lotSize = equity * tradePct / 100.0 * instrument.getPipValue() * gridSize;
        
        if (lotSize < 0.001) {
            lotSize = 0.001;
        }
        
        return round(lotSize, 3);
    }
    
    private IOrder placeOrder(String label, OrderCommand command, double lotSize, double price, double stopLoss, double takeProfit) throws JFException {
        //context.getConsole().getOut().println(label + ": Open=" + price + ", SL=" + stopLoss + ", TP=" + takeProfit);
        
        IOrder order = context.getEngine().submitOrder(label, instrument, command, lotSize, round(price, instrument.getPipScale()), 0, round(stopLoss, instrument.getPipScale()), round(takeProfit, instrument.getPipScale()));
        order.waitForUpdate(State.OPENED, State.FILLED);
        return order;
    }
    
    @Override
    public void onMessage(IMessage message) throws JFException {
        IOrder order = message.getOrder();
        
        if (State.OPENED.equals(order.getState())) {
            context.getConsole().getInfo().println(DF.format(new Date(order.getCreationTime())) + ": " + order.getLabel() + " - Opened " + order.getOrderCommand() + " @ $" + order.getOpenPrice() 
                    + " [SL=$" + order.getStopLossPrice() + ",TP=$" + order.getTakeProfitPrice() + ", amount=" + order.getAmount() + "]");
            
        } else if (State.FILLED.equals(order.getState())) {
            context.getConsole().getInfo().println(DF.format(new Date(order.getFillTime())) + ": " + order.getLabel() + " - Filled " + order.getOrderCommand() + " @ $" + order.getOpenPrice() 
                    + " [SL=$" + order.getStopLossPrice() + ",TP=$" + order.getTakeProfitPrice() + ", amount=" + order.getAmount() + "]");
            
            if (gridUp != null && gridUp.hasOrder(order) && !gridUp.isArmed()) {
                gridUp.setArmed(true);
                gridUp.initializeOrders();
                gridDown.closeAllOrders();
                gridGroups.add(gridUp);
            }
            
            if (gridDown != null && gridDown.hasOrder(order) && !gridDown.isArmed()) {
                gridDown.setArmed(true);
                gridDown.initializeOrders();
                gridUp.closeAllOrders();
                gridGroups.add(gridDown);
            }
        } else if (State.CANCELED.equals(order.getState())) {
            context.getConsole().getInfo().println(DF.format(new Date(order.getCloseTime())) + ": " + order.getLabel() + " - Cancelled " + order.getOrderCommand() + " @ $" + order.getOpenPrice() 
                    + " [SL=$" + order.getStopLossPrice() + ",TP=$" + order.getTakeProfitPrice() + ", amount=" + order.getAmount() + "]");
            
        } else if (State.CLOSED.equals(order.getState())) {
            context.getConsole().getInfo().println(DF.format(new Date(order.getCloseTime())) + ": " + order.getLabel() + " - Closed " + order.getOrderCommand() + " @ $" + order.getClosePrice() 
                    + " [Open=$" + order.getOpenPrice() + ",SL=$" + order.getStopLossPrice() + ",TP=$" + order.getTakeProfitPrice() + ",Profit=$" + order.getProfitLossInAccountCurrency() 
                    + ", Comm=$" + order.getCommission() + ", lots=" + order.getAmount() + "]");
            
            GridGroup gridGroup = gridUp.isArmed() ? gridUp : gridDown;
                
            if (gridGroup != null) {
                if (gridGroup.isWinner(order)) {
                    context.getConsole().getInfo().println("*************************** WINNER *************************** " + order.getLabel() +  " $" + gridGroup.getProfitLoss() + ", Comm=$" + gridGroup.getCommission());
                    gridGroup.closeAllOrders();
                } else if (gridGroup.isLoser(order)) {
                    context.getConsole().getInfo().println(order.getLabel() + "*************************** FAILURE *************************** " + order.getLabel() + " $" + gridGroup.getProfitLoss() + ", Comm=$" + gridGroup.getCommission());
                    gridGroup.closeAllOrders();
                }
            }
        }
    }

    @Override
    public void onStart(IContext context) throws JFException {
        this.context = context;
    }
    
    @Override
    public void onBar(Instrument instrument, Period period, IBar bidBar, IBar askBar) throws JFException {
        if (this.instrument.equals(instrument) && this.period.equals(period)) {
            
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
            calendar.setTimeInMillis(askBar.getTime());
            int dow = calendar.get(Calendar.DAY_OF_WEEK);
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            
            if (Calendar.SATURDAY != dow && Calendar.SUNDAY != dow && groupCounter < 100) {
                if (hour == startHour) {
                    double price = bidBar.getClose();
                    double lotSize = getLotSize();
                    
                    double highPrice = BigDecimal.valueOf(price + (3 * instrument.getPipValue())).setScale(3, RoundingMode.CEILING).doubleValue();
                    double lowPrice = BigDecimal.valueOf(price - (3 * instrument.getPipValue())).setScale(3, RoundingMode.FLOOR).doubleValue();
                    
                    context.getConsole().getInfo().println("--------------------------------------------------------------------------------------------------");
                    
                    gridUp = new GridGroup(++groupCounter, highPrice, lotSize, Direction.UP);
                    gridDown = new GridGroup(++groupCounter, lowPrice, lotSize, Direction.DOWN);
                }
            }
        }
    }
    
    @Override
    public void onStop() throws JFException {
        // Collate the results
        int wins = 0;
        int losses = 0;
        double profitLoss = 0;
        double commission = 0;
        int ordersFilled = 0;
        
        for (GridGroup group : gridGroups) {
            double value = group.getProfitLoss();
            
            if (value > 0) {
                wins++;
            } else {
                losses++;
            }
            
            profitLoss += value;
            commission += group.getCommission();
            ordersFilled += group.getOrdersFilled();
        }
        
        context.getConsole().getInfo().println("------------------------------------------------------------------------------------------------------------");
        context.getConsole().getInfo().println("Total Groups: " + (wins + losses) + " (" + wins + " wins, " + losses + " losses, " + (wins * 100.0 / (wins + losses)) + "%)");
        context.getConsole().getInfo().println("Total Orders Filled: " + ordersFilled);
        context.getConsole().getInfo().println("Profit/Loss: $" + profitLoss);
        context.getConsole().getInfo().println("Comission: $" + commission + " ($" + round(commission / ordersFilled, 2) + "/order)");
        context.getConsole().getInfo().println("Equity: $" + context.getAccount().getEquity());
        context.getConsole().getInfo().println("------------------------------------------------------------------------------------------------------------");
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
    private class GridGroup {
    
        private int id;
        private double lotSize;
        private double startPrice;
        private Direction direction;
        private boolean armed;
        
        private IOrder long0;
        private IOrder short0;
        
        private IOrder long1;
        private IOrder short1;
        private IOrder long2;
        private IOrder short2;

        public GridGroup(int id, double startPrice, double lotSize, Direction direction) throws JFException {
            this.id = id;
            this.startPrice = startPrice;
            this.lotSize = lotSize;
            this.direction = direction;
            
            String prefix = "X" + id + "_" + direction + "_";
            
            double priceUp1 = round(startPrice + (gridSize * instrument.getPipValue()), 4);
            double priceUp3 = round(startPrice + (gridSize * 3 * instrument.getPipValue()), 4);
            double priceDown1 = round(startPrice - (gridSize * instrument.getPipValue()), 4);
            double priceDown3 = round(startPrice - (gridSize * 3 * instrument.getPipValue()), 4);
            
            OrderCommand buyCommand = Direction.UP.equals(direction) ? OrderCommand.BUYSTOP_BYBID : OrderCommand.BUYLIMIT_BYBID;
            OrderCommand sellCommand = Direction.UP.equals(direction) ? OrderCommand.SELLLIMIT : OrderCommand.SELLSTOP;
            
            long0 = placeOrder(prefix + "LONG_0", buyCommand, lotSize, startPrice, priceDown3, priceUp1);
            short0 = placeOrder(prefix + "SHORT_0", sellCommand, lotSize, startPrice, priceUp3, priceDown1);
        }
        
        public void setArmed(boolean armed) {
            this.armed = armed;
        }
        
        public boolean isArmed() {
            return armed;
        }

        public void initializeOrders() throws JFException  {
            this.armed = true;
            String prefix = "X" + id + "_" + direction + "_";

            if (Direction.UP.equals(direction)) {
                double priceUp1 = round(startPrice + (gridSize * instrument.getPipValue()), 4);
                double priceUp2 = round(startPrice + (gridSize * 2 * instrument.getPipValue()), 4);
                double priceUp3 = round(startPrice + (gridSize * 3 * instrument.getPipValue()), 4);
                
                long1 = placeOrder(prefix + "LONG_1", OrderCommand.BUYSTOP_BYBID, lotSize, priceUp1, startPrice, priceUp2);
                short1 = placeOrder(prefix + "SHORT_1", OrderCommand.SELLLIMIT, lotSize, priceUp1, priceUp3, startPrice);
                
                long2 = placeOrder(prefix + "LONG_2", OrderCommand.BUYSTOP_BYBID, lotSize, priceUp2, priceUp1, priceUp3);
                short2 = placeOrder(prefix + "SHORT_2", OrderCommand.SELLLIMIT, lotSize, priceUp2, priceUp3, priceUp1);
                
            } else {
                double priceDown1 = round(startPrice - (gridSize * instrument.getPipValue()), 4);
                double priceDown2 = round(startPrice - (gridSize * 2 * instrument.getPipValue()), 4);
                double priceDown3 = round(startPrice - (gridSize * 3 * instrument.getPipValue()), 4);
                
                short1 = placeOrder(prefix + "SHORT_1", OrderCommand.SELLSTOP, lotSize, priceDown1, startPrice, priceDown2);
                long1 = placeOrder(prefix + "LONG_1", OrderCommand.BUYLIMIT_BYBID, lotSize, priceDown1, priceDown3, startPrice);
                
                short2 = placeOrder(prefix + "SHORT_2", OrderCommand.SELLSTOP, lotSize, priceDown2, priceDown1, priceDown3);
                long2 = placeOrder(prefix + "LONG_2", OrderCommand.BUYLIMIT_BYBID, lotSize, priceDown2, priceDown3, priceDown1);
            }
        }
        
        public boolean hasOrder(IOrder order) {
            return getOrders().contains(order);
        }
        
        public boolean isWinner(IOrder order) {
            return order.getProfitLossInPips() > 0 && ((Direction.UP.equals(direction) && (order.equals(short1) || order.equals(short2))) 
                    || (Direction.DOWN.equals(direction) && (order.equals(long1) || order.equals(long2))));
        }
        
        public boolean isLoser(IOrder order) {
            return order.getProfitLossInPips() > 0 && ((Direction.UP.equals(direction) && order.equals(long2)) 
                    || (Direction.DOWN.equals(direction) && order.equals(short2)));
        }
        
        public List<IOrder> getOrders() {
            List<IOrder> orders = new ArrayList<IOrder>();
            orders.add(long0);
            orders.add(long1);
            orders.add(long2);
            orders.add(short0);
            orders.add(short1);
            orders.add(short2);
            
            for (Iterator<IOrder> iterator = orders.iterator(); iterator.hasNext();) {
                if (iterator.next() == null) {
                    iterator.remove();
                }
            }
            
            return orders;
        }
        
        public double getProfitLoss() {
            double profitLoss = 0;
            for (IOrder order : getOrders()) {
                profitLoss += order.getProfitLossInAccountCurrency();
            }
            return profitLoss;
        }
        
        public double getCommission() {
            double commission = 0;
            for (IOrder order : getOrders()) {
                commission += order.getCommission();
            }
            return commission;
        }
        
        public int getOrdersFilled() {
            int ordersFilled = 0;
            for (IOrder order : getOrders()) {
                if (order.getFillTime() > 0) {
                    ordersFilled++;
                }
            }
            return ordersFilled;
        }
        
        public void closeAllOrders() throws JFException {
            for (IOrder order : getOrders()) {
                if (!State.CLOSED.equals(order.getState()) && !State.CANCELED.equals(order.getState())) {
                    order.close();
                }
            }
        }
    }
}
