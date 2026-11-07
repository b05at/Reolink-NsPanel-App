package at.reolinkpanel.viewer;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Reolink-Kameras nutzen ein selbstsigniertes Zertifikat. Für die Verbindung
 * im eigenen LAN wird es daher akzeptiert – nur für Verbindungen zur Kamera.
 */
final class Trust {
    private static SSLSocketFactory factory;

    private static final HostnameVerifier ANY_HOST = new HostnameVerifier() {
        @Override public boolean verify(String hostname, SSLSession session) { return true; }
    };

    private Trust() {}

    static synchronized void apply(HttpsURLConnection con) {
        try {
            if (factory == null) {
                TrustManager[] tm = new TrustManager[]{ new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }};
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, tm, new SecureRandom());
                factory = ctx.getSocketFactory();
            }
            con.setSSLSocketFactory(factory);
            con.setHostnameVerifier(ANY_HOST);
        } catch (Exception ignored) {
        }
    }
}
