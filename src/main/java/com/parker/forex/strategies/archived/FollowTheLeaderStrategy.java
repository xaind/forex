package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
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

public class FollowTheLeaderStrategy implements IStrategy {

	private static final String STRATEGY_NAME = "FOLLOW_THE_LEADER";

	private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

	static {
		DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
	}

	// *****************************************************************************************************************
	// Instance Fields
	// *****************************************************************************************************************
	private IContext context;
	private int orderCounter;
	
	private int takeProfitPips = 100;
	private int pipStep = 20; 
	private double riskReward = 4.0;
	private int maxConsecutiveLosses = 0;
	private int maxTrades = 10;
	private double baseLotSize = 0.002;
	private int ordersPerRound = 50; 
	
	private Map<String, Integer> orderIndexes;
	private int[] consecutiveLosses;
	private int round;
	private int ordersInRound;
	
//	private Instrument instrument = Instrument.EURUSD;
	private Instrument instrument = Instrument.GBPUSD;
//	private Instrument instrument = Instrument.USDJPY;
//	private Instrument instrument = Instrument.AUDUSD;
//	private Instrument instrument = Instrument.USDCHF;
//	private Instrument instrument = Instrument.USDCAD;
	
	private int winCounter;
	private int lossCounter;
	private double equity;
	private double commission;
	
	private boolean initialized;
	
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

	private double getLotSize(int index) {
		return round(Math.pow(1 + riskReward, consecutiveLosses[index]) * baseLotSize, 3);
	}
	
	private synchronized void placeOrder(OrderCommand orderCommand, double price, int index) throws JFException {
		String label = STRATEGY_NAME + "_" + instrument.name() + "_" + (++orderCounter) + "_" + round;
		price = round(price, instrument.getPipScale());
		context.getEngine().submitOrder(label, instrument, orderCommand, getLotSize(index), price, 0);
		orderIndexes.put(label, index);
	}

	private void onOrderCancelled(IMessage message) throws JFException {
		log("Error executing order: " + message.getContent(), message.getCreationTime());
	}

	private void onOrderFilled(IOrder order) throws JFException {
		// Set the take profit and stop loss prices
		double openPrice = order.getOpenPrice();
		double margin = instrument.getPipValue() * takeProfitPips;
		int negator = order.isLong() ? 1 : -1;

		order.setTakeProfitPrice(round(openPrice + (negator * margin), instrument.getPipScale()));
		order.setStopLossPrice(round(openPrice - (negator * margin * riskReward), instrument.getPipScale()));
	}

	private synchronized void onOrderClosed(IOrder order) throws JFException {
		equity += order.getProfitLossInUSD();
		commission += order.getCommissionInUSD();

		int index= orderIndexes.get(order.getLabel());
		OrderCommand orderCommand = order.getOrderCommand();
		
		if (order.getProfitLossInPips() >= 0) {
			winCounter++;
			consecutiveLosses[index] = 0;
		} else {
			lossCounter++;
			consecutiveLosses[index] = consecutiveLosses[index] + 1;
			
			if (consecutiveLosses[index] > maxConsecutiveLosses) {
				consecutiveLosses[index] = 0;
			}
			
			orderCommand = OrderCommand.SELL.equals(orderCommand) ? OrderCommand.BUY : OrderCommand.SELL;
		}

		log("Closed " + order.getOrderCommand() + " order " + index + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") "
				+ (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [open=$" + round(order.getOpenPrice(), instrument.getPipScale()) 
				+ ", close=$" + round(order.getClosePrice(), instrument.getPipScale()) + ", equity=$" + context.getAccount().getEquity() 
				+ ", comm=$" + order.getCommissionInUSD() + ", lots=" + round(order.getAmount(), 3) + ", consecutiveLosses=" 
				+ consecutiveLosses[index] + "]", order.getCloseTime());
		
		String label = order.getLabel();
		int orderRound = Integer.valueOf(label.substring(label.lastIndexOf("_") + 1));
		
		if (ordersInRound > ordersPerRound) {
			startRound(order.getClosePrice());
		} else if (orderRound == round) {
			ordersInRound++;
			placeOrder(orderCommand, 0, index);
		}
	}

	private void resetLossCounter() {
		for (int i = 0; i < consecutiveLosses.length; i++) {
			consecutiveLosses[i] = 0;
		}
	}
	
	private void startRound(double price) throws JFException{
		resetLossCounter();
		round++;
		ordersInRound = 0;
		
		log("-- ROUND " + round + " (basePrice=$" + round(price, instrument.getPipScale()) + ") -------------------------------------------------------");
		
		double pipValue = instrument.getPipValue() * pipStep;
		for (int i = 0; i < (maxTrades / 2); i++) {
			placeOrder(OrderCommand.BUYSTOP, price + (i * pipValue), i * 2);
			placeOrder(OrderCommand.SELLSTOP, price - (i * pipValue), i * 2 + 1);
		}
	}
	
	// *****************************************************************************************************************
	// Public Methods - Implementation of the IStrategy interface
	// *****************************************************************************************************************
	public void onStart(IContext context) throws JFException {
		this.context = context;
		this.orderIndexes = new HashMap<>();
		this.consecutiveLosses = new int[maxTrades];
		log("Started the " + STRATEGY_NAME + " strategy.");
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

	public void onStop() throws JFException {
		log("Trades: " + (winCounter + lossCounter));
		
		if ((winCounter + lossCounter) == 0) {
			lossCounter = 1;
		}
		
		log("Wins: " + winCounter + " (" + round(100.0 * winCounter / (winCounter + lossCounter), 1) + "%)");
		log("Profit: $" + round(equity - commission, 2));
		log("Commission: $" + round(commission, 2));
		log("-----------------------------------------------------------------------------");
		log(STRATEGY_NAME + " strategy stopped.");
	}

	public void onTick(Instrument instrument, ITick tick) throws JFException {
	}
	
	public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
		if (!initialized && this.instrument.equals(instrument) && Period.ONE_HOUR.equals(period)) {
			startRound(askBar.getClose());
			initialized = true;
		}
	}

	public void onAccount(IAccount account) throws JFException {
	}
}
