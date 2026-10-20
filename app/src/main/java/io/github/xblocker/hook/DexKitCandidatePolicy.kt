package io.github.xblocker.hook

import java.lang.reflect.Modifier

/** Pure validation also used by tests; no target classes are loaded in the module app. */
internal object DexKitCandidatePolicy {
    const val RESPONSE = "okhttp3.Response"
    const val FAST_DESCRIPTOR = "Lokhttp3/internal/connection/RealCall;->getResponseWithInterceptorChain\$okhttp()Lokhttp3/Response;"
    val requiredCalls = setOf(
        "Lokhttp3/OkHttpClient;->interceptors()Ljava/util/List;",
        "Lokhttp3/OkHttpClient;->networkInterceptors()Ljava/util/List;",
        "Lokhttp3/internal/http/RealInterceptorChain;->proceed(Lokhttp3/Request;)Lokhttp3/Response;",
    )
    private val descriptorPattern = Regex("^Lokhttp3/[A-Za-z0-9_$/]+;->[A-Za-z0-9_$]+\\(\\)Lokhttp3/Response;$")

    fun validDescriptor(value: String): Boolean = value.length <= 1024 && descriptorPattern.matches(value)

    fun accepts(descriptor: String, modifiers: Int, strings: Collection<String>, calls: Collection<String>): Boolean =
        validDescriptor(descriptor) && !Modifier.isStatic(modifiers) && !Modifier.isAbstract(modifiers) &&
            !Modifier.isNative(modifiers) && "Canceled" in strings && calls.containsAll(requiredCalls)

    /** Duplicate definitions in different splits are ambiguous too. Never silently pick the first. */
    fun unique(candidates: List<String>): String? = candidates.singleOrNull()?.takeIf(::validDescriptor)
}
