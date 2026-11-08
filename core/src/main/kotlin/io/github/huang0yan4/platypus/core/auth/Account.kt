package io.github.huang0yan4.platypus.core.auth

sealed interface Account {
    val displayName: String

    data class Offline(
        override val displayName: String,
        val uuid: String,
    ) : Account

    data class Microsoft(
        override val displayName: String,
        val uuid: String,
        val refreshToken: String,
    ) : Account

    data class Yggdrasil(
        override val displayName: String,
        val uuid: String,
        val serverUrl: String,
        val token: String,
    ) : Account
}

data class Session(
    val username: String,
    val uuid: String,
    val accessToken: String,
    val userType: String = "msa",
)

interface AuthService {
    suspend fun login(account: Account): Session
    suspend fun refresh(session: Session): Session
}
