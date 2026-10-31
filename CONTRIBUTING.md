# Contributing

Thanks for helping. Small, focused pull requests are easiest to review. For anything big, open an issue first.

## Setup

You need [scala-cli](https://scala-cli.virtuslab.org/), [scalino](https://github.com/lolgab/scalino) (to build the native
executable), and Node 22 for the panel page. See "Development" in the [README](README.md).

```bash
scala-cli test src/Core.scala test/CoreTests.test.scala                 # pure logic, on the JVM
scala-cli test panel/src/Crypto.scala panel/src/Json.scala panel/src/Auth.scala panel/src/Http.scala \
  panel/test/CryptoTests.test.scala panel/test/PanelTests.test.scala     # pure part of the panel
(cd web && npm ci && npm test && npm run build)                          # the page
scalino package vendor src -o unitship && ./e2e.sh ./unitship            # end to end
```

## Guidelines

- Add or update tests with each behavior change; `e2e.sh` covers the real edge FreeUnit.
- Keep the pure logic in `src/Core.scala` so it stays testable on the JVM.
- No new runtime dependencies on the server without a strong reason: the point is a single executable.
- `vendor/SNUnitBuild.scala` is copied from upstream snunit. Don't edit it.
- Add a line to `CHANGELOG.md` under *Unreleased*.
- CI must pass on Linux and macOS.

By contributing you agree your work is licensed under the [Apache 2.0 license](LICENSE).
