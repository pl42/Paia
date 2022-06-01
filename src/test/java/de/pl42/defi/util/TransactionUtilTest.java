package peggy42.cn.util;

import peggy42.cn.numberutil.Wad18;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static peggy42.cn.numberutil.NumberUtil.getMachineReadable;
import static peggy42.cn.util.TransactionUtil.getTransactionCosts;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionUtilTest {

  @Test
  void getTransactionCosts_realNumbers_true() {
    // 2 * 222.53 * 300,000 * 0.00000001 = 1.33518
    Wad18 gasPrice = new Wad18("10000000000"); // 10 GWEI
    Wad18 medianEthereumPrice = new Wad18(getMachineReadable(222.53));
    BigInteger gasLimit = BigInteger.valueOf(300000);
    Wad18 wad18 = getTransactionCosts(gasPrice, medianEthereumPrice, gasLimit, 2);
    System.out.println(wad18);
    assertTrue(wad18.compareTo(new Wad18(getMachineReadable(1.33518))) == 0);
  }
}
