package peggy42.cn.flipper;

import peggy42.cn.compounddai.CompoundDai;
import peggy42.cn.contractneedsprovider.*;
import peggy42.cn.dai.Dai;
import peggy42.cn.gasprovider.GasProvider;
import peggy42.cn.numberutil.Rad45;
import peggy42.cn.numberutil.Wad18;
import peggy42.cn.util.Balances;
import peggy42.cn.util.Ethereum;
import peggy42.cn.util.JavaProperties;
import peggy42.cn.weth.Weth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.util.ArrayList;

import static peggy42.cn.numberutil.NumberUtil.getMachineReadable;
import static org.junit.jupiter.api.Assertions.*;

class FlipperIT {
  private static final String TRAVIS_INFURA_PROJECT_ID = "TRAVIS_INFURA_PROJECT_ID";
  private static final String TRAVIS_WALLET = "TRAVIS_WALLET";
  private static final String TRAVIS_PASSWORD = "TRAVIS_PASSWORD";

  private static final Wad18 minimumGasPrice = new Wad18(1_000000000);
  private static final Wad18 maximumGasPrice = new Wad18(200_000000000L);
  Flipper flipper;
  Balances balances;

  @BeforeEach
  void setUp() {
    String infuraProjectId;
    String password;
    String wallet;

    JavaProperties javaProperties = new JavaProperties(true);

    if ("true".equals(System.getenv().get("TRAVIS"))) {
      infuraProjectId = System.getenv().get(TRAVIS_INFURA_PROJECT_ID);
      wallet = System.getenv().get(TRAVIS_WALLET);
      password = System.getenv().get(TRAVIS_PASSWORD);
    } else {
      infuraProjectId = javaProperties.getValue("infuraProjectId");
      wallet = javaProperties.getValue("wallet");
      password = javaProperties.getValue("password");
    }

    CircuitBreaker circuitBreaker = new CircuitBreaker();
    Web3j web3j = new Web3jProvider(infuraProjectId).web3j;
    Credentials credentials = new Wallet(password, wallet).getCredentials();
    GasProvider gasProvider = new GasProvider(web3j, minimumGasPrice, maximumGasPrice);
    Permissions permissions = new Permissions(true, true);
    ContractNeedsProvider contractNeedsProvider =
        new ContractNeedsProvider(web3j, credentials, gasProvider, permissions, circuitBreaker);
    flipper =
        new Flipper(
            contractNeedsProvider,
            Double.parseDouble(javaProperties.getValue("minimumFlipAuctionProfit")));

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

    balances = new Balances(dai, weth, compoundDai, ethereum);
  }

  @Test
  void getTotalAuctionCount_noParameters_biggerThan4888() {
    BigInteger actualValue = flipper.getTotalAuctionCount();
    assertTrue(actualValue.compareTo(new BigInteger("4888")) >= 0);
  }

  @Test
  void getAuction_4885_attributesAreCorrect() {
    Auction auction = flipper.getAuction(new BigInteger("4885"));
    assertEquals(
        0,
        auction.bidAmountInDai.compareTo(
            new Rad45("37299123089429162514476831876850683361693243730")));
    assertEquals(0, auction.collateralForSale.compareTo(new Wad18("175927491330994700")));
    assertTrue(
        auction.highestBidder.equalsIgnoreCase("0x04bB161C4e7583CDAaDEe93A8b8E6125FD661E57"));
    assertEquals(0, auction.bidExpiry.compareTo(new Wad18("1588287896")));
    assertEquals(0, auction.maxAuctionDuration.compareTo(new Wad18("1588266341")));
    assertTrue(
        auction.addressOfAuctionedVault.equalsIgnoreCase(
            "0x42A142cc082255CaEE58E3f30dc6d4Fc3056b6A7"));
    assertTrue(
        auction.recipientOfAuctionIncome.equalsIgnoreCase(
            "0xA950524441892A31ebddF91d3cEEFa04Bf454466"));
    assertEquals(
        0,
        auction.totalDaiWanted.compareTo(
            new Rad45("37299123089429162514476831876850683361693243730")));
  }

  @Test
  void getActiveAuctionList_noParameter_noException() {
    BigInteger actualValue = flipper.getTotalAuctionCount();
    ArrayList<Auction> auctionList = flipper.getActiveAffordableAuctionList(actualValue, balances);
    Wad18 minimumBidIncrease = flipper.getMinimumBidIncrease();
    for (Auction auction : auctionList) {
      assertTrue(auction.isActive());
      assertTrue(auction.isAffordable(minimumBidIncrease, balances.getMaxDaiToSell()));
    }
  }

  @Test
  void getMinimumBidIncrease_noParameter_noException() {
    Wad18 actualValue = flipper.getMinimumBidIncrease();
    assertEquals(new Wad18(getMachineReadable(1.03)), actualValue);
    assertDoesNotThrow(() -> flipper.getMinimumBidIncrease());
  }

  @Test
  void getBidLength_noParameter_noException() {
    BigInteger actualValue = flipper.getBidDuration();
    assertEquals(BigInteger.valueOf(21600L), actualValue);
    assertDoesNotThrow(() -> flipper.getBidDuration());
  }

  @Test
  void getAuctionLength_noParameter_noException() {
    BigInteger actualValue = flipper.getAuctionLength();
    assertEquals(BigInteger.valueOf(21600L), actualValue);
    assertDoesNotThrow(() -> flipper.getAuctionLength());
  }
}
