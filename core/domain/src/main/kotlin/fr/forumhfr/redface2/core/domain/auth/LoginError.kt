package fr.forumhfr.redface2.core.domain.auth

sealed class LoginError : Exception() {
    /** HFR returned the "Votre mot de passe ou nom d'utilisateur n'est pas valide" page. */
    data object InvalidCredentials : LoginError() {
        // Throwable -> Serializable, so the JVM treats every subtype, including data objects,
        // as Java-serializable. Without a readResolve hook a deserialized InvalidCredentials
        // would be a *new* instance, breaking the singleton invariant that callers rely on
        // (`is`, `==`, exhaustive `when`). No code path serializes LoginError today, but the
        // hook is cheap and removes a footgun for future Bundle/Parcelable consumers.
        @Suppress("unused")
        private fun readResolve(): Any = InvalidCredentials
    }

    /** HFR returned the "Afin de prévenir les tentatives de flood" anti-bot page. */
    data object RateLimited : LoginError() {
        @Suppress("unused")
        private fun readResolve(): Any = RateLimited
    }

    /** I/O failure: no network, DNS, TLS handshake, timeout, etc. */
    data class Network(override val cause: Throwable) : LoginError()

    /**
     * Unexpected response shape: the success page rendered without the md_user cookie, an
     * unknown HTTP code, or HTML that matches none of the known success/failure patterns.
     */
    data class Unknown(val detail: String) : LoginError()
}
