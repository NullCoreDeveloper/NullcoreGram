package tw.nekomimi.nekogram.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.telegram.messenger.FileLog
import org.telegram.tgnet.ConnectionsManager
import tw.nekomimi.nekogram.NekoConfig
import xyz.nextalone.nagram.network.NetworkRequestBuilder
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

object DnsFactory {
    private const val STATUS_NOERROR = 0
    private const val STATUS_NXDOMAIN = 3

    fun providers(): Array<String> {
        val custom = NekoConfig.customDoH.String().trim()
        val providerMode = NekoConfig.dohProvider.Int()
        if (providerMode == 6 && custom.isNotEmpty()) {
            return arrayOf(custom)
        }
        return when (providerMode) {
            1 -> arrayOf(
                "https://185.222.222.222/dns-query",
                "https://45.11.45.11/dns-query",
                "https://doh.dns.sb/dns-query"
            )
            2 -> arrayOf(
                "https://94.140.14.14/resolve",
                "https://94.140.15.15/resolve",
                "https://dns.adguard-dns.com/resolve"
            )
            3 -> arrayOf(
                "https://8.8.8.8/resolve",
                "https://8.8.4.4/resolve",
                "https://dns.google/resolve"
            )
            4 -> arrayOf(
                "https://1.1.1.1/dns-query",
                "https://1.0.0.1/dns-query",
                "https://cloudflare-dns.com/dns-query"
            )
            5 -> arrayOf(
                "https://223.5.5.5/resolve",
                "https://223.6.6.6/resolve",
                "https://dns.alidns.com/resolve"
            )
            else -> if (custom.isNotEmpty()) arrayOf(custom) else arrayOf(
                "https://185.222.222.222/dns-query",
                "https://94.140.14.14/resolve",
                "https://8.8.8.8/resolve",
                "https://1.1.1.1/dns-query",
                "https://45.11.45.11/dns-query",
                "https://94.140.15.15/resolve"
            )
        }
    }

    private val cache = mutableMapOf<String, List<InetAddress>>()
    private val txtCache = mutableMapOf<String, List<String>>()

    class CustomException(message: String) : Exception(message)

    @JvmStatic
    @JvmOverloads
    fun lookup(domain: String, fallback: Boolean = false): List<InetAddress> {

        if (!NekoConfig.useSystemDNS.Bool()) {

            FileLog.d("Lookup $domain")

            ConnectionsManager.getIpStrategy()

            val cached = cache[domain]
            if (cached != null) {
                FileLog.d("DNS cache hit for $domain: $cached")
                return cached
            }

            val noFallback = ConnectionsManager.hasIpv4 || ConnectionsManager.hasIpv6
            val recordType = if (noFallback) {
                if (ConnectionsManager.hasIpv4) "A" else "AAAA"
            } else if (!fallback) "A" else "AAAA"

            val counterAll = AtomicInteger(0)
            val counterGood = AtomicInteger(0)

            val ret = runBlocking {
                val ret: List<InetAddress>? = suspendCancellableCoroutine {
                    for (provider in providers()) {
                        launch(Dispatchers.IO) {
                            try {
                                val response = makeDohJsonRequest(provider, domain, recordType)

                                if (response.statusCode !in 200..299) {
                                    throw CustomException("$provider not successful: ${response.statusCode}")
                                }

                                val jsonResponse = JSONObject(response.body)
                                val status = jsonResponse.optInt("Status", -1)

                                if (status != STATUS_NOERROR && status != STATUS_NXDOMAIN) {
                                    throw CustomException("$provider DNS error: status=$status")
                                }

                                if (status == STATUS_NXDOMAIN) {
                                    throw CustomException("$provider NXDOMAIN")
                                }

                                val answers = jsonResponse.optJSONArray("Answer")
                                if (answers == null || answers.length() == 0) {
                                    if (!noFallback && !fallback) {
                                        val aaaaResult = lookup(domain, true)
                                        if (aaaaResult.isNotEmpty()) {
                                            cache[domain] = aaaaResult
                                            counterGood.incrementAndGet()
                                            it.resume(aaaaResult)
                                        }
                                    }
                                    throw CustomException("$provider no answer")
                                }

                                val addresses = mutableListOf<InetAddress>()
                                for (i in 0 until answers.length()) {
                                    val answer = answers.getJSONObject(i)
                                    val data = answer.optString("data", "")
                                    if (data.isNotBlank()) {
                                        try {
                                            addresses.add(InetAddress.getByName(data))
                                        } catch (e: Exception) {
                                            FileLog.w("Failed to parse address: $data")
                                        }
                                    }
                                }

                                if (addresses.isNotEmpty() && counterGood.incrementAndGet() == 1) {
                                    cache[domain] = addresses
                                    FileLog.d("DNS Result $domain: $addresses")
                                    it.resume(addresses)
                                }
                            } catch (e: Exception) {
                                if (e is CustomException) {
                                    FileLog.e(e)
                                } else {
                                    FileLog.w(e.stackTraceToString())
                                }
                            }
                            if (counterAll.incrementAndGet() == providers().size && counterGood.get() == 0) {
                                it.resume(null)
                            }
                        }
                    }

                    launch {
                        delay(5000L)
                    }
                }

                ret
            }

            if (ret != null) return ret
        }

        FileLog.d("Try system dns to resolve $domain")

        try {
            return InetAddress.getAllByName(domain).toList()
        } catch (e: Exception) {
            FileLog.d("System dns fail: ${e.message ?: e.javaClass.simpleName}")
        }

        return listOf()
    }

    @JvmStatic
    fun getTxts(domain: String): List<String> {

        FileLog.d("Lookup $domain for txts")

        val cached = txtCache[domain]
        if (cached != null) {
            FileLog.d("TXT cache hit for $domain: $cached")
            return cached
        }

        val counterAll = AtomicInteger(0)
        val counterGood = AtomicInteger(0)

        return runBlocking {
            val ret: List<String> = suspendCancellableCoroutine {
                for (provider in providers()) {
                    launch(Dispatchers.IO) {
                        try {
                            val response = makeDohJsonRequest(provider, domain, "TXT")

                            if (response.statusCode !in 200..299) {
                                throw CustomException("$provider not successful: ${response.statusCode}")
                            }

                            val jsonResponse = JSONObject(response.body)
                            val status = jsonResponse.optInt("Status", -1)

                            if (status != STATUS_NOERROR && status != STATUS_NXDOMAIN) {
                                throw CustomException("$provider DNS error: status=$status")
                            }

                            if (status == STATUS_NXDOMAIN) {
                                throw CustomException("$provider NXDOMAIN")
                            }

                            val answers = jsonResponse.optJSONArray("Answer")
                            if (answers == null || answers.length() == 0) {
                                throw CustomException("$provider no TXT answer")
                            }

                            val txts = mutableListOf<String>()
                            for (i in 0 until answers.length()) {
                                val answer = answers.getJSONObject(i)
                                val data = answer.optString("data", "")
                                if (data.isNotBlank()) {
                                    val cleanData = cleanTxtData(data)
                                    if (cleanData.isNotBlank()) {
                                        txts.add(cleanData)
                                    }
                                }
                            }

                            if (txts.isNotEmpty() && counterGood.incrementAndGet() == 1) {
                                txtCache[domain] = txts
                                FileLog.d("TXT Result $domain: $txts")
                                it.resume(txts)
                            }
                        } catch (e: Exception) {
                            if (e is CustomException) {
                                FileLog.e(e)
                            } else {
                                FileLog.w(e.stackTraceToString())
                            }
                        }
                        if (counterAll.incrementAndGet() == providers().size && counterGood.get() == 0) {
                            it.resume(listOf())
                        }
                    }
                }

                launch {
                    delay(5000L)
                }
            }

            ret
        }
    }

    private fun makeDohJsonRequest(provider: String, name: String, type: String): xyz.nextalone.nagram.network.NetworkResponse {
        return NetworkRequestBuilder.get(provider) {
            header("accept", "application/dns-json")
            parameter("name", name)
            parameter("type", type)
        }.execute()
    }

    private fun cleanTxtData(data: String): String {
        var result = data.trim()
        if (result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length - 1)
        }
        result = result.replace("\\\"", "\"")
        result = result.replace("\\\\", "\\")
        return result
    }
}
