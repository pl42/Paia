package peggy42.cn.flipper;

import peggy42.cn.compounddai.CompoundDai;
import peggy42.cn.contractneedsprovider.*;
import peggy42.cn.dai.Dai;
import peggy42.cn.gasprovider.GasProvider;
import peggy42.cn.numberutil.Wad18;
import peggy42.cn.uniswap.Uniswap;
import peggy42.cn.util.Balances;
import peggy42.cn.util.Ethereum;
import peggy42.cn.util.JavaProperties;
import peggy42.cn.weth.Weth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.tuples.generated.Tuple8;

import java.math.BigInteger;

import static peggy42.cn.numberutil.NumberUtil.getMachineReadable;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionIT {
  private static final String TRAVIS_INFURA_PROJECT_ID = "TRAVIS_INFURA_PROJECT_ID";
  private static final String TRAVIS_WALLET = "TRAVIS_WALLET";
  private static final String TRAVIS_PASSWORD = "TRAVIS_PASSWORD";

  Uniswap uniswap;
  Balances balances;
  ContractNeedsProvider contractNeedsProvider;
  JavaProperties javaProperties;
  Weth weth;
  Ethereum ethereum;
  CompoundDai compoundDai;
  Dai dai;
  Credentials credentials;

  @BeforeEach
  void setUp() {
    javaProperties = new JavaProperties(true);

    String infuraProjectId;
    String password;
    String wallet;

    Permissions permissions =
        new Permissions(
            Boolean.parseBoolean(javaProperties.getValue("transactionsRequireConfirmation")),
            Boolean.parseBoolean(javaProperties.getValue("playSoundOnTransaction")));

    if ("true".equals(System.getenv().get("TRAVIS"))) {
      infuraProjectId = System.getenv().get(TRAVIS_INFURA_PROJECT_ID);
      wallet = System.getenv().get(TRAVIS_WALLET);
      password = System.getenv().get(TRAVIS_PASSWORD);
    } else {
      infuraProjectId = javaProperties.getValue("infuraProjectId");
      wallet = javaProperties.getValue("wallet");
      password = javaProperties.getValue("password");
    }

    Web3j web3j = new Web3jProvider(infuraProjectId).web3j;
    GasProvider gasProvider =
            new GasProvider(
                    web3j, new Wad18(1_000000000), new Wad18(1000_000000000L));
    credentials = new Wallet(password, wallet).getCredentials();
    CircuitBreaker circuitBreaker = new CircuitBreaker();
    contractNeedsProvider =
        new ContractNeedsProvider(web3j, credentials, gasProvider, permissions, circuitBreaker);

    dai =
        new Dai(
            contractNeedsProvider,
            Double.parseDouble(javaProperties.getValue("minimumDaiNecessaryForSaleAndLending")));
    compoundDai = new CompoundDai(contractNeedsProvider);
    weth = new Weth(contractNeedsProvider);
    ethereum =
        new Ethereum(
            contractNeedsProvider,
            Double.parseDouble(javaProperties.getValue("minimumEthereumReserveUpperLimit")),
            Double.parseDouble(javaProperties.getValue("minimumEthereumReserveLowerLimit")),
            Double.parseDouble(javaProperties.getValue("minimumEthereumNecessaryForSale")));

    balances = new Balances(dai, weth, compoundDai, ethereum);
    uniswap = new Uniswap(contractNeedsProvider, javaProperties, compoundDai, weth);
  }

  @Test
  void isAffordable_maxDaiOwnedBiggerThanAuctionPrice_true() {
    Tuple8<BigInteger, BigInteger, String, BigInteger, BigInteger, String, String, BigInteger>
            auctionTuple =
            new Tuple8<>(
                    new BigInteger("200000000000000000000000000000000000000000000000"),
                    new BigInteger("10000000000000000000"),
                    "0x04bB161C4e7583CDAaDEe93A8b8E6125FD661E57",
                    new BigInteger("1588287896"),
                    new BigInteger("1588266341"),
                    "0x42A142cc082255CaEE58E3f30dc6d4Fc3056b6A7",
                    "0xA950524441892A31ebddF91d3cEEFa04Bf454466",
                    new BigInteger("37299123089429162514476831876850683361693243730"));
    Auction auction = new Auction(BigInteger.ONE, auctionTuple);
    Wad18 minimumBidIncrease = new Wad18(getMachineReadable(1.0));
    assertTrue(auction.isAffordable(minimumBidIncrease, new Wad18(getMachineReadable(201.0))));
  }

  @Test
  void isAffordable_maxDaiOwnedEqualAuctionPrice_false() {
    Tuple8<BigInteger, BigInteger, String, BigInteger, BigInteger, String, String, BigInteger>
            auctionTuple =
            new Tuple8<>(
                    new BigInteger("200000000000000000000000000000000000000000000000"),
                    new BigInteger("10000000000000000000"),
                    "0x04bB161C4e7583CDAaDEe93A8b8E6125FD661E57",
                    new BigInteger("1588287896"),
                    new BigInteger("1588266341"),
                    "0x42A142cc082255CaEE58E3f30dc6d4Fc3056b6A7",
                    "0xA950524441892A31ebddF91d3cEEFa04Bf454466",
                    new BigInteger("37299123089429162514476831876850683361693243730"));
    Auction auction = new Auction(BigInteger.ONE, auctionTuple);
    Wad18 minimumBidIncrease = new Wad18(getMachineReadable(1.0));
    assertFalse(auction.isAffordable(minimumBidIncrease, new Wad18(getMachineReadable(200.0))));
  }

  @Test
  void isAffordable_maxDaiOwnedSmallerThanAuctionPrice_false() {
    Tuple8<BigInteger, BigInteger, String, BigInteger, BigInteger, String, String, BigInteger>
            auctionTuple =
            new Tuple8<>(
                    new BigInteger("200000000000000000000000000000000000000000000000"),
                    new BigInteger("10000000000000000000"),
                    "0x04bB161C4e7583CDAaDEe93A8b8E6125FD661E57",
                    new BigInteger("1588287896"),
                    new BigInteger("1588266341"),
                    "0x42A142cc082255CaEE58E3f30dc6d4Fc3056b6A7",
                    "0xA950524441892A31ebddF91d3cEEFa04Bf454466",
                    new BigInteger("37299123089429162514476831876850683361693243730"));
    Auction auction = new Auction(BigInteger.ONE, auctionTuple);
    Wad18 minimumBidIncrease = new Wad18(getMachineReadable(1.0));
    assertFalse(auction.isAffordable(minimumBidIncrease, new Wad18(getMachineReadable(199.0))));
  }

  @Test
  void isAffordable_minimumBidMakesAuctionTooExpensive_false() {
    Tuple8<BigInteger, BigInteger, String, BigInteger, BigInteger, String, String, BigInteger>
            auctionTuple =
            new Tuple8<>(
                    new BigInteger("100000000000000000000000000000000000000000000000"),
                    new BigInteger("10000000000000000000"),
                    "0x04bB161C4e7583CDAaDEe93A8b8E6125FD661E57",
                    new BigInteger("1588287896"),
                    new BigInteger("1588266341"),
                    "0x42A142cc082255CaEE58E3f30dc6d4Fc3056b6A7",
                    "0xA950524441892A31ebddF91d3cEEFa04Bf454466",
                    new BigInteger("37299123089429162514476831876850683361693243730"));
    Auction auction = new Auction(BigInteger.ONE, auctionTuple);
    Wad18 minimumBidIncrease = new Wad18(getMachineReadable(1.05));
    assertFalse(auction.isAffordable(minimumBidIncrease, new Wad18(getMachineReadable(105.0))));
  }

  @Test
  void isHighestBidderNotMe_meHighestBidder_True() {
    Tuple8<BigInteger, BigInteger, String, BigInteger, BigInteger, String, String, BigInteger>
            auctionTuple =
            new Tuple8<>(
                    new BigInteger("37299123089429162514476831876850683361693243730"),
                    new BigInteger("175927491330994700"),
                    credentials.getAddress(),
                    new BigInteger("1588287896"),
                    new BigInteger("1588266341"),
                    "0x42A142cc082255CaEE58E3f30dc6d4Fc3056b6A7",
                    "0xA950524441892A31ebddF91d3cEEFa04Bf454466",
                    new BigInteger("37299123089429162514476831876850683361693243730"));
    Auction auction = new Auction(BigInteger.ONE, auctionTuple);
    assertTrue(auction.amIHighestBidder(credentials));
  }

  @Test
  void isHighestBidderNotMe_someoneElseHighestBidder_False() {
    Tuple8<BigInteger, BigInteger, String, BigInteger, BigInteger, String, String, BigInteger>
            auctionTuple =
            new Tuple8<>(
                    new BigInteger("37299123089429162514476831876850683361693243730"),
                    new BigInteger("175927491330994700"),
                    "0x04bB161C4e7583CDAaDEe93A8b8E6125FD661E57",
                    new BigInteger("1588287896"),
                    new BigInteger("1588266341"),
                    "0x42A142cc082255CaEE58E3f30dc6d4Fc3056b6A7",
                    "0xA950524441892A31ebddF91d3cEEFa04Bf454466",
                    new BigInteger("37299123089429162514476831876850683361693243730"));
    Auction auction = new Auction(BigInteger.ONE, auctionTuple);
    assertFalse(auction.amIHighestBidder(credentials));
  }
}
