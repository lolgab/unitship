//> using test.dep org.scalameta::munit::1.3.6
package panel

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.{PBEKeySpec, SecretKeySpec}

class CryptoTests extends munit.FunSuite {
  private val random = new java.util.Random(42)
  private def bytes(n: Int) = { val b = new Array[Byte](n); random.nextBytes(b); b }

  test("sha256 of the standard vectors") {
    assertEquals(Encoding.hex(Sha256.digest(Array.emptyByteArray)), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    assertEquals(Encoding.hex(Sha256.digest("abc".getBytes)), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    assertEquals(
      Encoding.hex(Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".getBytes)),
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    )
  }

  test("sha256 matches the JVM for every length around the block and padding boundaries") {
    val jvm = MessageDigest.getInstance("SHA-256")
    (0 to 300).foreach { n =>
      val m = bytes(n)
      assertEquals(Encoding.hex(Sha256.digest(m)), Encoding.hex(jvm.digest(m)), s"length $n")
    }
    val big = bytes(1 << 20)
    assertEquals(Encoding.hex(Sha256.digest(big)), Encoding.hex(jvm.digest(big)))
  }

  test("hmac matches the JVM, with short, block sized and long keys") {
    List(0, 1, 20, 63, 64, 65, 200).foreach { keyLength =>
      val key = bytes(keyLength)
      val message = bytes(random.nextInt(300))
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(new SecretKeySpec(if (key.isEmpty) Array[Byte](0) else key, "HmacSHA256"))
      // The JVM refuses an empty key: it is the same as a key of zeros of any length.
      if (key.nonEmpty) assertEquals(Encoding.hex(Hmac.sha256(key, message)), Encoding.hex(mac.doFinal(message)), s"key $keyLength")
    }
    assertEquals(
      Encoding.hex(Hmac.sha256("key".getBytes, "The quick brown fox jumps over the lazy dog".getBytes)),
      "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"
    )
  }

  test("pbkdf2 matches the JVM") {
    List(1, 2, 1000, 5000).foreach { iterations =>
      val password = "pässword with spaces".toCharArray
      val salt = bytes(16)
      val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password, salt, iterations, 256)).getEncoded
      assertEquals(Encoding.hex(Pbkdf2.sha256(new String(password).getBytes("UTF-8"), salt, iterations)), Encoding.hex(expected), s"$iterations iterations")
    }
  }

  test("encodings round trip") {
    val b = bytes(37)
    assertEquals(Encoding.unhex(Encoding.hex(b)).map(_.toSeq), Some(b.toSeq))
    assertEquals(Encoding.unbase64Url(Encoding.base64Url(b)).map(_.toSeq), Some(b.toSeq))
    assert(!Encoding.base64Url(bytes(100)).exists(c => "+/=".contains(c)))
    assertEquals(Encoding.unhex("zz"), None)
    assertEquals(Encoding.unhex("abc"), None)
    assertEquals(Encoding.unbase64Url("!!"), None)
  }

  test("constant time comparison") {
    assert(Secure.equal(Array[Byte](1, 2, 3), Array[Byte](1, 2, 3)))
    assert(!Secure.equal(Array[Byte](1, 2, 3), Array[Byte](1, 2, 4)))
    assert(!Secure.equal(Array[Byte](1, 2, 3), Array[Byte](1, 2)))
    assert(Secure.equal(Array.emptyByteArray, Array.emptyByteArray))
  }

  test("random bytes differ") {
    val a = Secure.randomBytes(32)
    val b = Secure.randomBytes(32)
    assertEquals(a.length, 32)
    assert(!Secure.equal(a, b))
  }
}
