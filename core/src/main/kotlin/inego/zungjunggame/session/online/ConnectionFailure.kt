package inego.zungjunggame.session.online

/** Why an online table connection was lost. Promoted out of OnlinePlayHost. */
enum class ConnectionFailure { Unreachable, Authentication, TableUnavailable }
