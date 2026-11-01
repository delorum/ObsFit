package ru.obsfit;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLHandshakeException;

public final class HttpGetTest {
  static int checks;
  static final String BODY = "{\"status\":1,\"product\":{\"product_name\":\"test\"}}";

  static void equal(Object e, Object a) {
    checks++;
    if (!Objects.equals(e, a)) throw new AssertionError("Expected " + e + ", got " + a);
  }

  static void response(HttpExchange x, int code, String body) throws IOException {
    byte[] data = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    x.sendResponseHeaders(code, data.length);
    try (var out = x.getResponseBody()) {
      out.write(data);
    }
  }

  public static void main(String[] args) throws Exception {
    HttpGet.Policy policy = new HttpGet.Policy(1000, 200, 3000, 2, 10);
    ExecutorService executor = Executors.newCachedThreadPool();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    Map<String, AtomicInteger> calls = new HashMap<>();
    for (String path :
        List.of(
            "/ok",
            "/slow",
            "/cut",
            "/service",
            "/missing",
            "/limited",
            "/bad",
            "/down",
            "/large",
            "/chunked")) {
      AtomicInteger count = new AtomicInteger();
      calls.put(path, count);
      server.createContext(
          path,
          x -> {
            int attempt = count.incrementAndGet();
            try {
              if (path.equals("/slow") && attempt == 1)
                try {
                  Thread.sleep(650);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              if (path.equals("/cut") && attempt == 1) {
                x.sendResponseHeaders(200, 100);
                x.getResponseBody().write('{');
                x.close();
                return;
              }
              if (path.equals("/large")) {
                x.sendResponseHeaders(200, HttpGet.MAX_BODY + 1);
                x.close();
                return;
              }
              if (path.equals("/chunked")) {
                x.sendResponseHeaders(200, 0);
                try (var out = x.getResponseBody()) {
                  out.write(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return;
              }
              int code =
                  path.equals("/missing")
                      ? 404
                      : path.equals("/limited")
                          ? 429
                          : path.equals("/bad")
                              ? 400
                              : path.equals("/down") || path.equals("/service") && attempt == 1
                                  ? 503
                                  : 200;
              response(x, code, code == 200 ? BODY : "error");
            } catch (IOException ignored) {
              x.close();
            }
          });
    }
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    try {
      equal(BODY, HttpGet.get(new URL(base + "/ok"), policy));
      equal(1, calls.get("/ok").get());
      equal(BODY, HttpGet.get(new URL(base + "/slow"), policy));
      equal(2, calls.get("/slow").get());
      equal(BODY, HttpGet.get(new URL(base + "/cut"), policy));
      equal(2, calls.get("/cut").get());
      equal(BODY, HttpGet.get(new URL(base + "/service"), policy));
      equal(2, calls.get("/service").get());
      equal(BODY, HttpGet.get(new URL(base + "/chunked"), policy));
      for (var test :
          Map.of("/missing", 404, "/limited", 429, "/bad", 400, "/down", 503).entrySet()) {
        try {
          HttpGet.get(new URL(base + test.getKey()), policy);
          throw new AssertionError("Expected failure");
        } catch (HttpGet.Failure e) {
          equal(test.getValue(), e.status);
        }
        equal(test.getKey().equals("/down") ? 2 : 1, calls.get(test.getKey()).get());
      }
      try {
        HttpGet.get(new URL(base + "/large"), policy);
        throw new AssertionError("Expected size error");
      } catch (IOException e) {
        equal("Response is too large", e.getMessage());
      }
      equal(1, calls.get("/large").get());
      AtomicInteger failures = new AtomicInteger();
      List<String> headers = new ArrayList<>();
      AtomicInteger retries = new AtomicInteger();
      URL closed = fake(failures, headers, new IOException("connection closed"));
      equal(
          BODY,
          HttpGet.get(
              closed,
              policy,
              (attempt, error, elapsed) -> {
                equal(1, attempt);
                equal(IOException.class, error.getClass());
                equal(true, elapsed >= 0);
                retries.incrementAndGet();
              }));
      equal(2, failures.get());
      equal("close", headers.get(1));
      equal(1, retries.get());
      AtomicInteger tls = new AtomicInteger();
      try {
        HttpGet.get(
            fake(tls, new ArrayList<>(), new SSLHandshakeException("certificate failure")), policy);
        throw new AssertionError("Expected TLS failure");
      } catch (SSLHandshakeException ok) {
        equal(1, tls.get());
      }
      AtomicInteger interrupted = new AtomicInteger();
      Thread.currentThread().interrupt();
      try {
        HttpGet.get(fake(interrupted, new ArrayList<>(), new IOException()), policy);
        throw new AssertionError("Expected cancellation");
      } catch (InterruptedIOException ok) {
        equal(0, interrupted.get());
      } finally {
        Thread.interrupted();
      }
      System.out.println("ObsFit HTTP recovery: " + checks + " checks passed");
    } finally {
      server.stop(0);
      executor.shutdownNow();
      executor.awaitTermination(2, TimeUnit.SECONDS);
    }
  }

  static URL fake(AtomicInteger calls, List<String> headers, IOException first) throws Exception {
    return new URL(
        null,
        "http://local.test/product",
        new URLStreamHandler() {
          protected URLConnection openConnection(URL u) {
            return new HttpURLConnection(u) {
              public void connect() {}

              public void disconnect() {}

              public boolean usingProxy() {
                return false;
              }

              public int getResponseCode() throws IOException {
                headers.add(getRequestProperty("Connection"));
                if (calls.incrementAndGet() == 1) throw first;
                return 200;
              }

              public InputStream getInputStream() {
                return new ByteArrayInputStream(
                    BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8));
              }
            };
          }
        });
  }
}
