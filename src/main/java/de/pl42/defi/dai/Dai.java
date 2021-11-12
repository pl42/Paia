package peggy42.cn.dai;

import peggy42.cn.contractneedsprovider.CircuitBreaker;
import peggy42.cn.contractneedsprovider.ContractNeedsProvider;
import peggy42.cn.contractneedsprovider.Permissions;
import peggy42.cn.gasprovider.GasProvider;
import peggy42.cn.util.BigNumberUtil;
import peggy42.cn.util.ContractUser;
import peggy42.cn.util.IContract;
import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.math.BigInteger;

public class Dai extends ContractUser {
  private static final org.slf4j.Logger logger =
      LoggerFactory.getLogger(MethodHandles.lookup().lookupClass().getSimpleName());
  public static final String ADDRESS = "0x6b175474e89094c44da98b954eedeac495271d0f";
  private static final String CDP_ADDRESS = "0x0000000000000000000000000000000000000000";
  private static final String EXCEPTION = "Exception";

  private final DaiContract contract;
  private final GasProvider gasProvider;
  private final Permissions permissions;
  private final Credentials credentials;
  private final CircuitBreaker circuitBreaker;

  public Dai(@NotNull ContractNeedsProvider contractNeedsProvider) {
    Web3j web3j = contractNeedsProvider.getWeb3j();
    credentials = contractNeedsProvider.getCredentials();
    gasProvider = contractNeedsProvider.getGasProvider();
    permissions = contractNeedsProvider.getPermissions();
    circuitBreaker = contractNeedsProvider.getCircuitBreaker();
    contract = DaiContract.load(ADDRESS, web3j, credentials, gasProvider);
  }

  private void approve(String address, String name) {
    if (permissions.check("DAI UNLOCK " + name)) {
      try {
        gasProvider.updateSlowGasPrice();
        contract.approve(address, BigNumberUtil.BIGGEST_NUMBER).send();
        logger.debug("{} UNLOCK DAI", name);
      } catch (Exception e) {
        logger.error(EXCEPTION, e);
        circuitBreaker.add(System.currentTimeMillis());
      }
    }
  }

  public void checkApproval(IContract toAllowContract) {
    try {
      String address = toAllowContract.getAddress();
      BigInteger allowance = contract.allowance(credentials.getAddress(), address).send();
      if (allowance.compareTo(BigNumberUtil.BIGGEST_NUMBER) < 0) {
        logger.warn(
            "ALLOWANCE IS TOO LOW {}",
            BigNumberUtil.makeBigNumberHumanReadableFullPrecision(allowance));
        approve(address, toAllowContract.getClass().getName());
      }
    } catch (Exception e) {
      logger.error(EXCEPTION, e);
    }
  }

  /** @deprecated DO NOT USE CDP ADDRESS THIS IS WRONG ADDRESS */
  @Deprecated
  private void cdpUnlockDai() {
    try {
      TransactionReceipt transferReceipt =
          contract.approve(CDP_ADDRESS, BigNumberUtil.BIGGEST_NUMBER).send();

      logger.debug(
          "Transaction complete, view it at https://etherscan.io/tx/{}",
          transferReceipt.getTransactionHash());

    } catch (Exception e) {
      logger.error(EXCEPTION, e);
    }
  }

  public BigDecimal getBalance(String ethereumAddress) {
    try {
      return new BigDecimal(contract.balanceOf(ethereumAddress).send());
    } catch (Exception e) {
      logger.error(EXCEPTION, e);
    }
    return BigDecimal.ZERO;
  }
}
