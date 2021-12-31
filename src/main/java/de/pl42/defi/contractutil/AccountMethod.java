package peggy42.cn.contractutil;

import org.web3j.protocol.core.RemoteFunctionCall;

import java.math.BigInteger;

public interface AccountMethod {
  RemoteFunctionCall<BigInteger> balanceOf(String param0);
}
