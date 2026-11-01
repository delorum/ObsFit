package ru.obsfit;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.util.Locale;

/** Bounded, idempotent GET retries. JSON parsing and permanent API errors are not retried. */
public final class HttpGet {
    private HttpGet() {}
    static final int MAX_BODY=1024*1024;
    public record Policy(int connectMs,int readMs,int totalMs,int attempts,int retryDelayMs) {
        public Policy {if(connectMs<=0||readMs<=0||totalMs<=0||attempts<1||retryDelayMs<0)throw new IllegalArgumentException("Invalid HTTP policy");}
    }
    public static final Policy DEFAULT=new Policy(10000,25000,55000,2,300);
    public static final class Failure extends IOException {
        public final int status;
        public Failure(int status){super("HTTP "+status);this.status=status;}
    }
    @FunctionalInterface public interface RetryListener {void retry(int failedAttempt,IOException error,long elapsedMs);}
    private static final RetryListener NOOP=(attempt,error,elapsed)->{};
    public static String get(URL url) throws IOException {return get(url,DEFAULT,NOOP);}
    public static String get(URL url,RetryListener listener) throws IOException {return get(url,DEFAULT,listener);}
    static String get(URL url,Policy policy) throws IOException {return get(url,policy,NOOP);}
    static String get(URL url,Policy policy,RetryListener listener) throws IOException {
        long started=System.nanoTime(),deadline=started+policy.totalMs()*1_000_000L;
        for(int attempt=0;attempt<policy.attempts();attempt++) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Request interrupted");
            try{return once(url,policy,deadline,attempt>0);}
            catch(IOException error) {
                if(attempt+1>=policy.attempts()||!retryable(error)||remaining(deadline)<=policy.retryDelayMs())throw error;
                listener.retry(attempt+1,error,(System.nanoTime()-started)/1_000_000L);
                try{Thread.sleep(policy.retryDelayMs());}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("Request interrupted");}
            }
        }
        throw new AssertionError("Unreachable");
    }
    private static boolean retryable(IOException error) {
        if(error instanceof Failure f)return f.status==408||f.status==500||f.status==502||f.status==503||f.status==504;
        if(error instanceof SSLHandshakeException||error instanceof SSLPeerUnverifiedException)return false;
        if(error instanceof SocketTimeoutException)return true;
        if(error instanceof InterruptedIOException)return false;
        if(error instanceof SocketException||error instanceof EOFException)return true;
        String message=String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("connection closed")||message.contains("connection reset")||message.contains("unexpected end of stream")||message.contains("broken pipe");
    }
    private static long remaining(long deadline){return (deadline-System.nanoTime())/1_000_000L;}
    private static int timeout(long deadline,int maximum) throws SocketTimeoutException {
        long remaining=remaining(deadline);if(remaining<=0)throw new SocketTimeoutException("Request deadline exceeded");return (int)Math.max(1,Math.min(maximum,remaining));
    }
    private static String once(URL url,Policy policy,long deadline,boolean retry) throws IOException {
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        connection.setConnectTimeout(timeout(deadline,policy.connectMs()));connection.setReadTimeout(timeout(deadline,policy.readMs()));
        connection.setRequestProperty("User-Agent","ObsFit/0.1.9 (Android; personal Obsidian nutrition tracker)");
        connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Accept-Encoding","identity");
        // Do not retain a failed connection for later requests.
        if(retry)connection.setRequestProperty("Connection","close");
        try {
            connection.connect();connection.setReadTimeout(timeout(deadline,policy.readMs()));
            int status=connection.getResponseCode();if(status!=200)throw new Failure(status);
            long expected=connection.getContentLengthLong();if(expected>MAX_BODY)throw new IOException("Response is too large");
            try(InputStream in=connection.getInputStream()) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                while(true) {
                    connection.setReadTimeout(timeout(deadline,policy.readMs()));count=in.read(buffer);if(count<0)break;
                    if(bytes.size()+count>MAX_BODY)throw new IOException("Response is too large");bytes.write(buffer,0,count);
                }
                if(expected>=0&&expected!=bytes.size())throw new EOFException("Connection closed before the response was received");
                return bytes.toString(StandardCharsets.UTF_8.name());
            }
        }finally{connection.disconnect();}
    }
}
