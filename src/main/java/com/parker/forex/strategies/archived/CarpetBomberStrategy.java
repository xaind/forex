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

public class CarpetBomberStrategy implements IStrategy {

	private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
	
	private static final String STRATEGY_NAME = "CARPET_BOMBER";
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
	
	private int winCounter;
	private int lossCounter;
	private double totalProfit;
	private double totalCommission;
	
	private boolean lastLoss;
	private double maxDrawdown;
	private double currentDrawdown;
	
	private List<Bomb> bombs = new ArrayList<>();
	private Map<IOrder, Bomb> orderMap = new HashMap<>();
	
	private double startEquity;
	private long startTime;
	
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

	private void closeOrders(Instrument instrument) throws JFException {
		for (IOrder order : context.getEngine().getOrders(instrument)) {
			if (State.FILLED.equals(order.getState()) || (State.OPENED.equals(order.getState()) && order.getOrderCommand().isConditional())) {
				order.close();
			}
		}
	}
	
	private synchronized IOrder placeOrder(Bomb bomb, OrderCommand orderCommand, double price) throws JFException {
		String label = STRATEGY_NAME + "_" + bomb.instrument.name() + "_" + (++orderCounter);
		price = round(price, bomb.instrument.getPipScale());
		IOrder order = context.getEngine().submitOrder(label, bomb.instrument, orderCommand, bomb.lotSize, price, 0);
		orderMap.put(order, bomb);
		return order;
	}

	private void onOrderCancelled(IMessage message) throws JFException {
		log("Error executing order: " + message.getContent(), message.getCreationTime());
	}

	private void onOrderFilled(IOrder order) throws JFException {
		// Set the take profit and stop loss prices
		Bomb bomb = orderMap.get(order);
		
		double openPrice = order.getOpenPrice();
		double margin = bomb.instrument.getPipValue() * bomb.takeProfitPips;
		int negator = order.isLong() ? 1 : -1;

		order.setTakeProfitPrice(round(openPrice + (negator * margin), bomb.instrument.getPipScale()));
		order.setStopLossPrice(round(openPrice - (negator * margin * bomb.riskReward), bomb.instrument.getPipScale()));
	}

	private synchronized void onOrderClosed(IOrder order) throws JFException {
		Bomb bomb = orderMap.get(order);
		
		totalProfit += order.getProfitLossInUSD();
		totalCommission += order.getCommissionInUSD();
		
		bomb.profit += order.getProfitLossInUSD();
		bomb.commission += order.getCommissionInUSD();

		OrderCommand orderCommand = order.getOrderCommand();
		
		if (order.getProfitLossInPips() >= 0) {
			winCounter++;
			bomb.winCounter++;
			
			lastLoss = false;
			if (currentDrawdown > maxDrawdown) {
				maxDrawdown = currentDrawdown;
			}
		} else {
			lossCounter++;
			bomb.lossCounter++;
			orderCommand = OrderCommand.SELL.equals(orderCommand) ? OrderCommand.BUY : OrderCommand.SELL;
			
			if (lastLoss) {
				currentDrawdown += Math.abs(order.getProfitLossInUSD());
			} else {
				currentDrawdown = Math.abs(order.getProfitLossInUSD());
				lastLoss = true;
			}
		}

		log("Closed " + bomb.instrument + " " + order.getOrderCommand() + " order " + bomb.round + "/" + bomb.orders.indexOf(order) + " for " + order.getProfitLossInPips() + " pip (US$" + order.getProfitLossInUSD() + ") "
				+ (order.getProfitLossInPips() < 0 ? "LOSS" : "PROFIT") + ". [open=$" + round(order.getOpenPrice(), bomb.instrument.getPipScale()) 
				+ ", close=$" + round(order.getClosePrice(), bomb.instrument.getPipScale()) + ", equity=$" + context.getAccount().getEquity() 
				+ ", comm=$" + order.getCommissionInUSD() + ", lots=" + round(order.getAmount(), 3) + "]", order.getCloseTime());
		
		if (bomb.stopRound()) {
			bomb.start();
		} else {
			bomb.orders.add(placeOrder(bomb, orderCommand, 0));
		}
	}

	private void add(Bomb bomb) throws JFException{
		bombs.add(bomb);
		bomb.start();
	}
	
	// *****************************************************************************************************************
	// Public Methods - Implementation of the IStrategy interface
	// *****************************************************************************************************************
	public void onStart(IContext context) throws JFException {
		this.context = context;
		log("\nStarted the " + STRATEGY_NAME + " strategy.");
//		add(new Bomb(Instrument.EURUSD, 4, 10, 0.002));
//		add(new Bomb(Instrument.GBPUSD, 4, 10, 0.002));
//		add(new Bomb(Instrument.USDJPY, 4, 10, 0.002));
		add(new Bomb(Instrument.AUDUSD, 4, 10, 0.002));
		
		startEquity = context.getAccount().getEquity();
		startTime = context.getHistory().getTimeOfLastTick(Instrument.EURUSD);
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
		long endTime = context.getHistory().getTimeOfLastTick(Instrument.EURUSD);
		log("Total Trades: " + (winCounter + lossCounter) + "(" + (1.0 * (endTime - startTime) / (MILLIS_IN_WEEK)) + "/week)");
		
		if ((winCounter + lossCounter) == 0) {
			lossCounter = 1;
		}
		
		double roi = ((totalProfit - totalCommission) / startEquity) / ((endTime - startTime) / MILLIS_IN_DAY) * 36500.0;
		
		log("Total Wins: " + winCounter + " (" + round(100.0 * winCounter / (winCounter + lossCounter), 1) + "%)");
		log("Total Profit: $" + round(totalProfit - totalCommission, 2) + "(" + round(roi, 1) + "% pa)");
		log("Total Commission: $" + round(totalCommission, 2));
		log("Total Drawdown: $" + round(maxDrawdown, 2));
		log("-----------------------------------------------------------------------------");
		
		for (Bomb bomb : bombs) {
			log(bomb.instrument.toString());
			log("Total Trades: " + (bomb.winCounter + bomb.lossCounter));
			
			if ((bomb.winCounter + bomb.lossCounter) == 0) {
				bomb.lossCounter = 1;
			}
			
			log("Wins: " + bomb.winCounter + " (" + round(100.0 * bomb.winCounter / (bomb.winCounter + bomb.lossCounter), 1) + "%)");
			log("Profit: $" + round(bomb.profit - bomb.commission, 2));
			log("Commission: $" + round(bomb.commission, 2));
			log("-----------------------------------------------------------------------------");
		}
		
		
		log(STRATEGY_NAME + " strategy stopped.");
	}

	public void onTick(Instrument instrument, ITick tick) throws JFException {
	}
	
	public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
	}

	public void onAccount(IAccount account) throws JFException {
	}
	
	private class Bomb {
		
		Instrument instrument;
		
		int takeProfitPips;
		double riskReward;
		int maxTrades;
		double lotSize;
		
		int round;
		int winCounter;
		int lossCounter;
		double profit;
		double commission; 
		
		List<IOrder> orders = new ArrayList<>();
		
		Bomb(Instrument instrument, double riskReward, int maxTrades, double lotSize) {
			this.instrument = instrument;
			this.riskReward = riskReward;
			this.maxTrades = maxTrades;
			this.lotSize = lotSize;
		}
		
		void start() throws JFException {
			round++;
			closeOrders(instrument);
			setupParams();
			
			double price = context.getHistory().getLastTick(instrument).getAsk();
			
			log("-- " + instrument + " ROUND " + round + " (basePrice=$" + round(price, instrument.getPipScale()) + ", tp=" 
					+ takeProfitPips + ") ------------------------------------------------------------------------------------------------------");
			
			double pipValue = instrument.getPipValue() * (takeProfitPips / (maxTrades / 2));
			for (int i = 0; i < (maxTrades / 2); i++) {
				orders.add(placeOrder(this, OrderCommand.BUYSTOP, price + (i * pipValue)));
				orders.add(placeOrder(this, OrderCommand.SELLSTOP, price - (i * pipValue)));
			}
		}

		void setupParams() throws JFException {
			IHistory history = context.getHistory();
			
			long time = history.getStartTimeOfCurrentBar(instrument, Period.DAILY);
			List<IBar> bars = history.getBars(instrument, Period.DAILY, OfferSide.ASK, Filter.WEEKENDS, 60, time, 0);
			
			double totalPips = 0;
			for (IBar bar : bars) {
				totalPips += (bar.getHigh() - bar.getLow()) / instrument.getPipValue();
			}
			
			takeProfitPips = (int) (totalPips / bars.size() * 2.0);
		}
		
		boolean stopRound() {
			List<double[]> pricePoints = new ArrayList<>();
			
			orderLoop: for (IOrder order : orders) {
				if (State.CLOSED.equals(order.getState())) {
					double close = order.getClosePrice();
					
					for (double[] pricePoint : pricePoints) {
						if (pricePoint[0] < close && pricePoint[1] > close) {
							pricePoint[2] = pricePoint[2] + 1;
							break orderLoop;
						} 
					}
					
					pricePoints.add(new double[]{close - (3 * instrument.getPipValue()), close + (3 * instrument.getPipValue()), 1});
				}
			}
			
			for (double[] pricePoint : pricePoints) {
				if (pricePoint[2] > 3) {
					return true;
				}
			}
			
			return orders.size() > 50;
		}
	}
}
