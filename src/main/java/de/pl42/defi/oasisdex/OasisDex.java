package peggy42.cn.oasisdex;

import peggy42.cn.compounddai.CompoundDai;
import peggy42.cn.contractneedsprovider.CircuitBreaker;
import peggy42.cn.contractneedsprovider.ContractNeedsProvider;
import peggy42.cn.contractneedsprovider.Permissions;
import peggy42.cn.dai.Dai;
import peggy42.cn.gasprovider.GasProvider;
import peggy42.cn.medianizer.MedianException;
import peggy42.cn.medianizer.Medianizer;
import peggy42.cn.util.Balances;
import peggy42.cn.util.IContract;
import peggy42.cn.weth.Weth;
import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tuples.generated.Tuple4;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static peggy42.cn.util.BigNumberUtil.*;
import static peggy42.cn.util.ProfitCalculator.getPotentialProfit;

public class OasisDex implements IContract {
  public static final String ADDRESS = "0x794e6e91555438aFc3ccF1c5076A74F42133d08D";
  private static final org.slf4j.Logger logger =
      LoggerFactory.getLogger(MethodHandles.lookup().lookupClass().getSimpleName());
  private final Weth weth;
  private final CompoundDai compoundDai;
  private final GasProvider gasProvider;
  private final Permissions permissions;
  private final CircuitBreaker circuitBreaker;
  private final OasisDexContract contract;

  public OasisDex(
      @NotNull ContractNeedsProvider contractNeedsProvider, CompoundDai compoundDai, Weth weth) {
    Web3j web3j = contractNeedsProvider.getWeb3j();
    Credentials credentials = contractNeedsProvider.getCredentials();
    permissions = contractNeedsProvider.getPermissions();
    gasProvider = contractNeedsProvider.getGasProvider();
    circuitBreaker = contractNeedsProvider.getCircuitBreaker();
    contract = OasisDexContract.load(ADDRESS, web3j, credentials, gasProvider);
    this.compoundDai = compoundDai;
    this.weth = weth;
    isContractValid();
  }

  void isContractValid() {
    try {
      contract.isValid();
      logger.trace("OASISDEX CONTRACT IS VALID");
    } catch (IOException e) {
      CircuitBreaker.stopRunning();
      logger.error("Exception", e);
    }
  }

  // warning offerValues can be empty
  @NotNull
  Map<String, BigDecimal> getOffer(BigInteger bestOffer) throws Exception {
    Map<String, BigDecimal> offerValues = new HashMap<>();
    Tuple4<BigInteger, String, BigInteger, String> getOffer = contract.getOffer(bestOffer).send();

    offerValues.put(getOffer.component2(), new BigDecimal(getOffer.component1()));
    offerValues.put(getOffer.component4(), new BigDecimal(getOffer.component3()));

    // TODO: test this method (equal should not care about capitalization)
    if (!(getOffer.component2().equals(Weth.ADDRESS) && getOffer.component4().equals(Dai.ADDRESS))
        && (!(getOffer.component2().equals(Dai.ADDRESS)
            && getOffer.component4().equals(Weth.ADDRESS)))) {
      logger.info("BIG INTEGER 1 {}", getOffer.component1());
      logger.info("CONTRACT ADDRESS 1 {}", getOffer.component2());
      logger.info("BIG INTEGER 2 {}", getOffer.component3());
      logger.info("CONTRACT ADDRESS 2 {}", getOffer.component4());
      throw new DaiOrWethMissingException("BOTH DAI AND WETH NEED TO BE PRESENT ONCE.");
    }
    return offerValues;
  }

  // TODO: maybe calling method should check for BigInteger.ZERO
  BigInteger getBestOffer(String buyAddress, String sellAddress) {
    try {
      return (contract.getBestOffer(buyAddress, sellAddress).send());
    } catch (Exception e) {
      logger.error("Exception", e);
      return BigInteger.ZERO;
    }
  }

  public void checkIfBuyDaiIsProfitableThenDoIt(@NotNull Balances balances) {
    if (balances.checkTooFewEthOrWeth()) {
      logger.info("NOT ENOUGH WETH AND ETH TO BUY DAI ON ETH2DAI");
      return;
    }
    try {
      logger.info("ETH2DAI BUY DAI PROFIT CALCULATION");
      BigDecimal medianEthereumPrice = Medianizer.getPrice();
      OasisDexOffer bestOffer =
          buyDaiSellWethIsProfitable(
              medianEthereumPrice,
              balances,
              gasProvider.getPercentageOfProfitAsFee(
                  gasProvider.getFailedTransactionsWithinTheLastTwelveHoursForGasPriceArrayList()));
      if (bestOffer.offerId.compareTo(BigInteger.ZERO) != 0) {
        String weiValue = "100000000"; // INFO: seems to be necessary due to rounding error
        BigDecimal ownConstraint =
            multiply(
                    balances
                        .getWethBalance()
                        .add(balances.getEthBalance())
                        .subtract(Balances.MINIMUM_ETHEREUM_RESERVE_UPPER_LIMIT),
                    bestOffer.bestOfferDaiPerEth)
                .subtract(new BigDecimal(weiValue));
        BigDecimal offerConstraint = bestOffer.offerValues.get(Dai.ADDRESS);
        logger.debug("OWN CONSTRAINT {}", makeBigNumberHumanReadableFullPrecision(ownConstraint));
        logger.debug(
            "OFFER CONSTRAINT {}", makeBigNumberHumanReadableFullPrecision(offerConstraint));
        if (balances.getEthBalance().compareTo(Balances.MINIMUM_ETHEREUM_RESERVE_UPPER_LIMIT) > 0) {
          weth.eth2Weth(
              balances
                  .getEthBalance()
                  .subtract(Balances.MINIMUM_ETHEREUM_RESERVE_UPPER_LIMIT)
                  .toBigInteger(),
              bestOffer.profit,
              medianEthereumPrice,
              balances);
        }
        eth2DaiTakeOrder(
            bestOffer.offerId,
            ownConstraint.min(offerConstraint).toBigInteger(),
            bestOffer.profit,
            medianEthereumPrice,
            balances);
      }
    } catch (MedianException e) {
      logger.error("Exception", e);
    }
  }

  public void checkIfSellDaiIsProfitableThenDoIt(@NotNull Balances balances) {
    if (balances.checkTooFewDaiAndDaiInCompound()) {
      logger.info("NOT ENOUGH DAI TO SELL DAI ON ETH2DAI");
      return;
    }
    try {
      logger.trace("ETH2DAI SELL DAI PROFIT CALCULATION");
      BigDecimal medianEthereumPrice = Medianizer.getPrice();
      BigDecimal maxDaiToSell = balances.getMaxDaiToSell();
      OasisDexOffer bestOffer =
          sellDaiBuyWethIsProfitable(
              medianEthereumPrice,
              maxDaiToSell,
              balances,
              gasProvider.getPercentageOfProfitAsFee(
                  gasProvider.getFailedTransactionsWithinTheLastTwelveHoursForGasPriceArrayList()));
      if (bestOffer.offerId.compareTo(BigInteger.ZERO) != 0) {
        BigDecimal ownConstraint = divide(balances.getMaxDaiToSell(), bestOffer.bestOfferDaiPerEth);
        BigDecimal offerConstraint = bestOffer.offerValues.get(Weth.ADDRESS);
        logger.debug("OWN CONSTRAINT {}", makeBigNumberHumanReadableFullPrecision(ownConstraint));
        logger.debug(
            "OFFER CONSTRAINT {}", makeBigNumberHumanReadableFullPrecision(offerConstraint));
        if (compoundDai.canOtherProfitMethodsWorkWithoutCDaiConversion(
            balances, bestOffer.profit, medianEthereumPrice)) {
          eth2DaiTakeOrder(
              bestOffer.offerId,
              ownConstraint.min(offerConstraint).toBigInteger(),
              bestOffer.profit,
              medianEthereumPrice,
              balances);
        }
      }
    } catch (MedianException e) {
      logger.error("Exception", e);
    }
  }

  // TODO: make both profitable methods into one
  @NotNull
  private OasisDexOffer buyDaiSellWethIsProfitable(
      BigDecimal medianEthereumPrice, Balances balances, double percentageOfProfitAsFee) {
    BigInteger bestOffer = getBestOffer(Dai.ADDRESS, Weth.ADDRESS);
    logger.trace("BEST BUY-DAI-OFFER ID {}", bestOffer);
    Map<String, BigDecimal> offerValues;
    try {
      offerValues = getOffer(bestOffer);
    } catch (Exception e) {
      logger.error("Exception", e);
      return new OasisDexOffer(BigInteger.ZERO, null, null, BigDecimal.ZERO);
    }
    logger.trace(
        "BUYABLE DAI AMOUNT {}{}",
        makeBigNumberHumanReadableFullPrecision(offerValues.get(Dai.ADDRESS)),
        " DAI");
    logger.trace(
        "SELLABLE WETH AMOUNT {}{}",
        makeBigNumberHumanReadableFullPrecision(offerValues.get(Weth.ADDRESS)),
        " WETH");
    if (!offerValues.get(Weth.ADDRESS).equals(BigDecimal.ZERO)) {
      BigDecimal bestOfferEthDaiRatioBuyDaiOnEth2Dai =
          divide(offerValues.get(Dai.ADDRESS), offerValues.get(Weth.ADDRESS));
      BigDecimal bestOfferMedianRatio =
          divide(medianEthereumPrice, bestOfferEthDaiRatioBuyDaiOnEth2Dai);
      logger.trace(
          "DAI PER WETH {}{}",
          makeBigNumberHumanReadable(bestOfferEthDaiRatioBuyDaiOnEth2Dai),
          " WETH/DAI");
      logger.trace("MEDIAN-OFFER RATIO {}", makeBigNumberHumanReadable(bestOfferMedianRatio));
      BigDecimal potentialProfit =
          getPotentialProfit(
              bestOfferMedianRatio,
              multiply(
                  ((balances
                          .getWethBalance()
                          .add(
                              BigDecimal.ZERO.max(
                                  balances
                                      .getEthBalance()
                                      .subtract(Balances.MINIMUM_ETHEREUM_RESERVE_UPPER_LIMIT))))
                      .min(offerValues.get(Weth.ADDRESS))),
                  bestOfferEthDaiRatioBuyDaiOnEth2Dai),
              percentageOfProfitAsFee);
      if (potentialProfit.compareTo(balances.getMinimumTradeProfitBuyDai()) > 0) {
        return new OasisDexOffer(
            bestOffer, offerValues, bestOfferEthDaiRatioBuyDaiOnEth2Dai, potentialProfit);
      }
    } else {
      logger.trace("OFFER TAKEN DURING PROCESSING!");
    }
    return new OasisDexOffer(BigInteger.ZERO, null, null, BigDecimal.ZERO);
  }

  // Checks if selling dai is profitable.
  @NotNull
  private OasisDexOffer sellDaiBuyWethIsProfitable(
      BigDecimal medianEthereumPrice,
      BigDecimal maxDaiToSell,
      Balances balances,
      double percentageOfProfitAsFee) {
    BigInteger bestOffer = getBestOffer(Weth.ADDRESS, Dai.ADDRESS);
    logger.trace("BEST SELL-DAI-OFFER ID {}", bestOffer);
    Map<String, BigDecimal> offerValues;
    try {
      offerValues = getOffer(bestOffer);
    } catch (Exception e) {
      logger.error("Exception", e);
      return new OasisDexOffer(BigInteger.ZERO, null, null, BigDecimal.ZERO);
    }
    logger.trace(
        "BUYABLE WETH AMOUNT {}{}",
        makeBigNumberHumanReadableFullPrecision(offerValues.get(Weth.ADDRESS)),
        " WETH");
    logger.trace(
        "SELLABLE DAI AMOUNT {}{}",
        makeBigNumberHumanReadableFullPrecision(offerValues.get(Dai.ADDRESS)),
        " DAI");

    if (!offerValues.get(Weth.ADDRESS).equals(BigDecimal.ZERO)) {
      BigDecimal bestOfferEthDaiRatioSellDaiOnEth2Dai =
          divide(offerValues.get(Dai.ADDRESS), offerValues.get(Weth.ADDRESS));
      BigDecimal bestOfferMedianRatio =
          divide(bestOfferEthDaiRatioSellDaiOnEth2Dai, medianEthereumPrice);
      logger.trace(
          "OFFER ETH PRICE {}{}",
          makeBigNumberHumanReadable(bestOfferEthDaiRatioSellDaiOnEth2Dai),
          " WETH/DAI");
      logger.trace("OFFER-MEDIAN RATIO {}", makeBigNumberHumanReadable(bestOfferMedianRatio));
      BigDecimal potentialProfit =
          getPotentialProfit(
              bestOfferMedianRatio,
              maxDaiToSell.min(offerValues.get(Dai.ADDRESS)),
              percentageOfProfitAsFee);
      // TODO: do constraints already here

      if (potentialProfit.compareTo(balances.getMinimumTradeProfitSellDai()) > 0) {
        return new OasisDexOffer(
            bestOffer, offerValues, bestOfferEthDaiRatioSellDaiOnEth2Dai, potentialProfit);
      }
    } else {
      logger.trace("OFFER TAKEN DURING PROCESSING!");
    }
    return new OasisDexOffer(BigInteger.ZERO, null, null, BigDecimal.ZERO);
  }

  private void eth2DaiTakeOrder(
      BigInteger offerId,
      BigInteger amountToBuy,
      BigDecimal potentialProfit,
      BigDecimal medianEthereumPrice,
      Balances balances) {
    if (permissions.check("ETH2DAI BUY ORDER")) {
      try {
        gasProvider.updateFastGasPrice(medianEthereumPrice, potentialProfit);
        TransactionReceipt transferReceipt = contract.buy(offerId, amountToBuy).send();
        logger.info(
            "Transaction complete, view it at https://etherscan.io/tx/{}",
            transferReceipt.getTransactionHash());
        TimeUnit.SECONDS.sleep(
            1); // for Balances to update, otherwise same (buy/sell) type of transaction happens,
        // although not enough balance weth/dai
        balances.refreshLastSuccessfulTransaction();
        balances.addToSumEstimatedProfits(potentialProfit);
      } catch (Exception e) {
        circuitBreaker.add(System.currentTimeMillis());
        balances.addToSumEstimatedMissedProfits(potentialProfit);
        logger.error("Exception", e);
      }
      balances.updateBalanceInformation(medianEthereumPrice);
    }
  }

  public String getAddress() {
    return ADDRESS;
  }
}
