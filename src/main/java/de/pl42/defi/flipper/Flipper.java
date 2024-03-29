package peggy42.cn.flipper;

import peggy42.cn.contractneedsprovider.CircuitBreaker;
import peggy42.cn.contractneedsprovider.ContractNeedsProvider;
import peggy42.cn.contractneedsprovider.Permissions;
import peggy42.cn.gasprovider.ArrayListUtil;
import peggy42.cn.gasprovider.GasProvider;
import peggy42.cn.medianizer.MedianException;
import peggy42.cn.medianizer.Medianizer;
import peggy42.cn.numberutil.Wad18;
import peggy42.cn.util.Balances;
import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tuples.generated.Tuple8;

import java.lang.invoke.MethodHandles;
import java.math.BigInteger;
import java.util.ArrayList;

import static peggy42.cn.numberutil.NumberUtil.getMachineReadable;

public class Flipper {
  public static final String ADDRESS = "0xd8a04F5412223F513DC55F839574430f5EC15531";
  private static final org.slf4j.Logger logger =
      LoggerFactory.getLogger(MethodHandles.lookup().lookupClass().getSimpleName());
  private static final String EXCEPTION = "Exception";
  private final FlipperContract flipperContract;
  private final Credentials credentials;
  private final Permissions permissions;
  private final CircuitBreaker circuitBreaker;
  private final Wad18 minimumBidIncrease; // beg
  private final BigInteger startingBiddingBeforeEnd;
  private final Wad18 minimumFlipAuctionProfit;
  private ArrayList<Auction> activeAuctionList;
  private BigInteger pastTotalAuctionCount;

  public Flipper(
      @NotNull ContractNeedsProvider contractNeedsProvider, double minimumFlipAuctionProfit) {
    Web3j web3j = contractNeedsProvider.getWeb3j();
    credentials = contractNeedsProvider.getCredentials();
    permissions = contractNeedsProvider.getPermissions();
    circuitBreaker = contractNeedsProvider.getCircuitBreaker();
    GasProvider gasProvider = contractNeedsProvider.getGasProvider();
    flipperContract = FlipperContract.load(ADDRESS, web3j, credentials, gasProvider);
    pastTotalAuctionCount = BigInteger.ZERO;
    minimumBidIncrease = getMinimumBidIncrease();
    activeAuctionList = new ArrayList<>();
    this.minimumFlipAuctionProfit = new Wad18(getMachineReadable(minimumFlipAuctionProfit));
    startingBiddingBeforeEnd = BigInteger.valueOf(300); // 5 minutes * 60 seconds
  }

  private void bid(@NotNull Auction auction, boolean isDent) {
    logger.trace("BIDDING ON {} DURING {} PHASE", auction, isDent ? "DENT" : "TEND");
    if (isDent) {
      dent(auction);
    } else {
      tend(auction);
    }
  }

  private void tend(@NotNull Auction auction) {
    if (permissions.check("TEND")) {
      try {
        TransactionReceipt transferReceipt =
            flipperContract
                .tend(
                    auction.id,
                    auction.collateralForSale.toBigInteger(),
                    auction.bidAmountInDai.multiply(getMinimumBidIncrease()).toBigInteger())
                .send();
        logger.trace(
            "Transaction complete, view it at https://etherscan.io/tx/{}",
            transferReceipt.getTransactionHash());
      } catch (Exception e) {
        logger.error(EXCEPTION, e);
        circuitBreaker.addTransactionFailedNow();
      }
    }
  }

  private void dent(@NotNull Auction auction) {
    if (permissions.check("DENT")) {
      try {
        TransactionReceipt transferReceipt =
            flipperContract
                .dent(
                    auction.id,
                    auction.collateralForSale.divide(getMinimumBidIncrease()).toBigInteger(),
                    auction.bidAmountInDai.toBigInteger())
                .send();
        logger.trace(
            "Transaction complete, view it at https://etherscan.io/tx/{}",
            transferReceipt.getTransactionHash());
      } catch (Exception e) {
        logger.error(EXCEPTION, e);
        circuitBreaker.addTransactionFailedNow();
      }
    }
  }

  public void checkIfThereAreProfitableFlipAuctions(Balances balances) {
    logger.trace("");
    logger.trace("CHECKING IF THERE ARE ANY PROFITABLE FLIP AUCTIONS");
    BigInteger totalAuctionCount = getTotalAuctionCount();
    if (totalAuctionCount.compareTo(BigInteger.ZERO) == 0) return;
    ArrayList<Auction> auctionList = getActiveAffordableAuctionList(totalAuctionCount, balances);
    Wad18 median;
    try {
      median = Medianizer.getPrice();
    } catch (MedianException e) {
      logger.error(EXCEPTION, e);
      return;
    }
    for (Auction auction : auctionList) {
      Wad18 potentialProfit = auction.getPotentialProfit(minimumBidIncrease, median);
      boolean isDent = auction.isDent(minimumBidIncrease);
      if (!auction.isInDefinedBiddingPhase(startingBiddingBeforeEnd, isDent)) {
        logger.trace("AUCTION IS NOT IN DEFINED BIDDING PHASE");
        return;
      }
      if (potentialProfit.compareTo(minimumFlipAuctionProfit) < 0) {
        logger.trace("FLIP AUCTION IS LESS PROFITABLE THAN MINIMUM PROFIT");
        return;
      }
      if (auction.amIHighestBidder(credentials)) {
        logger.trace("I AM THE HIGHEST BIDDER");
        return;
      }
      if (!auction.isAffordable(minimumBidIncrease, balances.getMaxDaiToSell())) {
        logger.trace("THERE IS A PROFITABLE FLIP AUCTION, BUT CONVERSION IS NOT YET IMPLEMENTED");
        return;
      }
      balances.weth.checkIfWeth2EthConversionNecessaryThenDoIt(
              auction.bidAmountInDai.multiply(minimumBidIncrease), balances, potentialProfit, median);
      bid(auction, isDent);
    }
  }

  BigInteger getAuctionLength() {
    BigInteger currentAuctionLength = BigInteger.valueOf(21600L);
    try {
      currentAuctionLength = flipperContract.tau().send();
      logger.trace("UPDATED AUCTION LENGTH {}", currentAuctionLength);
    } catch (Exception e) {
      logger.error(EXCEPTION, e);
    }
    return currentAuctionLength;
  }

  BigInteger getBidDuration() {
    BigInteger currentBidDuration = BigInteger.valueOf(21600L);
    try {
      currentBidDuration = flipperContract.ttl().send();
      logger.trace("UPDATED BID DURATION {}", currentBidDuration);
    } catch (Exception e) {
      logger.error(EXCEPTION, e);
    }
    return currentBidDuration;
  }

  Wad18 getMinimumBidIncrease() {
    Wad18 currentMinimumBidIncrease = new Wad18(BigInteger.valueOf(1030000000000000000L));
    try {
      currentMinimumBidIncrease = new Wad18(flipperContract.beg().send());
      logger.trace("UPDATED MINIMUM BID INCREASE {}", currentMinimumBidIncrease);
    } catch (Exception e) {
      logger.error(EXCEPTION, e);
    }
    return currentMinimumBidIncrease;
  }

  ArrayList<Auction> getActiveAffordableAuctionList(
      BigInteger totalAuctionCount, Balances balances) {

    logger.trace("PAST TOTAL AUCTION COUNT {}", pastTotalAuctionCount);

    updateAlreadyFoundAuctions(balances);

    boolean auctionIsCompleted = false;
    BigInteger auctionCounter = totalAuctionCount;
    while (!(auctionIsCompleted || pastTotalAuctionCount.compareTo(auctionCounter) == 0)) {
      Auction auction = getAuction(auctionCounter);
      if (auction != null) {
        if (auction.isCompleted()) {
          auctionIsCompleted = true;
        } else if (auction.isActive()
                && auction.isAffordable(minimumBidIncrease, balances.getTotalBalanceInUSD())) {
          activeAuctionList.add(auction);
        }
      }
      auctionCounter = auctionCounter.subtract(BigInteger.ONE);
    }
    logger.trace("ACTIVE AFFORDABLE AUCTION LIST SIZE: {}", activeAuctionList.size());
    if (!activeAuctionList.isEmpty())
      logger.trace("ACTIVE AUCTION LIST: {}", ArrayListUtil.toString(activeAuctionList));
    pastTotalAuctionCount = totalAuctionCount;
    return activeAuctionList;
  }

  private void updateAlreadyFoundAuctions(Balances balances) {
    ArrayList<Auction> newActiveAuctionList = new ArrayList<>();
    for (Auction value : activeAuctionList) {
      Auction auction = getAuction(value.id);
      if (auction != null
              && auction.isActive()
              && auction.isAffordable(minimumBidIncrease, balances.getTotalBalanceInUSD()))
        newActiveAuctionList.add(auction);
      logger.trace("UPDATED AUCTION {}", auction);
    }
    this.activeAuctionList = newActiveAuctionList;
  }

  Auction getAuction(BigInteger auctionId) {
    Tuple8<BigInteger, BigInteger, String, BigInteger, BigInteger, String, String, BigInteger>
        auctionTuple = null;
    try {
      auctionTuple = flipperContract.bids(auctionId).send();
    } catch (Exception e) {
      logger.error(EXCEPTION, e);
    }
    if (auctionTuple == null) return null;
    return new Auction(auctionId, auctionTuple);
  }

  BigInteger getTotalAuctionCount() {
    BigInteger totalAuctionCount = BigInteger.ZERO;
    try {
      totalAuctionCount = flipperContract.kicks().send();
    } catch (Exception e) {
      logger.error(EXCEPTION, e);
    }
    logger.trace("TOTAL AUCTION COUNT {}", totalAuctionCount);
    return totalAuctionCount;
  }
}
