package peggy42.cn.compounddai;

import peggy42.cn.contractneedsprovider.*;
import peggy42.cn.gasprovider.GasProvider;
import peggy42.cn.numberutil.Sth28;
import peggy42.cn.numberutil.Wad18;
import peggy42.cn.util.JavaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CompoundDaiIT {
  private static final String TRAVIS_INFURA_PROJECT_ID = "TRAVIS_INFURA_PROJECT_ID";
  private static final String TRAVIS_WALLET = "TRAVIS_WALLET";
  private static final String TRAVIS_PASSWORD = "TRAVIS_PASSWORD";

  private static final String TOO_HIGH = "Error, value is too high";
  private static final String TOO_LOW = "Error, value is too low";

  private static final Wad18 MINIMUM_GAS_PRICE = new Wad18(1_000000000);
  private static final Wad18 MAXIMUM_GAS_PRICE = new Wad18(200_000000000L);
  CompoundDai compoundDai;

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
    GasProvider gasProvider = new GasProvider(web3j, MINIMUM_GAS_PRICE, MAXIMUM_GAS_PRICE);
    Permissions permissions = new Permissions(true, true);
    ContractNeedsProvider contractNeedsProvider =
            new ContractNeedsProvider(web3j, credentials, gasProvider, permissions, circuitBreaker);
    compoundDai = new CompoundDai(contractNeedsProvider);
  }

  @Test
  void getExchangeRate_isBiggerThanHistoricRate_true() {
    Sth28 actual = compoundDai.getExchangeRate();
    Sth28 expected = new Sth28("204721828221871910000000000");
    assertTrue(actual.compareTo(expected) > 0);
  }

  @Test
  void getSupplyRate_betweenRealisticBounds_true() {
    Wad18 actual = compoundDai.getSupplyRate();
    Wad18 lowerBound = new Wad18("100000000000000"); // 0.001% = 0.0001
    Wad18 higherBound = new Wad18("500000000000000000"); // 50% = 0.5
    assertTrue(actual.compareTo(higherBound) < 0, TOO_LOW);
    assertTrue(actual.compareTo(lowerBound) > 0, TOO_HIGH);
  }

  @Test
  void getDailyInterest_betweenRealisticBounds_true() {
    Wad18 daiSupplied = new Wad18("5000000000000000000000");
    Wad18 actual = compoundDai.getDailyInterest(daiSupplied);
    Wad18 lowerBound = new Wad18("1360000000000000"); // 0.001% = 0.0001 / 365 * 5000
    Wad18 higherBound = new Wad18("6840000000000000000"); // 50% = 0.5 / 365 * 5000
    assertTrue(actual.compareTo(higherBound) < 0, TOO_LOW);
    assertTrue(actual.compareTo(lowerBound) > 0, TOO_HIGH);
  }
}
