package com.tiksaver.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int PERM_WRITE = 41;
    private static final String[] API_HOSTS = {
            "api16-normal-c-useast1a.tiktokv.com",
            "api22-normal-c-useast2a.tiktokv.com",
            "api16-normal-c-alisg.tiktokv.com"
    };
    private static final String UA_BROWSER =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final String UA_OKHTTP = "okhttp/3.10.0.1";

    private EditText urlInput;
    private Button downloadBtn;
    private ProgressBar progress;
    private TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Thread workThread;
    private String pendingUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleSharedText(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleSharedText(intent);
    }

    private void handleSharedText(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();
        if (Intent.ACTION_SEND.equals(action) && type != null && "text/plain".equals(type)) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null && !shared.trim().isEmpty()) {
                urlInput.setText(shared.trim());
                startDownload(shared.trim());
            }
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(24));
        root.setBackgroundColor(Color.parseColor("#101014"));

        TextView title = new TextView(this);
        title.setText("TikSaver");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);

        TextView subtitle = new TextView(this);
        subtitle.setText("تحميل فيديوهات تيك توك بدون علامة مائية وبجودة عالية");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.parseColor("#9AA0A6"));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(6), 0, dp(20));

        urlInput = new EditText(this);
        urlInput.setHint("الصق رابط الفيديو هنا (مثال: https://vm.tiktok.com/…)");
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(Color.parseColor("#6B7075"));
        urlInput.setBackground(null);
        urlInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout inputWrap = new LinearLayout(this);
        inputWrap.setOrientation(LinearLayout.VERTICAL);
        inputWrap.setBackgroundColor(Color.parseColor("#1E1F24"));
        inputWrap.setPadding(dp(2), dp(2), dp(2), dp(2));
        inputWrap.addView(urlInput);

        downloadBtn = new Button(this);
        downloadBtn.setText("تحميل");
        downloadBtn.setTextSize(16);
        downloadBtn.setTypeface(Typeface.DEFAULT_BOLD);
        downloadBtn.setTextColor(Color.BLACK);
        downloadBtn.setBackgroundColor(Color.parseColor("#25F4EE"));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        bp.topMargin = dp(12);
        downloadBtn.setLayoutParams(bp);
        downloadBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownload(urlInput.getText().toString());
            }
        });

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.getProgressDrawable().setColorFilter(0xFF25F4EE, android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(5));
        pp.topMargin = dp(14);
        progress.setLayoutParams(pp);
        progress.setMax(100);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#9AA0A6"));
        status.setTextSize(14);
        status.setPadding(0, dp(16), 0, 0);
        status.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

        TextView hint = new TextView(this);
        hint.setText("ملاحظة: افتح الفيديو في تيك توك ثم اضغط مشاركة ← نسخ الرابط، وافتح هذا التطبيق. "
                + "الفيديو يُحفظ في مجلد Movies/TikSaver داخل معرض الصور.");
        hint.setTextSize(12);
        hint.setTextColor(Color.parseColor("#5F6368"));
        hint.setPadding(0, dp(24), 0, 0);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.addView(title);
        inner.addView(subtitle);
        inner.addView(inputWrap);
        inner.addView(downloadBtn);
        inner.addView(progress);
        inner.addView(status);
        inner.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(inner);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void startDownload(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            toast("الرجاء لصق رابط الفيديو أولاً");
            return;
        }
        if (workThread != null && workThread.isAlive()) {
            toast("هناك تحميل جارٍ بالفعل");
            return;
        }
        if (needsWritePermission()) {
            pendingUrl = rawUrl.trim();
            requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERM_WRITE);
            return;
        }
        runDownload(rawUrl.trim());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_WRITE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                runDownload(pendingUrl);
            } else {
                runDownload(pendingUrl);
            }
        }
    }

    private boolean needsWritePermission() {
        return Build.VERSION.SDK_INT < 29 && checkSelfPermission(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void runDownload(String videoUrl) {
        setBusy(true);
        setStatus("جارٍ إيجاد الفيديو…", false);
        workThread = new Thread(() -> {
            try {
                Result r = grab(videoUrl, new ProgressSink() {
                    @Override
                    public void on(int percent, long bytes, long total) {
                        onProgress(percent, bytes, total);
                    }
                });
                String saved = saveVideo(r.bytes, r.name);
                main.post(() -> {
                    setBusy(false);
                    progress.setProgress(100);
                    setStatus("تم الحفظ ✅\n" + saved, false);
                    toast("تم الحفظ في المعرض: " + r.name);
                });
            } catch (final Exception e) {
                main.post(() -> {
                    setBusy(false);
                    progress.setProgress(0);
                    setStatus("فشل التحميل: " + e.getMessage(), true);
                });
            }
        }, "tiksaver-download");
        workThread.start();
    }

    private void onProgress(final int percent, final long bytes, final long total) {
        main.post(() -> {
            if (total > 0) {
                progress.setProgress(percent);
                status(String.format(Locale.US, "جارٍ التحميل… %d%% (%.1f MB من %.1f MB)",
                        percent, bytes / 1048576f, total / 1048576f), false);
            } else {
                status(String.format(Locale.US, "جارٍ التحميل… %.1f MB", bytes / 1048576f), false);
            }
        });
    }

    private interface ProgressSink {
        void on(int percent, long bytes, long total);
    }

    private static class Result {
        byte[] bytes;
        String name;
    }

    static Result grab(String inputUrl, ProgressSink sink) throws Exception {
        String link = extractUrl(inputUrl);
        String videoId = null;
        String fullUrl = link;

        Matcher direct = Pattern.compile("/video/(\\d+)").matcher(link);
        if (!direct.find()) {
            fullUrl = resolveRedirects(link);
            direct = Pattern.compile("/video/(\\d+)").matcher(fullUrl);
        }
        if (direct.find()) videoId = direct.group(1);

        Exception last = null;

        if (videoId != null) {
            for (String host : API_HOSTS) {
                try {
                    String meta = httpGet("https://" + host + "/aweme/v1/feed/?aweme_id=" + videoId
                            + "&version_code=300904&app_name=musical_ly&channel=googleplay"
                            + "&device_platform=android&device_type=Pixel%207&os_version=13&aid=1180", UA_OKHTTP);
                    JSONObject o = new JSONObject(meta);
                    JSONArray list = o.optJSONArray("aweme_list");
                    if (list != null && list.length() > 0) {
                        JSONObject aw = list.getJSONObject(0);
                        String title = aw.optString("desc", "");
                        String author = "";
                        JSONObject au = aw.optJSONObject("author");
                        if (au != null) author = au.optString("nickname", "");
                        String pick = highestBitrateUrl(aw);
                        if (pick != null && !pick.isEmpty()) {
                            Result r = new Result();
                            r.bytes = downloadBytes(pick, UA_OKHTTP, sink);
                            r.name = safeName("TikTok_" + (author.isEmpty() ? "" : author + "_") + title, videoId);
                            return r;
                        }
                    }
                } catch (Exception e) {
                    last = e;
                }
            }
        }

        try {
            String api = "https://www.tikwm.com/api/?url=" + java.net.URLEncoder.encode(fullUrl, "UTF-8") + "&hd=1";
            String body = httpGet(api, UA_BROWSER);
            JSONObject o = new JSONObject(body);
            if (o.optInt("code", -1) == 0 && o.optJSONObject("data") != null) {
                JSONObject d = o.getJSONObject("data");
                String pick = d.optString("hdplay", "");
                if (pick.isEmpty()) pick = d.optString("play", "");
                if (pick.startsWith("/")) pick = "https://www.tikwm.com" + pick;
                if (!pick.isEmpty()) {
                    Result r = new Result();
                    r.bytes = downloadBytes(pick, UA_BROWSER, sink);
                    String id = d.optString("id", String.valueOf(System.currentTimeMillis()));
                    r.name = safeName(d.optString("title", ""), id);
                    return r;
                }
            }
        } catch (Exception e) {
            last = e;
        }

        try {
            String html = httpGet(fullUrl, UA_BROWSER);
            String pick = scrapePlayAddr(html);
            if (pick != null) {
                String id = videoId != null ? videoId : String.valueOf(System.currentTimeMillis());
                Result r = new Result();
                r.bytes = downloadBytes(pick, UA_BROWSER, sink);
                Matcher dm = Pattern.compile("/video/(\\d+)").matcher(fullUrl);
                r.name = safeName("", dm.find() ? dm.group(1) : id);
                return r;
            }
        } catch (Exception e) {
            last = e;
        }

        throw last != null ? last : new Exception("تعذر استخراج الفيديو، تأكد من صحة الرابط");
    }

    private static String scrapePlayAddr(String html) {
        String q = String.valueOf((char) 34);
        Pattern p = Pattern.compile("playAddr" + q + ":" + q + "([^" + q + "]+)");
        Matcher m = p.matcher(html);
        if (!m.find()) return null;
        String u = m.group(1)
                .replace(String.valueOf((char) 92) + "/", "/")
                .replace(String.valueOf((char) 92) + "u0026", "&");
        return u.startsWith("http") ? u : null;
    }

    private static String highestBitrateUrl(JSONObject aweme) {
        try {
            JSONArray rates = aweme.getJSONObject("video").optJSONArray("bit_rate");
            if (rates == null || rates.length() == 0) return null;
            long best = -1;
            String bestUrl = null;
            for (int i = 0; i < rates.length(); i++) {
                JSONObject br = rates.getJSONObject(i);
                long bit = br.optLong("bit_rate", 0);
                String u = firstUrl(br.getJSONObject("play_addr"));
                if (bit > best && u != null) {
                    best = bit;
                    bestUrl = u;
                }
            }
            return bestUrl;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstUrl(JSONObject parent, String key1, String key2) {
        try {
            return firstUrl(parent.getJSONObject(key1).getJSONObject(key2));
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstUrl(JSONObject addr) {
        JSONArray urls = addr != null ? addr.optJSONArray("url_list") : null;
        if (urls == null || urls.length() == 0) return null;
        String u = urls.optString(0, "");
        if (u.startsWith("http")) return u;
        return "https://api16-normal-c-useast1a.tiktokv.com/aweme/v1/play/?uri=" + addr.optString("uri", "");
    }

    private static String extractUrl(String text) {
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        if (m.find()) return m.group(1).replaceAll("[\"'\\ ]$", "");
        return text.trim();
    }

    private static String resolveRedirects(String url) throws Exception {
        String current = url;
        for (int i = 0; i < 8; i++) {
            URL u = new URL(current);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA_BROWSER);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                if (loc == null || loc.isEmpty()) break;
                if (loc.startsWith("/")) loc = "https://" + u.getHost() + loc;
                current = URLDecoder.decode(loc, "UTF-8");
                c.disconnect();
            } else if (code == 200) {
                String finalUrl = c.getURL().toString();
                c.disconnect();
                return finalUrl;
            } else {
                c.disconnect();
                break;
            }
        }
        return current;
    }

    private static String httpGet(String url, String ua) throws Exception {
        HttpURLConnection c = open(url, ua);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        return sb.toString();
    }

    private static HttpURLConnection open(String url, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", ua);
        return c;
    }

    private static byte[] downloadBytes(String url, String ua, ProgressSink sink) throws Exception {
        HttpURLConnection c = open(url, ua);
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("تعذر تنزيل الفيديو (HTTP " + code + ")");
        long total = c.getContentLengthLong();
        InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long read = 0;
        int n;
        long lastReport = 0;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            read += n;
            if (sink != null && read - lastReport > 256 * 1024) {
                lastReport = read;
                sink.on(total > 0 ? (int) (read * 100 / total) : -1, read, total);
            }
        }
        in.close();
        c.disconnect();
        if (out.size() < 1024) throw new Exception("الملف المستلم فارغ");
        return out.toByteArray();
    }

    private String saveVideo(byte[] data, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/TikSaver");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("تعذر إنشاء الملف");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                os.write(data);
            }
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);
            return "Movies/TikSaver/" + name;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), "TikSaver");
            if (!dir.exists()) dir.mkdirs();
            writeUnique(new File(dir, name), data);
            return "Movies/TikSaver/" + name;
        }
    }

    private static void writeUnique(File target, byte[] data) throws Exception {
        File f = target;
        String base = target.getName().replaceFirst("\\.mp4$", "");
        int i = 1;
        while (f.exists()) f = new File(target.getParentFile(), base + "_" + (i++) + ".mp4");
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(data);
        }
    }

    private static String safeName(String title, String id) {
        String t = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\n\\r]", " ").trim();
        if (t.length() > 40) t = t.substring(0, 40).trim();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder();
        if (!t.isEmpty()) sb.append(t).append("_");
        sb.append(id).append("_").append(stamp).append(".mp4");
        return sb.toString();
    }

    private void setBusy(boolean busy) {
        downloadBtn.setEnabled(!busy);
        downloadBtn.setText(busy ? "جارٍ التحميل…" : "تحميل");
        urlInput.setEnabled(!busy);
    }

    private void setStatus(String text, boolean error) {
        status(text, error);
    }

    private void status(String text, boolean error) {
        status.setText(text);
        status.setTextColor(error ? Color.parseColor("#FF5C5C") : Color.parseColor("#9AA0A6"));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
