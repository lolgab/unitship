//> using test.dep org.scalameta::munit::1.3.6
package unitship

class CoreTests extends munit.FunSuite {

  test("env files round trip awkward values") {
    val env = Map(
      "PLAIN" -> "value",
      "SPACES" -> "a b  c",
      "QUOTES" -> "say \"hi\" \\ bye",
      "NEWLINE" -> "line1\nline2",
      "EQUALS" -> "a=b=c",
      "EMPTY" -> "",
      "DOLLAR" -> "$HOME %h"
    )
    assertEquals(EnvFile.parse(EnvFile.render(env)), env)
  }

  test("env file keys") {
    assert(EnvFile.validKey("DATABASE_URL"))
    assert(EnvFile.validKey("_x1"))
    assert(!EnvFile.validKey("1X"))
    assert(!EnvFile.validKey("A-B"))
    assert(!EnvFile.validKey(""))
  }

  test("env file ignores comments and blank lines") {
    assertEquals(EnvFile.parse("# c\n\nA=\"1\"\nB=2\n"), Map("A" -> "1", "B" -> "2"))
  }

  test("props round trip") {
    val p = Map("domains" -> "a.example.com,b.example.com", "active" -> "3")
    assertEquals(Props.parse(Props.render(p)), p)
  }

  test("app names") {
    assertEquals(Names.app("my-app1"), "my-app1")
    List("", "My", "1app", "-a", "a-", "a_b", "a" * 40).foreach { n =>
      intercept[HostError](Names.app(n))
    }
  }

  test("domains are lowercased and validated") {
    assertEquals(Names.domain("App.Example.COM"), "app.example.com")
    List("localhost", "a..b", "-a.com", "a.com/", "a b.com", "*.a.com", ("a" * 64) + ".com").foreach { d =>
      intercept[HostError](Names.domain(d))
    }
  }

  private def render(
      apps: Seq[EdgeApp],
      certs: Set[String] = Set.empty,
      https: Int = 443
  ): String = EdgeConfig.render(apps, certs, 80, https, "/w")

  test("edge: only HTTP while there is no certificate") {
    val json = render(Seq(EdgeApp("a", Seq("a.example.com"), Some(20001))))
    assertEquals(
      json,
      """{"listeners":{"*:80":{"pass":"routes/http"}},"routes":{"http":[""" +
        """{"match":{"uri":"/.well-known/acme-challenge/*"},"action":{"share":"/w$uri"}},""" +
        """{"match":{"host":"a.example.com"},"action":{"proxy":"http://127.0.0.1:20001"}},""" +
        """{"action":{"return":404}}]}}"""
    )
  }

  test("edge: HTTPS listener, redirect and proxy for a domain with a certificate") {
    val json = render(
      Seq(EdgeApp("a", Seq("a.example.com", "b.example.com"), Some(20001))),
      certs = Set("a.example.com")
    )
    assert(json.contains(""""*:443":{"pass":"routes/https","tls":{"certificate":["a.example.com"]}}"""), json)
    assert(
      json.contains(
        """{"match":{"host":"a.example.com"},"action":{"return":301,"location":"https://$host$request_uri"}}"""
      ),
      json
    )
    // b has no certificate: it is still proxied over HTTP.
    assert(json.contains("""{"match":{"host":"b.example.com"},"action":{"proxy":"http://127.0.0.1:20001"}}"""), json)
    assert(json.contains(""""https":[{"match":{"host":"a.example.com"},"action":{"proxy":"http://127.0.0.1:20001"}}"""))
  }

  test("edge: redirect includes a non default HTTPS port") {
    val json = render(Seq(EdgeApp("a", Seq("a.example.com"), Some(20001))), Set("a.example.com"), https = 8443)
    assert(json.contains(""""location":"https://$host:8443$request_uri""""), json)
  }

  test("edge: an app without a release answers 503") {
    val json = render(Seq(EdgeApp("a", Seq("a.example.com"), None)))
    assert(json.contains("""{"match":{"host":"a.example.com"},"action":{"return":503}}"""), json)
  }

  test("edge: certificates of unknown domains are not used") {
    val json = render(Seq(EdgeApp("a", Seq("a.example.com"), Some(1))), Set("gone.example.com"))
    assert(!json.contains("443"), json)
    assert(!json.contains("gone"), json)
  }

  test("edge: no apps still serves the challenge and 404") {
    val json = render(Nil)
    assert(json.contains("acme-challenge") && json.contains("404"), json)
  }

  test("json escaping") {
    assertEquals(Json.quote("a\"b\\c\nd\u0001"), "\"a\\\"b\\\\c\\nd\\u0001\"")
  }

  test("systemd unit") {
    val unit = SystemdUnit.render(
      Service(
        "unitship-a-3",
        "app a",
        Seq("/home/u/my app/app", "--x=$1", "100%"),
        "/home/u/my app",
        Some("/home/u/env"),
        "/log"
      )
    )
    assert(unit.contains("""ExecStart="/home/u/my app/app" "--x=$$1" "100%%""""), unit)
    assert(unit.contains("""WorkingDirectory="/home/u/my app""""), unit)
    assert(unit.contains("EnvironmentFile=/home/u/env"), unit)
    assert(unit.contains("Restart=always") && unit.contains("WantedBy=default.target"), unit)
  }

  test("ports skip used and busy ones and wrap around") {
    assertEquals(Ports.allocate(Set(20000, 20001), _ => true, 20000), Some(20002))
    assertEquals(Ports.allocate(Set.empty, _ != 20005, 20005), Some(20006))
    assertEquals(Ports.allocate(Set(29999), _ => true, 29999), Some(20000))
    assertEquals(Ports.allocate(Set.empty, _ => false, 20000), None)
    assertEquals(Ports.allocate(Set.empty, _ => true, 5), Some(20000))
  }

  test("pruning keeps the newest, the active and the previous releases") {
    assertEquals(Releases.toPrune(Seq(1, 2, 3, 4, 5, 6), Some(6), Some(5), 2), Seq(1, 2, 3, 4))
    // An old active release (after a rollback) is kept.
    assertEquals(Releases.toPrune(Seq(1, 2, 3, 4, 5, 6), Some(2), Some(6), 2), Seq(1, 3, 4))
    assertEquals(Releases.toPrune(Seq(1, 2), None, None, 5), Nil)
  }

  test("rollback target") {
    assertEquals(Releases.rollbackTarget(Seq(1, 2, 3), Some(3), None), Right(2))
    assertEquals(Releases.rollbackTarget(Seq(1, 2, 3), Some(2), None), Right(1))
    assert(Releases.rollbackTarget(Seq(3), Some(3), None).isLeft)
    assertEquals(Releases.rollbackTarget(Seq(1, 2, 3), Some(3), Some(1)), Right(1))
    assert(Releases.rollbackTarget(Seq(1, 2, 3), Some(3), Some(3)).isLeft)
    assert(Releases.rollbackTarget(Seq(1, 2, 3), Some(3), Some(9)).isLeft)
  }

  test("shell quoting") {
    assertEquals(Shell.quote("it's"), "'it'\\''s'")
    assertEquals(Shell.quote("a b"), "'a b'")
  }

  test("lego version") {
    assertEquals(Lego.majorVersion("lego version 4.26.0"), Some(4))
    assertEquals(Lego.majorVersion("lego version 5.5.2"), Some(5))
    assertEquals(Lego.majorVersion("5.5.2"), Some(5))
    assertEquals(Lego.majorVersion("nope"), None)
  }

  test("lego 4 command line") {
    assertEquals(
      Lego.command(4, "a@b.c", None, "/w", "/p", "x.example.com", None),
      Seq("lego", "--accept-tos", "--email", "a@b.c", "--http", "--http.webroot", "/w", "--path", "/p", "--domains", "x.example.com", "run")
    )
    assertEquals(
      Lego.command(4, "a@b.c", Some("https://s/dir"), "/w", "/p", "x.example.com", Some(30)).takeRight(3),
      Seq("renew", "--days", "30", "--no-random-sleep").takeRight(3)
    )
    assert(Lego.command(4, "a@b.c", Some("https://s/dir"), "/w", "/p", "d.com", None).containsSlice(Seq("--server", "https://s/dir")))
  }

  test("forcing a renewal") {
    val v4 = Lego.command(4, "a@b.c", None, "/w", "/p", "d.com", Some(30), force = true)
    assertEquals(v4.takeRight(5), Seq("renew", "--days", "36500", "--ari-disable", "--no-random-sleep"))
    val v5 = Lego.command(5, "a@b.c", None, "/w", "/p", "d.com", Some(30), force = true)
    assertEquals(v5.takeRight(4), Seq("--renew-days", "30", "--no-random-sleep", "--renew-force"))
    // Without a renewal there is nothing to force.
    assert(!Lego.command(5, "a@b.c", None, "/w", "/p", "d.com", None, force = true).contains("--renew-force"))
  }

  test("lego 5 command line") {
    assertEquals(
      Lego.command(5, "a@b.c", None, "/w", "/p", "x.example.com", None),
      Seq("lego", "run", "--accept-tos", "--email", "a@b.c", "--http", "--http.webroot", "/w", "--path", "/p", "--domains", "x.example.com")
    )
    assertEquals(
      Lego.command(5, "a@b.c", None, "/w", "/p", "d.com", Some(30)).takeRight(3),
      Seq("--renew-days", "30", "--no-random-sleep")
    )
  }

  test("iso times") {
    assertEquals(Iso.format(0L), "1970-01-01T00:00:00Z")
    assertEquals(Iso.format(1000000000000L), "2001-09-09T01:46:40Z")
    assertEquals(Iso.format(1700000000000L), "2023-11-14T22:13:20Z")
    assertEquals(Iso.format(951782400000L), "2000-02-29T00:00:00Z")
    assertEquals(Iso.format(-1000L), "1969-12-31T23:59:59Z")
  }

  test("certificate expiry") {
    assertEquals(Cert.parseNotAfter("notAfter=Jan 22 14:12:07 2027 GMT").map(Iso.format), Some("2027-01-22T14:12:07Z"))
    assertEquals(Cert.parseNotAfter("notAfter=Feb  3 01:02:03 2026 GMT\n").map(Iso.format), Some("2026-02-03T01:02:03Z"))
    assertEquals(Cert.parseNotAfter("notAfter=Dec 31 23:59:59 2099 GMT").map(Iso.format), Some("2099-12-31T23:59:59Z"))
    assertEquals(Cert.parseNotAfter("garbage"), None)
    assertEquals(Cert.parseNotAfter("notAfter=Foo 22 14:12:07 2027 GMT"), None)
  }

  test("events") {
    assertEquals(
      Events.line(1700000000000L, "deploy", Some(3), "release \"3\" is live"),
      """{"at":"2023-11-14T22:13:20Z","type":"deploy","release":3,"message":"release \"3\" is live"}"""
    )
    assertEquals(
      Events.line(0L, "stop", None, "x"),
      """{"at":"1970-01-01T00:00:00Z","type":"stop","release":null,"message":"x"}"""
    )
    assertEquals(Events.array(Seq("""{"a":1}""", "", "junk", """{"b":2}""")), """[{"a":1},{"b":2}]""")
    assertEquals(Events.array(Nil), "[]")
  }

  test("access log") {
    assertEquals(AccessLog.parse("Web.Example.com\t200\t0.012\t512"), Some(AccessLog.Entry("web.example.com", 200, 0.012, 512L)))
    assertEquals(AccessLog.parse("a\t200\t0.000\t0"), Some(AccessLog.Entry("a", 200, 0.0, 0L)))
    assertEquals(AccessLog.parse("a\t200\tx\t0"), None)
    assertEquals(AccessLog.parse("not a log line"), None)
  }

  test("edge: access log") {
    val json = EdgeConfig.render(Nil, Set.empty, 80, 443, "/w", Some("/log"))
    assert(json.contains(""""access_log":{"path":"/log","format":"$host\t$status\t$request_time\t$body_bytes_sent"}"""), json)
    assert(!render(Nil).contains("access_log"))
  }

  private def entry(status: Int, ms: Double, bytes: Long = 10) = AccessLog.Entry("a", status, ms / 1000, bytes)

  test("metrics aggregate") {
    val a = Metrics.aggregate((1 to 20).map(i => entry(if (i == 20) 500 else 200, i.toDouble)) :+ entry(404, 1) :+ entry(301, 1))
    assertEquals((a.requests, a.s2, a.s3, a.s4, a.s5), (22, 19, 1, 1, 1))
    assertEquals(a.bytes, 220L)
    assertEqualsDouble(a.p95Ms, 19.0, 0.001) // sorted: 1, 1, 1, 2 ... 20: rank 21 of 22 is 19
    assertEqualsDouble(a.avgMs, (210 + 2) / 22.0, 0.001)
    assertEquals(Metrics.aggregate(Nil), Aggregate(0, 0, 0, 0, 0, 0, 0, 0))
    assertEqualsDouble(Metrics.aggregate(Seq(entry(200, 5))).p95Ms, 5.0, 0.001)
  }

  test("cpu percent") {
    assertEquals(Metrics.cpuPercent(0, 0, 500000000L, 1000), Some(50.0))
    assertEquals(Metrics.cpuPercent(10, 0, 5, 1000), None) // the counter went back: the service restarted
    assertEquals(Metrics.cpuPercent(0, 1000, 5, 1000), None)
  }

  test("metrics points are json lines that can be filtered by time") {
    val a = Aggregate(3, 2, 0, 1, 0, 12.5, 30.0, 99)
    val p1 = Metrics.point(1700000000000L, 60.0, a, Some(1024), None)
    assertEquals(
      p1,
      """{"ms":1700000000000,"t":"2023-11-14T22:13:20Z","seconds":60.00,"requests":3,"s2":2,"s3":0,"s4":1,"s5":0,"avgMs":12.50,"p95Ms":30.00,"bytes":99,"memoryBytes":1024,"cpuPct":null}"""
    )
    val p2 = Metrics.point(1700000060000L, 60.0, a, None, Some(12.345))
    assert(p2.endsWith(""""memoryBytes":null,"cpuPct":12.35}"""), p2)
    assertEquals(Metrics.since(Seq(p1, "junk", p2), 1700000030000L), "[" + p2 + "]")
    assertEquals(Metrics.since(Seq(p1, p2), 0L), "[" + p1 + "," + p2 + "]")
    assertEquals(Metrics.since(Nil, 0L), "[]")
  }
}
