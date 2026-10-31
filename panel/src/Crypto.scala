package panel

import java.io.FileInputStream

// SHA-256, HMAC and PBKDF2 for the password and the sessions: Scala Native has no MessageDigest. Checked against the
// JVM's implementations in the tests.

object Sha256 {
  // First 32 bits of the fractional parts of the square roots of the first 8 primes and of the cube roots of the first 64.
  private val primes: Array[Int] = Iterator.from(2).filter(n => (2 until n).forall(n % _ != 0)).take(64).toArray
  private def fraction(x: Double): Int = ((x - Math.floor(x)) * 4294967296.0).toLong.toInt
  private val H0: Array[Int] = primes.take(8).map(p => fraction(Math.sqrt(p.toDouble)))
  private val K: Array[Int] = primes.map(p => fraction(Math.cbrt(p.toDouble)))

  private def compress(state: Array[Int], block: Array[Byte], offset: Int, w: Array[Int]): Unit = {
    var i = 0
    while (i < 16) {
      val o = offset + i * 4
      w(i) = ((block(o) & 0xff) << 24) | ((block(o + 1) & 0xff) << 16) | ((block(o + 2) & 0xff) << 8) | (block(o + 3) & 0xff)
      i += 1
    }
    while (i < 64) {
      val s0 = Integer.rotateRight(w(i - 15), 7) ^ Integer.rotateRight(w(i - 15), 18) ^ (w(i - 15) >>> 3)
      val s1 = Integer.rotateRight(w(i - 2), 17) ^ Integer.rotateRight(w(i - 2), 19) ^ (w(i - 2) >>> 10)
      w(i) = w(i - 16) + s0 + w(i - 7) + s1
      i += 1
    }
    var a = state(0); var b = state(1); var c = state(2); var d = state(3)
    var e = state(4); var f = state(5); var g = state(6); var h = state(7)
    i = 0
    while (i < 64) {
      val s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25)
      val ch = (e & f) ^ (~e & g)
      val t1 = h + s1 + ch + K(i) + w(i)
      val s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22)
      val maj = (a & b) ^ (a & c) ^ (b & c)
      val t2 = s0 + maj
      h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
      i += 1
    }
    state(0) += a; state(1) += b; state(2) += c; state(3) += d
    state(4) += e; state(5) += f; state(6) += g; state(7) += h
  }

  def digest(message: Array[Byte]): Array[Byte] = {
    val state = H0.clone()
    val w = new Array[Int](64)
    val fullBlocks = message.length / 64
    var i = 0
    while (i < fullBlocks) { compress(state, message, i * 64, w); i += 1 }
    // Padding: 0x80, zeros, the length in bits on 8 bytes.
    val rest = message.length - fullBlocks * 64
    val tail = new Array[Byte](if (rest < 56) 64 else 128)
    System.arraycopy(message, fullBlocks * 64, tail, 0, rest)
    tail(rest) = 0x80.toByte
    val bits = message.length.toLong * 8
    var j = 0
    while (j < 8) { tail(tail.length - 1 - j) = ((bits >>> (8 * j)) & 0xff).toByte; j += 1 }
    compress(state, tail, 0, w)
    if (tail.length == 128) compress(state, tail, 64, w)
    val out = new Array[Byte](32)
    i = 0
    while (i < 8) {
      out(i * 4) = (state(i) >>> 24).toByte; out(i * 4 + 1) = (state(i) >>> 16).toByte
      out(i * 4 + 2) = (state(i) >>> 8).toByte; out(i * 4 + 3) = state(i).toByte
      i += 1
    }
    out
  }
}

object Hmac {
  def sha256(key: Array[Byte], message: Array[Byte]): Array[Byte] = {
    val k = (if (key.length > 64) Sha256.digest(key) else key).padTo(64, 0.toByte)
    val inner = k.map(b => (b ^ 0x36).toByte) ++ message
    val outer = k.map(b => (b ^ 0x5c).toByte) ++ Sha256.digest(inner)
    Sha256.digest(outer)
  }
}

object Pbkdf2 {

  /** PBKDF2-HMAC-SHA256 with a 32 byte result (one block). */
  def sha256(password: Array[Byte], salt: Array[Byte], iterations: Int): Array[Byte] = {
    require(iterations >= 1)
    var u = Hmac.sha256(password, salt ++ Array[Byte](0, 0, 0, 1))
    val t = u.clone()
    var i = 1
    while (i < iterations) {
      u = Hmac.sha256(password, u)
      var j = 0
      while (j < 32) { t(j) = (t(j) ^ u(j)).toByte; j += 1 }
      i += 1
    }
    t
  }
}

object Encoding {
  private val Hex = "0123456789abcdef"

  def hex(bytes: Array[Byte]): String = {
    val out = new StringBuilder
    bytes.foreach(b => { out += Hex((b >> 4) & 0xf); out += Hex(b & 0xf) })
    out.toString
  }

  def unhex(s: String): Option[Array[Byte]] =
    if (s.length % 2 != 0 || !s.forall(Hex.contains(_))) None
    else Some(s.grouped(2).map(p => ((Hex.indexOf(p(0)) << 4) | Hex.indexOf(p(1))).toByte).toArray)

  /** Base64 with the URL alphabet and no padding: fine in cookies. */
  def base64Url(bytes: Array[Byte]): String = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  def unbase64Url(s: String): Option[Array[Byte]] =
    try Some(java.util.Base64.getUrlDecoder.decode(s))
    catch { case _: IllegalArgumentException => None }
}

object Secure {

  /** The same time whatever the position of the first difference. */
  def equal(a: Array[Byte], b: Array[Byte]): Boolean = {
    var diff = a.length ^ b.length
    var i = 0
    while (i < Math.min(a.length, b.length)) { diff |= a(i) ^ b(i); i += 1 }
    diff == 0
  }

  def randomBytes(n: Int): Array[Byte] = {
    val in = new FileInputStream("/dev/urandom")
    try {
      val bytes = new Array[Byte](n)
      var read = 0
      while (read < n) {
        val r = in.read(bytes, read, n - read)
        if (r < 0) throw new java.io.IOException("/dev/urandom ended")
        read += r
      }
      bytes
    } finally in.close()
  }
}
