package panel

/** The password of the panel, kept as a PBKDF2 hash. */
object Passwords {
  val Iterations = 120000

  def hash(password: String, salt: Array[Byte]): String = {
    val derived = Pbkdf2.sha256(password.getBytes("UTF-8"), salt, Iterations)
    s"pbkdf2-sha256$$$Iterations$$${Encoding.base64Url(salt)}$$${Encoding.base64Url(derived)}"
  }

  def hash(password: String): String = hash(password, Secure.randomBytes(16))

  def verify(password: String, stored: String): Boolean =
    stored.trim.split('$') match {
      case Array("pbkdf2-sha256", iterations, salt, expected) =>
        (for {
          n <- iterations.toIntOption.filter(i => i >= 1 && i <= 10000000)
          s <- Encoding.unbase64Url(salt)
          e <- Encoding.unbase64Url(expected)
        } yield Secure.equal(Pbkdf2.sha256(password.getBytes("UTF-8"), s, n), e)).getOrElse(false)
      case _ => false
    }
}

/**
 * Sessions: `<issued>.<expiry>.<nonce>` signed with a secret that only the panel knows. The server keeps no list of them, only
 * the moment from which they are valid: logging out and changing the password move it, which ends every session issued before.
 */
object Sessions {
  def issue(secret: Array[Byte], nowMillis: Long, ttlSeconds: Long): String = {
    val expiry = nowMillis / 1000 + ttlSeconds
    val payload = Encoding.base64Url(s"$nowMillis.$expiry.${Encoding.hex(Secure.randomBytes(8))}".getBytes("UTF-8"))
    s"$payload.${Encoding.base64Url(Hmac.sha256(secret, payload.getBytes("UTF-8")))}"
  }

  /** @param revokedAtMillis sessions issued at or before this moment are not valid anymore */
  def valid(secret: Array[Byte], token: String, nowMillis: Long, revokedAtMillis: Long): Boolean =
    token.split('.') match {
      case Array(payload, signature) =>
        val expected = Hmac.sha256(secret, payload.getBytes("UTF-8"))
        Encoding.unbase64Url(signature).exists(Secure.equal(_, expected)) &&
        Encoding.unbase64Url(payload).map(b => new String(b, "UTF-8").split('.')).exists {
          case Array(issued, expiry, _) =>
            (for { i <- issued.toLongOption; e <- expiry.toLongOption } yield i > revokedAtMillis && e > nowMillis / 1000 && i <= nowMillis + 60000)
              .getOrElse(false)
          case _ => false
        }
      case _ => false
    }
}

object Lockout {
  val WindowSeconds = 300L
  val MaxFailures = 5

  /** Seconds to wait before logging in again, if there were too many failures in the last five minutes. */
  def retryAfter(failures: Seq[Long], nowSeconds: Long): Option[Long] = {
    val recent = failures.filter(_ > nowSeconds - WindowSeconds).sorted
    if (recent.length < MaxFailures) None
    else Some(Math.max(1L, recent(recent.length - MaxFailures) + WindowSeconds - nowSeconds))
  }
}
