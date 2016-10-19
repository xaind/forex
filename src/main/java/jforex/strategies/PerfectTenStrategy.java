package jforex.strategies;

import java.util.ArrayList;
import java.util.List;

import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;

public class PerfectTenStrategy implements IStrategy {

	Basket longBasket, shortBasket;

	@Override
	public void onStart(IContext context) throws JFException {
		longBasket = new Basket();

		longBasket.addInstrumentInfo(Instrument.EURUSD, OrderCommand.BUY);
		longBasket.addInstrumentInfo(Instrument.EURGBP, OrderCommand.SELL);
		longBasket.addInstrumentInfo(Instrument.EURAUD, OrderCommand.BUY);
		longBasket.addInstrumentInfo(Instrument.EURJPY, OrderCommand.SELL);
		longBasket.addInstrumentInfo(Instrument.GBPUSD, OrderCommand.SELL);
		longBasket.addInstrumentInfo(Instrument.GBPJPY, OrderCommand.BUY);
		longBasket.addInstrumentInfo(Instrument.GBPAUD, OrderCommand.SELL);
		longBasket.addInstrumentInfo(Instrument.USDJPY, OrderCommand.SELL);
		longBasket.addInstrumentInfo(Instrument.AUDJPY, OrderCommand.BUY);
		longBasket.addInstrumentInfo(Instrument.AUDUSD, OrderCommand.SELL);

		shortBasket = new Basket();
		
		// The short basket has opposite order commands to the long basket
		for (InstrumentInfo instrumentInfo : longBasket.instruments) {
			shortBasket.addInstrumentInfo(instrumentInfo.instrument,
					instrumentInfo.inverseOrderCommand());
		}
	}

	@Override
	public void onTick(Instrument instrument, ITick tick) throws JFException {
		// TODO Auto-generated method stub

	}

	@Override
	public void onBar(Instrument instrument, Period period, IBar askBar,
			IBar bidBar) throws JFException {
		// TODO Auto-generated method stub

	}

	@Override
	public void onMessage(IMessage message) throws JFException {
		// TODO Auto-generated method stub

	}

	@Override
	public void onAccount(IAccount account) throws JFException {
		// TODO Auto-generated method stub

	}

	@Override
	public void onStop() throws JFException {
		// TODO Auto-generated method stub

	}

	//-------------------------------------------------------------------------------------------
	// Inner classes
	//-------------------------------------------------------------------------------------------
	class Basket {

		List<InstrumentInfo> instruments = new ArrayList<>();

		void addInstrumentInfo(Instrument instrument, OrderCommand orderCommand) {
			this.instruments.add(new InstrumentInfo(instrument, orderCommand));
		}
	}

	class InstrumentInfo {

		Instrument instrument;
		OrderCommand orderCommand;

		public InstrumentInfo(Instrument instrument, OrderCommand orderCommand) {
			this.instrument = instrument;
			this.orderCommand = orderCommand;
		}

		OrderCommand inverseOrderCommand() {
			return OrderCommand.BUY.equals(orderCommand) ? OrderCommand.SELL
					: OrderCommand.BUY;
		}

	}

}
