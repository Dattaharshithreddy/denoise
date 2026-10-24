package com.harshith.hush;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Insets;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts the Hush denoiser page. Noise removal runs inside the WebView. This activity adds what a
 * web page can't do on Android: the system file picker, "Open with / Share to Hush", decoding of
 * formats the WebView can't read, AAC / Opus / video encoding, and saving into Downloads/Hush.
 */
public class MainActivity extends Activity {

    private static final int PICK_AUDIO = 41;
    private static final String HOST = "appassets.androidplatform.net";
    private static final int OUT_RATE_HINT = 48000;

    private WebView web;
    private ValueCallback<Uri[]> pendingPick;
    private volatile Uri lastSource;
    private String pendingOpenName;
    private boolean pageReady;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bg = night ? 0xFF0E1417 : 0xFFEDF1F2;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        web = new WebView(this);
        web.setBackgroundColor(bg);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | (night ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            }
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                deliverPendingOpen();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingPick != null) pendingPick.onReceiveValue(null);
                pendingPick = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"audio/*", "video/*", "application/ogg", "application/octet-stream"});
                try {
                    startActivityForResult(i, PICK_AUDIO);
                } catch (ActivityNotFoundException e) {
                    pendingPick = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "HushNative");
        web.loadUrl("https://" + HOST + "/assets/index.html");
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    /** "Open with Hush" and "Share to Hush" from other apps. */
    @SuppressWarnings("deprecation")
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) u = intent.getData();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) return;
        lastSource = u;
        pendingOpenName = displayName(u);
        deliverPendingOpen();
    }

    private void deliverPendingOpen() {
        if (!pageReady || pendingOpenName == null) return;
        String name = pendingOpenName;
        pendingOpenName = null;
        web.evaluateJavascript("window.hushOpenNative && window.hushOpenNative("
                + JSONObject.quote(name) + ")", null);
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
        }
        String seg = u.getLastPathSegment();
        return seg != null ? seg : "Shared audio";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_AUDIO || pendingPick == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            lastSource = data.getData();
            result = new Uri[]{data.getData()};
        }
        pendingPick.onReceiveValue(result);
        pendingPick = null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (web != null) {
            web.removeJavascriptInterface("HushNative");
            web.destroy();
        }
        super.onDestroy();
    }

    /* ------------------------------------------------------------------ */

    private void post(String id, JSONObject msg) {
        try {
            msg.put("id", id);
        } catch (Exception ignored) {
        }
        final String js = "window.__hushNative && window.__hushNative(" + JSONObject.quote(msg.toString()) + ")";
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    private void progress(String id, double p) {
        try {
            post(id, new JSONObject().put("type", "progress").put("p", p));
        } catch (Exception ignored) {
        }
    }

    private void fail(String id, Exception e) {
        try {
            String m = e.getMessage();
            post(id, new JSONObject().put("type", "error").put("message", m == null ? e.toString() : m));
        } catch (Exception ignored) {
        }
    }

    private File cache(String name) {
        return new File(getCacheDir(), name);
    }

    /** A file being written into Downloads/Hush through MediaStore. */
    private final class Download {
        final Uri uri;
        final ParcelFileDescriptor pfd;
        final String path;

        Download(String name, String mime) throws IOException {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Hush");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Couldn't create the file in Downloads.");
            ParcelFileDescriptor p = getContentResolver().openFileDescriptor(uri, "rw");
            if (p == null) throw new IOException("Couldn't open the file for writing.");
            pfd = p;
            path = "Downloads/Hush/" + savedName(uri, name);
        }

        void commit() throws IOException {
            pfd.close();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, v, null, null);
        }

        void discard() {
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
            try {
                getContentResolver().delete(uri, null, null);
            } catch (Exception ignored) {
            }
        }
    }

    private String savedName(Uri uri, String fallback) {
        try (Cursor c = getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /* ---------------- encoding cleaned PCM with MediaCodec ---------------- */

    private interface Sink {
        void format(MediaFormat f) throws IOException;

        void sample(ByteBuffer data, MediaCodec.BufferInfo info) throws IOException;
    }

    private int pcmRate = OUT_RATE_HINT, pcmChannels = 2;

    /** Encodes cache/export.pcm (16-bit interleaved) to AAC or Opus. Progress covers [p0, p1]. */
    private void encodePcm(String id, boolean aac, Sink sink, double p0, double p1) throws Exception {
        String mime = aac ? MediaFormat.MIMETYPE_AUDIO_AAC : MediaFormat.MIMETYPE_AUDIO_OPUS;
        int ch = Math.min(2, pcmChannels);
        MediaFormat f = MediaFormat.createAudioFormat(mime, pcmRate, ch);
        f.setInteger(MediaFormat.KEY_BIT_RATE, aac ? (ch > 1 ? 256000 : 160000) : (ch > 1 ? 160000 : 96000));
        if (aac) f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
        MediaCodec enc = MediaCodec.createEncoderByType(mime);
        File pcm = cache("export.pcm");
        long total = Math.max(1, pcm.length());
        int inFrame = 2 * pcmChannels, outFrame = 2 * ch;
        try (InputStream in = new BufferedInputStream(new FileInputStream(pcm), 1 << 17)) {
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] raw = new byte[1 << 16];
            byte[] packed = new byte[1 << 16];
            long framesIn = 0, readBytes = 0;
            boolean inDone = false, outDone = false;
            int lastPct = -1;
            while (!outDone) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                if (!inDone) {
                    int ii = enc.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = enc.getInputBuffer(ii);
                        int capFrames = Math.min(ib.capacity() / outFrame, raw.length / inFrame);
                        int got = readFully(in, raw, capFrames * inFrame);
                        long pts = framesIn * 1000000L / pcmRate;
                        if (got <= 0) {
                            enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            int frames = got / inFrame;
                            int len;
                            if (ch == pcmChannels) {
                                len = frames * inFrame;
                                System.arraycopy(raw, 0, packed, 0, len);
                            } else { // keep the first two channels
                                for (int fI = 0; fI < frames; fI++)
                                    System.arraycopy(raw, fI * inFrame, packed, fI * outFrame, outFrame);
                                len = frames * outFrame;
                            }
                            ib.clear();
                            ib.put(packed, 0, len);
                            enc.queueInputBuffer(ii, 0, len, pts, 0);
                            framesIn += frames;
                            readBytes += got;
                            int pct = (int) (readBytes * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                progress(id, p0 + (p1 - p0) * pct / 100.0);
                            }
                        }
                    }
                }
                int oi = enc.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sink.format(enc.getOutputFormat());
                } else if (oi >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(oi);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && ob != null) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        sink.sample(ob, info);
                    }
                    enc.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
        } finally {
            try {
                enc.stop();
            } catch (Exception ignored) {
            }
            enc.release();
        }
    }

    private static int readFully(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(b, off, len - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    private String exportAudio(String id, boolean aac, String base) throws Exception {
        String name = base + (aac ? ".m4a" : ".ogg");
        Download d = new Download(name, aac ? "audio/mp4" : "audio/ogg");
        final MediaMuxer mux = new MediaMuxer(d.pfd.getFileDescriptor(), aac
                ? MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 : MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);
        final int[] track = {-1};
        boolean ok = false;
        try {
            encodePcm(id, aac, new Sink() {
                public void format(MediaFormat f) {
                    track[0] = mux.addTrack(f);
                    mux.start();
                }

                public void sample(ByteBuffer data, MediaCodec.BufferInfo info) {
                    if (track[0] >= 0) mux.writeSampleData(track[0], data, info);
                }
            }, 0, 1);
            if (track[0] < 0) throw new IOException("The encoder produced no audio.");
            mux.stop();
            ok = true;
        } finally {
            mux.release();
            if (ok) d.commit();
            else d.discard();
        }
        return d.path;
    }

    /** Original video track + cleaned audio, interleaved by timestamp. */
    private String exportVideo(String id, String base) throws Exception {
        Uri src = lastSource;
        if (src == null) throw new IOException("The original video isn't available any more. Open it again.");
        MediaExtractor ex = new MediaExtractor();
        File aFile = cache("audio.enc");
        try {
            ex.setDataSource(this, src, null);
            int vt = -1;
            MediaFormat vf = null;
            for (int t = 0; t < ex.getTrackCount(); t++) {
                MediaFormat tf = ex.getTrackFormat(t);
                String m = tf.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) {
                    vt = t;
                    vf = tf;
                    break;
                }
            }
            if (vt < 0) throw new IOException("This file has no video track.");
            String vmime = vf.getString(MediaFormat.KEY_MIME);
            boolean webm = MediaFormat.MIMETYPE_VIDEO_VP8.equals(vmime) || MediaFormat.MIMETYPE_VIDEO_VP9.equals(vmime);

            // 1. Encode audio to a temp file, remembering sample sizes and timestamps.
            final ArrayList<long[]> index = new ArrayList<>(); // {size, ptsUs, flags}
            final MediaFormat[] af = {null};
            try (final OutputStream aout = new BufferedOutputStream(new FileOutputStream(aFile), 1 << 17)) {
                final byte[] tmp = new byte[1 << 16];
                encodePcm(id, !webm, new Sink() {
                    public void format(MediaFormat f) {
                        af[0] = f;
                    }

                    public void sample(ByteBuffer data, MediaCodec.BufferInfo info) throws IOException {
                        byte[] b = info.size <= tmp.length ? tmp : new byte[info.size];
                        data.get(b, 0, info.size);
                        aout.write(b, 0, info.size);
                        index.add(new long[]{info.size, info.presentationTimeUs, info.flags});
                    }
                }, 0, 0.75);
            }
            if (af[0] == null) throw new IOException("The encoder produced no audio.");

            // 2. Mux.
            String name = base + (webm ? ".webm" : ".mp4");
            Download d = new Download(name, webm ? "video/webm" : "video/mp4");
            MediaMuxer mux = new MediaMuxer(d.pfd.getFileDescriptor(), webm
                    ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            boolean ok = false;
            try (RandomAccessFile ain = new RandomAccessFile(aFile, "r")) {
                if (vf.containsKey(MediaFormat.KEY_ROTATION)) mux.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION));
                int vTrack = mux.addTrack(vf);
                int aTrack = mux.addTrack(af[0]);
                mux.start();
                ex.selectTrack(vt);
                int vcap = vf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? Math.max(1 << 20, vf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) : 8 << 20;
                ByteBuffer vb = ByteBuffer.allocateDirect(vcap);
                ByteBuffer ab = ByteBuffer.allocateDirect(1 << 16);
                byte[] abytes = new byte[1 << 16];
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                long durUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : 0;
                int ai = 0;
                boolean vDone = false;
                int lastPct = -1;
                while (!vDone || ai < index.size()) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                    long vPts = Long.MAX_VALUE;
                    int vSize = -1;
                    if (!vDone) {
                        vb.clear();
                        vSize = ex.readSampleData(vb, 0);
                        if (vSize < 0) vDone = true;
                        else vPts = ex.getSampleTime();
                    }
                    while (ai < index.size() && index.get(ai)[1] <= vPts) {
                        long[] e = index.get(ai++);
                        int sz = (int) e[0];
                        if (sz > abytes.length) {
                            abytes = new byte[sz];
                            ab = ByteBuffer.allocateDirect(sz);
                        }
                        ain.readFully(abytes, 0, sz);
                        ab.clear();
                        ab.put(abytes, 0, sz);
                        ab.flip();
                        info.set(0, sz, e[1], (int) e[2]);
                        mux.writeSampleData(aTrack, ab, info);
                    }
                    if (!vDone) {
                        int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        info.set(0, vSize, Math.max(0, vPts), flags);
                        mux.writeSampleData(vTrack, vb, info);
                        ex.advance();
                        if (durUs > 0) {
                            int pct = (int) Math.min(100, vPts * 100 / durUs);
                            if (pct != lastPct) {
                                lastPct = pct;
                                progress(id, 0.75 + 0.25 * pct / 100.0);
                            }
                        }
                    }
                }
                mux.stop();
                ok = true;
            } finally {
                mux.release();
                if (ok) d.commit();
                else d.discard();
            }
            return d.path;
        } finally {
            ex.release();
            //noinspection ResultOfMethodCallIgnored
            aFile.delete();
        }
    }

    /* ---------------- decoding the original with MediaCodec ---------------- */

    private JSONObject decodeSource(String id) throws Exception {
        Uri src = lastSource;
        if (src == null) throw new IOException("No file to read.");
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(this, src, null);
            int at = -1;
            MediaFormat f = null;
            for (int t = 0; t < ex.getTrackCount(); t++) {
                MediaFormat tf = ex.getTrackFormat(t);
                String m = tf.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    at = t;
                    f = tf;
                    break;
                }
            }
            if (at < 0) throw new IOException("This file has no audio track.");
            ex.selectTrack(at);
            long durUs = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
            int rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int enc = AudioFormat.ENCODING_PCM_16BIT;
            dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
            dec.configure(f, null, null, 0);
            dec.start();
            File outF = cache("decoded.pcm");
            long bytes = 0;
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outF), 1 << 17)) {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inDone = false, outDone = false;
                byte[] tmp = new byte[1 << 16];
                int lastPct = -1;
                while (!outDone) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                    if (!inDone) {
                        int ii = dec.dequeueInputBuffer(10000);
                        if (ii >= 0) {
                            ByteBuffer ib = dec.getInputBuffer(ii);
                            int sz = ex.readSampleData(ib, 0);
                            if (sz < 0) {
                                dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inDone = true;
                            } else {
                                dec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                                ex.advance();
                            }
                        }
                    }
                    int oi = dec.dequeueOutputBuffer(info, 10000);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = dec.getOutputFormat();
                        if (bytes == 0) {
                            if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        }
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    } else if (oi >= 0) {
                        ByteBuffer ob = dec.getOutputBuffer(oi);
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            ob.order(ByteOrder.LITTLE_ENDIAN);
                            if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
                                int n = info.size / 4;
                                if (tmp.length < n * 2) tmp = new byte[n * 2];
                                for (int i = 0; i < n; i++) {
                                    float v = ob.getFloat();
                                    int s = Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
                                    tmp[2 * i] = (byte) s;
                                    tmp[2 * i + 1] = (byte) (s >> 8);
                                }
                                out.write(tmp, 0, n * 2);
                                bytes += n * 2L;
                            } else {
                                if (tmp.length < info.size) tmp = new byte[info.size];
                                ob.get(tmp, 0, info.size);
                                out.write(tmp, 0, info.size);
                                bytes += info.size;
                            }
                            if (durUs > 0) {
                                int pct = (int) Math.min(100, info.presentationTimeUs * 100 / durUs);
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    progress(id, pct / 100.0);
                                }
                            }
                        }
                        dec.releaseOutputBuffer(oi, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    }
                }
            }
            if (bytes == 0) throw new IOException("No audio could be decoded from this file.");
            return new JSONObject().put("type", "done").put("sr", rate).put("ch", ch)
                    .put("frames", bytes / (2L * ch)).put("bytes", bytes);
        } finally {
            if (dec != null) {
                try {
                    dec.stop();
                } catch (Exception ignored) {
                }
                dec.release();
            }
            ex.release();
        }
    }

    /* ---------------- bridge ---------------- */

    private class Bridge {
        private OutputStream out;
        private Download saving;
        private OutputStream pcmOut;

        /** Start saving an already-encoded file (WAV, FLAC, MP3, AIFF) to Downloads/Hush. */
        @JavascriptInterface
        public synchronized String begin(String filename, String mime) {
            abort();
            try {
                saving = new Download(filename.replaceAll("[\\\\/:*?\"<>|]+", "_"), mime);
                out = new BufferedOutputStream(new FileOutputStream(saving.pfd.getFileDescriptor()), 1 << 20);
                return "";
            } catch (Exception e) {
                abort();
                return "Couldn't save: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized String write(String base64) {
            if (out == null) return "Save wasn't started.";
            try {
                out.write(Base64.decode(base64, Base64.NO_WRAP));
                return "";
            } catch (Exception e) {
                return "Write failed: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized String finish() {
            if (out == null || saving == null) return "ERR:Save wasn't started.";
            try {
                out.flush();
                saving.pfd.getFileDescriptor().sync();
                out = null;
                saving.commit();
                String p = saving.path;
                saving = null;
                return p;
            } catch (Exception e) {
                abort();
                return "ERR:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized void abort() {
            out = null;
            if (saving != null) {
                saving.discard();
                saving = null;
            }
        }

        /** Tracks in the last opened file: {"hasVideo":true,"videoMime":"video/avc"} */
        @JavascriptInterface
        public String sourceInfo() {
            JSONObject o = new JSONObject();
            Uri src = lastSource;
            if (src == null) return "{}";
            MediaExtractor ex = new MediaExtractor();
            try {
                ex.setDataSource(MainActivity.this, src, null);
                for (int t = 0; t < ex.getTrackCount(); t++) {
                    String m = ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME);
                    if (m == null) continue;
                    if (m.startsWith("video/") && !o.has("videoMime")) o.put("hasVideo", true).put("videoMime", m);
                    if (m.startsWith("audio/") && !o.has("audioMime")) o.put("audioMime", m);
                }
            } catch (Exception ignored) {
            } finally {
                ex.release();
            }
            return o.toString();
        }

        /** Cleaned PCM for the native encoders: 16-bit little-endian interleaved. */
        @JavascriptInterface
        public synchronized String pcmBegin(int sampleRate, int channels) {
            try {
                if (pcmOut != null) pcmOut.close();
                pcmRate = sampleRate;
                pcmChannels = channels;
                pcmOut = new BufferedOutputStream(new FileOutputStream(cache("export.pcm")), 1 << 20);
                return "";
            } catch (Exception e) {
                return "Couldn't prepare audio: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized String pcmWrite(String base64) {
            if (pcmOut == null) return "Audio transfer wasn't started.";
            try {
                pcmOut.write(Base64.decode(base64, Base64.NO_WRAP));
                return "";
            } catch (Exception e) {
                return "Write failed: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized String pcmEnd() {
            try {
                if (pcmOut != null) pcmOut.close();
                pcmOut = null;
                return "";
            } catch (Exception e) {
                return "Write failed: " + e.getMessage();
            }
        }

        /** format: "m4a", "ogg" or "video". Runs in the background; replies through __hushNative. */
        @JavascriptInterface
        public String exportMedia(final String id, final String format, final String baseName) {
            final String base = baseName.replaceAll("[\\\\/:*?\"<>|]+", "_");
            worker.submit(() -> {
                try {
                    String path = "video".equals(format) ? exportVideo(id, base) : exportAudio(id, "m4a".equals(format), base);
                    post(id, new JSONObject().put("type", "done").put("path", path));
                } catch (Exception e) {
                    fail(id, e);
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    cache("export.pcm").delete();
                }
            });
            return "";
        }

        /** Decode the last opened file with Android's own codecs (AMR, 3GP, MKV and others). */
        @JavascriptInterface
        public String decodeSource(final String id) {
            worker.submit(() -> {
                try {
                    post(id, MainActivity.this.decodeSource(id));
                } catch (Exception e) {
                    fail(id, e);
                }
            });
            return "";
        }

        /** Read part of the decoded PCM as base64. */
        @JavascriptInterface
        public String readDecoded(double offset, int length) {
            try (RandomAccessFile r = new RandomAccessFile(cache("decoded.pcm"), "r")) {
                long off = (long) offset;
                int n = (int) Math.max(0, Math.min(length, r.length() - off));
                byte[] b = new byte[n];
                r.seek(off);
                r.readFully(b);
                return Base64.encodeToString(b, Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void releaseDecoded() {
            //noinspection ResultOfMethodCallIgnored
            cache("decoded.pcm").delete();
        }
    }
}
