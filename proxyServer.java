import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Java is the middleman for everything. Loopback-only server on port 8080.
//
//   GET  /p/<scheme>/<host>/<path>?<query>   fetches that URL and sends it back (pages, images,
//                                            css, js, API calls... everything a page loads)
//   GET  /check?url=<url>                    "ok" or "invalid"
//   POST /block                             sets the block list or allow list (only main.html may call it)
//   POST /cookies                           "on" or "off": are the sites' cookies saved and sent (only main.html may call it)
//   GET/POST /cookie?page=<page's /p/ path> document.cookie of a proxied page: reads or writes the cookie jar (only proxied pages may call it)
//   POST /shutdown                          stops this server (only main.html may call it)
//
// Each class below does one job:
//   proxyServer  starts the server and sends each request to the right place
//   ProxyHandler fetches a site for the browser
//   DnsLookup    asks the DNS server for a site's IP addresses
//   SiteRequest  connects to that IP address and asks the site for a file (html, css, images...)
//   CookieJar    keeps the cookies the sites set and says which ones to send back
//   ScriptCookies document.cookie for proxied pages, kept in the same cookie jar as the sites' own cookies
//   SiteResponse the site's answer: status, headers and body
//   BodyStream   reads the body of the site's answer
//   ProxyUrls    turns real URLs into /p/... URLs and back
//   PageRewriter changes the links inside html and css so they come back through here
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
        // Only main.html may call these three. Add any new main.html-only endpoint here.
        boolean mainHtmlOnly = method.equals("POST")
                && (path.equals("/shutdown") || path.equals("/block") || path.equals("/cookies"));
        try {
            if (method.equals("OPTIONS")) {
                Responses.cors(ex);
                ex.sendResponseHeaders(204, -1);

            } else if (path.equals("/check")) {
                Responses.cors(ex);
                Responses.text(ex, 200, check(ProxyUrls.queryParam(query, "url")));

            } else if (path.equals("/cookie")) {
                // document.cookie of a proxied page (the SHIM calls this): GET reads, POST writes
                ScriptCookies.handle(ex, method, query);

            } else if (mainHtmlOnly) {
                Responses.cors(ex);
                if (!isFromMainHtml(ex)) {
                    Responses.text(ex, 403, "forbidden");
                } else if (path.equals("/shutdown")) {
                    // the session's time is up
                    Responses.text(ex, 200, "ok");
                    new Thread(new Shutdown()).start();
                } else if (path.equals("/cookies")) {
                    // "on" or "off" (the cookie button in main.html)
                    SiteRequest.cookiesOn = readBody(ex, 16).trim().equals("on");
                    Responses.text(ex, 200, "ok");
                } else {
                    // "Instagram,TikTok", or "allow:a.com,b.org", or empty for no limits
                    BlockList.set(readBody(ex, 64000));
                    Responses.text(ex, 200, "ok");
                }

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

    // url -> "ok", or "invalid" if it isn't an http(s) URL on a public host
    static String check(String url) {
        if (url == null) return "invalid";
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return "invalid";
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return "invalid";
        if (BlockList.isBlocked(host, true)) return "blocked";
        if (DnsLookup.publicAddresses(host) == null) return "invalid";
        return "ok";
    }

    // Only main.html may call /shutdown and /block: it runs from file://, which
    // browsers send as "Origin: null", or from localhost/127.0.0.1 on a different port than this
    // server (a page proxied through this server shares this server's own origin and port).
    static boolean isFromMainHtml(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.equals("null")) return true;
        String self1 = "http://localhost:" + PORT;
        String self2 = "http://127.0.0.1:" + PORT;
        if (origin.equals(self1) || origin.equals(self2)) return false;
        return origin.startsWith("http://localhost:") || origin.startsWith("http://127.0.0.1:");
    }

    // Only a page proxied through this server may use /cookie (it shares this server's origin).
    // Browsers say who is asking in Sec-Fetch-Site, so a page from another website is refused.
    static boolean isFromProxiedPage(HttpExchange ex) {
        String site = ex.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if (site != null) return site.equals("same-origin");
        String origin = ex.getRequestHeaders().getFirst("Origin");
        return origin == null || origin.equals("http://localhost:" + PORT) || origin.equals("http://127.0.0.1:" + PORT);
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

    // "Instagram,TikTok,example.com" -> blocked domains. An empty list blocks nothing.
    // "allow:wikipedia.org,desmos.com" -> only those sites are allowed instead.
    static void set(String csv) {
        csv = csv.trim();
        Set<String> allowed = new HashSet<>();
        Set<String> blocked = new HashSet<>();
        if (csv.toLowerCase().startsWith("allow:")) {
            for (String item : csv.substring(6).split(",")) {
                String name = item.trim().toLowerCase();
                if (name.startsWith("www.")) name = name.substring(4);
                if (!name.isEmpty()) allowed.add(name);
            }
        } else {
            for (String item : csv.split(",")) {
                String name = item.trim().toLowerCase();
                if (name.isEmpty()) continue;
                if (APP_DOMAINS.containsKey(name)) {
                    blocked.addAll(APP_DOMAINS.get(name));
                } else {
                    blocked.add(name);
                }
            }
        }
        allowedDomains = allowed;
        blockedDomains = blocked;
    }

    // isPage: the URL opens a page (not an image, script, css or API call). The allow list only
    // checks pages, because allowed sites load their files from other hosts (CDNs).
    static boolean isBlocked(String host, boolean isPage) {
        if (host == null) return false;
        if (onDomain(host, blockedDomains)) return true;
        Set<String> allowed = allowedDomains;
        return isPage && !allowed.isEmpty() && !onDomain(host, allowed);
    }

    // host is one of domains, or a subdomain of one ("www.reddit.com" is on "reddit.com")
    static boolean onDomain(String host, Set<String> domains) {
        host = host.toLowerCase();
        for (String d : domains) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
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


// ==================== Fetching a site for the browser ====================
class ProxyHandler {

    // Redirects are not followed here: the browser gets them, so every hop is checked again.

    static void proxy(HttpExchange ex, String path, String query) throws Exception {
        Headers in = ex.getRequestHeaders();
        String q = "";
        if (query != null) q = "?" + query;
        String referer = ProxyUrls.unproxyUrl(in.getFirst("Referer")); // real URL of the page that asked, or null
        String dest = in.getFirst("Sec-Fetch-Dest");
        boolean opensPage = "document".equals(dest) || "iframe".equals(dest);

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
            // A page opening here (location.href = "/v3/signin") would be at the wrong address,
            // so its own links would break: send it to its /p/... address instead
            if (opensPage) {
                Responses.redirect(ex, 302, ProxyUrls.toProxyPath(target));
                return;
            }
        }
        URI uri = URI.create(target);

        // Checked on every request, not just the first, so a blocked site can't be reached by a link
        if (BlockList.isBlocked(uri.getHost(), opensPage)) {
            Responses.text(ex, 403, "This website is blocked");
            return;
        }

        String method = ex.getRequestMethod();
        byte[] body = ex.getRequestBody().readAllBytes();
        boolean isHead = method.equals("HEAD");

        // 2. Get the answer from the site: Java asks DNS for its IP address, then asks that IP for the file
        SiteResponse resp = SiteRequest.send(method, uri, buildHeaders(in, uri, referer), body, 30);
        int status = resp.status;
        Map<String, List<String>> h = resp.headers;
        InputStream respBody = resp.body;

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
            raw = PageRewriter.rewrite(raw, contentType, uri, isHtml);
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

    // The headers Java sends to the real site, copying the browser's useful ones
    static Map<String, String> buildHeaders(Headers in, URI uri, String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        String[] copied = {"User-Agent", "Accept", "Accept-Language", "Content-Type", "Range"};
        for (String name : copied) {
            if (in.getFirst(name) != null) headers.put(name, in.getFirst(name));
        }

        // The site is told the real page that asked, not localhost
        if (referer != null) {
            headers.put("Referer", referer);
            if (in.containsKey("Origin")) headers.put("Origin", ProxyUrls.origin(URI.create(referer)));
        } else {
            headers.put("Referer", ProxyUrls.origin(uri) + "/");
            if (in.containsKey("Origin")) headers.put("Origin", ProxyUrls.origin(uri));
        }
        return headers;
    }

    // The value of one header, or null if the site didn't send it
    static String header(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) return null;
        return values.get(0);
    }
}


// ==================== Asking the DNS server for a site's IP address ====================
class DnsLookup {

    // The IP addresses of host, or null if the host doesn't exist or any of its addresses
    // is on this computer or the local network. (Java remembers each answer for about 30s.)
    // Java reads an IPv6 address in brackets, like "[::1]", by itself.
    static InetAddress[] publicAddresses(String host) {
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            for (InetAddress a : all) {
                if (isPrivate(a)) return null;
            }
            return all;
        } catch (Exception e) {
            return null; // unknown host
        }
    }

    // Keeps pages from reaching this computer or the local network
    static boolean isPrivate(InetAddress a) {
        String ip = a.getHostAddress();
        boolean ipv6Private = ip.startsWith("fc") || ip.startsWith("fd"); // fc00::/7
        return a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress()
                || a.isLinkLocalAddress() || ipv6Private;
    }
}


// ==================== Asking a site's IP address for a file ====================
// Java connects to the IP address DnsLookup found (the same one that was checked),
// then asks for the file over HTTP/1.1. One connection per file.
class SiteRequest {

    // Keeps the sites' login cookies
    static final CookieJar COOKIES = new CookieJar();

    // The cookie button in main.html. While this is off no cookie is saved or sent (the ones
    // already saved stay in COOKIES and are used again when it is turned back on).
    // volatile: every thread sees the newest value as soon as it changes
    static volatile boolean cookiesOn = false;
    static final SSLSocketFactory TLS = (SSLSocketFactory) SSLSocketFactory.getDefault();

    static SiteResponse send(String method, URI uri, Map<String, String> headers, byte[] body,
                             int timeoutSeconds) throws IOException {
        String host = uri.getHost();
        String bareHost = host;
        if (bareHost.startsWith("[")) bareHost = bareHost.substring(1, bareHost.length() - 1);
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort();
        if (port == -1) {
            port = 80;
            if (https) port = 443;
        }

        // 1. Ask the DNS server for the site's IP addresses
        InetAddress[] ips = DnsLookup.publicAddresses(bareHost);
        if (ips == null) throw new IOException(host + " doesn't exist, or is on this computer or the local network");

        // 2. Connect to the IP address
        Socket socket = connect(ips, port);
        boolean ok = false;
        try {
            socket.setSoTimeout(timeoutSeconds * 1000);
            if (https) socket = startTls(socket, bareHost, port);

            // 3. Ask for the file
            OutputStream out = socket.getOutputStream();
            out.write(requestHead(method, uri, headers, body).getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();

            // 4. Read the answer's status and headers ("100 Continue" and other 1xx answers are skipped)
            InputStream in = new BufferedInputStream(socket.getInputStream());
            int status;
            Map<String, List<String>> responseHeaders;
            do {
                status = statusOf(readLine(in));
                responseHeaders = readHeaders(in);
            } while (status >= 100 && status < 200);
            if (cookiesOn) COOKIES.save(uri, responseHeaders);

            // 5. The body: how its end is found depends on the headers
            String encoding = ProxyHandler.header(responseHeaders, "Transfer-Encoding");
            String length = ProxyHandler.header(responseHeaders, "Content-Length");
            BodyStream bodyStream;
            if (method.equals("HEAD") || status == 204 || status == 304) {
                bodyStream = new BodyStream(in, socket, false, 0);
            } else if (encoding != null && encoding.toLowerCase().contains("chunked")) {
                bodyStream = new BodyStream(in, socket, true, -1);
            } else if (length != null) {
                bodyStream = new BodyStream(in, socket, false, Long.parseLong(length.trim()));
            } else {
                bodyStream = new BodyStream(in, socket, false, -1); // until the site hangs up
            }
            ok = true;
            return new SiteResponse(status, responseHeaders, bodyStream);
        } finally {
            if (!ok) socket.close();
        }
    }

    // Tries each IP address until one answers
    static Socket connect(InetAddress[] ips, int port) throws IOException {
        IOException last = null;
        for (InetAddress ip : ips) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(ip, port), 10000);
                return socket;
            } catch (IOException e) {
                socket.close();
                last = e;
            }
        }
        throw last;
    }

    // Turns on https. The site is told its name (SNI), and its certificate must be for that
    // name, not just for the IP address that was connected to.
    static Socket startTls(Socket plain, String host, int port) throws IOException {
        SSLSocket tls = (SSLSocket) TLS.createSocket(plain, host, port, true);
        SSLParameters params = tls.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setSSLParameters(params);
        tls.startHandshake();
        return tls;
    }

    // "GET /a?b HTTP/1.1", then the headers, then an empty line
    static String requestHead(String method, URI uri, Map<String, String> headers, byte[] body) throws IOException {
        URI ascii = URI.create(uri.toASCIIString());
        String target = ascii.getRawPath();
        if (target == null || target.isEmpty()) target = "/";
        if (ascii.getRawQuery() != null) target = target + "?" + ascii.getRawQuery();
        String hostHeader = uri.getHost();
        if (uri.getPort() != -1) hostHeader = hostHeader + ":" + uri.getPort();

        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
        addHeader(head, "Host", hostHeader);
        for (String name : headers.keySet()) {
            addHeader(head, name, headers.get(name));
        }
        if (cookiesOn) {
            String cookies = COOKIES.header(uri);
            if (!cookies.isEmpty()) addHeader(head, "Cookie", cookies);
        }
        boolean sendsBody = body.length > 0 || method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
        if (sendsBody) addHeader(head, "Content-Length", String.valueOf(body.length));
        addHeader(head, "Accept-Encoding", "identity"); // not zipped, so html and css can be rewritten
        addHeader(head, "Connection", "close");
        head.append("\r\n");
        return head.toString();
    }

    static void addHeader(StringBuilder head, String name, String value) {
        value = value.replace("\r", "").replace("\n", ""); // a line break would start a new header
        head.append(name).append(": ").append(value).append("\r\n");
    }

    // "HTTP/1.1 200 OK" -> 200
    static int statusOf(String line) throws IOException {
        if (line == null) throw new IOException("The site closed the connection without answering");
        String[] parts = line.split(" ");
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("Not an HTTP answer");
        return Integer.parseInt(parts[1]);
    }

    // "Name: value" lines until an empty line. Names ignore upper/lower case.
    static Map<String, List<String>> readHeaders(InputStream in) throws IOException {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String line = readLine(in);
        while (line != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (!headers.containsKey(name)) headers.put(name, new ArrayList<>());
                headers.get(name).add(value);
            }
            line = readLine(in);
        }
        return headers;
    }

    // One line (without the \r\n), or null if the site hung up first
    static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (line.length() == 0) return null;
                break;
            }
            if (b == '\n') break;
            if (line.length() > 65536) throw new IOException("Line too long");
            line.append((char) b);
        }
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\r') line.setLength(end - 1);
        return line.toString();
    }
}


// ==================== The cookie jar ====================
// Keeps the cookies the sites set and says which ones a request carries, by the rules browsers use
// (RFC 6265). java.net.CookieManager is not used: it treats a cookie with Max-Age as an old-style one
// and sends it back as name="value";$Path="/", and it only sends a ".google.com" cookie to hosts with
// one name in front of google.com (so not to drive.clients6.google.com).
// Not done: SameSite (every cookie is sent whoever asks), and the public suffix list (a site could
// set a cookie for "co.uk").
class CookieJar {

    static final long NEVER = Long.MAX_VALUE;    // expires: a cookie that lasts until the server stops
    static final long INVALID = Long.MIN_VALUE;  // a date that can't be read
    static final long MAX_AGE_SECONDS = 400L * 24 * 60 * 60; // browsers don't keep a cookie longer than this
    static final int MAX_COOKIES = 3000;
    static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    // The pieces of a date are told apart by these characters: "Thu, 26-Oct-2028 03:21:05 GMT"
    static final Pattern DATE_SEPARATORS = Pattern.compile("[\\x09\\x20-\\x2F\\x3B-\\x40\\x5B-\\x60\\x7B-\\x7E]+");
    static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{1,2}):(\\d{1,2})");
    static final Pattern DAY = Pattern.compile("\\d{1,2}");
    static final Pattern YEAR = Pattern.compile("\\d{2,4}");

    // One cookie. Its name, domain and path say which cookie it is.
    static class StoredCookie implements Comparable<StoredCookie> {
        String name;
        String value;
        String domain;     // lower case, without a leading dot
        boolean hostOnly;  // only for exactly this host, not for its subdomains
        String path;
        boolean secure;
        boolean httpOnly;
        long expires;      // milliseconds since 1970, or NEVER
        long created;      // 1 for the first cookie, 2 for the next one...

        // A request carries the cookies with the longest path first, and the oldest first among those
        public int compareTo(StoredCookie other) {
            if (path.length() != other.path.length()) return other.path.length() - path.length();
            return Long.compare(created, other.created);
        }
    }

    final List<StoredCookie> cookies = new ArrayList<>();
    long createdCount = 0;

    // The Set-Cookie headers of a site's answer
    synchronized void save(URI uri, Map<String, List<String>> responseHeaders) {
        List<String> lines = responseHeaders.get("Set-Cookie");
        if (lines == null) return;
        for (String line : lines) {
            store(uri, line, false);
        }
    }

    // document.cookie = "..." in a script on the page
    synchronized void saveFromScript(URI page, String cookie) {
        store(page, cookie, true);
    }

    // The Cookie header for a request to uri, like "a=1; b=2" ("" when there is nothing to send)
    synchronized String header(URI uri) {
        return join(matching(uri, false));
    }

    // What document.cookie shows on that page: the same, but without the HttpOnly cookies
    synchronized String forScript(URI page) {
        return join(matching(page, true));
    }

    // "a=1; Domain=.example.com; Path=/; Max-Age=3600; Secure" -> saved, replaced or deleted
    void store(URI uri, String line, boolean fromScript) {
        String host = uri.getHost();
        if (host == null) return;
        host = host.toLowerCase();
        boolean https = "https".equalsIgnoreCase(uri.getScheme());

        String[] parts = line.split(";");
        int equals = parts[0].indexOf('=');
        if (equals < 0) return; // not "name=value"
        StoredCookie c = new StoredCookie();
        c.name = parts[0].substring(0, equals).trim();
        c.value = parts[0].substring(equals + 1).trim();
        if (c.name.isEmpty()) return;
        c.expires = NEVER;

        String domainAttribute = "";
        String pathAttribute = "";
        boolean maxAgeGiven = false; // Max-Age wins over Expires, whichever comes first
        long now = System.currentTimeMillis();
        for (int i = 1; i < parts.length; i++) {
            String attribute = parts[i].trim();
            String attributeName = attribute;
            String attributeValue = "";
            int eq = attribute.indexOf('=');
            if (eq >= 0) {
                attributeName = attribute.substring(0, eq).trim();
                attributeValue = attribute.substring(eq + 1).trim();
            }
            attributeName = attributeName.toLowerCase();

            if (attributeName.equals("domain")) {
                domainAttribute = attributeValue.toLowerCase();
                if (domainAttribute.startsWith(".")) domainAttribute = domainAttribute.substring(1);
            } else if (attributeName.equals("path")) {
                pathAttribute = attributeValue;
            } else if (attributeName.equals("secure")) {
                c.secure = true;
            } else if (attributeName.equals("httponly")) {
                c.httpOnly = true;
            } else if (attributeName.equals("max-age")) {
                try {
                    long seconds = Long.parseLong(attributeValue);
                    if (seconds <= 0) {
                        c.expires = 0; // already over: this deletes the cookie
                    } else {
                        c.expires = now + Math.min(seconds, MAX_AGE_SECONDS) * 1000;
                    }
                    maxAgeGiven = true;
                } catch (NumberFormatException e) {
                    // a Max-Age that isn't a number is ignored
                }
            } else if (attributeName.equals("expires") && !maxAgeGiven) {
                long date = parseDate(attributeValue);
                if (date != INVALID) c.expires = Math.min(date, now + MAX_AGE_SECONDS * 1000);
            }
        }

        // Domain: the cookie is for the host alone, unless the site names a domain the host is part of
        if (domainAttribute.isEmpty()) {
            c.domain = host;
            c.hostOnly = true;
        } else {
            boolean isIpAddress = host.matches("[0-9.]+") || host.contains(":");
            boolean inDomain = host.equals(domainAttribute)
                    || (!isIpAddress && domainAttribute.contains(".") && host.endsWith("." + domainAttribute));
            if (!inDomain) return; // a site can't set cookies for other sites
            c.domain = domainAttribute;
            c.hostOnly = false;
        }

        if (pathAttribute.startsWith("/")) {
            c.path = pathAttribute;
        } else {
            c.path = defaultPath(uri);
        }

        if (c.secure && !https) return;                      // a plain http page can't set a Secure cookie
        if (fromScript && c.httpOnly) return;                // a script can't make an HttpOnly cookie
        if (c.name.startsWith("__Secure-") && !c.secure) return;
        if (c.name.startsWith("__Host-") && !(c.secure && c.hostOnly && c.path.equals("/"))) return;

        // The same cookie again replaces the old one
        for (int i = 0; i < cookies.size(); i++) {
            StoredCookie old = cookies.get(i);
            if (old.name.equals(c.name) && old.domain.equals(c.domain) && old.path.equals(c.path)) {
                if (fromScript && old.httpOnly) return;      // a script can't replace an HttpOnly cookie
                c.created = old.created;
                cookies.remove(i);
                break;
            }
        }
        if (c.expires <= now) return; // Max-Age=0 or a date in the past: the old cookie is gone, nothing new is kept
        if (c.created == 0) {
            createdCount++;
            c.created = createdCount;
        }
        cookies.add(c);
        if (cookies.size() > MAX_COOKIES) removeOldest();
    }

    // The cookies a request to uri carries (without the HttpOnly ones when scriptOnly), in sending order
    List<StoredCookie> matching(URI uri, boolean scriptOnly) {
        List<StoredCookie> found = new ArrayList<>();
        String host = uri.getHost();
        if (host == null) return found;
        host = host.toLowerCase();
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        long now = System.currentTimeMillis();

        for (int i = cookies.size() - 1; i >= 0; i--) {
            StoredCookie c = cookies.get(i);
            if (c.expires <= now) {
                cookies.remove(i);
                continue;
            }
            if (c.secure && !https) continue;
            if (scriptOnly && c.httpOnly) continue;
            boolean domainMatches = host.equals(c.domain) || (!c.hostOnly && host.endsWith("." + c.domain));
            if (domainMatches && pathMatches(c.path, path)) found.add(c);
        }
        Collections.sort(found);
        return found;
    }

    static String join(List<StoredCookie> list) {
        StringBuilder text = new StringBuilder();
        for (StoredCookie c : list) {
            if (text.length() > 0) text.append("; ");
            text.append(c.name).append('=').append(c.value);
        }
        return text.toString();
    }

    // "/a/b/page" -> "/a/b", and "/page" or "/" -> "/"
    static String defaultPath(URI uri) {
        String path = uri.getRawPath();
        if (path == null || !path.startsWith("/")) return "/";
        int last = path.lastIndexOf('/');
        if (last == 0) return "/";
        return path.substring(0, last);
    }

    // A cookie for "/a" goes to "/a" and "/a/b" but not "/ab"
    static boolean pathMatches(String cookiePath, String requestPath) {
        if (requestPath.equals(cookiePath)) return true;
        if (!requestPath.startsWith(cookiePath)) return false;
        return cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length()) == '/';
    }

    void removeOldest() {
        int oldest = 0;
        for (int i = 1; i < cookies.size(); i++) {
            if (cookies.get(i).created < cookies.get(oldest).created) oldest = i;
        }
        cookies.remove(oldest);
    }

    // The date styles sites use in Expires ("Thu, 26-Oct-2028 03:21:05 GMT", "Wed Oct 21 07:28:00 2026"...)
    // -> milliseconds since 1970, or INVALID. The pieces are found by what they look like, as browsers do.
    static long parseDate(String text) {
        int hour = -1;
        int minute = -1;
        int second = -1;
        int day = -1;
        int month = -1;
        int year = -1;
        for (String token : DATE_SEPARATORS.split(text)) {
            if (token.isEmpty()) continue;
            Matcher time = TIME.matcher(token);
            Matcher dayMatch = DAY.matcher(token);
            Matcher yearMatch = YEAR.matcher(token);
            if (hour < 0 && time.lookingAt()) {
                hour = Integer.parseInt(time.group(1));
                minute = Integer.parseInt(time.group(2));
                second = Integer.parseInt(time.group(3));
            } else if (day < 0 && dayMatch.lookingAt()) {
                day = Integer.parseInt(dayMatch.group());
            } else if (month < 0 && token.length() >= 3 && monthNumber(token.substring(0, 3)) >= 0) {
                month = monthNumber(token.substring(0, 3));
            } else if (year < 0 && yearMatch.lookingAt()) {
                year = Integer.parseInt(yearMatch.group());
            }
        }
        if (hour < 0 || day < 0 || month < 0 || year < 0) return INVALID;
        if (year >= 70 && year <= 99) year += 1900;
        if (year >= 0 && year <= 69) year += 2000;
        try {
            return ZonedDateTime.of(year, month + 1, day, hour, minute, second, 0, ZoneOffset.UTC)
                    .toInstant().toEpochMilli();
        } catch (DateTimeException e) {
            return INVALID; // like the 31st of February
        }
    }

    // "Oct" -> 9, or -1
    static int monthNumber(String name) {
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equalsIgnoreCase(name)) return i;
        }
        return -1;
    }
}


// ==================== document.cookie of a proxied page ====================
// A proxied page sits in a frame under main.html, which is another site, so the browser throws away
// the cookies its javascript sets (and sites like Google then say cookies are disabled). The SHIM
// sends document.cookie here instead: the same cookie jar as the sites' own Set-Cookie headers, so a
// cookie a script sets is also sent to the site, and a cookie the site sets can be read by the script.
class ScriptCookies {

    // GET /cookie?page=/p/https/host/path reads, POST writes (the body is what was assigned to document.cookie)
    static void handle(HttpExchange ex, String method, String query) throws IOException {
        if (!proxyServer.isFromProxiedPage(ex)) {
            Responses.text(ex, 403, "forbidden");
            return;
        }
        String real = ProxyUrls.unproxyUrl(ProxyUrls.queryParam(query, "page"));
        if (real == null) {
            Responses.text(ex, 400, "Bad page");
            return;
        }
        URI page = URI.create(real);
        if (page.getHost() == null || BlockList.isBlocked(page.getHost(), false)) {
            Responses.text(ex, 403, "This website is blocked");
            return;
        }

        ex.getResponseHeaders().set("Cache-Control", "no-store"); // every read must ask again
        if (method.equals("POST")) {
            write(page, proxyServer.readBody(ex, 8192));
            Responses.text(ex, 200, "ok");
        } else {
            Responses.text(ex, 200, read(page));
        }
    }

    // The cookies a script on this page may read, like "a=1; b=2". HttpOnly cookies are for the
    // site only, so a script can't see them (and they are still sent to the site by SiteRequest).
    static String read(URI page) {
        if (!SiteRequest.cookiesOn) return "";
        return SiteRequest.COOKIES.forScript(page);
    }

    // document.cookie = "a=1; path=/; max-age=3600", saved like a Set-Cookie header from the page's site
    static void write(URI page, String cookie) {
        if (!SiteRequest.cookiesOn) return;
        SiteRequest.COOKIES.saveFromScript(page, cookie);
    }
}


// ==================== The site's answer ====================
class SiteResponse {
    final int status;
    final Map<String, List<String>> headers;
    final InputStream body; // closing it closes the connection

    SiteResponse(int status, Map<String, List<String>> headers, InputStream body) {
        this.status = status;
        this.headers = headers;
        this.body = body;
    }
}


// ==================== Reading the body of the site's answer ====================
// The body ends after Content-Length bytes, or after the last "chunk" (chunked answers send
// their size before each piece), or when the site hangs up.
class BodyStream extends InputStream {
    private final InputStream in;
    private final Socket socket;
    private final boolean chunked;
    private long left;                 // bytes left in the body (or in this chunk). -1 = until the site hangs up
    private boolean firstChunk = true;
    private boolean done;

    BodyStream(InputStream in, Socket socket, boolean chunked, long length) {
        this.in = in;
        this.socket = socket;
        this.chunked = chunked;
        if (chunked) {
            left = 0;
        } else {
            left = length;
            if (length == 0) done = true;
        }
    }

    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        if (n < 0) return -1;
        return one[0] & 0xff;
    }

    public int read(byte[] buf, int off, int len) throws IOException {
        if (done) return -1;
        if (len == 0) return 0;
        if (chunked && left == 0) {
            if (!firstChunk) SiteRequest.readLine(in); // the empty line after each chunk
            firstChunk = false;
            String sizeLine = SiteRequest.readLine(in);
            if (sizeLine == null) throw new EOFException("The site stopped in the middle of the answer");
            int semicolon = sizeLine.indexOf(';');
            if (semicolon >= 0) sizeLine = sizeLine.substring(0, semicolon);
            left = Long.parseLong(sizeLine.trim(), 16);
            if (left == 0) {
                // The last chunk. Any headers after it are skipped.
                String line = SiteRequest.readLine(in);
                while (line != null && !line.isEmpty()) line = SiteRequest.readLine(in);
                done = true;
                return -1;
            }
        }
        int want = len;
        if (left >= 0 && left < want) want = (int) left;
        int n = in.read(buf, off, want);
        if (n < 0) {
            done = true;
            if (left > 0) throw new EOFException("The site stopped in the middle of the answer");
            return -1;
        }
        if (left >= 0) {
            left -= n;
            if (!chunked && left == 0) done = true;
        }
        return n;
    }

    public void close() throws IOException {
        socket.close();
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
    static final Pattern REFERRER_META = Pattern.compile("(?is)<meta\\b[^>]*name\\s*=\\s*[\"']?referrer[^>]*>");
    static final Pattern REFERRER_POLICY = Pattern.compile("(?i)\\sreferrerpolicy\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    // url(...) in css. Group 2 is the url.
    static final Pattern CSS_URL = Pattern.compile("(?i)url\\(\\s*([\"']?)([^)\"']*)\\1\\s*\\)");
    // @import "..." in css. Group 2 is the url.
    static final Pattern CSS_IMPORT = Pattern.compile("(?i)@import\\s+([\"'])([^\"']+)\\1");
    static final Pattern HEAD_OPEN = Pattern.compile("(?i)<head\\b[^>]*>");
    static final Pattern HTML_OPEN = Pattern.compile("(?i)<html\\b[^>]*>");
    static final Pattern DOCTYPE = Pattern.compile("(?i)<!doctype[^>]*>");
    static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([\\w.:-]+)");

    // Runs first in every proxied page. Points URLs that javascript builds (fetch, XHR,
    // img.src, links, pushState...) back through the proxy, and tells main.html when the
    // page is loading and what it is called.
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
                  if (k === "pushState") setTimeout(tell, 1000, "page"); // a new page drawn by javascript
                  return o.call(this, s, t, u == null ? u : px(u));
                };
              });
              try { Object.defineProperty(navigator, "serviceWorker", { value: undefined }); } catch (e) {}
              // document.cookie: the browser would throw away what this page sets (it is in a frame under
              // another site), so Java keeps the cookies instead, together with the ones the site sets itself
              function cookieCall(method, body) {
                try {
                  var x = new XMLHttpRequest();
                  x.open(method, "/cookie?page=" + encodeURIComponent(location.pathname + location.search), false);
                  x.send(body);
                  return x.status === 200 ? x.responseText : "";
                } catch (e) { return ""; }
              }
              try {
                Object.defineProperty(Document.prototype, "cookie", {
                  get: function () { return cookieCall("GET"); },
                  set: function (v) { cookieCall("POST", String(v)); },
                  configurable: true,
                  enumerable: true
                });
              } catch (e) {}
              // main.html is told about this page: "page" gives its address and title (for the tab's
              // name and its history, which drives the back and forward buttons), "loading" locks it
              // when a link or form is about to open a new page, and "loaded" (the page's load event)
              // unlocks it. Only the site's own frame tells, not ads and other frames inside it.
              function tell(kind) {
                if (window.parent !== window.top) return;
                window.parent.postMessage({ cac: kind, url: location.href, title: document.title }, "*");
              }
              // location.href = "https://..." can't be hooked, so it would leave the proxy (and the
              // real site refuses to show in a frame). The navigation is stopped and sent through Java.
              if (window.navigation) navigation.addEventListener("navigate", function (e) {
                if (e.destination.sameDocument || e.downloadRequest != null) return; // no new page loads
                // The site's own handlers run after this one and may cancel the navigation or keep it
                // in this page (the history entry changes), so the page is only locked once they are done
                var entry = navigation.currentEntry;
                setTimeout(function () {
                  if (!e.defaultPrevented && !navigation.transition && navigation.currentEntry === entry) tell("loading");
                }, 0);
                if (!e.cancelable || e.formData) return;
                var u = e.destination.url, p = px(u);
                if (p === u) return; // already through the proxy
                e.preventDefault();
                location.href = p;
              });
              document.addEventListener("DOMContentLoaded", function () { tell("page"); });
              window.addEventListener("load", function () { tell("loaded"); });
              window.addEventListener("popstate", function () { setTimeout(tell, 1000, "page"); });
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
        // Root-relative requests (/CheckCookie) find their site from the Referer, so it must stay the full URL
        html = REFERRER_META.matcher(html).replaceAll("");
        html = REFERRER_POLICY.matcher(html).replaceAll(" ");
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
