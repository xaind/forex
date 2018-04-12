package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.Filter;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IHistory;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.OfferSide;
import com.dukascopy.api.Period;

public class ThePipStarStrategy implements IStrategy {

	private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
	
	private static final String STRATEGY_NAME = "THE_PIPSTAR";
	private static final long MILLIS_IN_DAY = 1000 * 60 * 60 * 24;
	private static final long MILLIS_IN_WEEK = MILLIS_IN_DAY * 7;
	
	static {
		DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
	}

	// *****************************************************************************************************************
	// Instance Fields
	// *****************************************************************************************************************
	private IContext context;
	private int orderCounter;
		
	private int takeProfitPips;
	private int round;		
	private int winCounter;
	private int lossCounter;
	
	private int pips;
	private double profit;
	private double commission;
	
	private boolean lastLoss;
	private double maxDrawdown;
	private double currentDrawdown;
	
	private double openingEquity;
	private long startTime;
	
	private Map<Integer, List<IOrder>> orderMap = new HashMap<>();
	
	@Configurable("Instrument")
	public Instrument instrument = Instrument.AUDUSD;
	
	@Configurable("% of Equity per Trade")
	public double tradePct = 0.5;
	
	@Configurable("Risk Reward Ratio")
	public double riskReward = 4.0;
	
	@Configurable("Maximum Trades per Round")
	public int maxTrades = 10;
	
	@Configurable("Range Multiplier")
	public double adrMultiplier = 2.0;
	
	// *****************************************************************************************************************
	// Private Methods
	// *****************************************************************************************************************
	private void log(String message) {
		log(message, 0);
	}

	private void log(String message, long time) {
		String date = "";
		if (time != 0) {
			date = DATE_FORMAT_LONG.format(new Date(time)) + " ";
		}
		context.getConsole().getOut().println(date + message);
	}

	private double round(double value, int precision) {
		return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
	}

	private synchronized void closeOrders() throws JFException {
		for (IOrder order : context.getEngine().getOrders(instrument)) {
			if (State.OPENED.equals(order.getState())) {
				order.close();
			}
		}
	}
	
	private double getLotSize() throws JFException {
		double tradeAmount = context.getAccount().getBalance() * tradePct / 100.0;
		double lotSize = tradeAmount / (takeProfitPips * 100.0);
		
		if (lotSize < 0.001) {
			lotSize = 0.001;
		}
		
		return round(lotSize, 3); 
	}
	
	private synchronized void placeOrder(OrderCommand orderCommand, double price) throws JFException {
		String label = STRATEGY_NAME + "_" + instrument.name() + "_" + (++orderCounter) + "_" + round;
		price = round(price, instrument.getPipScale());
		IOrder order = context.getEngine().submitOrder(label, instrument, orderCommand, getLotSize(), price, 0);
		orderMap.get(round).add(order);
	}

	private void onOrderCancelled(IMessage message) throws JFException {
		IOrder order = message.getOrder();
		
		String label = order.getLabel();
		int orderRound = Integer.valueOf(label.substring(label.lastIndexOf("_") + 1));
		List<IOrder> orders = orderMap.get(orderRound);
		
		log("Order " + instrument + " " + order.getOrderCommand() + " " + orderRound + "/" + orders.indexOf(order) + " " + order.getState() + ".");
	}

	private synchronized void onOrderFilled(IOrder order) throws JFException {
		// Set the take profit and stop loss prices
		double openPrice = order.getOpenPrice();
		double margin = instrument.getPipValue() * takeProfitPips;
		int negator = order.isLong() ? 1 : -1;

		order.setTakeProfitPrice(round(openPrice + (negator * margin), instrument.getPipScale()));
		order.setStopLossPrice(round(openPrice - (negator * margin * riskReward), instrument.getPipScale()));
	}

	private synchronized void onOrderClosed(IOrder order) throws JFException {		
		profit += order.getProfitLossInUSD();
		commission += order.getCommissionInUSD();

		OrderCommand orderCommand = order.getOrderCommand();
		pips += order.getProfitLossInPips();
		
		if (order.getProfitLossInPips() >= 0) {
			winCounter++;			
			lastLoss = false;
			if (currentDrawdown > maxDrawdown) {
				maxDrawdown = currentDrawdown;
				currentDrawdown = 0;
			}
		} else {
			lossCounter++;			
			orderCommand = OrderCommand.SELL.equals(orderCommand) ? OrderCommand.BUY : OrderCommand.SELL;
			
			if (lastLoss) {
				currentDrawdown += Math.abs(order.getProfitLossInUSD());
			} else {
				currentDrawdown = Math.abs(order.getProfitLossInUSD());
				lastLoss = true;
			}
		}

		String label = order.getLabel();
		int orderRound = Integer.valueOf(label.substring(label.lastIndexOf("_") + 1));
		List<IOrder> orders = orderMap.get(orderRound);
		
		log("Closed " + instrument + " " + order.getOrderCommand() + " order " + orderRound + "/" + orders.indexOf(order) + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") "
				+ (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [open=$" + round(order.getOpenPrice(), instrument.getPipScale()) 
				+ ", close=$" + round(order.getClosePrice(), instrument.getPipScale()) + ", equity=$" + context.getAccount().getEquity() 
				+ ", comm=$" + order.getCommissionInUSD() + ", lots=" + round(order.getAmount(), 3) + "]", order.getCloseTime());
		
		
		if (stopRound()) {
			startRound();
		} else if (round == orderRound) {
			placeOrder(orderCommand, 0);
		}
	}
	
	private synchronized void startRound() throws JFException {
		round++;
		orderMap.put(round, new ArrayList<IOrder>());
		
		closeOrders();
		setTakeProfit();
		
		double price = context.getHistory().getLastTick(instrument).getAsk();
		
		log("-- " + instrument + " ROUND " + round + " (basePrice=$" + round(price, instrument.getPipScale()) + ", tp=" 
				+ takeProfitPips + " pips) ------------------------------------------------------------------------------------------------------");
		
		double stepValue = getStepValue();
		
		for (int i = 0; i < (maxTrades / 2); i++) {
			placeOrder(OrderCommand.BUYSTOP, price + (i * stepValue));
			placeOrder(OrderCommand.SELLSTOP, price - (i * stepValue));
		}
	}
	
	private double getStepValue() {
		return instrument.getPipValue() * (takeProfitPips / (maxTrades / 2));
	}

	private void setTakeProfit() throws JFException {
		IHistory history = context.getHistory();
		
		long time = history.getStartTimeOfCurrentBar(instrument, Period.DAILY);
		List<IBar> bars = history.getBars(instrument, Period.DAILY, OfferSide.ASK, Filter.WEEKENDS, 60, time, 0);
		
		double totalPips = 0;
		for (IBar bar : bars) {
			totalPips += (bar.getHigh() - bar.getLow()) / instrument.getPipValue();
		}
		
		takeProfitPips = (int) (totalPips / bars.size() * adrMultiplier);
	}

	private synchronized boolean stopRound() {
		List<double[]> pricePoints = new ArrayList<>();
				
		List<IOrder> orders = orderMap.get(round);
		orderLoop: for (IOrder order : orders) {
			if (State.CLOSED.equals(order.getState())) {
				double close = order.getClosePrice();
				
				for (double[] pricePoint : pricePoints) {
					if (pricePoint[0] <= close && pricePoint[1] >= close) {
						pricePoint[2] = pricePoint[2] + 1;
						continue orderLoop;
					} 
				}
				
				pricePoints.add(new double[]{close - instrument.getPipValue(), close + instrument.getPipValue(), 1});
			}
		}
		
		int coincidentOrders = 0;
		for (double[] pricePoint : pricePoints) {
			if (pricePoint[2] >= 2) {
				coincidentOrders += 2;
			}
		}

		// When 4 or more orders are coincident start a new round
		if (coincidentOrders >= 4) {
			return true;
		} else {
			return false;
		}
	}
		
	// *****************************************************************************************************************
	// Public Methods - Implementation of the IStrategy interface
	// *****************************************************************************************************************
	public void onStart(IContext context) throws JFException {
		this.context = context;
		log("Started the " + STRATEGY_NAME + " strategy.");
		
		openingEquity = context.getAccount().getBalance();
		startTime = context.getHistory().getTimeOfLastTick(instrument);
		
		startRound();
	}

	public void onStop() throws JFException {
		long endTime = context.getHistory().getTimeOfLastTick(instrument);
		int trades = winCounter + lossCounter;
		log("Trades: " + trades + " (" + round(1.0 * trades / ((endTime - startTime) / MILLIS_IN_WEEK), 1) + "/week)");
		
		if (trades == 0) {
			lossCounter = 1;
		}
		
		double netProfit = profit - commission;
		double paRoi = (netProfit / openingEquity) / ((endTime - startTime) / MILLIS_IN_DAY) * 36500.0;
		
		log("Wins: " + winCounter + " (" + round(100.0 * winCounter / trades, 1) + "%)");
		log("Profit: $" + round(netProfit, 2) + " (pips=" + pips + ", openingBalance=$" + round(openingEquity, 2) + ", " + round(paRoi, 1) + "% pa)");
		log("Commission: $" + round(commission, 2) + " (" + round(commission / profit * 100.0, 2) + "% of profit)");
		log("Drawdown: $" + round(maxDrawdown, 2) + " (" + round(maxDrawdown / openingEquity * 100.0, 2) + "% of opening balance)");
		log("-----------------------------------------------------------------------------");		
		
		log(STRATEGY_NAME + " strategy stopped.");
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

	public void onTick(Instrument instrument, ITick tick) throws JFException {
	}
	
	public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
	}

	public void onAccount(IAccount account) throws JFException {
	}
}
