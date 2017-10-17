package com.orcsoftware.liquidator.examples.basketarbitrage;

import java.util.ArrayList;
import java.util.List;

import com.orcsoftware.liquidator.AmendParameters;
import com.orcsoftware.liquidator.AmendStatus;
import com.orcsoftware.liquidator.Basket;
import com.orcsoftware.liquidator.Basket.Component;
import com.orcsoftware.liquidator.Combination;
import com.orcsoftware.liquidator.Future;
import com.orcsoftware.liquidator.ImpliedMarketData;
import com.orcsoftware.liquidator.Instrument;
import com.orcsoftware.liquidator.LiquidatorException;
import com.orcsoftware.liquidator.LiquidatorModule;
import com.orcsoftware.liquidator.Order;
import com.orcsoftware.liquidator.OrderExecutionStyle;
import com.orcsoftware.liquidator.OrderManager;
import com.orcsoftware.liquidator.OrderParameters;
import com.orcsoftware.liquidator.OrderPriceCondition;
import com.orcsoftware.liquidator.OrderStatus;
import com.orcsoftware.liquidator.OrderValidity;
import com.orcsoftware.liquidator.OrderVolumeCondition;
import com.orcsoftware.liquidator.Side;
import com.orcsoftware.liquidator.Trade;

/**
 * @author barryga
 */

/**
 * A simple strategy which arbs between a basket and a future. The strategy will
 * "bait" the future based on the current basket market value + the user-defined
 * edge.
 * <p>
 * When the future trades, the strategy will execute at-market orders on the
 * basket's components.
 */

@SuppressWarnings("unchecked")
public class BasketArbitrage extends LiquidatorModule {
	/**
	 * The BasketArbitrage template parameter "basket".
	 * <p>
	 * This parameter should contain a string which uniquely identifies an
	 * instrument of type Basket.<br>
	 * Typically this parameter would be populated by the Orc client when the
	 * user creates an instance of the strategy.
	 */
	public static final String BASKET_PARAMETER = "basket";
	/**
	 * The BasketArbitrage template parameter "future".
	 * <p>
	 * This parameter should contain a string which uniquely identifies an
	 * instrument of type Future.<br>
	 * Typically this parameter would be populated by the Orc client when the
	 * user creates an instance of the strategy.
	 */
	public static final String FUTURE_PARAMETER = "future";
	/**
	 * The BasketArbitrage template parameter "edge-offset".
	 * <p>
	 * This parameter should contain a float that declares the price offset
	 * (from the basket's price) determining the bid/offer prices sent to market
	 * on the future.
	 */
	public static final String EDGE_PARAMETER = "edge-offset";
	/**
	 * The BasketArbitrage template parameter "volume".
	 * <p>
	 * This parameter contains a long value that declares the clip size i.e. the
	 * number of futures shown to the market and consequently the number of
	 * baskets which will be executed if the futures order(s) is filled.
	 */
	public static final String VOLUME_PARAMETER = "volume";
	/**
	 * The BasketArbitrage template parameter "legs".
	 * <p>
	 * This parameter will contain an instrument array corresponding to the
	 * components of the basket. It is set by the strategy when created.<br>
	 * By populating this parameter, the components are considered "in-context"
	 * for the strategy and the Orc client will display the basket's components
	 * in the Strategy Overview window alongside the basket and future.
	 */
	public static final String COMPONENTS_PARAMETER = "legs";

	/**
	 * Volume below which we consider an order to have zero volume.
	 */
	public static final double LOWER_VOLUME_THRESHOLD = 0.01;

	/**
	 * Get hold of the default Order Manager.
	 */
	private OrderManager orderManager = runtime.getOrderManager();

	/**
	 * The state associated with an instance of the BasketArbitrage strategy.
	 */
	static class State {

		// CHECKSTYLE:OFF
		// We don't need setters/getters. Liquidator enforces synchronized
		// access to state.

		/**
		 * The Basket instrument referred to by the "basket" strategy template
		 * parameter.
		 */
		Basket mBasket;
		/**
		 * The Future instrument referred to by the "future" strategy template
		 * parameter.
		 */
		Future mFuture;
		/**
		 * The calculated ask price of the basket.
		 */
		double basketAskPrice = 0;
		/**
		 * The calculated bid price of the basket.
		 */
		double basketBidPrice = 0;
		/**
		 * The implied volume available on the bid for the basket.
		 */
		double basketBidVolume = 0;
		/**
		 * The implied volume available on the offer for the basket.
		 */
		double basketAskVolume = 0;
		/**
		 * The market ask price for the future.
		 */
		double futurePriceAsk = 0;
		/**
		 * The market bid price for the future.
		 */
		double futurePriceBid = 0;
		/**
		 * Whether we are buying or selling the future (The opposite will happen
		 * for the basket).
		 */
		Side side = Side.BUY;
		/**
		 * The accumulated volume of futures trades.
		 */
		double tradedVolume;

		/**
		 * The internal Liquidator ID for the currently working future order.
		 */
		long currentOrderID;

		// CHECKSTYLE:ON
	}

	/**
	 * Calculate the implied basket bid/offer and bid/offer volumes then update
	 * the state with these values.
	 */
	private void calculateBasketPrice() {
		// Get basket market data
		State state = (State) runtime.getStrategy().getState();
		Basket basket = state.mBasket;
		ImpliedMarketData impliedMarketData = ((Combination) basket)
				.getImpliedMarketData();

		// Set market data
		state.basketAskVolume = impliedMarketData.getAskVolume();
		state.basketBidVolume = impliedMarketData.getBidVolume();
		state.basketAskPrice = impliedMarketData.getAsk();
		state.basketBidPrice = impliedMarketData.getBid();
	}

	/**
	 * Calculate the bid/ask prices for the future based on the current basket
	 * price calculated in {@link #calculateBasketPrice} and the
	 * {@link #EDGE_PARAMETER} strategy template parameter.
	 */
	private void calculateFuturePrice() {
		// Get future price
		State state = (State) runtime.getStrategy().getState();
		Future future = state.mFuture;

		// Get edge
		double edge = runtime.getStrategy().getLongParameter(EDGE_PARAMETER);

		// Calculate prices at which we want to send futures orders
		state.futurePriceAsk = future
				.getNextHigherTickPrice(state.basketAskPrice + edge);
		state.futurePriceBid = future
				.getNextLowerTickPrice(state.basketBidPrice - edge);
	}

	/**
	 * Calculate the appropriate volume for the future's order(s). If the volume
	 * is too small, withdraw the orders, otherwise insert/update an order(s) on
	 * the future.
	 * 
	 * @param state
	 *            the state
	 */
	private void baitFuture(final State state) {
		// Strategy strategy = runtime.getStrategy();
		Side side = state.side;
		Order order = getOrder(state);

		if (order != null
				&& order.getStatus().equals(OrderStatus.PENDING_DELETE)) {
			return;
		}

		// Determine order volume
		double availableBasketVolume = 0;
		if (side == Side.BUY) {
			// We are buying the future and selling the basket. The maximum
			// future order size is the volume on the basket's best bid.
			availableBasketVolume = state.basketBidVolume;
		} else {
			// We are selling the future and buying the basket. The maximum
			// future order size is the volume on the basket's best offer.
			availableBasketVolume = state.basketAskVolume;
		}
		double paramVolume = runtime.getStrategy().getLongParameter(
				VOLUME_PARAMETER);
		double remainingVolume = Math.min(availableBasketVolume, paramVolume
				- state.tradedVolume);
		double volume = state.mFuture.getNearestLowerLotVolume(remainingVolume);

		if (volume <= LOWER_VOLUME_THRESHOLD) {
			if (order != null) {
				order.remove();
				runtime.log("Removing order "
						+ order.getOrderIdentifier()
						+ " as there is not enough volume in the basket's market.");
			}

			return;
		}

		// If no order then insert an order
		double price = 0;
		if (side == Side.BUY) {
			price = state.futurePriceBid;
		} else {
			price = state.futurePriceAsk;
		}

		if (order == null) {
			if (side == Side.BUY) {
				state.currentOrderID = orderManager.bid(state.mFuture, volume,
						price);
			} else {
				state.currentOrderID = orderManager.offer(state.mFuture,
						volume, price);
			}
			runtime.log("Sending " + side + " order to: " + volume + "@"
					+ price);
		} else {
			AmendParameters params = new AmendParameters();
			params.setPrice(price);
			params.setVolume(volume);
			order.amend(params);
			runtime.log("Amending order to: " + volume + "@" + price);
		}
	}

	/**
	 * Get the first active order for the strategy.
	 * 
	 * @param state
	 *            the state
	 * @return the first active order belonging to the strategy instance.
	 */
	private Order getOrder(final State state) {
		for (Order order : runtime.getStrategy().getOrders()) {
			if (order.getStatus() != OrderStatus.DELETED
					&& order.getInstrument().equals(state.mFuture)) {
				return order;
			}
		}

		return null;
	}

	/**
	 * Invoked when an instance of the strategy is instantiated.
	 * 
	 * Checks that the instruments are of the correct type and subscribes to
	 * market data for the instruments and their components
	 */
	public final void onCreate() {
		// Get basket
		Instrument basket = runtime.getStrategy().getInstrumentParameter(
				BASKET_PARAMETER);

		if (!(basket instanceof Basket)
				|| !basket.getMarketName().equals("MiniBasket")) {
			throw new LiquidatorException(
					"Fatal: Basket parameter must be a basket instrument with market MiniBasket.");
		}

		// Get future
		Instrument future = runtime.getStrategy().getInstrumentParameter(
				FUTURE_PARAMETER);

		if (!(future instanceof Future)) {
			throw new LiquidatorException(
					"Fatal: Future parameter must be a future instrument");
		}

		// Create state
		State state = new State();
		state.mBasket = (Basket) basket;
		state.mFuture = (Future) future;
		runtime.getStrategy().setState(state);

		// Subscribe to market data for each component
		ArrayList<Instrument> basketComponents = new ArrayList<Instrument>();
		List<Component> componentList = (List<Component>) (List<?>) state.mBasket
				.getComponentList();

		for (Component component : componentList) {
			component.getInstrument().marketDataSubscribe();
			basketComponents.add(component.getInstrument());
		}

		runtime.getStrategy().setParameter(COMPONENTS_PARAMETER,
				basketComponents);

		// Subscribe to future market data
		future.marketDataSubscribe();

		runtime.log("Creating Basket Arb: Basket="
				+ state.mBasket.getFeedcode() + " Future="
				+ state.mFuture.getFeedcode());
	}

	/**
	 * Triggered by a strategy instance transitioning to the running state.
	 */
	public final void onStart() {
		runtime.log("Starting Basket Arb");
		calculateBasketPrice();
		calculateFuturePrice();
		State state = (State) runtime.getStrategy().getState();
		baitFuture(state);
	}

	/**
	 * Triggered by a strategy instance transitioning to the halted state.
	 */
	public final void onStop() {
		State state = (State) runtime.getStrategy().getState();

		if (state == null) {
			return;
		}

		for (Order order : runtime.getStrategy().getOrders()) {
			order.remove();
		}
	}

	/**
	 * Triggered by a market data update in a basket component.
	 * <p>
	 * The basket's implied price is recalculated, the appropriate future
	 * order(s) price is recalculated and and the future's order(s)
	 * inserted/amended.
	 */
	public final void onBasketMarketUpdate() {
		State state = (State) runtime.getStrategy().getState();
		calculateBasketPrice();
		calculateFuturePrice();
		baitFuture(state);
	}

	/**
	 * Triggered by a fill being received.
	 * <p>
	 * Fills on basket components are ignored, fills on futures orders result in
	 * the inverse orders being generated for the basket.
	 * 
	 * @param trade
	 *            The {@link Trade} object passed by the {@link Runtime} as the
	 *            activation payload.
	 * @see Trade
	 */
	public final void onTrade(final Trade trade) {
		// Ignore trade on basket components
		State state = (State) runtime.getStrategy().getState();

		// Ignore all trades except those on the future
		if (trade.getInstrument() != state.mFuture) {
			return;
		}

		// Update traded volume
		state.tradedVolume += trade.getVolume();

		// Execute the basket at market
		Side side = trade.getSide();
		Side oppositeSide = null;
		if (side == Side.BUY) {
			oppositeSide = Side.SELL;
		} else {
			oppositeSide = Side.BUY;
		}
		OrderParameters params = new OrderParameters();
		params.setPriceCondition(OrderPriceCondition.AT_MARKET);
		params.setVolumeCondition(OrderVolumeCondition.NORMAL);
		params.setValidity(OrderValidity.IMMEDIATE);
		params.setExecutionStyle(OrderExecutionStyle.NORMAL);
		double tradeVolume = trade.getVolume();
		List<Component> componentList = (List<Component>) (List<?>) state.mBasket
				.getComponentList();

		for (Component component : componentList) {
			Instrument instrument = component.getInstrument();
			double volume = tradeVolume * component.getVolume();

			if (oppositeSide == Side.BUY) {
				orderManager.bid(instrument, volume, 0, params);
			} else {
				orderManager.offer(instrument, volume, 0, params);
			}
		}

		if (orderManager.getOrder(state.mFuture.getMarketName(),
				state.currentOrderID).getVolume() <= LOWER_VOLUME_THRESHOLD) {
			runtime.log("Arb completed. Halting.");
			state.tradedVolume = 0;
			runtime.getStrategy().stop();
		}
	}

	/**
	 * Triggered by a change in one or more of the future's orders' market
	 * state.
	 * 
	 * @param order
	 *            The {@link Order} passed by the
	 *            {@link com.orcsoftware.liquidator.LiquidatorRuntime} as the
	 *            activation payload.
	 */
	public final void onOrderChange(final Order order) {
		State state = (State) runtime.getStrategy().getState();
		// Check error status
		OrderStatus status = order.getStatus();

		if (status == OrderStatus.ERROR_ADD
				|| status == OrderStatus.ERROR_DELETE) {
			throw new LiquidatorException("Error while inserting order:"
					+ order.getErrorReason());
		}

		// If the future order is now deleted, put a new order in.
		if (status == OrderStatus.DELETED) {
			baitFuture(state);
		}

		AmendStatus amendStatus = order.getAmendStatus();
		if (amendStatus == AmendStatus.FAILED) {
			throw new LiquidatorException("Error while amending order:"
					+ order.getErrorReason());
		}

		// Ignore all other (non-handled) status other than ACTIVE
		if (status != OrderStatus.ACTIVE) {
			return;
		}
	}

	/**
	 * Triggered by a change to the strategy parameters.
	 * <p>
	 * When a parameter which impacts the calculation of the futures is altered,
	 * the appropriate future order price(s) is recalculated and the future's
	 * orders inserted/amended.
	 */
	public final void onQuotingParameterChange() {
		State state = (State) runtime.getStrategy().getState();
		calculateFuturePrice();
		baitFuture(state);
		runtime.log("Order updated due to parameter change.");
	}
}
