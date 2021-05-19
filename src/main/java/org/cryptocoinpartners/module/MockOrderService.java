package org.cryptocoinpartners.module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.cryptocoinpartners.enumeration.FillType;
import org.cryptocoinpartners.enumeration.OrderState;
import org.cryptocoinpartners.enumeration.TransactionType;
import org.cryptocoinpartners.esper.annotation.When;
import org.cryptocoinpartners.schema.Amount;
import org.cryptocoinpartners.schema.Book;
import org.cryptocoinpartners.schema.Event;
import org.cryptocoinpartners.schema.Fill;
import org.cryptocoinpartners.schema.Market;
import org.cryptocoinpartners.schema.Offer;
import org.cryptocoinpartners.schema.Order;
import org.cryptocoinpartners.schema.OrderUpdate;
import org.cryptocoinpartners.schema.Portfolio;
import org.cryptocoinpartners.schema.SpecificOrder;
import org.cryptocoinpartners.schema.Trade;
import org.cryptocoinpartners.schema.TradeFactory;
import org.cryptocoinpartners.schema.Tradeable;
import org.cryptocoinpartners.util.ConfigUtil;
import org.cryptocoinpartners.util.EM;

/**
 * MockOrderService simulates the Filling of Orders by looking at broadcast Book data for price and
 * volume information.
 *
 * @author Tim Olson
 */
@Singleton
@SuppressWarnings("UnusedDeclaration")
public class MockOrderService extends BaseOrderService {
  private static ExecutorService mockOrderService = Executors.newFixedThreadPool(1);
  // static Double doubleSlippage = ConfigUtil.combined().getDouble("mock.exchange.slippage", 0.02);
  private static double slippage = ConfigUtil.combined().getDouble("mock.exchange.slippage", 0);
  protected final Lock updateOrderBookLock = new ReentrantLock();
  @Inject protected transient TradeFactory tradeFactory;
  // Object orderProcessingLock;

  // protected final Lock orederMatchingLock = new ReentrantLock();

  @Override
  protected void handleSpecificOrder(SpecificOrder specificOrder) {
    if (specificOrder.getStopPrice() != null) {

      reject(specificOrder, "Stop prices unsupported");
    }
    specificOrder.setEntryTime(context.getTime());
    // if we are trading the cash market, let's take off the fees.

    addOrder(specificOrder);

    updateOrderState(specificOrder, OrderState.PLACED, true);

    //	updateBook((quotes.getLastBook(specifcOrder.getMarket()) == null ?
    // quotes.getLastTrade(specificOrder.getMarket())
    //			: quotes.getLastBook(specificOrder.getMarket())));
    specificOrder.merge();
    updateBook(
        (quotes.getLastBook(specificOrder.getMarket()) == null
            ? quotes.getLastTrade(specificOrder.getMarket())
            : quotes.getLastBook(specificOrder.getMarket())));
    //			: quotes.getLastBook(specificOrder.getMarket())));
    // now we need to check if it can be filled

    // TODO when placing the order it is on the same listener so it needs to be routed.

  }

  private class updateBookRunnable implements Runnable {
    private final Event event;

    // protected Logger log;

    public updateBookRunnable(Event event) {
      this.event = event;
    }

    @Override
    public void run() {
      updateBook(event);
    }
  }

  @SuppressWarnings("ConstantConditions")
  // @When("@Priority(9) select * from Book(Book.market in (TrendStrategy.getMarkets()),
  // TrendStrategy.getMarketAllocation(Book.market)>0, Book.bidVolumeAsDouble>0,
  // Book.askVolumeAsDouble<0 )")
  // @When("@Priority(9) select * from Book")
  @When(
      "@Priority(9) @Audit select * from LastBookWindow(market.synthetic=false,market in (BaseStrategy.getMarkets()))")
  public void handleBook(Book b) {
    // mockOrderService.submit(new updateBookRunnable(b));
    updateBook(b);
    // mockOrderService.submit(new updateBookRunnable(b));

  }

  @SuppressWarnings("ConstantConditions")
  @When("@Priority(9)  @Audit select * from LastTradeWindow(market in (BaseStrategy.getMarkets()))")
  public void handleTrade(Trade t) {
    if (t.getMarket() == null || (t.getMarket() != null && t.getMarket().isSynthetic())) return;
    updateBook(t);
    // mockOrderService.submit(new updateBookRunnable(t));
  }

  @SuppressWarnings("ConstantConditions")
  private synchronized void updateBook(Event event) {
    // TODO sync on even rather than bids/asks.
    if (event == null || (!getTradingEnabled()) || pendingOrders == null) return;
    Book b = null;
    Trade t = null;
    Tradeable market = null;
    if (event instanceof Book) {
      b = (Book) event;
      if (b.getMarket().isSynthetic()) return;
      market = b.getMarket();
    }
    if (event instanceof Trade) {
      t = (Trade) event;
      if (t.getMarket().isSynthetic()) return;
      market = t.getMarket();
    }
    if (market == null) return;
    if (pendingOrders.get(market) == null
        || (pendingOrders.get(market) != null
            && (pendingOrders.get(market).get(TransactionType.BUY) == null
                || pendingOrders.get(market).get(TransactionType.BUY).isEmpty())
            && (pendingOrders.get(market).get(TransactionType.SELL) == null
                || pendingOrders.get(market).get(TransactionType.SELL).isEmpty()))) return;
    //   log.trace(this.getClass().getSimpleName() + " : updateBook to called from stack " +
    // sun.reflect.Reflection.getCallerClass(2));
    List<Offer> asks = new ArrayList<>();

    List<Offer> bids = new ArrayList<>();
    if (b != null) {

      asks = b.getAsks();
      bids = b.getBids();
    }

    if (t != null) {
      t = (Trade) event;

      // if Trade is a sell then it must have big the ask
      // Someone has sold, so this would has bit the bid
      if (t.getVolume().isNegative()) {
        Offer bestBid =
            new Offer(
                market,
                t.getTime(),
                t.getTimeReceived(),
                t.getPrice().getCount(),
                t.getVolume().negate().getCount());
        bids.add(bestBid);

      } else {
        Offer bestAsk =
            new Offer(
                market,
                t.getTime(),
                t.getTimeReceived(),
                t.getPrice().getCount(),
                t.getVolume().negate().getCount());
        asks.add(bestAsk);
      }
    }

    // todo multiple Orders may be filled with the same Offer.  We should deplete the Offers as we
    // fill
    // we will loop over the orders that are looking to buy against the sell orders from the book
    // i.e. the asks.
    List<Fill> buyFillsToProcess = new ArrayList<Fill>();
    List<Fill> sellFillsToProcess = new ArrayList<Fill>();
    Set<SpecificOrder> sellOrdersToRemove = new HashSet<SpecificOrder>();

    Set<SpecificOrder> buyOrdersToRemove = new HashSet<SpecificOrder>();

    if (asks != null
        && (pendingOrders.get(market).get(TransactionType.BUY) != null
            && !pendingOrders.get(market).get(TransactionType.BUY).isEmpty())) {

      try {
        synchronized (pendingOrders.get(market).get(TransactionType.BUY)) {
          log.trace(
              "{}:UpdateBook - determining fills for buy orders {}",
              this.getClass().getSimpleName(),
              pendingOrders.get(market).get(TransactionType.BUY));

          BIDORDERSLOOP:
          for (SpecificOrder order : pendingOrders.get(market).get(TransactionType.BUY)) {
            synchronized (order) {
              if (order.getUnfilledVolumeCount() == 0) {
                buyOrdersToRemove.add(order);

                continue BIDORDERSLOOP;
              }
              if (order.getMarket().equals(market)
                  && (order.getTimestamp() <= event.getTimestamp())) {

                log.trace(
                    "{} :UpdateBook - determining fills for buy order {}/{} with working volume {}",
                    this.getClass().getSimpleName(),
                    order.getUuid(),
                    System.identityHashCode(this),
                    order.getUnfilledVolumeCount());

                synchronized (event) {
                  Set<Offer> asksToRemove = new HashSet<Offer>();
                  synchronized (asks) {
                    ASKSLOOP:
                    for (Offer ask : asks) {
                      if (ask.getVolumeCount() == 0) {
                        asksToRemove.add(ask);
                        continue ASKSLOOP;
                      }
                      if (ask == null
                          || ((order.getFillType() != null
                                  && !order.getFillType().equals(FillType.MARKET))
                              && (order.getLimitPrice() != null
                                  && ask != null
                                  && order.getLimitPrice().getCount() < ask.getPriceCount()))) {

                        log.trace(
                            "{}:UpdateBook - ask price {} greater than limit price  {} for order  {}",
                            this.getClass().getSimpleName(),
                            ask,
                            order.getLimitPrice(),
                            order.getUuid());

                        break BIDORDERSLOOP;
                      }
                      //
                      // long buyOrderVolumeCount = order.getUnfilledVolumeCount();

                      long buyFillVolume =
                          Math.min(
                              Math.abs(ask.getVolumeCount()),
                              Math.abs(order.getUnfilledVolumeCount()));
                      if (buyFillVolume == 0) {
                        continue BIDORDERSLOOP;
                      }
                      long slippageDiff = Math.round(ask.getPriceCount() * slippage);
                      // we are buying
                      long fillPriceCount = (ask.getPriceCount() + slippageDiff);

                      log.debug(
                          "{}:updateBook - Calculated fillPriceCount={}, limitPrice={}, , limitPriceCount={} , ask={} , askCount={}, slippageDiff={}",
                          this.getClass().getSimpleName(),
                          fillPriceCount,
                          order.getLimitPrice(),
                          order.getLimitPrice().getCount(),
                          ask,
                          ask.getPriceCount(),
                          slippageDiff);

                      log.debug(
                          "{}:updateBook - Creating fill with ask={}, book={}, order={} ,working volume={}",
                          this.getClass().getSimpleName(),
                          ask,
                          quotes.getLastBook(market),
                          order.getUuid(),
                          order.getUnfilledVolumeCount());
                      // Instant fillTime = ask.getTime().getMillis() > order.getTimestamp() ?
                      // ask.getTime() : new Instant(order.getTimestamp()) ;
                      Fill fill =
                          fillFactory.create(
                              order,
                              context.getTime(),
                              context.getTime(),
                              order.getMarket(),
                              fillPriceCount,
                              buyFillVolume,
                              Long.toString(context.getTime().getMillis()));
                      fill.persit();
                      buyFillsToProcess.add(fill);

                      if (order.getUnfilledVolumeCount() == 0) {

                        buyOrdersToRemove.add(order);
                        continue BIDORDERSLOOP;
                      }
                      logFill(order, ask, fill);

                      log.debug(
                          "{}:UpdateBook - set askVolume {} to {} ",
                          this.getClass().getSimpleName(),
                          ask.getVolumeCount(),
                          (ask.getVolumeCount() < 0
                              ? -((Math.abs(ask.getVolumeCount()) - buyFillVolume))
                              : (Math.abs(ask.getVolumeCount() - buyFillVolume))));

                      ask.setVolumeCount(
                          (ask.getVolumeCount() < 0
                              ? -((Math.abs(ask.getVolumeCount()) - buyFillVolume))
                              : (Math.abs(ask.getVolumeCount() - buyFillVolume))));

                      if (fill.getVolume() == null
                          || (fill.getVolume() != null && fill.getVolume().isZero()))
                        log.debug(
                            "fill {} zero lots {}",
                            fill.getUuid(),
                            (order.getUnfilledVolumeCount()));
                      if (fill.getVolume().abs().compareTo(order.getVolume().abs()) > 0)
                        log.debug(
                            "overfilled {} {}", fill.getUuid(), (order.getUnfilledVolumeCount()));

                      if (ask.getVolumeCount() == 0) {
                        asksToRemove.add(ask);
                        continue ASKSLOOP;
                      }
                      if (order.getUnfilledVolumeCount() == 0) {
                        buyOrdersToRemove.add(order);
                        log.debug(
                            "Bid Order {} fully filled, unfilledVolumeCount {}",
                            order.getUuid(),
                            order.getUnfilledVolumeCount());
                        continue BIDORDERSLOOP;
                      }
                    }
                    asks.removeAll(asksToRemove);
                  }
                }
              }
            }
          }
        }
      } catch (Exception e) {
        log.error(
            "{}: updateBook - Unable to itterate over buy mock order book stack trace:{} ",
            this.getClass().getSimpleName(),
            e);
      } finally {

        synchronized (pendingOrders.get(market).get(TransactionType.BUY)) {
          pendingOrders.get(market).get(TransactionType.BUY).removeAll(buyOrdersToRemove);
        }
        for (Fill fill : buyFillsToProcess)
          try {
            handleFillProcessing(fill);
          } catch (Exception | Error ex) {
            log.error(
                "{}: updateBook - unable to process buy fill {} {}",
                this.getClass().getSimpleName(),
                fill,
                ex);

            continue;
          }
      }
    }

    if (bids != null
        && pendingOrders.get(market).get(TransactionType.SELL) != null
        && !pendingOrders.get(market).get(TransactionType.SELL).isEmpty()) {

      try {

        log.trace(
            "{}:UpdateBook - determining fills for sell orders {}",
            this.getClass().getSimpleName(),
            pendingOrders.get(market).get(TransactionType.SELL));

        ASKORDERSLOOP:
        for (SpecificOrder order : pendingOrders.get(market).get(TransactionType.SELL)) {
          synchronized (order) {
            if (order.getUnfilledVolumeCount() == 0) {
              sellOrdersToRemove.add(order);
              continue ASKORDERSLOOP;
            }
            if (order.getMarket().equals(market)
                && (order.getTimestamp() <= event.getTimestamp())) {

              log.trace(
                  "{}:UpdateBook - determining fills for sell order {} with working volume {}",
                  this.getClass().getSimpleName(),
                  order.getUuid(),
                  order.getUnfilledVolumeCount());

              synchronized (event) {
                synchronized (bids) {
                  Set<Offer> bidsToRemove = new HashSet<Offer>();
                  BIDSLOOP:
                  for (Offer bid : bids) {
                    if (bid.getVolumeCount() == 0) {
                      bidsToRemove.add(bid);
                      continue BIDSLOOP;
                    }
                    if (bid == null
                        || ((order.getFillType() != null
                                && !order.getFillType().equals(FillType.MARKET))
                            && (order.getLimitPrice() != null
                                && bid != null
                                && order.getLimitPrice().getCount() > bid.getPriceCount()))) {

                      log.trace(
                          "{} :UpdateBook - bid price {} greater than limit price  {} for order {}",
                          this.getClass().getSimpleName(),
                          bid,
                          order.getLimitPrice(),
                          order.getUuid());

                      break ASKORDERSLOOP;
                    }
                    // long sellOrderVolumeCount = order.getUnfilledVolumeCount();

                    //	-30000000,-11
                    long askFillVolume =
                        -Math.min(
                            Math.abs(bid.getVolumeCount()),
                            Math.abs(order.getUnfilledVolumeCount()));
                    if (askFillVolume == 0) {
                      continue ASKORDERSLOOP;
                    }
                    long slippageDiff = Math.round(bid.getPriceCount() * slippage);

                    log.debug(
                        "{}:updateBook - Calculated slippage={} , slippage={}, bid={}  ",
                        this.getClass().getSimpleName(),
                        slippageDiff,
                        slippage,
                        bid);

                    // I want to sell at 22.5 and buy order of only 10, will not get filled, but if
                    // it is market, I will get filled at fill count.
                    // I am selling so want to see at 24 or higher.
                    // if  order.getLimitPrice().getCount() > bid.getPriceCount()) if 24>22.
                    long fillPriceCount = (bid.getPriceCount() - slippageDiff);

                    log.debug(
                        "{}:updateBook - Calculated fillPriceCount={} , limitPrice={} , limitPriceCount={}  , bid={} , bidCount={} , slippageDiff={}",
                        this.getClass().getSimpleName(),
                        fillPriceCount,
                        order.getLimitPrice(),
                        order.getLimitPrice().getCount(),
                        bid,
                        bid.getPriceCount(),
                        slippageDiff);

                    log.debug(
                        "{}:updateBook - Creating fill with bid={} , book={},order={} ,working volume={}",
                        this.getClass().getSimpleName(),
                        bid,
                        quotes.getLastBook(market),
                        order.getUuid(),
                        order.getUnfilledVolumeCount());
                    Fill fill =
                        fillFactory.create(
                            order,
                            context.getTime(),
                            context.getTime(),
                            order.getMarket(),
                            fillPriceCount,
                            askFillVolume,
                            Long.toString(context.getTime().getMillis()));
                    fill.persit();
                    sellFillsToProcess.add(fill);

                    if (order.getUnfilledVolumeCount() == 0) {

                      sellOrdersToRemove.add(order);

                      continue ASKORDERSLOOP;
                    }
                    //	sellOrderVolumeCount = sellOrderVolumeCount - askFillVolume;
                    logFill(order, bid, fill);

                    log.debug(
                        "{}:UpdateBook - set bidVolume {} to {} with askFillVolume  {}",
                        this.getClass().getSimpleName(),
                        bid.getVolumeCount(),
                        (bid.getVolumeCount() < 0
                            ? -((Math.abs(bid.getVolumeCount()) + askFillVolume))
                            : (Math.abs(bid.getVolumeCount() + askFillVolume))),
                        askFillVolume);

                    bid.setVolumeCount(
                        (bid.getVolumeCount() < 0
                            ? -((Math.abs(bid.getVolumeCount()) + askFillVolume))
                            : (Math.abs(bid.getVolumeCount() + askFillVolume))));

                    // bid.setVolumeCount(bid.getVolumeCount()>0 ? Math.abs(bid.getVolumeCount()) +
                    // askFillVolume : );

                    if (fill.getVolume() == null
                        || (fill.getVolume() != null && fill.getVolume().isZero()))
                      log.debug(
                          "fill zero lots {} {}", fill.getUuid(), (order.getUnfilledVolumeCount()));

                    if (fill.getVolume().abs().compareTo(order.getVolume().abs()) > 0)
                      log.debug("overfilled  {}", fill.getUuid());

                    // if the bid is empty, move to next bid
                    if (bid.getVolumeCount() == 0) {
                      bidsToRemove.add(bid);
                      continue BIDSLOOP;
                    }
                    // if this order if filled go to next order
                    if (order.getUnfilledVolumeCount() == 0) {
                      log.debug(
                          "Ask Order {} fully filled, unfilledVolumeCount {}",
                          order.getUuid(),
                          order.getUnfilledVolumeCount());

                      continue ASKORDERSLOOP;
                    }
                  }
                  bids.removeAll(bidsToRemove);
                }
              }
            }
          }
        }

      } catch (Exception e) {

        log.error(
            "{} updateBook - Unable to itterate over sell mock order book {}  stack trace: {}",
            this.getClass().getSimpleName(),
            e);

      } finally {

        synchronized (pendingOrders.get(market).get(TransactionType.SELL)) {
          pendingOrders.get(market).get(TransactionType.SELL).removeAll(sellOrdersToRemove);
        }
        for (Fill fill : sellFillsToProcess) {
          try {
            handleFillProcessing(fill);
          } catch (Exception | Error ex) {

            log.error(
                "{} : updateBook - unable to process sell fill {}  {}",
                this.getClass().getSimpleName(),
                fill,
                ex);

            continue;
          }
        }
      }
    }
  }

  @SuppressWarnings("finally")
  @Override
  protected boolean specificOrderToCancel(SpecificOrder order) {
    boolean deleted = false;
    if (orderStateMap.get(order) != null && orderStateMap.get(order).isNew()) {
      log.error("Cancelling new order " + orderStateMap.get(order) + " :" + order);
      updateOrderState(order, OrderState.CANCELLED, true);

      deleted = true;

      return deleted;
    } else if (orderStateMap.get(order) == null || !orderStateMap.get(order).isOpen()) {
      log.error(
          "Unable to cancel order as is "
              + (orderStateMap.get(order) == null
                  ? order.getOrderState()
                  : orderStateMap.get(order))
              + " :"
              + order.getUuid());
      deleted = true;
      return deleted;
    }

    try {
      if (pendingOrders == null
          || pendingOrders.get(order.getMarket()) == null
          || pendingOrders.get(order.getMarket()).get(order.getTransactionType()) == null)
        return deleted;
      log.trace(
          this.getClass().getSimpleName()
              + ":specificOrderToCancel - removing order("
              + order.hashCode()
              + ") "
              + order
              + " from orderbook ");

      // synchronized (pendingOrders.get(order.getMarket()).get(order.getTransactionType())) {
      //   } catch (Exception e) {
      //     log.error(this.getClass().getSimpleName() + ": addOrder - Unable to itterate over order
      // book " + pendingOrders.get(market).get(TransactionType.BUY) + " stack trace: ", e);
      // } finally {
      //   updateOrderBookLock.unlock();

      // }
      //      log.debug(this.getClass().getSimpleName() + ":cancelSpecificOrder Locking
      // updateOrderBookLock");
      //    updateOrderBookLock.lock();

      //	synchronized (pendingOrders.get(order.getMarket()).get(order.getTransactionType())) {
      if (pendingOrders.get(order.getMarket()).get(order.getTransactionType()).remove(order)) {
        log.debug(
            this.getClass().getSimpleName()
                + ":specificOrderToCancel - removed order("
                + order.hashCode()
                + ") "
                + order
                + " from orderbook ");

        updateOrderState(order, OrderState.CANCELLED, true);

        deleted = true;

      } else {
        //       if
        // (!pendingOrders.get(order.getMarket()).get(order.getTransactionType()).contains(order)) {
        log.error(
            "Unable to cancel order as not present in mock order book. Order:"
                + order
                + " order book ");
        updateOrderState(order, OrderState.REJECTED, true);
        deleted = false;
      }
      // }

      //  }
      //   }
      return deleted;
    } catch (Error | Exception e) {
      log.error("Unable to cancel order :" + order + ". full stack trace", e);
      return deleted;

    } finally {
      // log.debug(this.getClass().getSimpleName() + ":cancelSpecificOrder unlocking
      // updateOrderBookLock");

      //  updateOrderBookLock.unlock();

      //	return deleted;
    }
  }

  private synchronized void addOrder(SpecificOrder order) {
    //   targetDiscrete = (DiscreteAmount) (limitPrice.minus(new DiscreteAmount((long)
    // (targetPrice), market.getPriceBasis()))).abs();
    //   targetDiscrete = (DiscreteAmount) (limitPrice.minus(new DiscreteAmount((long)
    // (targetPrice), market.getPriceBasis()))).abs();

    try {
      //   log.debug(this.getClass().getSimpleName() + ":addOrder Locking updateOrderBookLock");

      //   updateOrderBookLock.lock();

      if (pendingOrders.get(order.getMarket()) == null
          || (pendingOrders.get(order.getMarket()) != null
              && pendingOrders.get(order.getMarket()).isEmpty())) {
        ArrayList<SpecificOrder> orders = new ArrayList<SpecificOrder>();

        //		order.isBid() ? descendingPriceComparator : ascendingPriceComparator);

        //  ArrayList<SpecificOrder> orders = new ArrayList<SpecificOrder>();
        orders.add(order);
        Collections.sort(
            orders,
            order.getTransactionType().equals(TransactionType.BUY)
                ? descendingPriceComparator
                : ascendingPriceComparator);
        Map<TransactionType, ArrayList<SpecificOrder>> orderBook =
            new ConcurrentHashMap<TransactionType, ArrayList<SpecificOrder>>();
        orderBook.put(order.getTransactionType(), orders);

        pendingOrders.put(order.getMarket(), orderBook);
        // getlatest boook and call matcher
        log.trace(
            this.getClass().getSimpleName()
                + ":addOrder("
                + order.hashCode()
                + "): "
                + order.getUuid()
                + " added to mock order book ");
        return;

      } else if (pendingOrders.get(order.getMarket()).get(order.getTransactionType()) == null
          || (pendingOrders.get(order.getMarket()).get(order.getTransactionType()) != null
              && pendingOrders.get(order.getMarket()).get(order.getTransactionType()).isEmpty())) {
        ArrayList<SpecificOrder> orders = new ArrayList<SpecificOrder>();

        // order.isBid() ? descendingPriceComparator : ascendingPriceComparator);
        orders.add(order);
        Collections.sort(
            orders,
            order.getTransactionType().equals(TransactionType.BUY)
                ? descendingPriceComparator
                : ascendingPriceComparator);
        pendingOrders.get(order.getMarket()).put(order.getTransactionType(), orders);
        log.trace(
            this.getClass().getSimpleName()
                + ":addOrder("
                + order.hashCode()
                + "): "
                + order.getUuid()
                + " added to mock order book ");
        return;

      } else {
        //      synchronized (pendingOrders.get(order.getMarket()).get(order.getTransactionType()))
        // {
        if (pendingOrders.get(order.getMarket()).get(order.getTransactionType()).add(order)) {
          Collections.sort(
              pendingOrders.get(order.getMarket()).get(order.getTransactionType()),
              order.getTransactionType().equals(TransactionType.BUY)
                  ? descendingPriceComparator
                  : ascendingPriceComparator);
          // orderBook.add(order);
          // Comparator<SpecificOrder> bookComparator = order.isBid() ? bidComparator :
          // askComparator;

          //
          // Collections.sort(pendingOrders.get(order.getMarket()).get(order.getTransactionType()),
          // order.isBid() ? bidComparator : askComparator);
          log.trace(
              this.getClass().getSimpleName()
                  + ":addOrder("
                  + order.hashCode()
                  + "):  -"
                  + order.getUuid()
                  + " added to mock order book.");

          return;
        } else {
          log.error(
              this.getClass().getSimpleName()
                  + ":addOrder("
                  + order.hashCode()
                  + ") -"
                  + order.getUuid()
                  + " unable to add order to mock order book ");
          pendingOrders.get(order.getMarket()).get(order.getTransactionType()).add(order);

          Collections.sort(
              pendingOrders.get(order.getMarket()).get(order.getTransactionType()),
              order.getTransactionType().equals(TransactionType.BUY)
                  ? descendingPriceComparator
                  : ascendingPriceComparator);
        }
        //       }

        // askComparator
      }

    } catch (Exception e) {
      log.error(
          this.getClass().getSimpleName()
              + ": addOrder - Unable to add order "
              + order
              + "stack trace: ",
          e);
    } finally {
      updateBook(quotes.getLastBook(order.getMarket()));
      //       log.debug(this.getClass().getSimpleName() + ":addOrder Unlocking
      // updateOrderBookLock");
      //     updateOrderBookLock.unlock();

    }
    // }
    //  }

  }

  private void logFill(SpecificOrder order, Offer offer, Fill fill) {
    //  if (log.isDebugEnabled())
    if (order != null && offer != null && fill != null)
      log.debug("Mock fill of Order {} with Offer {} : {}", order, offer, fill);
  }

  // private static Object lock = new Object();
  // private static ConcurrentHashMap<Market, ConcurrentHashMap<TransactionType,
  // ArrayList<SpecificOrder>>> pendingOrders = new ConcurrentHashMap<Market,
  // ConcurrentHashMap<TransactionType, ArrayList<SpecificOrder>>>();
  private static transient Map<Market, Map<TransactionType, ArrayList<SpecificOrder>>>
      pendingOrders =
          new ConcurrentHashMap<Market, Map<TransactionType, ArrayList<SpecificOrder>>>();

  // new ConcurrentSkipListSet<>
  //  new ConcurrentLinkedQueue<SpecificOrder>();

  // new CopyOnWriteArrayList<SpecificOrder>();

  // private QuoteService quotes;

  //  @Override

  // }

  @Override
  public void init() {
    Set<org.cryptocoinpartners.schema.Order> cointraderOpenOrders =
        new HashSet<org.cryptocoinpartners.schema.Order>();

    super.init();
    // Once we have all the order loaded, let's add all the open specific orders to the mock order
    // book (pendingOrders)
    // if (stateOrderMap.get(OrderState.NEW) != null)
    //    cointraderOpenOrders.addAll(stateOrderMap.get(OrderState.NEW));
    if (stateOrderMap.get(OrderState.PLACED) != null)
      cointraderOpenOrders.addAll(stateOrderMap.get(OrderState.PLACED));
    if (stateOrderMap.get(OrderState.PARTFILLED) != null)
      cointraderOpenOrders.addAll(stateOrderMap.get(OrderState.PARTFILLED));
    if (stateOrderMap.get(OrderState.ROUTED) != null)
      cointraderOpenOrders.addAll(stateOrderMap.get(OrderState.ROUTED));
    if (stateOrderMap.get(OrderState.CANCELLING) != null)
      cointraderOpenOrders.addAll(stateOrderMap.get(OrderState.CANCELLING));
    for (org.cryptocoinpartners.schema.Order openOrder : cointraderOpenOrders) {
      if (openOrder instanceof SpecificOrder) addOrder((SpecificOrder) openOrder);
    }
  }

  @Override
  protected OrderState getOrderStateFromOrderService(Order order) throws Throwable {
    // so let's get order from database
    log.trace("getOrderStateFromOrderService - Loading order update from DB for {}", order);
    if (order.getOrderState() != null) return order.getOrderState();
    OrderUpdate orderUpdate =
        EM.namedQueryOne(OrderUpdate.class, "orderUpdate.findStateByOrder", order);

    log.debug(
        this.getClass().getSimpleName()
            + ":getOrderStateFromOrderService - Loaded order update "
            + orderUpdate);
    if (orderUpdate != null) return orderUpdate.getState();
    else return null;
  }

  @Override
  public void updateWorkingOrderQuantity(Order order, Amount quantity) {
    // TODO Auto-generated method stub

  }

  @Override
  public boolean placeOrders(Collection<Order> orders) throws Throwable {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public Collection<SpecificOrder> getPendingOrders(
      Portfolio portfolio, Market market, double interval) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public void lockTriggerOrders() {
    // TODO Auto-generated method stub

  }

  @Override
  public void unlockTriggerOrders() {
    // TODO Auto-generated method stub

  }
}
