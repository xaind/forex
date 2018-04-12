package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
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

public class HappyHourStrategy implements IStrategy {

	private static final String STRATEGY_NAME = "HAPPY_HOUR";

	private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
	private static final SimpleDateFormat DATE_FORMAT_SHORT = new SimpleDateFormat("yyyyMMdd_HHmm");
	private static final TimeZone GMT = TimeZone.getTimeZone("GMT");
	
	static {
		DATE_FORMAT_LONG.setTimeZone(GMT);
		DATE_FORMAT_SHORT.setTimeZone(GMT);
	}

	// *****************************************************************************************************************
	// Instance Fields
	// *****************************************************************************************************************
	private IContext context;
	private int orderCounter;
	
	private int takeProfitPips = 50;
	private double martingaleFactor = 1.1;
	private double baseLotSize = 0.01;
	private int maxConsecutiveLosses = 10;
	
//	private Instrument instrument = Instrument.GBPUSD;
	private Instrument instrument = Instrument.EURUSD;
	
	private OrderCommand orderCommand = OrderCommand.BUY;
	private int consecutiveLosses;
	private int winCounter;
	private int lossCounter;
	private double equity;
	private double commission;
	
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

	private double getLotSize() {
		return round(Math.pow(1 + martingaleFactor, consecutiveLosses) * baseLotSize, 3);
	}
	
	private void placeOrder() throws JFException {
		String label = STRATEGY_NAME + "_" + instrument.name() + "_" + (++orderCounter);
		context.getEngine().submitOrder(label, instrument, orderCommand, getLotSize(), 0, 0);
	}

	private IOrder getOpenOrder() throws JFException {
		for (IOrder order : context.getEngine().getOrders(instrument)) {
			if (!State.CLOSED.equals(order.getState()) && !State.CANCELED.equals(order.getState())) {
				return order;
			}
		}
		return null;
	}
	
	private boolean hasOpenOrder() throws JFException {
		return getOpenOrder() != null;
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
		order.setStopLossPrice(round(openPrice - (negator * margin), instrument.getPipScale()));
	}

	private void onOrderClosed(IOrder order) throws JFException {
		equity += order.getProfitLossInUSD();
		commission += order.getCommissionInUSD();

		if (order.getProfitLossInPips() >= 0) {
			winCounter++;
			consecutiveLosses = 0;
		} else {
			// Always trade in the current direction of the price
			orderCommand = orderCommand.isLong() ? OrderCommand.SELL : OrderCommand.BUY;
			lossCounter++;
			consecutiveLosses++;
			
			if (consecutiveLosses > maxConsecutiveLosses) {
				log("Max consecutive losses reached!!! ----------------------------------------------------------------------------");
				consecutiveLosses = 0;
			} else if (isHappyHour(order.getCloseTime())) {
				placeOrder();
			}
		}

		log("Closed " + order.getOrderCommand() + " order" + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") "
				+ (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [equity=$" + context.getAccount().getEquity() + ", comm=$"
				+ order.getCommissionInUSD() + ", lots=" + round(order.getAmount(), 3) + ", consecutiveLosses=" + consecutiveLosses + "]", order.getCloseTime());
	}

	// *****************************************************************************************************************
	// Public Methods - Implementation of the IStrategy interface
	// *****************************************************************************************************************
	public void onStart(IContext context) throws JFException {
		this.context = context;
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

	private boolean isHappyHour(long time) {
		Calendar calendar = Calendar.getInstance(GMT);
		calendar.setTimeInMillis(time);
		
		int dow = calendar.get(Calendar.DAY_OF_WEEK);
		int hour = calendar.get(Calendar.HOUR_OF_DAY);
		int min = calendar.get(Calendar.MINUTE);
				
		return (Calendar.SATURDAY != dow && Calendar.SUNDAY != dow) && ((hour >= 13 && hour < 14 && min >= 30 && min <= 59) ||
			(hour >= 14 && hour < 15 && min >= 0 && min <= 30));
	}
	
	public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
		if (this.instrument.equals(instrument) && Period.ONE_MIN.equals(period) && isHappyHour(askBar.getTime()) && !hasOpenOrder()) {
			placeOrder();
		}
	}

	public void onTick(Instrument instrument, ITick tick) throws JFException {
	}
	
	public void onAccount(IAccount account) throws JFException {
	}
}
