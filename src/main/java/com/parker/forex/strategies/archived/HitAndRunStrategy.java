package com.parker.forex.strategies.archived;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
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

/**
 * EURUSD: takeProfit=10, velocityLimit=20, tradeWindow=M10 
 * GBPUSD: takeProfit=10, velocityLimit=40, tradeWindow=M5 
 * USDJPY: takeProfit=10, velocityLimit=20, tradeWindow=M5 
 * AUDUSD: takeProfit=10, velocityLimit=20, tradeWindow=M5 
 * USDCAD: takeProfit=20, velocityLimit=20, tradeWindow=M5 
 * USDCHF: takeProfit=10, velocityLimit=20, tradeWindow=M5 
 */
public class HitAndRunStrategy implements IStrategy {

	private static final String STRATEGY_NAME = "HIT_AND_RUN";

	private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

	static {
		DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
	}

	// *****************************************************************************************************************
	// Instance Fields
	// *****************************************************************************************************************
	private IContext context;
	private int orderCounter;
	
	private double velocityLimit = 10;
	private int takeProfitPips = 5;
	private Period tradeWindow = Period.TEN_SECS;
	
	private double martingaleFactor = 1.0;
	private int consecutiveLosses;
	private int maxConsecutiveLosses = 0;
	private double baseLotSize = 0.2;
	private boolean isTicking;
	
	private Instrument instrument = Instrument.EURUSD;
//	private Instrument instrument = Instrument.GBPUSD;
//	private Instrument instrument = Instrument.USDJPY;
//	private Instrument instrument = Instrument.AUDUSD;
//	private Instrument instrument = Instrument.USDCHF;
//	private Instrument instrument = Instrument.USDCAD;
	private Period period = Period.ONE_SEC;
	
	private LinkedList<ITick> ticks;
	private LinkedList<Result> results;
	
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
	
	private void placeOrder(OrderCommand orderCommand) throws JFException {
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
		order.setStopLossPrice(round(openPrice - (negator * margin * martingaleFactor), instrument.getPipScale()));
	}

	private void onOrderClosed(IOrder order) throws JFException {
		equity += order.getProfitLossInUSD();
		commission += order.getCommissionInUSD();

		if (order.getProfitLossInPips() >= 0) {
			winCounter++;
			consecutiveLosses = 0;
		} else {
			lossCounter++;
			consecutiveLosses++;
			
			if (consecutiveLosses > maxConsecutiveLosses) {
				consecutiveLosses = 0;
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
		this.ticks = new LinkedList<>();
		this.results = new LinkedList<>();
		
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
		if (this.instrument.equals(instrument)) {
			ticks.add(tick);
			double millis = ticks.peekLast().getTime() - ticks.peekFirst().getTime();
			
			if (millis > period.getInterval()) {
				double secs = millis / 1000.0; 
				double velocity = (tick.getBid() - ticks.poll().getBid()) / secs / instrument.getPipValue();
				double acceleration = 0;
				
				if (!results.isEmpty()) {
					acceleration = (velocity - results.peekLast().velocity) / secs;
				}
				
				Result result = new Result(velocity);
				
				if (Math.abs(velocity) > velocityLimit && Math.signum(velocity) == Math.signum(acceleration) && !hasOpenOrder()) {
					log("Hotspot Detected (v=" + round(velocity, 1) + " pips/sec, a=" + round(acceleration, 1) + " pips/sec2) " +
							"------------------------------------------------------------------------------------", tick.getTime());
					OrderCommand orderCommand = OrderCommand.BUY;
					if (velocity < 0) {
						orderCommand = OrderCommand.SELL;
					}
					placeOrder(orderCommand);
				}
				
				results.add(result);
			}
		}
	}
	
	public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
		if (this.instrument.equals(instrument) && tradeWindow.equals(period)) {
			IOrder order = getOpenOrder();
			if (order != null) {
				if (isTicking) {
					order.close();
					isTicking = false;
				} else {
					isTicking = true;
				}
			} else {
				isTicking = false;
			}
		}
	}

	public void onAccount(IAccount account) throws JFException {
	}
	
	private class Result {
		
		public double velocity;
		
		public Result(double velocity) {
			this.velocity = velocity;
		}
	}
}
