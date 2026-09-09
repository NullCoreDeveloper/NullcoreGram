package org.telegram.tgnet

import android.content.Context
import org.chromium.net.CronetEngine
import org.chromium.net.ExperimentalCronetEngine
import org.telegram.messenger.ApplicationLoader
import java.util.HashMap

object CronetEchEngine {

    private const val DNS_PLATFORM_FEATURE_FLAG =
        "ChromiumBaseFeature_CronetEnableDnsPlatform"

    /**
     * Enables Chromium platform DNS for Cronet before native FeatureList
     * initialization while preserving any HTTP flags supplied by the system.
     */
    private fun installDnsPlatformFeatureOverride() {
        try {
            val context = ApplicationLoader.applicationContext

            val zzemClass = Class.forName("org.chromium.net.impl.zzem")
            val sourceMethod = zzemClass.getDeclaredMethod("zzr")
            sourceMethod.isAccessible = true

            val cronetSource = sourceMethod.invoke(null)
                ?: throw IllegalStateException("CronetSource is null")

            val cronetSourceClass =
                Class.forName("org.chromium.net.impl.CronetLogger\$CronetSource")

            val zzclClass = Class.forName("org.chromium.net.impl.zzcl")
            val resolveFlagsMethod = zzclClass.getDeclaredMethod(
                "zza",
                Context::class.java,
                cronetSourceClass
            )
            resolveFlagsMethod.isAccessible = true

            val resolvedFlags = resolveFlagsMethod.invoke(
                null,
                context,
                cronetSource
            ) ?: throw IllegalStateException("Resolved Cronet flags are null")

            val zzecClass = Class.forName("org.chromium.net.internal.zzec")
            val getMapMethod = zzecClass.getDeclaredMethod("zza")
            getMapMethod.isAccessible = true

            val existingFlags =
                getMapMethod.invoke(resolvedFlags) as? Map<*, *>
                    ?: throw IllegalStateException("Cronet flags map is null")

            val mergedFlags = HashMap<Any, Any>()

            for ((key, value) in existingFlags) {
                if (key != null && value != null) {
                    mergedFlags[key] = value
                }
            }

            val zzebClass = Class.forName("org.chromium.net.internal.zzeb")
            val booleanValueConstructor = zzebClass.getConstructor(
                Boolean::class.javaPrimitiveType!!
            )

            mergedFlags[DNS_PLATFORM_FEATURE_FLAG] =
                booleanValueConstructor.newInstance(true)

            val zzecConstructor = zzecClass.getConstructor(Map::class.java)
            val replacementResolvedFlags =
                zzecConstructor.newInstance(mergedFlags)

            /*
             * CronetLibraryLoader reads the cached ResolvedFlags from zzdz.zza
             * when creating native base::Feature overrides.
             */
            val zzdzClass = Class.forName("org.chromium.net.internal.zzdz")
            val cacheField = zzdzClass.getDeclaredField("zza")
            cacheField.isAccessible = true
            cacheField.set(null, replacementResolvedFlags)
        } catch (e: Throwable) {
            throw IllegalStateException(
                "Unable to install CronetEnableDnsPlatform override",
                e
            )
        }
    }

    val engine: CronetEngine by lazy {
        /*
         * Must run before Builder/build(). Once native FeatureList is
         * initialized, Cronet no longer accepts these base::Feature overrides.
         */
        installDnsPlatformFeatureOverride()

        ExperimentalCronetEngine.Builder(ApplicationLoader.applicationContext)
            .enableQuic(false)
            .enableHttp2(true)
            .setExperimentalOptions(
                """
                {
                  "AsyncDNS": {
                    "enable": true
                  },
                  "UseDnsHttpsSvcb": {
                    "enable": true,
                    "use_alpn": true
                  }
                }
                """.trimIndent()
            )
            .setDnsOptions(
                org.chromium.net.DnsOptions.builder()
                    .useBuiltInDnsResolver(true)
                    .build()
            )
            .build()
    }
}
