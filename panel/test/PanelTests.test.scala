//> using test.dep org.scalameta::munit::1.3.6
package panel

class PanelTests extends munit.FunSuite {

  test("json parses what the browser sends") {
    val Right(j) = J.parse(""" {"name": "my-app", "n": 3, "on": true, "off": false, "none": null,
       "list": ["a", "b\"c", "d\u00e9\n"], "env": {"K": "v=1", "L": ""}} """): @unchecked
    assertEquals(j.string("name"), Some("my-app"))
    assertEquals(j.int("n"), Some(3))
    assertEquals(j.boolean("on"), Some(true))
    assertEquals(j.boolean("off"), Some(false))
    assertEquals(j.strings("list"), Some(Seq("a", "b\"c", "dé\n")))
    assertEquals(j.stringMap("env"), Some(Seq("K" -> "v=1", "L" -> "")))
    assertEquals(j.string("n"), None)
    assertEquals(j.string("missing"), None)
    assertEquals(j.int("name"), None)
  }

  test("json rejects what is not json") {
    List("", "{", "{\"a\":}", "[1,]", "{\"a\" 1}", "nul", "{\"a\":1} x", "\"abc", "[1 2]", "{'a':1}", "\"\u0001\"", "{\"a\":\"\\x\"}").foreach { text =>
      assert(J.parse(text).isLeft, text)
    }
    assert(J.parse("[" * 100 + "]" * 100).isLeft, "too deep")
    assert(J.parse("[" * 20 + "]" * 20).isRight)
  }

  test("json renders and parses back") {
    val value = J.obj("a" -> J.Str("x\"y\\z\n\u0001"), "b" -> J.Arr(Seq(J.Num(1), J.Num(2.5), J.Bool(true), J.Null)), "c" -> J.Raw("""{"raw":1}"""))
    assertEquals(value.render, """{"a":"x\"y\\z\n\u0001","b":[1,2.5,true,null],"c":{"raw":1}}""")
    assertEquals(J.parse(value.render).map(_.string("a")), Right(Some("x\"y\\z\n\u0001")))
  }

  test("a password is checked against its hash") {
    val stored = Passwords.hash("correct horse")
    assert(stored.startsWith("pbkdf2-sha256$120000$"))
    assert(Passwords.verify("correct horse", stored))
    assert(!Passwords.verify("correct horse ", stored))
    assert(!Passwords.verify("", stored))
    assert(!Passwords.verify("x", "garbage"))
    assert(!Passwords.verify("x", "pbkdf2-sha256$0$AA$AA"))
    assert(!Passwords.verify("x", "pbkdf2-sha256$abc$AA$AA"))
    // The salt is random: the same password gives another hash.
    assertNotEquals(stored, Passwords.hash("correct horse"))
  }

  test("sessions expire, can be revoked and cannot be forged") {
    val secret = Secure.randomBytes(32)
    val token = Sessions.issue(secret, 1000000, 60)
    assert(Sessions.valid(secret, token, 1000000, 0))
    assert(Sessions.valid(secret, token, 1059000, 0))
    assert(!Sessions.valid(secret, token, 1060000, 0), "expired")
    assert(!Sessions.valid(Secure.randomBytes(32), token, 1000000, 0), "another secret")
    assert(!Sessions.valid(secret, token, 1000001, 1000000), "issued at the moment of the revocation")
    assert(Sessions.valid(secret, token, 1000001, 999999), "issued after the revocation")
    assert(!Sessions.valid(secret, token, 1000001, 5000000), "issued before the revocation")
    // One issued in the future is not one of ours (the clock of another machine).
    assert(!Sessions.valid(secret, Sessions.issue(secret, 9000000, 60), 1000000, 0))
    val Array(payload, signature) = token.split('.')
    val forged = Encoding.base64Url(s"1000000.${Long.MaxValue}.00".getBytes) + "." + signature
    assert(!Sessions.valid(secret, forged, 1000000, 0), "a longer expiry with the old signature")
    assert(!Sessions.valid(secret, payload, 1000000, 0))
    assert(!Sessions.valid(secret, payload + "." + signature + "x", 1000000, 0))
    assert(!Sessions.valid(secret, "", 1000000, 0))
    assert(!Sessions.valid(secret, ".", 1000000, 0))
    assertNotEquals(Sessions.issue(secret, 1000000, 60), token)
  }

  test("lockout after five failures in five minutes") {
    assertEquals(Lockout.retryAfter(Nil, 1000), None)
    assertEquals(Lockout.retryAfter(Seq(990, 991, 992, 993), 1000), None)
    assertEquals(Lockout.retryAfter(Seq(990, 991, 992, 993, 994), 1000), Some(290L))
    // The oldest failures leave the window.
    assertEquals(Lockout.retryAfter(Seq(100, 101, 102, 103, 104), 1000), None)
    assertEquals(Lockout.retryAfter(Seq(600, 750, 800, 900, 990, 995), 1000), Some(50L)) // 600 left the window; 750 leaves in 50 s
  }

  test("queries and cookies") {
    assertEquals(Http.parseQuery("n=300&x=a%20b+c&flag&e=%C3%A9"), Map("n" -> "300", "x" -> "a b c", "flag" -> "", "e" -> "é"))
    assertEquals(Http.parseQuery(""), Map.empty[String, String])
    assertEquals(Http.parseQuery("bad=%zz"), Map("bad" -> "%zz"))
    assertEquals(Http.parseQuery("end=%4"), Map("end" -> "%4"))
    assertEquals(Http.cookie(Some("a=1; unitship_session=tok.en; b=2"), "unitship_session"), Some("tok.en"))
    assertEquals(Http.cookie(Some("xunitship_session=bad"), "unitship_session"), None)
    assertEquals(Http.cookie(None, "a"), None)
  }

  test("only requests from the page of the panel may change something") {
    assert(Http.sameOrigin(Some("https://panel.example.com"), Some("panel.example.com"), None))
    assert(Http.sameOrigin(Some("http://localhost:8081"), Some("localhost:8081"), None))
    assert(!Http.sameOrigin(Some("https://evil.example"), Some("panel.example.com"), None))
    assert(!Http.sameOrigin(Some("https://panel.example.com.evil.io"), Some("panel.example.com"), None))
    assert(!Http.sameOrigin(Some("null"), Some("panel.example.com"), None))
    assert(!Http.sameOrigin(Some("https://panel.example.com"), None, None))
    assert(Http.sameOrigin(None, Some("panel.example.com"), Some("same-origin")))
    assert(!Http.sameOrigin(None, Some("panel.example.com"), Some("cross-site")))
    assert(!Http.sameOrigin(None, Some("panel.example.com"), None))
  }

  test("content types") {
    assertEquals(Http.contentType("/assets/app-1a2b.js"), "text/javascript; charset=utf-8")
    assertEquals(Http.contentType("/index.html"), "text/html; charset=utf-8")
    assertEquals(Http.contentType("/x.CSS"), "text/css; charset=utf-8")
    assertEquals(Http.contentType("/blob"), "application/octet-stream")
  }
}
