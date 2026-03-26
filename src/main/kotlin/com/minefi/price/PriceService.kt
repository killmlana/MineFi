package com.minefi.price

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

class PriceService {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var cachedEthUsd: BigDecimal = BigDecimal.ZERO
    private var cachedUsdInr: BigDecimal = BigDecimal.ZERO
    private var lastFetchEth: Long = 0
    private var lastFetchInr: Long = 0
    private val cacheDurationMs = 60_000L

    fun getEthUsdPrice(): BigDecimal {
        val now = System.currentTimeMillis()
        if (cachedEthUsd > BigDecimal.ZERO && now - lastFetchEth < cacheDurationMs) {
            return cachedEthUsd
        }

        try {
            val request = Request.Builder()
                .url("https://api.coingecko.com/api/v3/simple/price?ids=ethereum&vs_currencies=usd")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return cachedEthUsd
            val json = JsonParser.parseString(body).asJsonObject
            val price = json.getAsJsonObject("ethereum")?.get("usd")?.asBigDecimal
            if (price != null) {
                cachedEthUsd = price
                lastFetchEth = now
            }
        } catch (e: Exception) {}

        return cachedEthUsd
    }

    fun getUsdInrRate(): BigDecimal {
        val now = System.currentTimeMillis()
        if (cachedUsdInr > BigDecimal.ZERO && now - lastFetchInr < cacheDurationMs) {
            return cachedUsdInr
        }

        try {
            val request = Request.Builder()
                .url("https://api.coingecko.com/api/v3/simple/price?ids=usd-coin&vs_currencies=inr")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return cachedUsdInr
            val json = JsonParser.parseString(body).asJsonObject
            val rate = json.getAsJsonObject("usd-coin")?.get("inr")?.asBigDecimal
            if (rate != null) {
                cachedUsdInr = rate
                lastFetchInr = now
            }
        } catch (e: Exception) {}

        return cachedUsdInr
    }

    fun inrToUsd(inrAmount: BigDecimal): BigDecimal {
        val rate = getUsdInrRate()
        if (rate == BigDecimal.ZERO) return BigDecimal.ZERO
        return inrAmount.divide(rate, 2, java.math.RoundingMode.HALF_UP)
    }

    fun ethToUsd(ethAmount: BigDecimal): BigDecimal {
        val price = getEthUsdPrice()
        if (price == BigDecimal.ZERO) return BigDecimal.ZERO
        return ethAmount.multiply(price).setScale(2, java.math.RoundingMode.HALF_UP)
    }

    fun usdToEth(usdAmount: BigDecimal): BigDecimal {
        val price = getEthUsdPrice()
        if (price == BigDecimal.ZERO) return BigDecimal.ZERO
        return usdAmount.divide(price, 18, java.math.RoundingMode.HALF_UP)
    }
}
