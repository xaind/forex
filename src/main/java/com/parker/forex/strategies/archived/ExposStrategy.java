package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

public class ExposStrategy implements IStrategy {

    //*****************************************************************************************************************
    // Static Fields
    //*****************************************************************************************************************
    private static final DateFormat DF = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
    private static int groupCounter = 0;
    
    static {
        DF.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    //*****************************************************************************************************************
    // Instance Fields
    //*****************************************************************************************************************
    private IContext context;
	private double gridAmount;
	private GridGroup currentGroup; 
	private double initialEquity;
	private double maxDrawdown;
	
    private List<GridGroup> gridGroups = new ArrayList<GridGroup>();

    //*****************************************************************************************************************
    // Configurable Fields
    //*****************************************************************************************************************
    @Configurable(value = "Instrument")
    public Instrument instrument = Instrument.EURUSD;
    
    @Configurable(value = "Trade % (of current Equity)")
    public double tradePct = 0.1;
    
    @Configurable(value = "Grid Size (Pips)")
    public int gridSize = 5;

    //*****************************************************************************************************************
    // Private Methods
    //*****************************************************************************************************************
	private void log(String message) {
        context.getConsole().getInfo().println(message);
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
		this.initialEquity = context.getAccount().getEquity();
    }
    
	@Override
    public void onStop() throws JFException {
        // Collate the results
        double profitLoss = 0;
        double commission = 0;
        int orders = 0;
        
        for (GridGroup group : gridGroups) {
            profitLoss += group.getProfitLossInCurrency();
            commission += group.getCommission();
            orders += group.getOrders().size();
        }
        
        log("------------------------------------------------------------------------------------------------------------");                
        log("Profit/Loss: $" + round(profitLoss, 2));
        log("Comission: $" + round(commission, 2) + " ($" + round(commission / orders, 2) + "/order)");
        log("Equity: $" + round(context.getAccount().getEquity(), 2));
		log("Max Drawdown: $" + round(maxDrawdown, 2) + " (" + round(100.0 * maxDrawdown / initialEquity, 1) + "%)");
		log("Total Orders: " + orders + " (" + gridGroups.size() + " groups, " + round(1.0 * orders / gridGroups.size(), 1) + " orders/group)");
        log("------------------------------------------------------------------------------------------------------------");
    }

	@Override
    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (this.instrument.equals(instrument)) {
			if (currentGroup == null) {	
				log("------------------------------------------------------------------------------------------------------------");  
				log("Grid Group " + (++groupCounter) + " initialized...");
				currentGroup = new GridGroup(groupCounter);
				gridGroups.add(currentGroup);				             
			} else if (currentGroup.isProfitable(false)) {
				currentGroup.closeAllOrders();
				log("Grid Group closed. Profit=$" + round(currentGroup.getProfitLossInCurrency(), 2) + " (equity=$" + round(context.getAccount().getEquity(), 2) 
					+ ", comm=$" + round(currentGroup.getCommission(), 2) + ", orders=" + currentGroup.getOrders().size() + ")");	
				currentGroup = null;
			 
			//else if (currentGroup.isProfitable(true)) {
			//	currentGroup.setTrailingStop(tick.getBid(), tick.getAsk());
			//	log("Grid Group profitable. Setting trailing stops...");						
			} else if (tick.getAsk() >= (currentGroup.getLastBuy() + gridAmount)) {
				currentGroup.placeOrder(OrderCommand.BUY);
			} else if (tick.getBid() <= (currentGroup.getLastSell() - gridAmount)) {
				currentGroup.placeOrder(OrderCommand.SELL);
			}			
        } 
    }

    @Override
    public void onMessage(IMessage message) throws JFException {
		if (message.getOrder().getInstrument().equals(instrument)) {			 
            if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
				IOrder order = message.getOrder();  
                log("Filled order " + order.getLabel() + " @ " + order.getOpenPrice() + " (equity=$" + round(context.getAccount().getEquity(), 2) + ")");                              
				//DF.format(new Date(order.getFillTime())) 
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
        private double lotSize;  
		
		private List<IOrder> orders = new ArrayList<IOrder>();  

        public GridGroup(int id) throws JFException {
            this.id = id;            			
			
			double equity = context.getAccount().getEquity();			
			this.lotSize = (equity * tradePct / 100.0) * instrument.getPipValue() * gridSize;
			
			if (this.lotSize < 0.001) {
				this.lotSize = 0.001;
			}			
			this.lotSize = round(this.lotSize, 3);
			
			// Place initial orders
			placeOrder(OrderCommand.BUY);
			placeOrder(OrderCommand.SELL);
        }      

		public void placeOrder(OrderCommand orderCommand) throws JFException {
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
        
		public boolean isProfitable(boolean isTrailingStop) {
            double profitLossPips = 0;
			int profitableOrders = 0;

            for (IOrder order : getOrders()) {
                profitLossPips += order.getProfitLossInPips();
				profitableOrders++;
            }
			
			if (isTrailingStop) {
				return profitLossPips >= gridSize + (1.0 * gridSize / profitableOrders);
			} else {
				return profitLossPips >= gridSize;
			}
		}

		public double getProfitLossInCurrency() {
            double profitLossPips = 0;
            for (IOrder order : getOrders()) {
                profitLossPips += order.getProfitLossInAccountCurrency();
            }
            return profitLossPips;
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
