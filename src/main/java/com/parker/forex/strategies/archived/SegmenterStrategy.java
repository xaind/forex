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

public class SegmenterStrategy implements IStrategy {

	private static final String STRATEGY_NAME = "SEGMENTER";

	private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

	static {
		DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
	}

	// *****************************************************************************************************************
	// Instance Fields
	// *****************************************************************************************************************
	private IContext context;
	private int orderCounter;
	
	private Instrument instrument = Instrument.AUDUSD;
	private double baseLotSize = 0.05;
	private int takeProfitPips = 5;
	private double riskRewardRatio = 20.0;
	private int maxConsecutiveLosses = 0;
	private int segments = 1;
	
	private int[] consecutiveLosses;
	
	private boolean started;
	private int segmentCounter;
	private int winCounter;
	private int lossCounter;
	private int finalLossCounter;
	private int dayCounter;
	
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
		return round(Math.pow(1 + riskRewardRatio, consecutiveLosses[segmentCounter]) * baseLotSize, 3);
	}
	
	private void placeOrder(OrderCommand orderCommand) throws JFException {
		String label = STRATEGY_NAME + "_" + instrument.name() + "_" + (++orderCounter);
		context.getEngine().submitOrder(label, instrument, orderCommand, getLotSize(), 0, 0);
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
		order.setStopLossPrice(round(openPrice - (negator * margin * riskRewardRatio), instrument.getPipScale()));
	}

	private void onOrderClosed(IOrder order) throws JFException {
		OrderCommand orderCommand = order.getOrderCommand();

		equity += order.getProfitLossInUSD();
		commission += order.getCommissionInUSD();

		if (order.getProfitLossInPips() >= 0) {
			winCounter++;
			consecutiveLosses[segmentCounter] = 0;
		} else {
			// Always trade in the current direction of the price
			orderCommand = orderCommand.isLong() ? OrderCommand.SELL : OrderCommand.BUY;
			consecutiveLosses[segmentCounter] = consecutiveLosses[segmentCounter] + 1;
			
			lossCounter++;
			if (consecutiveLosses[segmentCounter] > maxConsecutiveLosses) {
				consecutiveLosses[segmentCounter] = 0;
				finalLossCounter++;
			}
		}
		
		log("Closed " + order.getOrderCommand() + " order" + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") "
				+ (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [equity=$" + context.getAccount().getEquity() + ", comm=$"
				+ order.getCommissionInUSD() + ", lots=" + round(order.getAmount(), 3) + ", consecutiveLosses=" + consecutiveLosses[segmentCounter] + "]", order.getCloseTime());
		
		segmentCounter++;
		if (segmentCounter >= segments) {
			segmentCounter = 0;
		}
		
		placeOrder(orderCommand);
	}

	// *****************************************************************************************************************
	// Public Methods - Implementation of the IStrategy interface
	// *****************************************************************************************************************
	public void onStart(IContext context) throws JFException {
		this.context = context;
		this.consecutiveLosses = new int[segments];
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
		int trades = winCounter + lossCounter;
		log("Trades: " + trades + " (" + round(1.0 * trades / dayCounter, 1) + "/day)");
		
		if (trades == 0) {
			lossCounter = 1;
		}
		
		log("Wins: " + winCounter + " (" + round(100.0 * winCounter / trades, 1) + "%)");
		log("Losses: " + lossCounter + " (" + round(100.0 * lossCounter / trades, 1) + "%)");
		log("Final Losses: " + finalLossCounter + " (" + round(100.0 * finalLossCounter / trades, 1) + "%)");
		
		log("Profit: $" + round(equity - commission, 2));
		log("Commission: $" + round(commission, 2));
		log("-----------------------------------------------------------------------------");
		log(STRATEGY_NAME + " strategy stopped.");
	}

	public void onTick(Instrument instrument, ITick tick) throws JFException {
		if (!started && this.instrument.equals(instrument)) {
			started = true;
			placeOrder(OrderCommand.BUY);
		}
	}
	
	public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
		if (this.instrument.equals(instrument) && Period.DAILY.equals(period)) {
			Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
			calendar.setTimeInMillis(bidBar.getTime());
			
			if (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY && calendar.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
				dayCounter++;
			}
		}
	}

	public void onAccount(IAccount account) throws JFException {
	}
}
