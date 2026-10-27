# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

"CAC Phone Blocker" is a minimal web browser whose tagline is "Lock in browsing." The user types a URL into `main.html`. A local Java server fetches the site and everything it loads, and the result is displayed in an iframe inside `.webSiteContainer` in `main.html`.

## Current state

There is no build system, package manager, test suite, linter, or git repository. The two real files are:

- `main.html`: the whole UI (home, settings, focus timer, web view, blocked and invalid screens) plus its JavaScript.
- `proxyServer.java`: a loopback-only HTTP server on port 8080. **Java is the middleman for everything.** It is split into small classes, one job each: `proxyServer` (start + routing, implements `HttpHandler`), `BlockList`, `ProxyHandler`, `DnsLookup`, `SiteRequest`, `SiteResponse`, `BodyStream`, `ProxyUrls`, `PageRewriter` (html/css rewriting and `SHIM`) and `Responses`. Only `proxyServer` is public. It is written in beginner-style Java: no lambdas, records, ternaries, `Optional` or `CompletableFuture`. Keep it that way.
  - `GET /p/<scheme>/<host>/<path>?<query>` fetches that URL and returns it. The iframe loads the page from here, and every image, audio file, stylesheet, script and API call the page makes also comes through here. Because the real path is kept in the URL, relative links resolve to proxied URLs on their own.
  - Html and css are rewritten (absolute, protocol-relative and root-relative URLs are pointed back at `/p/...`). An injected script (`SHIM`) does the same for URLs that javascript builds at runtime. `location.href = "https://..."` can't be hooked, so `SHIM` cancels such navigations with the Navigation API and restarts them through `/p/...`. A page request outside `/p/` (a root-relative `location.href = "/x"`) is redirected to its `/p/...` address. Requests outside `/p/` find their site from the `Referer`, so `<meta name="referrer">` tags and `referrerpolicy` attributes are removed from html. Everything else (images, js, json, media) is streamed through untouched, including `Range` requests.
  - Every outbound request first asks DNS for the host's IPs (`DnsLookup`, refused if any is private), then `SiteRequest` connects to that IP with a plain `Socket`/`SSLSocket` (SNI + certificate checked against the host name) and speaks HTTP/1.1 itself, one connection per request (`Connection: close`). `java.net.http.HttpClient` is not used. Cookies live in `SiteRequest.COOKIES`, a `CookieJar` (in memory, gone when the server stops), and are **off by default**: while `SiteRequest.cookiesOn` is false no cookie is saved or sent, and `document.cookie` reads empty and ignores writes (the ones already saved are kept and used again when it is switched back on). `CookieJar` is our own on purpose: `java.net.CookieManager` turns any cookie with `Max-Age` into an old-style one and sends it as `name="value";$Path="/"` (so logins break), and only sends a `.google.com` cookie to hosts with one name in front of it. It follows RFC 6265 (Max-Age beats Expires, host-only cookies, path matching, `Secure`, `__Host-`/`__Secure-`, HttpOnly). Not done: SameSite (every cookie is sent whoever asks) and the public suffix list.
  - Redirects are not followed by Java. They are returned to the browser as proxied redirects so every hop is checked.
  - `GET /check?url=` returns `ok`, `blocked` or `invalid`. `main.html` calls this before loading a site.
  - `POST /block` takes a comma-separated list of app names (Instagram, TikTok, YouTube, Reddit, or a domain). Only requests from `main.html` are accepted (`Origin: null`, or localhost on another port). Blocked hosts are rejected on every proxied request, not just the first. A body of `allow:a.com,b.org` switches to an allow list instead: only **pages** (`/check`, and requests with `Sec-Fetch-Dest: document` or `iframe`) on those domains or their subdomains may open, while images/css/js/API calls from other hosts still pass so CDNs work. `main.html` sends its `COMMON_SITES` (as `google.com`-style domains) when a session begins, and an empty body when it ends.
  - `POST /cookies` takes `on` or `off` and sets `SiteRequest.cookiesOn`. Only `main.html` may call it. The round 🍪 button in the top right corner of the home and invalid screens (`.cookieButton`, one on each screen, kept in sync by `setCookies`/`showCookieButtons`) toggles it: page-colored when off, green (`--ok`, not the theme accent) when on. The button only changes once Java has answered. It is one global switch for all tabs, and `main.html` sets it back to off when a session starts or ends.
  - `GET/POST /cookie?page=<the page's /p/... path>` is `document.cookie` of a proxied page (`ScriptCookies`). A proxied page is in a frame under `main.html` (another site), so the browser throws away the cookies its javascript sets, and Google's sign-in, which writes a test cookie and reads it back, said "cookies are disabled". `SHIM` therefore replaces `Document.prototype.cookie` with synchronous XHRs to this endpoint, which reads and writes the same `CookieJar` the sites' `Set-Cookie` headers use, so a script's cookie is also sent to the real site. HttpOnly cookies are hidden from scripts and a script can't create, replace or delete one. Only proxied pages may call it (`Sec-Fetch-Site: same-origin`, `proxyServer.isFromProxiedPage`), not `main.html` and not other websites. It is not in `mainHtmlOnly`.
  - `POST /shutdown` stops the server (`Shutdown` class). Only `main.html` may call it. It sends it when the session timer runs out. `proxyServer.handle` checks "only `main.html`" once for `/shutdown`, `/block` and `/cookies` (`mainHtmlOnly`), so a new `main.html`-only endpoint is added there.
  - `SHIM` posts messages to `main.html` (main frame only, not frames inside the page). `{cac: "page", url, title}` is sent when the page can be read (`DOMContentLoaded`, and after `pushState`/`popstate`), and `{cac: "loaded", url, title}` at its `load` event. `main.html` uses them for tab names and its own per-tab history, which drives the ←/→ buttons. The iframe is cross-origin, and its `history` would be shared by all tabs. `{cac: "loading", url, title}` is sent when a link or form in the page is about to open a new page (the Navigation API `navigate` event, a moment later, only if neither the site nor the browser cancelled it or kept it in the page).
  - **Nothing on a site can be used while it loads.** Each tab has a `loading` flag (`setLoading` in `main.html`). It is set when `main.html` points the tab's iframe at a URL (a new URL, ←/→) and when `SHIM` sends `loading`, and cleared by the iframe's `load` event or `SHIM`'s `loaded`. While it is set, a cover (`.loadingCover`, "Loading...") sits over the site, which blocks the mouse, and the iframe is `inert`, which blocks the keyboard. The toolbar and tabs stay usable, and each tab has its own flag. A ←/→ move that only changes the `#fragment` (`onlyFragmentChanges`) loads nothing, so it does not lock.
  - Loopback, private and link-local addresses (and unresolvable hosts) are refused.
  - There is no prefetching: every page waits for the site.

Known gaps:

- A link whose answer never becomes a page (a download that the sandbox blocks, a `204`) gets no `load`, so the tab stays locked until `LOADING_TIMEOUT` (20s) unlocks it. The ↻ refresh button or ←/→ also get out sooner. Downloads still don't happen.
- The session allow list keeps only the last two labels of each `COMMON_SITES` host (`docs.google.com` → `google.com`). A site that signs in on other domains lists them in `alsoAllow` (Outlook: `live.com`, `microsoftonline.com`, …).
- WebSockets cannot be proxied. Sites that need them (live chat, some feeds) won't fully work.
- Service workers are disabled in proxied pages. Login cookies are kept by Java, not the browser, and only while the 🍪 button is on, so logins (Google Classroom, Docs...) need it turned on first. Because all proxied sites share one origin, a page can ask `/cookie` for another site's (non-HttpOnly) cookies by faking its own address with `history.replaceState`. That is no worse than the proxy itself, which already attaches every site's cookies to any `/p/...` request, and the session's allow list limits which sites open. `navigator.cookieEnabled` is not changed, so with the button off a site only finds out cookies don't work when its write-then-read test fails.
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
