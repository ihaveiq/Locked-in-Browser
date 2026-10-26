import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Java is the middleman for everything. Loopback-only server on port 8080.
//
//   GET  /p/<scheme>/<host>/<path>?<query>   fetches that URL and sends it back (pages, images,
//                                            css, js, API calls... everything a page loads)
//   GET  /check?url=<url>                    "ok", "blocked" or "invalid"
//   POST /block  (body: "Instagram,TikTok")  sets which sites are blocked (only main.html may call it)
//   POST /block  (body: "allow:a.com,b.org") only pages on these sites may open; empty body = no limits
//   POST /shutdown                           stops this server (only main.html may call it)
//   POST /__cac/clear                       deletes every stored page (only main.html may call it)
//   POST /__cac/opened (body: page url)      a proxied page opened: deletes every other stored page
//   POST /__cac/connected[?all]              a proxied page's links, so their pages get stored
//
// Each class below does one job:
//   proxyServer  starts the server and sends each request to the right place
//   BlockList    which sites are allowed
//   ProxyHandler fetches a site for the browser
//   DnsLookup    asks the DNS server for a site's IP addresses
//   SiteRequest  connects to that IP address and asks the site for a file (html, css, images...)
//   SiteResponse the site's answer: status, headers and body
//   BodyStream   reads the body of the site's answer
//   Fetched      one answer downloaded from a site
//   ProxyUrls    turns real URLs into /p/... URLs and back
//   PageRewriter changes the links inside html and css so they come back through here
//   Prefetcher   downloads the pages links open before the user clicks them
//   StoredPage   one page Prefetcher downloaded
//   DownloadJob  the work a download thread does
//   Responses    small helpers for sending answers
public class proxyServer implements HttpHandler {

    // main.html expects 8080. CAC_PORT only exists to test on another port.
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("CAC_PORT", "8080"));

    // Kept here so Shutdown can stop it
    static HttpServer server;

    public static void main(String[] args) throws IOException {
        // Loopback only, so other computers on the network can't use this as a proxy
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT);
        server = HttpServer.create(address, 0);
        server.createContext("/", new proxyServer());          // every request goes to handle() below
        server.setExecutor(Executors.newCachedThreadPool()); // many requests at once: a page loads many files
        server.start();
        System.out.println("Proxy listening on http://localhost:" + PORT + "/");
    }

    // The server calls this for every request
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        String method = ex.getRequestMethod();
        boolean isPost = method.equals("POST");
        boolean fromApp = BlockList.isAppOrigin(ex.getRequestHeaders().getFirst("Origin"));
        try {
            if (method.equals("OPTIONS")) {
                Responses.cors(ex);
                ex.sendResponseHeaders(204, -1);

            } else if (path.equals("/check")) {
                Responses.cors(ex);
                Responses.text(ex, 200, BlockList.check(ProxyUrls.queryParam(query, "url")));

            } else if (path.equals("/shutdown") && isPost) {
                // main.html: the session's time is up
                Responses.cors(ex);
                if (fromApp) {
                    Responses.text(ex, 200, "ok");
                    new Thread(new Shutdown()).start();
                } else {
                    Responses.text(ex, 403, "forbidden");
                }

            } else if (path.equals("/block") && isPost) {
                Responses.cors(ex);
                if (fromApp) {
                    BlockList.set(readBody(ex, Integer.MAX_VALUE));
                    Responses.text(ex, 200, "ok");
                } else {
                    Responses.text(ex, 403, "forbidden");
                }

            } else if (path.equals("/__cac/clear") && isPost) {
                // main.html: the user entered a new URL, or is closing the browser
                Responses.cors(ex);
                if (fromApp) {
                    Prefetcher.deleteAllExcept(null);
                    Responses.text(ex, 200, "ok");
                } else {
                    Responses.text(ex, 403, "forbidden");
                }

            } else if (path.equals("/__cac/opened") && isPost) {
                // The shim: a new page opened. Only that page's storage is kept.
                String opened = ProxyUrls.unproxyUrl(readBody(ex, 8000));
                if (opened == null) {
                    Prefetcher.deleteAllExcept(null);
                } else {
                    Prefetcher.deleteAllExcept(ProxyUrls.asRequested(opened));
                }
                ex.sendResponseHeaders(204, -1);

            } else if (path.equals("/__cac/connected") && isPost) {
                // The shim: the page's proxied URL, a newline, then the html of its links.
                // "?all" = every link on the page. Without it, it is the one link the user points at.
                String[] parts = readBody(ex, 256000).split("\n", 2);
                if (parts.length == 2) {
                    String page = ProxyUrls.unproxyUrl(parts[0]);
                    boolean allLinks = "all".equals(query);
                    if (page != null) Prefetcher.storeLinks(parts[1], URI.create(page), !allLinks);
                }
                ex.sendResponseHeaders(204, -1);

            } else {
                ProxyHandler.proxy(ex, path, query);
            }
        } catch (Exception e) {
            try {
                Responses.text(ex, 502, "Error: " + e.getMessage());
            } catch (IOException ignored) {
                // the answer had already started, or the browser went away
            }
        } finally {
            ex.close();
        }
    }

    static String readBody(HttpExchange ex, int maxBytes) throws IOException {
        byte[] bytes = ex.getRequestBody().readNBytes(maxBytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}


// ==================== Stopping the server ====================
// Runs on its own thread, so the "ok" for /shutdown is sent before the server stops
class Shutdown implements Runnable {

    public void run() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
            // stop now then
        }
        System.out.println("Session over, stopping the server");
        proxyServer.server.stop(0);
        System.exit(0);
    }
}


// ==================== Which sites are allowed ====================
class BlockList {

    // App names from main.html -> the domains they use
    static final Map<String, List<String>> APP_DOMAINS = Map.of(
            "instagram", List.of("instagram.com", "cdninstagram.com"),
            "tiktok", List.of("tiktok.com", "tiktokcdn.com", "tiktokv.com"),
            "youtube", List.of("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com"),
            "reddit", List.of("reddit.com", "redd.it", "redditmedia.com", "redditstatic.com"));

    // volatile: every thread sees the newest list as soon as it changes
    static volatile Set<String> blockedDomains = new HashSet<>();

    // When this is not empty, only pages on these sites may open (main.html's common sites)
    static volatile Set<String> allowedDomains = new HashSet<>();

    // host -> is it private? Remembered so each host is only looked up once.
    // A ConcurrentHashMap can be used by many threads at the same time.
    static final Map<String, Boolean> privateHosts = new ConcurrentHashMap<>();

    // "Instagram,TikTok,example.com" -> blocked domains. An empty list blocks nothing.
    // "allow:wikipedia.org,desmos.com" -> only those sites are allowed instead.
    static void set(String csv) {
        csv = csv.trim();
        if (csv.toLowerCase().startsWith("allow:")) {
            Set<String> allowed = new HashSet<>();
            for (String item : csv.substring(6).split(",")) {
                String name = item.trim().toLowerCase();
                if (name.startsWith("www.")) name = name.substring(4);
                if (!name.isEmpty()) allowed.add(name);
            }
            allowedDomains = allowed;
            blockedDomains = new HashSet<>();
            Prefetcher.deleteAllExcept(null);
            return;
        }
        allowedDomains = new HashSet<>();
        Set<String> domains = new HashSet<>();
        for (String item : csv.split(",")) {
            String name = item.trim().toLowerCase();
            if (name.isEmpty()) continue;
            if (APP_DOMAINS.containsKey(name)) {
                domains.addAll(APP_DOMAINS.get(name));
            } else {
                domains.add(name);
            }
        }
        blockedDomains = domains;
        Prefetcher.deleteAllExcept(null);
    }

    // The answer for /check: "ok", "blocked" or "invalid"
    static String check(String url) {
        if (url == null) return "invalid";
        try {
            String problem = reject(URI.create(url.trim()), true);
            if (problem == null) return "ok";
            return problem;
        } catch (Exception e) {
            return "invalid";
        }
    }

    // null = allowed, otherwise "invalid" or "blocked".
    // isPage: the URL opens a page (not an image, script, css or API call). The allow list only
    // checks pages, because allowed sites load their files from other hosts (CDNs).
    static String reject(URI u, boolean isPage) {
        String host = u.getHost();
        String scheme = u.getScheme();
        boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (host == null || !web) return "invalid";
        if (onDomain(host, blockedDomains)) return "blocked";
        Set<String> allowed = allowedDomains;
        if (isPage && !allowed.isEmpty() && !onDomain(host, allowed)) return "blocked";
        if (isPrivate(host)) return "invalid"; // also covers hosts that don't exist
        return null;
    }

    // host is one of domains, or a subdomain of one ("www.reddit.com" is on "reddit.com")
    static boolean onDomain(String host, Set<String> domains) {
        host = host.toLowerCase();
        for (String d : domains) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    // Only main.html (file:// sends "null", or localhost on another port) may change settings.
    // Proxied sites run on this server's own port, so they are refused.
    static boolean isAppOrigin(String origin) {
        if (origin == null) return false;
        if (origin.equals("null")) return true;
        boolean local = origin.startsWith("http://localhost:") || origin.startsWith("http://127.0.0.1:");
        return local && !origin.endsWith(":" + proxyServer.PORT);
    }
}


// ==================== Fetching a site for the browser ====================
class ProxyHandler {

    // Redirects are not followed here: the browser gets them, so every hop is checked again.
    // The CookieManager keeps the sites' login cookies.
    static final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new CookieManager())
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // A post to one of these can log the user in or out, so stored pages are out of date
    static final Pattern ACCOUNT = Pattern.compile("(?i)log[-_]?(?:in|out|off)|sign[-_]?(?:in|up|out|off)|auth");

    static void proxy(HttpExchange ex, String path, String query) throws Exception {
        Headers in = ex.getRequestHeaders();
        String q = "";
        if (query != null) q = "?" + query;
        String referer = ProxyUrls.unproxyUrl(in.getFirst("Referer")); // real URL of the page that asked, or null

        // 1. Work out the real URL
        String target;
        if (path.startsWith("/p/")) {
            int schemeEnd = path.indexOf('/', 3);
            if (schemeEnd < 0) {
                Responses.text(ex, 400, "Bad proxy URL");
                return;
            }
            if (path.indexOf('/', schemeEnd + 1) < 0) {
                // /p/https/example.com -> add the slash so relative links work
                Responses.redirect(ex, 302, path + "/" + q);
                return;
            }
            target = ProxyUrls.fromProxyPath(path) + q;
        } else {
            // A root-relative request (like /logo.png) from a proxied page: send it to that page's site
            if (referer == null) {
                Responses.text(ex, 404, "Not a proxied URL");
                return;
            }
            target = ProxyUrls.origin(URI.create(referer)) + path + q;
        }
        URI uri = URI.create(target);
        // A page opening in the view (or in a frame inside it), not a file the page loads
        String dest = in.getFirst("Sec-Fetch-Dest");
        boolean isPage = "document".equals(dest) || "iframe".equals(dest);
        String problem = BlockList.reject(uri, isPage);
        if (problem != null) {
            Responses.rejectPage(ex, problem);
            return;
        }

        String method = ex.getRequestMethod();
        byte[] body = ex.getRequestBody().readAllBytes();
        boolean isGet = method.equals("GET");
        boolean isHead = method.equals("HEAD");

        // Remembered so stored pages are downloaded the way this browser would get them
        if (in.getFirst("User-Agent") != null) Prefetcher.userAgent = in.getFirst("User-Agent");
        if (in.getFirst("Accept-Language") != null) Prefetcher.acceptLanguage = in.getFirst("Accept-Language");

        // A form post, or a login/logout post, changes what pages show: stored pages are dropped
        boolean formPost = "navigate".equals(in.getFirst("Sec-Fetch-Mode"));
        if (!isGet && !isHead && (formPost || ACCOUNT.matcher(uri.getRawPath()).find())) {
            Prefetcher.deleteAllExcept(null);
        }

        // 2. Get the answer: from storage if this page was downloaded ahead of time, otherwise from the site
        Fetched stored = null;
        if (isGet && body.length == 0 && in.getFirst("Range") == null) {
            stored = Prefetcher.take(uri.toString());
        }

        int status;
        HttpHeaders h;
        InputStream respBody;
        if (stored != null) {
            status = stored.status;
            h = stored.headers;
            respBody = new ByteArrayInputStream(stored.body);
        } else {
            HttpRequest request = buildRequest(in, uri, method, body, referer);
            HttpResponse<InputStream> resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            status = resp.statusCode();
            h = resp.headers();
            respBody = resp.body();
        }

        // 3. Send it to the browser
        String location = header(h, "Location");
        if (status >= 300 && status < 400 && location != null) {
            // Redirect: point it back through here so the next hop is checked too
            respBody.close();
            Responses.redirect(ex, status, ProxyUrls.toProxyPath(uri.resolve(location).toString()));
            return;
        }

        String contentType = header(h, "Content-Type");
        if (contentType == null) contentType = "";
        if (!contentType.isEmpty()) ex.getResponseHeaders().set("Content-Type", contentType);

        if (isHead || status == 204 || status == 304) {
            respBody.close();
            ex.sendResponseHeaders(status, -1); // -1 = no body
            return;
        }

        String lower = contentType.toLowerCase();
        boolean isHtml = lower.contains("text/html") || lower.contains("application/xhtml");
        boolean isCss = lower.contains("text/css");
        if (isHtml || isCss) {
            byte[] raw;
            try {
                raw = respBody.readAllBytes();
            } finally {
                respBody.close();
            }
            // Stored pages were already rewritten when they were downloaded
            if (stored == null) raw = PageRewriter.rewrite(raw, contentType, uri, isHtml);
            Responses.send(ex, status, null, raw);
            return;
        }

        // Everything else (images, audio, video, js, json, fonts...) is passed through untouched
        String contentRange = header(h, "Content-Range");
        String acceptRanges = header(h, "Accept-Ranges");
        if (contentRange != null) ex.getResponseHeaders().set("Content-Range", contentRange);
        if (acceptRanges != null) ex.getResponseHeaders().set("Accept-Ranges", acceptRanges);
        ex.sendResponseHeaders(status, 0); // 0 = the length isn't known yet
        OutputStream os = ex.getResponseBody();
        try {
            respBody.transferTo(os);
        } finally {
            respBody.close();
            os.close();
        }
    }

    // The request Java sends to the real site, copying the browser's useful headers
    static HttpRequest buildRequest(Headers in, URI uri, String method, byte[] body, String referer) {
        HttpRequest.BodyPublisher sendBody = HttpRequest.BodyPublishers.noBody();
        if (body.length > 0) sendBody = HttpRequest.BodyPublishers.ofByteArray(body);

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .method(method, sendBody);
        String[] copied = {"User-Agent", "Accept", "Accept-Language", "Content-Type", "Range"};
        for (String name : copied) {
            if (in.getFirst(name) != null) request.header(name, in.getFirst(name));
        }

        // The site is told the real page that asked, not localhost
        if (referer != null) {
            request.header("Referer", referer);
            if (in.containsKey("Origin")) request.header("Origin", ProxyUrls.origin(URI.create(referer)));
        } else {
            request.header("Referer", ProxyUrls.origin(uri) + "/");
            if (in.containsKey("Origin")) request.header("Origin", ProxyUrls.origin(uri));
        }
        return request.build();
    }

    // The value of one header, or null if the site didn't send it
    static String header(HttpHeaders headers, String name) {
        List<String> values = headers.allValues(name);
        if (values.isEmpty()) return null;
        return values.get(0);
    }
}


// ==================== One answer downloaded from a site ====================
class Fetched {
    final int status;          // 200, 301, 404...
    final HttpHeaders headers;
    final byte[] body;

    Fetched(int status, HttpHeaders headers, byte[] body) {
        this.status = status;
        this.headers = headers;
        this.body = body;
    }
}


// ==================== Real URLs <-> proxied URLs ====================
class ProxyUrls {

    // "https://example.com/a?b" -> "/p/https/example.com/a?b"
    static String toProxyPath(String url) {
        int i = url.indexOf("://");
        String scheme = url.substring(0, i).toLowerCase();
        String hostAndPath = url.substring(i + 3);
        String query = "";
        int q = hostAndPath.indexOf('?');
        if (q >= 0) {
            query = hostAndPath.substring(q);
            hostAndPath = hostAndPath.substring(0, q);
        }
        if (!hostAndPath.contains("/")) hostAndPath = hostAndPath + "/"; // "example.com" -> "example.com/"
        return "/p/" + scheme + "/" + hostAndPath + query;
    }

    // "/p/https/example.com/a" -> "https://example.com/a"
    static String fromProxyPath(String path) {
        String rest = path.substring(3);
        int slash = rest.indexOf('/');
        return rest.substring(0, slash) + "://" + rest.substring(slash + 1);
    }

    // A proxied URL (like a Referer header) -> the real URL, or null
    static String unproxyUrl(String proxied) {
        if (proxied == null) return null;
        int i = proxied.indexOf("/p/");
        if (i < 0) return null;
        try {
            String real = withoutFragment(fromProxyPath(proxied.substring(i)));
            URI.create(real); // throws if it isn't a valid URL
            return real;
        } catch (Exception e) {
            return null;
        }
    }

    // "https://example.com/a/b" -> "https://example.com"
    static String origin(URI u) {
        return u.getScheme() + "://" + u.getRawAuthority();
    }

    // A URL written the way the browser will ask for it: no #fragment, and "https://a.com" -> "https://a.com/"
    static String asRequested(String url) {
        return fromProxyPath(toProxyPath(withoutFragment(url)));
    }

    // "https://a.com/page#top" -> "https://a.com/page"
    static String withoutFragment(String url) {
        int hash = url.indexOf('#');
        if (hash >= 0) return url.substring(0, hash);
        return url;
    }

    // Absolute, protocol-relative and root-relative URLs are pointed through the proxy.
    // Plain relative URLs are left alone: they already resolve to /p/... URLs.
    static String proxify(String value, URI page) {
        String t = value.trim();
        String lower = t.toLowerCase();
        if (t.startsWith("//")) return toProxyPath(page.getScheme() + ":" + t);
        if (t.startsWith("/")) return toProxyPath(origin(page) + t);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return toProxyPath(t);
        return value;
    }

    // "url=abc&x=1", "url" -> "abc"
    static String queryParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                return URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}


// ==================== Rewriting links inside html and css ====================
class PageRewriter {

    // src="..." href='...' action=... and similar. Group 3, 4 or 5 is the value.
    static final Pattern URL_ATTR = Pattern.compile(
            "(?i)(?<![\\w-])(src|href|action|formaction|poster|data-src)(\\s*=\\s*)(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
    // srcset="a.png 1x, b.png 2x". Group 3 or 4 is the value.
    static final Pattern SRCSET = Pattern.compile(
            "(?i)(?<![\\w-])(srcset|data-srcset)(\\s*=\\s*)(?:\"([^\"]*)\"|'([^']*)')");
    static final Pattern TARGET = Pattern.compile("(?i)(?<![\\w-])target\\s*=\\s*([\"']?)_(?:blank|top|parent)\\1");
    static final Pattern INTEGRITY = Pattern.compile("(?i)\\s(?:integrity|nonce)\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    static final Pattern CSP_META = Pattern.compile("(?is)<meta\\b[^>]*http-equiv\\s*=\\s*[\"']?content-security-policy[^>]*>");
    // url(...) in css. Group 2 is the url.
    static final Pattern CSS_URL = Pattern.compile("(?i)url\\(\\s*([\"']?)([^)\"']*)\\1\\s*\\)");
    // @import "..." in css. Group 2 is the url.
    static final Pattern CSS_IMPORT = Pattern.compile("(?i)@import\\s+([\"'])([^\"']+)\\1");
    static final Pattern HEAD_OPEN = Pattern.compile("(?i)<head\\b[^>]*>");
    static final Pattern HTML_OPEN = Pattern.compile("(?i)<html\\b[^>]*>");
    static final Pattern DOCTYPE = Pattern.compile("(?i)<!doctype[^>]*>");
    static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([\\w.:-]+)");

    // Runs first in every proxied page. Points URLs that javascript builds (fetch, XHR,
    // img.src, links, pushState...) back through the proxy, and tells Java about links.
    static final String SHIM = """
            (function () {
              var S = location.pathname.split("/")[2] || "https";
              function px(u) {
                if (u == null) return u;
                u = String(u);
                if (u.slice(0, 2) === "//") u = S + ":" + u;
                var k = u.indexOf("://");
                if (k === 4 || k === 5) {
                  var sc = u.slice(0, k).toLowerCase();
                  if ((sc === "http" || sc === "https") &&
                      u.toLowerCase().indexOf(location.origin.toLowerCase() + "/") !== 0) {
                    return "/p/" + sc + "/" + u.slice(k + 3);
                  }
                }
                return u;
              }
              var of = window.fetch;
              if (of) window.fetch = function (i, init) {
                if (typeof i === "string" || (window.URL && i instanceof URL)) i = px(i);
                else if (i && i.url) i = new Request(px(i.url), i);
                return of.call(this, i, init);
              };
              var xo = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function () {
                var a = Array.prototype.slice.call(arguments);
                a[1] = px(a[1]);
                return xo.apply(this, a);
              };
              function hook(proto, prop) {
                var d = proto && Object.getOwnPropertyDescriptor(proto, prop);
                if (!d || !d.set) return;
                Object.defineProperty(proto, prop, {
                  get: d.get,
                  set: function (v) { d.set.call(this, px(v)); },
                  configurable: true,
                  enumerable: d.enumerable
                });
              }
              hook(window.HTMLImageElement && HTMLImageElement.prototype, "src");
              hook(window.HTMLScriptElement && HTMLScriptElement.prototype, "src");
              hook(window.HTMLLinkElement && HTMLLinkElement.prototype, "href");
              hook(window.HTMLIFrameElement && HTMLIFrameElement.prototype, "src");
              hook(window.HTMLMediaElement && HTMLMediaElement.prototype, "src");
              hook(window.HTMLSourceElement && HTMLSourceElement.prototype, "src");
              hook(window.HTMLVideoElement && HTMLVideoElement.prototype, "poster");
              hook(window.HTMLAnchorElement && HTMLAnchorElement.prototype, "href");
              hook(window.HTMLFormElement && HTMLFormElement.prototype, "action");
              var sa = Element.prototype.setAttribute;
              Element.prototype.setAttribute = function (n, v) {
                if (typeof n === "string" && ({ src: 1, href: 1, action: 1, poster: 1 })[n.toLowerCase()]) v = px(v);
                return sa.call(this, n, v);
              };
              ["pushState", "replaceState"].forEach(function (k) {
                var o = history[k];
                history[k] = function (s, t, u) {
                  if (k === "pushState") setTimeout(scan, 1000); // a new page drawn by javascript
                  return o.call(this, s, t, u == null ? u : px(u));
                };
              });
              try { Object.defineProperty(navigator, "serviceWorker", { value: undefined }); } catch (e) {}
              // Java downloads the pages links open before they are clicked, so a click is answered
              // from Java's temporary storage. Every link the user can see is sent once the page
              // loads (only from the site's own frame, not ads and other frames inside it), and a
              // link the user points at (or a button inside one) jumps the queue.
              var told = new WeakSet(), pointed = new WeakSet();
              // A new page opened: Java deletes the pages it stored for the last one
              if (of && window.parent === window.top) {
                of.call(window, "/__cac/opened", { method: "POST", body: location.href }).catch(function () {});
              }
              function tell(all, links) {
                if (!of) return;
                of.call(window, "/__cac/connected" + (all ? "?all" : ""),
                    { method: "POST", body: location.href + "\\n" + links }).catch(function () {});
              }
              function intent(e) {
                var a = e.target && e.target.closest && e.target.closest("a[href]");
                if (!a || pointed.has(a)) return;
                pointed.add(a);
                tell(false, a.cloneNode(false).outerHTML);
              }
              function scan() {
                if (window.parent !== window.top) return;
                // main.html shows the title on this page's tab and keeps the tab's history
                // (for its back and forward buttons)
                window.parent.postMessage({ cac: "page", url: location.href, title: document.title }, "*");
                var list = document.querySelectorAll("a[href]"), onScreen = [], below = [], chunk = "";
                for (var i = 0; i < list.length; i++) {
                  var a = list[i];
                  // Hidden links are skipped: the user can't click them (and some sites hide links to catch bots)
                  if (told.has(a) || !a.getClientRects().length) continue;
                  told.add(a);
                  var r = a.getBoundingClientRect();
                  // Links on screen go first: the user is most likely to click those
                  (r.bottom > 0 && r.top < innerHeight ? onScreen : below).push(a.cloneNode(false).outerHTML);
                }
                onScreen.concat(below).forEach(function (html) {
                  chunk += html + "\\n";
                  if (chunk.length > 50000) { tell(true, chunk); chunk = ""; }
                });
                if (chunk) tell(true, chunk);
              }
              document.addEventListener("pointerover", intent, true);
              document.addEventListener("focusin", intent, true);
              document.addEventListener("DOMContentLoaded", scan);
              window.addEventListener("load", scan);
              window.addEventListener("popstate", function () { setTimeout(scan, 1000); });
            })();
            """;

    // Html or css bytes -> the same file with its links pointed through the proxy
    static byte[] rewrite(byte[] raw, String contentType, URI page, boolean isHtml) {
        Charset cs = charsetOf(contentType, raw);
        String text = new String(raw, cs);
        if (isHtml) {
            text = rewriteHtml(text, page);
        } else {
            text = rewriteCss(text, page);
        }
        return text.getBytes(cs);
    }

    static String rewriteHtml(String html, URI page) {
        html = CSP_META.matcher(html).replaceAll("");                 // would block the proxied loads
        html = INTEGRITY.matcher(html).replaceAll(" ");               // rewritten files no longer match their hash
        html = TARGET.matcher(html).replaceAll("target=\"_self\""); // stay inside the focus window
        html = rewriteMatches(URL_ATTR, html, page);
        html = rewriteMatches(SRCSET, html, page);
        html = rewriteCss(html, page); // <style> blocks and style="" attributes
        return injectShim(html);
    }

    static String rewriteCss(String css, URI page) {
        css = rewriteMatches(CSS_URL, css, page);
        css = rewriteMatches(CSS_IMPORT, css, page);
        return css;
    }

    // Goes through every match of pattern in text and swaps it for newTextFor(match)
    static String rewriteMatches(Pattern pattern, String text, URI page) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0; // where the previous match ended
        while (m.find()) {
            out.append(text.substring(last, m.start())); // the text between matches stays the same
            out.append(newTextFor(pattern, m, page));
            last = m.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    // One match, with its URL pointed through the proxy
    static String newTextFor(Pattern pattern, Matcher m, URI page) {
        if (pattern == URL_ATTR) {
            String quote = "";
            if (m.group(3) != null) quote = "\"";
            else if (m.group(4) != null) quote = "'";
            return m.group(1) + m.group(2) + quote + ProxyUrls.proxify(valueOf(m), page) + quote;
        }
        if (pattern == SRCSET) {
            String value = m.group(4);
            String quote = "'";
            if (m.group(3) != null) {
                value = m.group(3);
                quote = "\"";
            }
            // "a.png 1x, b.png 2x": proxify() only changes the start, so the 1x/2x stays
            String urls = "";
            for (String candidate : value.split(",")) {
                if (candidate.isBlank()) continue;
                if (!urls.isEmpty()) urls += ", ";
                urls += ProxyUrls.proxify(candidate.trim(), page);
            }
            return m.group(1) + m.group(2) + quote + urls + quote;
        }
        if (pattern == CSS_URL) {
            return "url(" + m.group(1) + ProxyUrls.proxify(m.group(2), page) + m.group(1) + ")";
        }
        // CSS_IMPORT
        return "@import " + m.group(1) + ProxyUrls.proxify(m.group(2), page) + m.group(1);
    }

    // Puts the shim right after <head>, else <html>, else <!doctype>, else at the very start
    static String injectShim(String html) {
        String tag = "<script>" + SHIM + "</script>";
        Pattern[] places = {HEAD_OPEN, HTML_OPEN, DOCTYPE};
        for (Pattern place : places) {
            Matcher m = place.matcher(html);
            if (m.find()) return html.substring(0, m.end()) + tag + html.substring(m.end());
        }
        return tag + html;
    }

    // The value of a URL_ATTR match, whichever way it was quoted
    static String valueOf(Matcher m) {
        if (m.group(3) != null) return m.group(3);
        if (m.group(4) != null) return m.group(4);
        return m.group(5);
    }

    // The value of one url attribute (src, href...) in a tag, or null
    static String attrValue(String tag, String name) {
        Matcher m = URL_ATTR.matcher(tag);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase(name)) return valueOf(m);
        }
        return null;
    }

    // The text encoding: from the Content-Type header, else <meta charset> near the top, else UTF-8
    static Charset charsetOf(String contentType, byte[] raw) {
        Matcher m = CHARSET.matcher(contentType);
        if (!m.find()) {
            String top = new String(raw, 0, Math.min(raw.length, 2048), StandardCharsets.ISO_8859_1);
            m = CHARSET.matcher(top);
            if (!m.find()) return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(m.group(1));
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}


// ==================== Downloading pages before they are clicked ====================
// The shim sends every link on a page. Java downloads the pages they open, so a click is
// answered from memory instead of waiting for the site. Each stored page is handed out
// once and expires KEEP_SECONDS after it downloads.
class Prefetcher {

    static final int KEEP_SECONDS = 30;
    static final int MAX_PAGE_BYTES = 5_000_000;
    static final long MAX_TOTAL_BYTES = Runtime.getRuntime().maxMemory() / 2; // half of Java's memory

    // Links the user isn't pointing at wait in line here: 4 threads, one page each at a time
    static final ExecutorService inLine = Executors.newFixedThreadPool(4);

    // The browser's own headers, so a stored page is the one the browser would have been sent
    static volatile String userAgent;
    static volatile String acceptLanguage;

    // Links Java already rewrote: /p/https/... or http://localhost:8080/p/https/...
    static final Pattern PROXIED = Pattern.compile(
            "(?i)(?:https?://(?:localhost|127\\.0\\.0\\.1):" + proxyServer.PORT + ")?/p/https?/");
    static final Pattern LINK_TAG = Pattern.compile("(?is)<a\\b[^>]*>");
    // Paid links, download links, and links the site's javascript sends as POST/DELETE
    static final Pattern SKIPPED_LINK = Pattern.compile(
            "(?i)\\brel\\s*=\\s*[\"']?[^\"'>]*\\bsponsored\\b|\\s(?:download|data-method)\\b(?!-)");
    // Opening one of these changes something on the site (logs out, deletes...)
    static final Pattern NEVER_PREFETCH = Pattern.compile(
            "(?i)log[-_]?(?:out|off)|sign[-_]?(?:out|off)|unsubscribe|delete|add[-_]?to[-_]?cart");
    // Opening a link through an ad network counts as clicking the ad
    static final Set<String> AD_DOMAINS = Set.of("doubleclick.net", "googleadservices.com",
            "googlesyndication.com", "adservice.google.com", "amazon-adsystem.com", "adnxs.com",
            "taboola.com", "outbrain.com", "criteo.com");

    // real URL -> its stored page
    static final Map<String, StoredPage> pages = new ConcurrentHashMap<>();

    // links: the html of some <a> tags. page: the real URL they are on. now: the user is pointing at it.
    // synchronized: two requests can't add pages at the same moment.
    static synchronized void storeLinks(String links, URI page, boolean now) {
        removeExpired();
        String pageUrl = ProxyUrls.asRequested(page.toString());
        Matcher tag = LINK_TAG.matcher(links);
        while (tag.find()) {
            String element = tag.group();
            if (SKIPPED_LINK.matcher(element).find()) continue;
            URI link = linkTarget(PageRewriter.attrValue(element, "href"), page);
            if (link == null || BlockList.onDomain(link.getHost(), AD_DOMAINS)) continue;
            String url = link.toString();
            if (url.equals(pageUrl) || NEVER_PREFETCH.matcher(url).find()) continue;

            StoredPage stored = pages.get(url);
            if (stored != null && !now) continue; // already stored or on its way
            if (stored == null) {
                stored = new StoredPage();
                pages.put(url, stored);
            }
            // (if the user points at a link that is still in line, it is started now)
            DownloadJob job = new DownloadJob(stored, link, pageUrl, now);
            if (now) {
                new Thread(job).start(); // the user is pointing at it: download right away
            } else {
                inLine.execute(job);
            }
        }
    }

    // Runs on a download thread (see DownloadJob)
    static void download(StoredPage page, URI link, String referer, boolean now) {
        String url = link.toString();
        if (pages.get(url) != page) return; // deleted while it waited
        if (!page.start()) return;          // already downloading, or the browser asked for it first

        long storedBytes = 0;
        for (StoredPage p : pages.values()) storedBytes += p.size();
        boolean waitedTooLong = !now && page.waitedLongerThan(KEEP_SECONDS);
        if (waitedTooLong || storedBytes > MAX_TOTAL_BYTES) {
            pages.remove(url, page);
            page.fill(null);
            return;
        }

        Fetched html = fetch(link, referer);
        if (html != null && html.status < 300) {
            // Rewritten now, so a click only has to send it
            String type = ProxyHandler.header(html.headers, "Content-Type");
            if (type == null) type = "";
            byte[] rewritten = PageRewriter.rewrite(html.body, type, link, true);
            html = new Fetched(html.status, html.headers, rewritten);
        }
        page.fill(html);
    }

    // Downloads one page. null = nothing worth keeping (blocked, not html, too big, an error...).
    static Fetched fetch(URI uri, String referer) {
        try {
            if (BlockList.reject(uri, true) != null) return null;
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .header("Referer", referer);
            if (userAgent != null) request.header("User-Agent", userAgent);
            if (acceptLanguage != null) request.header("Accept-Language", acceptLanguage);
            HttpResponse<InputStream> resp = ProxyHandler.client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());

            InputStream in = resp.body();
            try {
                int status = resp.statusCode();
                String type = ProxyHandler.header(resp.headers(), "Content-Type");
                if (type == null) type = "";
                type = type.toLowerCase();
                boolean isRedirect = status >= 300 && status < 400 && ProxyHandler.header(resp.headers(), "Location") != null;
                boolean isPage = status >= 200 && status < 300
                        && (type.contains("text/html") || type.contains("application/xhtml"));
                if (!isRedirect && !isPage) return null; // closed before most of it downloads
                byte[] body = in.readNBytes(MAX_PAGE_BYTES + 1);
                if (body.length > MAX_PAGE_BYTES) return null;
                return new Fetched(status, resp.headers(), body);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // The stored copy of url, the first time the browser asks for it, else null.
    static Fetched take(String url) {
        StoredPage page = pages.get(url);
        if (page == null) return null;
        if (!page.handOut()) return null; // it was already given out once
        if (page.start()) return null;    // it was still in line: cancelled, the browser asks the site instead
        return page.waitForDownload();    // downloading or done: wait for it (up to 30s)
    }

    // Deletes every stored page except keepUrl (null = delete all)
    static void deleteAllExcept(String keepUrl) {
        for (String url : pages.keySet()) {
            if (!url.equals(keepUrl)) pages.remove(url);
        }
    }

    static void removeExpired() {
        for (String url : pages.keySet()) {
            StoredPage page = pages.get(url);
            if (page != null && page.isExpired()) pages.remove(url, page);
        }
    }

    // An href value -> the real URL it opens, or null if it isn't a web page (#top, mailto:...)
    static URI linkTarget(String value, URI page) {
        if (value == null) return null;
        String v = value.trim().replace("&amp;", "&"); // html writes & as &amp; inside attributes
        if (v.isEmpty() || v.startsWith("#")) return null;
        try {
            URI u;
            if (PROXIED.matcher(v).lookingAt()) {
                u = URI.create(ProxyUrls.fromProxyPath(v.substring(v.indexOf("/p/")))); // already rewritten
            } else {
                u = page.resolve(v);
            }
            String scheme = u.getScheme();
            boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!web || u.getHost() == null) return null;
            return URI.create(ProxyUrls.asRequested(u.toASCIIString()));
        } catch (Exception e) {
            return null;
        }
    }
}


// ==================== One page downloaded ahead of time ====================
// Several threads use a StoredPage at once (the download thread and the browser's request),
// so its methods are "synchronized": only one thread can be inside them at a time.
class StoredPage {
    private final long created = System.currentTimeMillis();
    private final CountDownLatch downloaded = new CountDownLatch(1); // opens once, when fill() is called
    private boolean started;   // the download began (or the browser asked first, so it never will)
    private boolean handedOut; // the browser was given it
    private Fetched result;    // null = nothing worth keeping
    private long filledAt;     // when fill() was called, 0 = not yet

    // true the first time only, so the page is downloaded at most once
    synchronized boolean start() {
        if (started) return false;
        started = true;
        return true;
    }

    // true the first time only, so the page is handed out at most once
    synchronized boolean handOut() {
        if (handedOut) return false;
        handedOut = true;
        return true;
    }

    // The download finished (page = null if there was nothing worth keeping)
    synchronized void fill(Fetched page) {
        result = page;
        filledAt = System.currentTimeMillis();
        downloaded.countDown();
    }

    // Waits for fill() (up to 30s), then returns the page, or null.
    // Not synchronized, so the download thread can call fill() while this waits.
    Fetched waitForDownload() {
        try {
            downloaded.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return null;
        }
        return resultIfFresh();
    }

    synchronized Fetched resultIfFresh() {
        if (filledAt == 0 || isExpired()) return null;
        return result;
    }

    // Downloaded more than KEEP_SECONDS ago
    synchronized boolean isExpired() {
        return filledAt != 0 && System.currentTimeMillis() - filledAt > Prefetcher.KEEP_SECONDS * 1000L;
    }

    boolean waitedLongerThan(int seconds) {
        return System.currentTimeMillis() - created > seconds * 1000L;
    }

    // Memory used, in bytes
    synchronized long size() {
        if (result == null) return 0;
        return result.body.length;
    }
}


// ==================== The work a download thread does ====================
// A Runnable is something a thread can run: the thread calls run().
class DownloadJob implements Runnable {
    private final StoredPage page;
    private final URI link;
    private final String referer;
    private final boolean now;

    DownloadJob(StoredPage page, URI link, String referer, boolean now) {
        this.page = page;
        this.link = link;
        this.referer = referer;
        this.now = now;
    }

    public void run() {
        Prefetcher.download(page, link, referer, now);
    }
}


// ==================== Sending answers ====================
class Responses {

    // Lets main.html (a different origin) read the answer
    static void cors(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Headers", "Content-Type");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    static void redirect(HttpExchange ex, int status, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(status, -1);
    }

    // why: "blocked" or "invalid"
    static void rejectPage(HttpExchange ex, String why) throws IOException {
        int status = 400;
        String msg = "Invalid or unsafe URL";
        if (why.equals("blocked")) {
            status = 403;
            msg = "This website is blocked";
        }
        String page = "<!doctype html><meta charset=utf-8><body style=\"font-family:sans-serif;"
                + "text-align:center;padding:40px\"><h1>" + msg + "</h1></body>";
        send(ex, status, "text/html; charset=utf-8", page.getBytes(StandardCharsets.UTF_8));
    }

    static void text(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        if (type != null) ex.getResponseHeaders().set("Content-Type", type);
        if (bytes.length == 0) {
            ex.sendResponseHeaders(status, -1); // -1 = no body
            return;
        }
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
