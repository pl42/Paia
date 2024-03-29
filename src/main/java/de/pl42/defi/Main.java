package peggy42.cn;

import peggy42.cn.compounddai.CompoundDai;
import peggy42.cn.contractneedsprovider.*;
import peggy42.cn.dai.Dai;
import peggy42.cn.flipper.Flipper;
import peggy42.cn.gasprovider.GasProvider;
import peggy42.cn.medianizer.Medianizer;
import peggy42.cn.numberutil.Wad18;
import peggy42.cn.oasis.Oasis;
import peggy42.cn.uniswap.Uniswap;
import peggy42.cn.util.Balances;
import peggy42.cn.util.Ethereum;
import peggy42.cn.util.JavaProperties;
import peggy42.cn.weth.Weth;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;

import java.lang.invoke.MethodHandles;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Main {
  private static final org.slf4j.Logger logger =
      LoggerFactory.getLogger(MethodHandles.lookup().lookupClass().getSimpleName());
  private static final boolean IS_DEVELOPMENT_ENVIRONMENT = true;
  private static Web3j web3j;

  public static void main(String[] args) {
    logger.trace("NEW START");
    JavaProperties javaProperties = new JavaProperties(IS_DEVELOPMENT_ENVIRONMENT);
    String password = javaProperties.getValue("password");
    String infuraProjectId = javaProperties.getValue("infuraProjectId");
    String wallet = javaProperties.getValue("wallet");
    boolean playSoundOnTransaction =
            Boolean.parseBoolean(javaProperties.getValue("playSoundOnTransaction"));
    boolean transactionsRequireConfirmation =
            Boolean.parseBoolean(javaProperties.getValue("transactionsRequireConfirmation"));

    CircuitBreaker circuitBreaker = new CircuitBreaker();
    web3j = new Web3jProvider(infuraProjectId).web3j;
    Credentials credentials = new Wallet(password, wallet).getCredentials();
    GasProvider gasProvider =
            new GasProvider(
                    web3j,
                    new Wad18(BigInteger.valueOf(Long.parseLong(javaProperties.getValue("minimumGasPrice")))),
                    new Wad18(BigInteger.valueOf(Long.parseLong(javaProperties.getValue("maximumGasPrice")))));
    Permissions permissions =
            new Permissions(transactionsRequireConfirmation, playSoundOnTransaction);
    ContractNeedsProvider contractNeedsProvider =
            new ContractNeedsProvider(web3j, credentials, gasProvider, permissions, circuitBreaker);

    Medianizer.setMedianizerContract(contractNeedsProvider);
    Dai dai =
            new Dai(
                    contractNeedsProvider,
                    Double.parseDouble(javaProperties.getValue("minimumDaiNecessaryForSaleAndLending")));
    Weth weth = new Weth(contractNeedsProvider);
    CompoundDai compoundDai = new CompoundDai(contractNeedsProvider);
    Ethereum ethereum =
            new Ethereum(
                    contractNeedsProvider,
                    Double.parseDouble(javaProperties.getValue("minimumEthereumReserveUpperLimit")),
                    Double.parseDouble(javaProperties.getValue("minimumEthereumReserveLowerLimit")),
                    Double.parseDouble(javaProperties.getValue("minimumEthereumNecessaryForSale")));

    Balances balances = new Balances(dai, weth, compoundDai, ethereum);

    Oasis oasis = new Oasis(contractNeedsProvider, compoundDai, weth);
    Uniswap uniswap = new Uniswap(contractNeedsProvider, javaProperties, compoundDai, weth);
    Flipper flipper =
            new Flipper(
                    contractNeedsProvider,
                    Double.parseDouble(javaProperties.getValue("minimumFlipAuctionProfit")));

    dai.getApproval().check(uniswap);
    dai.getApproval().check(oasis);
    dai.getApproval().check(compoundDai);
    weth.getApproval().check(oasis);

    while (circuitBreaker.getContinueRunning()) {
      try {
        balances.updateBalance(60);
        if (circuitBreaker.isAllowingOperations(3)) {
          // TODO: if infura backoff exception, then backoff
          balances.checkEnoughEthereumForGas(ethereum);
          oasis.checkIfSellDaiIsProfitableThenDoIt(balances);
          oasis.checkIfBuyDaiIsProfitableThenDoIt(balances);
          uniswap.checkIfSellDaiIsProfitableThenDoIt(balances);
          uniswap.checkIfBuyDaiIsProfitableThenDoIt(balances);
          compoundDai.lendDai(balances);
          flipper.checkIfThereAreProfitableFlipAuctions(balances);
        }
      } catch (Exception e) {
        logger.error("Exception", e);
        shutdown();
      }

      List<Long> failedTransactions = circuitBreaker.getFailedTransactions();
      if (!failedTransactions.isEmpty()) {
        circuitBreaker.update();
        gasProvider.updateFailedTransactions(failedTransactions);
      }

      try {
        TimeUnit.MILLISECONDS.sleep(8500);
      } catch (InterruptedException e) {
        logger.error("Exception", e);
        Thread.currentThread().interrupt();
      }
    }
    shutdown();
  }

  public static void shutdown() {
    logger.trace("EXIT");
    web3j.shutdown();
    System.exit(0);
  }
}
