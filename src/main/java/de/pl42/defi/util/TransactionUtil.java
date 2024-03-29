package peggy42.cn.util;

import peggy42.cn.numberutil.Wad18;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;

import static peggy42.cn.numberutil.NumberUtil.getMachineReadable;

public class TransactionUtil {
  private TransactionUtil() {
    throw new IllegalStateException("Utility class");
  }

  public static Wad18 getTransactionCosts(
          @NotNull Wad18 slowGasPrice, Wad18 medianEthereumPrice, @NotNull BigInteger gasLimit, int times) {
    return new Wad18(getMachineReadable((double) times))
            .multiply(new Wad18(getMachineReadable(gasLimit.doubleValue())))
            .multiply(slowGasPrice.multiply(medianEthereumPrice));
  }
}
