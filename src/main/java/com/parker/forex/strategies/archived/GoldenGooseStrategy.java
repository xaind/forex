package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
import com.dukascopy.api.Period;

public class GoldenGooseStrategy implements IStrategy {

    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final DateFormat DF = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
	private static final long MILLIS_IN_HOUR = 1000 * 3600;
    private static int groupCounter = 0;
    
    static {
        DF.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IContext context;
    private double gridAmount;
    private double startingPrice;
    private GridGroup currentGroup; 
    private double maxDrawdown;
	private double initialEquity;
	private int failureCount;
    
    private List<GridGroup> gridGroups = new ArrayList<GridGroup>();

    //*****************************************************************************************************************
    // Configurable Fields
    //*****************************************************************************************************************
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.USDJPY;
    
    @Configurable(value = "Grid Size (Pips)")
    public int gridSize = 50;

    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
    private void log(String message, long time) {
    	String date = "";
    	if (time != 0) {
    		date = DF.format(new Date(time));
    	}
        context.getConsole().getInfo().println(date + " - " + message);
    }
    
    private void log(String message) {
    	log(message, 0);
    }

    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    } 

    //*****************************************************************************************************************
    // Public Methods
    //*****************************************************************************************************************
    @Override
    public void onStart(IContext context) throws JFException {
        this.context = context;
        this.gridAmount = gridSize * instrument.getPipValue();
    }
    
    @Override
    public void onStop() throws JFException {
        if (currentGroup != null) {
            currentGroup.closeAllOrders();
        }
        
        // Collate the results
        double profitLoss = 0;
        double commission = 0;
        int orders = 0;
		long duration = 0;
        
        for (GridGroup group : gridGroups) {
            profitLoss += group.getProfitLossInCurrency();
            commission += group.getCommission();
            orders += group.getOrders().size();
			duration += group.getDuration();
        }
        
        log("------------------------------------------------------------------------------------------------------------");                
        log("Profit/Loss: $" + round(profitLoss, 2));
        log("Comission: $" + round(commission, 2) + " ($" + round(commission / orders, 2) + "/order)");
        log("Equity: $" + round(context.getAccount().getEquity(), 2));
        log("Max Drawdown: $" + round(maxDrawdown, 2) + " (" + round(100.0 * maxDrawdown / initialEquity, 1) + "%)");
        log("Total Orders: " + orders + " (" + gridGroups.size() + " groups, " + round(1.0 * orders / gridGroups.size(), 1) + " orders/group)");
		log("Avg Group Duration: " + round(1.0 * duration / gridGroups.size() / MILLIS_IN_HOUR, 2)  + " hours");
		log("Failure Count: " + failureCount);
        log("------------------------------------------------------------------------------------------------------------");
    }

    @Override
    public void onTick(Instrument instrument, ITick tick) throws JFException {
		if (initialEquity == 0) {
			initialEquity = context.getAccount().getEquity();
		}
		
        if (this.instrument.equals(instrument)) {
        	if (startingPrice == 0) {
    			startingPrice = tick.getBid();
        	} else if (currentGroup == null) {
        		if (Math.abs(tick.getBid() - startingPrice) > instrument.getPipValue() * gridSize) {    
	                log("------------------------------------------------------------------------------------------------------------");  
	                log("Grid Group " + (++groupCounter) + " initialized...", tick.getTime());
	                
	                OrderCommand orderCommand = OrderCommand.BUY;
	                if (tick.getBid() - startingPrice < 0) {
	                	orderCommand = OrderCommand.SELL;
	                }
	                currentGroup = new GridGroup(groupCounter, tick.getTime(), orderCommand);
	                gridGroups.add(currentGroup);  
	                startingPrice = tick.getBid();
        		}
            } else if (currentGroup.isDone()) {
                currentGroup.closeAllOrders();
				currentGroup.setEndTime(tick.getTime());

				double profitLoss = currentGroup.getProfitLossInCurrency();				
				if (profitLoss < 0) {
					failureCount++;
				}

                log("Grid Group closed.", tick.getTime());
                log("Profit=$" + round(profitLoss, 2) + " (equity=$" + round(context.getAccount().getEquity(), 2) 
                    + ", comm=$" + round(currentGroup.getCommission(), 2) + ", orders=" + currentGroup.getOrders().size() + ")", tick.getTime());  
				log("Duration: " + round(1.0 * currentGroup.getDuration() / MILLIS_IN_HOUR, 2) + " hours (" 
					+ round(1.0 * currentGroup.getDuration() / currentGroup.getOrders().size() / MILLIS_IN_HOUR, 2) + " hours/order)", tick.getTime());

                currentGroup = null; 
                
                if (tick.getBid() > startingPrice) {
                	startingPrice = tick.getBid() - (instrument.getPipValue() * gridSize * 10);
                } else {
                	startingPrice = tick.getBid() + (instrument.getPipValue() * gridSize * 10);
                }
//            } else if (currentGroup.getLastOrder() != null && Math.abs(currentGroup.getLastOrder().getOpenPrice() - tick.getBid()) > (instrument.getPipValue() * gridSize)) {
//            	IOrder lastOrder = currentGroup.getLastOrder();
//            	
//            	if (lastOrder.getOpenPrice() < tick.getBid()) {
//            		currentGroup.placeOrder(OrderCommand.BUY);
//            	} else {
//            		currentGroup.placeOrder(OrderCommand.SELL);
//            	}
//            }
            } else if (currentGroup.getLastBuy() != 0 && tick.getAsk() >= (currentGroup.getLastBuy() + gridAmount)) {
                currentGroup.placeOrder(OrderCommand.BUY);
            } else if (currentGroup.getLastSell() != Double.MAX_VALUE && tick.getBid() <= (currentGroup.getLastSell() - gridAmount)) {
                currentGroup.placeOrder(OrderCommand.SELL);
            }            
        } 
    }

    @Override
    public void onMessage(IMessage message) throws JFException {
        if (message.getOrder().getInstrument().equals(instrument)) {             
            if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
                IOrder order = message.getOrder();  
                log("Filled order " + order.getLabel() + " @ " + order.getOpenPrice() + " (equity=$" + round(context.getAccount().getEquity(), 2) 
                        + ", lotSize=" + order.getAmount() + ")", order.getFillTime());     
            } else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType()) && currentGroup != null && !currentGroup.hasOpenOrders()) {                
                currentGroup = null;                
            }
        }
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar bidBar, IBar askBar) throws JFException {
        if (this.instrument.equals(instrument) && Period.ONE_HOUR.equals(period)) {
            double equity = context.getAccount().getEquity();
            if (equity < initialEquity && (initialEquity - equity) > maxDrawdown) {
                maxDrawdown = initialEquity - equity;
            }
        }
    }

    @Override
    public void onAccount(IAccount account) throws JFException {
    }

    //*************************************************************************************************************************************************************
    // Inner classes.
    //*************************************************************************************************************************************************************
    private class GridGroup {
    
        private int id;
        private double initialLotSize = 0.01;  
        private long startTime;
		private long endTime;
        
        private List<IOrder> orders = new ArrayList<IOrder>();  

        public GridGroup(int id, long startTime, OrderCommand orderCommand) throws JFException {
            this.id = id;                      
            this.startTime = startTime;
            
            // Place initial order
            placeOrder(orderCommand);           
        }      

        public long getDuration() {
            return endTime - startTime;
        }

		public void setEndTime(long endTime) {
			this.endTime = endTime;
		}

        public void placeOrder(OrderCommand orderCommand) throws JFException {
			double lotSize = initialLotSize;	
			
//			if (!orders.isEmpty()) {				
//				int orderSize = orders.size();
//				if (orderSize == 1) {
//					lotSize = 4 * initialLotSize;
//				} else if (orderSize == 2) {
//					lotSize = 15 * initialLotSize;
//				} else if (orderSize == 3) {
//					lotSize = 72 * initialLotSize;
//				} else if (orderSize >= 4) {
//					lotSize = 419 * initialLotSize;
//				} 
//			}

            String label = orderCommand.toString() + orders.size() + "_" + id;
            IOrder order = context.getEngine().submitOrder(label, instrument, orderCommand, lotSize);
            order.waitForUpdate(State.FILLED);
            orders.add(order);
        }

        public double getLastBuy() {
            double price = 0;
            for (IOrder order : orders) {
                if (order.getOpenPrice() > price) {
                    price = order.getOpenPrice();
                }
            }
            return price;
        }

        public double getLastSell() {
            double price = Double.MAX_VALUE;
            for (IOrder order : orders) {
                if (order.getOpenPrice() < price) {
                    price = order.getOpenPrice();
                }
            }
            return price;
        }

        public List<IOrder> getOrders() {
            return orders;
        }
        
        public boolean isDone() {           
            double profitLoss = getProfitLossInCurrency();      
			return profitLoss >= 5 || profitLoss < -10;			
        }

        public double getProfitLossInCurrency() {
            double profitLoss = 0;
            double commission = 0;
            
            for (IOrder order : getOrders()) {
                profitLoss += order.getProfitLossInAccountCurrency();
                commission += order.getCommission();
            }
            return (profitLoss - commission);
        }

        public double getCommission() {
            double commission = 0;
            for (IOrder order : getOrders()) {
                commission += order.getCommission();
            }
            return commission;
        }

        public boolean hasOpenOrders() {
            for (IOrder order : getOrders()) {
                if (!State.CLOSED.equals(order.getState()) && !State.CANCELED.equals(order.getState())) {
                    return true;
                }
            }
            return false;
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
