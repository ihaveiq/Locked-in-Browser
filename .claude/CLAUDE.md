# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

"CAC Phone Blocker" is a minimal web browser whose tagline is "Lock in browsing." The user types a URL into `main.html`. A local Java server fetches the site and everything it loads, and the result is displayed in an iframe inside `.webSiteContainer` in `main.html`.

## Current state

There is no build system, package manager, test suite, linter, or git repository. The two real files are:

- `main.html`: the whole UI (home, settings, focus timer, web view, blocked and invalid screens) plus its JavaScript.
- `proxyServer.java`: a loopback-only HTTP server on port 8080. **Java is the middleman for everything.** It is split into small classes, one job each: `proxyServer` (start + routing, implements `HttpHandler`), `BlockList`, `ProxyHandler`, `Fetched`, `ProxyUrls`, `PageRewriter` (html/css rewriting and `SHIM`), `Prefetcher`, `StoredPage`, `DownloadJob` and `Responses`. Only `proxyServer` is public. It is written in beginner-style Java: no lambdas, records, ternaries, `Optional` or `CompletableFuture`. Keep it that way.
  - `GET /p/<scheme>/<host>/<path>?<query>` fetches that URL and returns it. The iframe loads the page from here, and every image, audio file, stylesheet, script and API call the page makes also comes through here. Because the real path is kept in the URL, relative links resolve to proxied URLs on their own.
  - Html and css are rewritten (absolute, protocol-relative and root-relative URLs are pointed back at `/p/...`). An injected script (`SHIM`) does the same for URLs that javascript builds at runtime. Everything else (images, js, json, media) is streamed through untouched, including `Range` requests.
  - Redirects are not followed by Java. They are returned to the browser as proxied redirects so every hop is checked.
  - `GET /check?url=` returns `ok`, `blocked` or `invalid`. `main.html` calls this before loading a site.
  - `POST /block` takes a comma-separated list of app names (Instagram, TikTok, YouTube, Reddit, or a domain). Only requests from `main.html` are accepted (`Origin: null`, or localhost on another port). Blocked hosts are rejected on every proxied request, not just the first. A body of `allow:a.com,b.org` switches to an allow list instead: only **pages** (`/check`, and requests with `Sec-Fetch-Dest: document` or `iframe`) on those domains or their subdomains may open, while images/css/js/API calls from other hosts still pass so CDNs work. `main.html` sends its `COMMON_SITES` (as `google.com`-style domains) when a session begins, and an empty body when it ends.
  - `POST /shutdown` stops the server (`Shutdown` class). Only `main.html` may call it. It sends it when the session timer runs out.
  - `SHIM` posts `{cac: "page", url, title}` to `main.html` whenever a page loads in a tab. `main.html` uses it for tab names and its own per-tab history, which drives the ←/→ buttons. The iframe is cross-origin, and its `history` would be shared by all tabs.
  - Loopback, private and link-local addresses (and unresolvable hosts) are refused.
  - `POST /__cac/connected?all` is sent by `SHIM` with every visible link once a page loads (main frame only, on-screen links first). `POST /__cac/connected` is sent with a link the user points at. A stored page that gets shown does the same, so its connected pages are stored in turn. `Prefetcher` downloads each link's html (already rewritten) into a `StoredPage`, as many as fit in half of Java's memory. The click is answered once from there. Css/js/images are not prefetched.
  - Pointed-at links download right away. The rest wait in a line (`inLine`, 4 at a time, in the order they were sent). Ones that waited over 30s are skipped, and a click on one that hasn't started goes straight to the site. There is no per-host rate limit. Ad links, `rel=sponsored`, `download`/`data-method` links and logout/delete-style URLs are never prefetched.
  - All stored pages are deleted (`Prefetcher.deleteAllExcept`) when:
    - a new page opens in the view: `SHIM` sends `POST /__cac/opened`, and only the opened page's own entry is kept;
    - the user enters a new URL, or main.html closes (`pagehide`): main.html sends `POST /__cac/clear`, which is refused from other origins;
    - there is a form post or a login/logout post, or the block list changes.

    Each stored page also expires 30s after it is filled (it is no longer handed out, and `removeExpired` deletes it the next time links come in).

Known gaps:

- WebSockets cannot be proxied. Sites that need them (live chat, some feeds) won't fully work.
- Service workers are disabled in proxied pages. Login cookies are kept by Java, not the browser.
- All proxied sites share one origin (`localhost:8080`), so they share storage with each other, but not with `main.html`.
- Reloading or closing `main.html` ends the session's timer, but the allow list stays set in Java until the next session or exit.

## Architecture constraints to keep in mind

- **JavaScript in the page cannot call Java directly,** so `main.html` talks to the Java server over HTTP (`fetch` to `/check` and `/block`, iframe `src` to `/p/...`).
- **Java must make every outbound request,** per the project's stated design. Don't replace this with a browser-side `fetch` or a plain `<iframe src="https://...">`.
- **A `public class` must match its file name.** The class in `proxyServer.java` is `proxyServer`.
- `main.html` must stay on a different origin than the server (it is opened as `file://`). The `/block` check relies on that, since proxied sites run on the server's own origin.

## Commands

A JDK 25 is installed but not on PATH (`JAVA_HOME` is unset). Call it by full path, no install needed:

```powershell
& "C:\Users\snail\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe" proxyServer.java
```

This starts the server on `http://localhost:8080/`. Restart it after changing the Java file. Then open `main.html` in a browser.

Set the `CAC_PORT` environment variable to test on another port (main.html expects 8080).
