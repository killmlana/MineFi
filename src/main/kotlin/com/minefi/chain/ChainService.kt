package com.minefi.chain

import org.web3j.crypto.Credentials
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Convert
import org.web3j.utils.Numeric
import java.math.BigDecimal
import java.math.BigInteger

class ChainService(rpcUrl: String, private val chainId: Long = 31337) {

    private val web3j: Web3j = Web3j.build(HttpService(rpcUrl))

    fun getBalanceWei(address: String): BigInteger {
        return web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST)
            .send().balance
    }

    fun getBalanceEther(address: String): BigDecimal {
        val wei = getBalanceWei(address)
        return Convert.fromWei(BigDecimal(wei), Convert.Unit.ETHER)
    }

    fun getTokenBalance(tokenContract: String, walletAddress: String): BigInteger {
        val data = "0x70a08231000000000000000000000000${walletAddress.removePrefix("0x")}"
        val result = web3j.ethCall(
            org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                walletAddress, tokenContract, data
            ),
            DefaultBlockParameterName.LATEST,
        ).send()
        return if (result.value.isNullOrEmpty() || result.value == "0x") {
            BigInteger.ZERO
        } else {
            BigInteger(result.value.removePrefix("0x"), 16)
        }
    }

    fun getTransactionReceipt(txHash: String): TransactionReceipt? {
        return web3j.ethGetTransactionReceipt(txHash).send().transactionReceipt.orElse(null)
    }

    fun estimateTransferGasEther(): BigDecimal {
        val gasPrice = web3j.ethGasPrice().send().gasPrice
        val gasLimit = BigInteger.valueOf(21000)
        val gasCostWei = gasPrice.multiply(gasLimit)
        val buffered = gasCostWei.multiply(BigInteger.valueOf(110)).divide(BigInteger.valueOf(100))
        return Convert.fromWei(BigDecimal(buffered), Convert.Unit.ETHER)
    }

    fun sendEth(privateKey: String, to: String, amountEther: BigDecimal): String {
        val credentials = Credentials.create(privateKey)
        val nonce = web3j.ethGetTransactionCount(credentials.address, DefaultBlockParameterName.PENDING)
            .send().transactionCount
        val gasPrice = web3j.ethGasPrice().send().gasPrice
        val gasLimit = BigInteger.valueOf(21000)
        val weiValue = Convert.toWei(amountEther, Convert.Unit.ETHER).toBigInteger()

        val rawTx = RawTransaction.createEtherTransaction(nonce, gasPrice, gasLimit, to, weiValue)
        val signed = TransactionEncoder.signMessage(rawTx, chainId, credentials)
        val hex = Numeric.toHexString(signed)

        val response = web3j.ethSendRawTransaction(hex).send()
        if (response.hasError()) {
            throw RuntimeException("Send failed: ${response.error.message}")
        }
        return response.transactionHash
    }

    fun vaultDeposit(privateKey: String, contractAddress: String, playerAddress: String, amountWei: BigInteger): String {
        val credentials = Credentials.create(privateKey)
        val nonce = web3j.ethGetTransactionCount(credentials.address, DefaultBlockParameterName.PENDING)
            .send().transactionCount
        val gasPrice = web3j.ethGasPrice().send().gasPrice
        val gasLimit = BigInteger.valueOf(100000)
        val paddedAddress = playerAddress.removePrefix("0x").padStart(64, '0')
        val data = "0xf340fa01$paddedAddress"

        val rawTx = RawTransaction.createTransaction(nonce, gasPrice, gasLimit, contractAddress, amountWei, data)
        val signed = TransactionEncoder.signMessage(rawTx, chainId, credentials)
        val response = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send()
        if (response.hasError()) throw RuntimeException("Deposit failed: ${response.error.message}")
        return response.transactionHash
    }

    fun vaultWithdraw(privateKey: String, contractAddress: String, playerAddress: String, amountWei: BigInteger, signature: ByteArray): String {
        val credentials = Credentials.create(privateKey)
        val nonce = web3j.ethGetTransactionCount(credentials.address, DefaultBlockParameterName.PENDING)
            .send().transactionCount
        val gasPrice = web3j.ethGasPrice().send().gasPrice
        val gasLimit = BigInteger.valueOf(150000)
        val paddedPlayer = playerAddress.removePrefix("0x").padStart(64, '0')
        val paddedAmount = amountWei.toString(16).padStart(64, '0')
        val sigHex = signature.joinToString("") { "%02x".format(it) }
        val sigLen = signature.size.toString(16).padStart(64, '0')
        val sigPadded = sigHex.padEnd((((signature.size + 31) / 32) * 64), '0')
        val offset = "0000000000000000000000000000000000000000000000000000000000000060"

        val selector = org.web3j.crypto.Hash.sha3String("withdraw(address,uint256,bytes)").substring(0, 10)
        val data = "$selector$paddedPlayer$paddedAmount$offset$sigLen$sigPadded"

        val rawTx = RawTransaction.createTransaction(nonce, gasPrice, gasLimit, contractAddress, BigInteger.ZERO, data)
        val signed = TransactionEncoder.signMessage(rawTx, chainId, credentials)
        val response = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send()
        if (response.hasError()) throw RuntimeException("Withdraw failed: ${response.error.message}")
        return response.transactionHash
    }

    fun vaultBalanceOf(contractAddress: String, playerAddress: String): BigInteger {
        val paddedAddress = playerAddress.removePrefix("0x").padStart(64, '0')
        val data = "0xe3d670d7$paddedAddress"
        val result = web3j.ethCall(
            org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                playerAddress, contractAddress, data
            ),
            DefaultBlockParameterName.LATEST,
        ).send()
        return if (result.value.isNullOrEmpty() || result.value == "0x") {
            BigInteger.ZERO
        } else {
            BigInteger(result.value.removePrefix("0x"), 16)
        }
    }

    fun vaultGetNonce(contractAddress: String, playerAddress: String): BigInteger {
        val paddedAddress = playerAddress.removePrefix("0x").padStart(64, '0')
        val data = "0x7ecebe00$paddedAddress"
        val result = web3j.ethCall(
            org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                playerAddress, contractAddress, data
            ),
            DefaultBlockParameterName.LATEST,
        ).send()
        return if (result.value.isNullOrEmpty() || result.value == "0x") {
            BigInteger.ZERO
        } else {
            BigInteger(result.value.removePrefix("0x"), 16)
        }
    }

    fun vaultUpdateRoot(privateKey: String, contractAddress: String, rootBytes: ByteArray): String {
        val credentials = Credentials.create(privateKey)
        val nonce = web3j.ethGetTransactionCount(credentials.address, DefaultBlockParameterName.PENDING)
            .send().transactionCount
        val gasPrice = web3j.ethGasPrice().send().gasPrice
        val gasLimit = BigInteger.valueOf(100000)
        val selector = org.web3j.crypto.Hash.sha3String("updateBalanceRoot(bytes32)").substring(0, 10)
        val rootHex = rootBytes.joinToString("") { "%02x".format(it) }
        val data = "$selector$rootHex"

        val rawTx = RawTransaction.createTransaction(nonce, gasPrice, gasLimit, contractAddress, BigInteger.ZERO, data)
        val signed = TransactionEncoder.signMessage(rawTx, chainId, credentials)
        val response = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send()
        if (response.hasError()) throw RuntimeException("Root update failed: ${response.error.message}")
        return response.transactionHash
    }

    fun getCurrentBlockNumber(): BigInteger {
        return web3j.ethBlockNumber().send().blockNumber
    }

    fun shutdown() {
        web3j.shutdown()
    }
}
