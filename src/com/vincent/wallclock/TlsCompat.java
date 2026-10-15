package com.vincent.wallclock;

import android.os.Build;
import android.util.Base64;

import com.vincent.wallclock.gen.TlsAnchors;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 老安卓的 HTTPS 兼容层。挂钟要能跑在 Android 4.4 的老机器上，这里有两个必须解决的问题：
 *
 * <h3>问题一：TLS 1.2 默认没开</h3>
 * API 19（Android 4.4）的 {@code SSLContext} 默认协议是 TLSv1，服务端早就拒了。
 * 需要把 TLSv1.2 显式打开；而且光指定协议还不够，4.4 上 TLS 1.2 能用的加密套件
 * 默认也是关着的，得一并打开。
 *
 * <h3>问题二：系统信任库里没有 Let's Encrypt 的新根</h3>
 * Open-Meteo 的证书链是：
 * <pre>
 *   open-meteo.com  &lt;-  YR2  &lt;-  Root YR  &lt;-  ISRG Root X1
 * </pre>
 * {@code ISRG Root X1} 直到 Android 7.1.1（API 25）才进系统信任库。所以
 * <b>API 19 到 24 全都会在证书校验这一步失败</b>——只修 TLS 1.2 是没用的，
 * 必须把这张链上的证书随包带上（见 {@link TlsAnchors}）。
 *
 * <h3>做法</h3>
 * 自定义一个 TrustManager，把「系统默认信任库」和「随包内置锚」并列：
 * 先用系统默认校验，抛异常再用内置锚校验，任一通过即放行。
 * 这样既补上了缺的根，又不会削弱对其它站点的校验强度（内置锚里没有
 * 任何弱 CA，只有 Let's Encrypt 新层级的三张证书）。
 *
 * <p>API 25 及以上不需要任何干预，直接走系统默认。
 */
public final class TlsCompat {

    /** Android 7.1.1 起系统信任库才含 ISRG Root X1 */
    private static final int SDK_HAS_ISRG_ROOT_X1 = 25;
    /** Android 5.0 起 TLS 1.2 默认开启 */
    private static final int SDK_TLS12_DEFAULT_ON = 20;

    private static SSLSocketFactory factory;
    private static boolean installed;
    private static String note = "尚未初始化";

    private TlsCompat() {
    }

    /**
     * 装一次即可，重复调用无副作用。建议在 Application 或首个 Activity 的
     * {@code onCreate} 里调用，晚于任何网络请求。
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        if (Build.VERSION.SDK_INT >= SDK_HAS_ISRG_ROOT_X1) {
            note = "系统信任库已含所需根证书，走系统默认 TLS（无需兼容处理）";
            return;
        }

        try {
            boolean forceTls12 = Build.VERSION.SDK_INT < SDK_TLS12_DEFAULT_ON;

            X509TrustManager defaults = defaultTrustManager();
            X509TrustManager anchors = anchorTrustManager();

            SSLContext ctx = newContext();
            ctx.init(null,
                    new TrustManager[]{new CompositeTrustManager(defaults, anchors)},
                    null);

            factory = new CompatSocketFactory(ctx.getSocketFactory(), forceTls12);
            HttpsURLConnection.setDefaultSSLSocketFactory(factory);

            StringBuilder sb = new StringBuilder();
            sb.append("已启用老系统兼容层（API ").append(Build.VERSION.SDK_INT).append("）");
            sb.append(forceTls12 ? "：强制 TLS 1.2" : "：TLS 1.2 由系统默认开启");
            sb.append("；并内置 ").append(TlsAnchors.CERTS.length).append(" 张 Let's Encrypt 新层级证书");
            note = sb.toString();
        } catch (Throwable t) {
            // 兼容层失败不该拖垮整个应用：退回系统默认，让上面显示真实错误
            factory = null;
            note = "老系统 TLS 兼容层初始化失败（" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()) + "），已退回系统默认";
        }
    }

    /** 给单个连接套上兼容 SocketFactory；未启用兼容层时是空操作 */
    public static void apply(HttpURLConnection conn) {
        SSLSocketFactory f = factory;
        if (f == null || !(conn instanceof HttpsURLConnection)) {
            return;
        }
        try {
            ((HttpsURLConnection) conn).setSSLSocketFactory(f);
        } catch (Throwable ignored) {
        }
    }

    /** 供设置页「关于」展示，说明当前设备上 TLS 走到了哪条路径 */
    public static String describe() {
        install();
        return note;
    }

    /** 当前是否处于兼容模式（true 表示被替换过 SocketFactory） */
    public static boolean isCompatActive() {
        return factory != null;
    }

    // ------------------------------------------------------------------ 内部实现

    private static SSLContext newContext() throws Exception {
        try {
            return SSLContext.getInstance("TLSv1.2");
        } catch (Throwable ignored) {
            // 个别 4.4 定制 ROM 没有 TLSv1.2 这个名字，退回 "TLS" 再靠 socket 层指定协议
            return SSLContext.getInstance("TLS");
        }
    }

    private static X509TrustManager defaultTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return pickX509(tmf.getTrustManagers());
    }

    /** 用随包内置的证书建一个 KeyStore，再包成 TrustManager */
    private static X509TrustManager anchorTrustManager() throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        for (int i = 0; i < TlsAnchors.CERTS.length; i++) {
            byte[] der = Base64.decode(TlsAnchors.CERTS[i], Base64.DEFAULT);
            Certificate cert = cf.generateCertificate(new ByteArrayInputStream(der));
            ks.setCertificateEntry("wallclock-anchor-" + i, cert);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return pickX509(tmf.getTrustManagers());
    }

    private static X509TrustManager pickX509(TrustManager[] managers) {
        if (managers != null) {
            for (TrustManager m : managers) {
                if (m instanceof X509TrustManager) {
                    return (X509TrustManager) m;
                }
            }
        }
        throw new IllegalStateException("找不到 X509TrustManager");
    }

    /**
     * 逐个尝试，任一通过即放行。
     * 第一个是系统默认校验（对绝大多数站点直接通过，不多绕一圈），
     * 第二个是内置锚（只为了补齐老系统缺失的 Let's Encrypt 新根）。
     */
    private static final class CompositeTrustManager implements X509TrustManager {

        private final X509TrustManager[] all;

        CompositeTrustManager(X509TrustManager... managers) {
            this.all = managers;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            tryEach(true, chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            tryEach(false, chain, authType);
        }

        private void tryEach(boolean client, X509Certificate[] chain, String authType)
                throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager m : all) {
                if (m == null) {
                    continue;
                }
                try {
                    if (client) {
                        m.checkClientTrusted(chain, authType);
                    } else {
                        m.checkServerTrusted(chain, authType);
                    }
                    return;
                } catch (CertificateException e) {
                    last = e;
                } catch (Throwable t) {
                    last = new CertificateException(t);
                }
            }
            throw last != null ? last : new CertificateException("没有可用的 TrustManager");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> out = new ArrayList<X509Certificate>();
            for (X509TrustManager m : all) {
                if (m == null) {
                    continue;
                }
                X509Certificate[] issuers = m.getAcceptedIssuers();
                if (issuers != null) {
                    for (X509Certificate c : issuers) {
                        out.add(c);
                    }
                }
            }
            return out.toArray(new X509Certificate[out.size()]);
        }
    }

    /**
     * 只做两件事：按需强制 TLS 1.2、按需打开 4.4 上默认关闭的强加密套件。
     * 加密套件只从「支持的」里筛掉 NULL / RC4 / DES / 3DES / EXPORT / 匿名，
     * 其余保留，避免为了兼容而把弱套件一起放回来。
     */
    private static final class CompatSocketFactory extends SSLSocketFactory {

        private final SSLSocketFactory base;
        private final boolean forceTls12;

        CompatSocketFactory(SSLSocketFactory base, boolean forceTls12) {
            this.base = base;
            this.forceTls12 = forceTls12;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return base.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return base.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose)
                throws java.io.IOException {
            return patch(base.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws java.io.IOException {
            return patch(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws java.io.IOException {
            return patch(base.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws java.io.IOException {
            return patch(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port,
                                  InetAddress localAddress, int localPort)
                throws java.io.IOException {
            return patch(base.createSocket(address, port, localAddress, localPort));
        }

        private Socket patch(Socket socket) {
            if (!(socket instanceof SSLSocket) || !forceTls12) {
                return socket;
            }
            SSLSocket ssl = (SSLSocket) socket;
            try {
                ssl.setEnabledProtocols(new String[]{"TLSv1.2"});
            } catch (Throwable ignored) {
            }
            try {
                List<String> strong = new ArrayList<String>();
                for (String suite : ssl.getSupportedCipherSuites()) {
                    String u = suite.toUpperCase(Locale.US);
                    if (u.contains("_NULL_") || u.contains("_RC4_") || u.contains("_3DES_")
                            || u.contains("_DES_") || u.contains("_EXPORT_")
                            || u.contains("_ANON_") || u.contains("_EMPTY_")) {
                        continue;
                    }
                    strong.add(suite);
                }
                if (!strong.isEmpty()) {
                    ssl.setEnabledCipherSuites(strong.toArray(new String[strong.size()]));
                }
            } catch (Throwable ignored) {
            }
            return socket;
        }
    }
}
