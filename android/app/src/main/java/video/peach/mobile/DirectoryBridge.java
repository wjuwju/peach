package video.peach.mobile;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import org.json.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** 原版后端的目录选择与 SAF 文件操作，只接受持有私有口令的回环请求。 */
final class DirectoryBridge implements AutoCloseable {
    private static volatile WeakReference<MainActivity> foreground = new WeakReference<>(null);
    private static DirectoryBridge current;
    private final Context context;
    private final ServerSocket server;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String secret;
    private CompletableFuture<JSONObject> pending;
    private String pendingId;
    private volatile boolean closed;
    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    static void attach(MainActivity activity) { foreground = new WeakReference<>(activity); }
    static void detach(MainActivity activity) { if (foreground.get() == activity) foreground.clear(); }

    DirectoryBridge(Context value) throws Exception {
        context = value.getApplicationContext();
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random); secret = hex(random);
        server = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
        File state = new File(RuntimeBundle.data(context), "state");
        if (!state.isDirectory() && !state.mkdirs()) throw new IOException("无法建立安卓目录服务状态目录");
        File temporary = new File(state, "android-bridge.json.tmp");
        Files.write(temporary.toPath(), new JSONObject().put("port", server.getLocalPort()).put("secret", secret).toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), new File(state, "android-bridge.json").toPath(), StandardCopyOption.REPLACE_EXISTING);
        synchronized (DirectoryBridge.class) { current = this; }
        workers.execute(() -> {
            while (!closed) try {
                Socket socket = server.accept(); workers.execute(() -> serve(socket));
            } catch (IOException | RejectedExecutionException error) { if (!closed) close(); }
        });
    }

    static String hex(byte[] data) {
        StringBuilder text = new StringBuilder();
        for (byte part : data) text.append(String.format(Locale.ROOT, "%02x", part & 255));
        return text.toString();
    }

    static void completeSelection(String identifier, JSONObject result) {
        DirectoryBridge bridge;
        synchronized (DirectoryBridge.class) { bridge = current; }
        if (bridge == null) return;
        synchronized (bridge) { if (bridge.pending != null && Objects.equals(identifier, bridge.pendingId)) bridge.pending.complete(result); }
    }

    static void cancelSelection(String identifier) {
        try { completeSelection(identifier, new JSONObject().put("cancelled", true)); }
        catch (JSONException ignored) { }
    }

    static JSONObject documentSelection(Context context, Uri tree, int flags) throws Exception {
        int granted = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((granted & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new SecurityException("未取得目录读取权限");
        context.getContentResolver().takePersistableUriPermission(tree, granted);
        String identifier = hex(MessageDigest.getInstance("SHA-256").digest(tree.toString().getBytes(StandardCharsets.UTF_8)));
        if (!context.getSharedPreferences("media-trees", Context.MODE_PRIVATE).edit().putString(identifier, tree.toString()).commit())
            throw new IOException("无法保存目录授权");
        return new JSONObject().put("kind", "document").put("id", identifier);
    }

    private JSONObject pick(String initial) throws Exception {
        final CompletableFuture<JSONObject> result = new CompletableFuture<>();
        final String identifier = UUID.randomUUID().toString();
        synchronized (this) {
            if (pending != null) throw new IOException("已经有一个选择文件夹的窗口开着");
            pending = result;
            pendingId = identifier;
        }
        main.post(() -> {
            MainActivity activity = foreground.get();
            if (activity == null || activity.isDestroyed() || activity.isFinishing())
                result.completeExceptionally(new IOException("请返回 Peach 页面后选择目录"));
            else activity.pickMediaFolder(initial, identifier);
        });
        try { return result.get(15, TimeUnit.MINUTES); }
        catch (TimeoutException error) {
            main.post(() -> { MainActivity activity = foreground.get(); if (activity != null) activity.cancelMediaPicker(identifier); });
            return new JSONObject().put("cancelled", true);
        } finally { synchronized (this) { if (pending == result) pending = null; } }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(60000);
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            try {
                int size = input.readInt();
                if (size <= 0 || size > 65536) throw new IOException("安卓目录请求过大");
                byte[] bytes = new byte[size]; input.readFully(bytes);
                JSONObject body = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                if (!MessageDigest.isEqual(secret.getBytes(StandardCharsets.US_ASCII), body.optString("secret").getBytes(StandardCharsets.UTF_8)))
                    throw new SecurityException("安卓目录口令无效");
                if (body.optLong("length", 0) < 0) throw new IOException("文件长度无效");
                if (body.getString("op").equals("pick")) {
                    reply(output, pick(body.optString("initial")), null); return;
                }
                String identifier = body.getString("id");
                String stored = context.getSharedPreferences("media-trees", Context.MODE_PRIVATE).getString(identifier, null);
                if (stored == null) throw new SecurityException("目录尚未授权，请重新选择手机目录");
                Uri tree = Uri.parse(stored);
                List<String> parts = parts(body.getJSONArray("parts"));
                switch (body.getString("op")) {
                    case "stat": reply(output, attributes(find(tree, parts)), null); break;
                    case "list": reply(output, new JSONObject().put("entries", children(tree, find(tree, parts))), null); break;
                    case "read": {
                        long offset = body.getLong("offset"); int count = body.getInt("count");
                        if (offset < 0 || count < 0 || count > 8 * 1024 * 1024) throw new IOException("文件读取范围无效");
                        Uri document = find(tree, parts);
                        long total = attributes(document).getLong("size");
                        if (offset > total || count > total - offset) throw new IOException("文件读取范围越界");
                        byte[] data = new byte[count];
                        try (InputStream source = context.getContentResolver().openInputStream(document)) {
                            if (source == null) throw new IOException("无法打开文件");
                            long skipped = 0;
                            while (skipped < offset) {
                                long step = source.skip(offset - skipped);
                                if (step == 0) { if (source.read() == -1) throw new EOFException("文件已变化"); step = 1; }
                                skipped += step;
                            }
                            new DataInputStream(source).readFully(data);
                        }
                        reply(output, new JSONObject(), data); break;
                    }
                    case "write": {
                        requireChild(parts); Uri target;
                        try {
                            target = find(tree, parts);
                            if (body.optBoolean("exclusive")) throw new Exists("文件已存在");
                            if (attributes(target).getBoolean("directory")) throw new IOException("不能覆盖文件夹");
                        } catch (FileNotFoundException missing) {
                            target = create(tree, parts, "application/octet-stream");
                        }
                        long remaining = body.optLong("length", 0);
                        try (OutputStream destination = context.getContentResolver().openOutputStream(target, "wt")) {
                            if (destination == null) throw new IOException("无法写入文件");
                            byte[] buffer = new byte[256 * 1024];
                            while (remaining > 0) {
                                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                                if (count == -1) throw new EOFException("文件写入已中断");
                                destination.write(buffer, 0, count); remaining -= count;
                            }
                        }
                        reply(output, new JSONObject(), null); break;
                    }
                    case "mkdir": {
                        requireChild(parts);
                        try { find(tree, parts); throw new Exists("文件夹已存在"); }
                        catch (FileNotFoundException missing) { create(tree, parts, DocumentsContract.Document.MIME_TYPE_DIR); }
                        reply(output, new JSONObject(), null); break;
                    }
                    case "delete": {
                        requireChild(parts); Uri target = find(tree, parts);
                        boolean directory = attributes(target).getBoolean("directory");
                        if (directory != body.optBoolean("directory")) throw new IOException("文件类型已变化");
                        if (directory && children(tree, target).length() != 0) throw new IOException("目录非空，未删除");
                        if (!DocumentsContract.deleteDocument(context.getContentResolver(), target)) throw new IOException("文件未删除");
                        reply(output, new JSONObject(), null); break;
                    }
                    case "rename": {
                        List<String> target = parts(body.getJSONArray("target"));
                        requireChild(parts); requireChild(target);
                        move(tree, parts, target);
                        reply(output, new JSONObject(), null); break;
                    }
                    default: throw new IOException("未知安卓目录操作");
                }
            } catch (Exception error) {
                Throwable detail = error instanceof ExecutionException ? error.getCause() : error;
                String kind = detail instanceof SecurityException ? "permission" : detail instanceof FileNotFoundException ? "missing" : detail instanceof Exists ? "exists" : "io";
                reply(output, new JSONObject().put("error", kind).put("message", detail.getMessage() == null ? "安卓目录操作未完成" : detail.getMessage()), null);
            }
        } catch (Exception ignored) { /* 对端退出不改变服务及授权。 */ }
    }

    private static void reply(DataOutputStream output, JSONObject value, byte[] data) throws Exception {
        value.put("length", data == null ? 0 : data.length);
        byte[] json = value.toString().getBytes(StandardCharsets.UTF_8);
        output.writeInt(json.length); output.write(json); if (data != null) output.write(data); output.flush();
    }

    private static List<String> parts(JSONArray value) throws JSONException, IOException {
        if (value.length() > 256) throw new IOException("目录层级过深");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < value.length(); i++) {
            String part = value.getString(i);
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf('/') >= 0 || part.indexOf('\\') >= 0 || part.indexOf(0) >= 0)
                throw new IOException("安卓目录越界");
            result.add(part);
        }
        return result;
    }

    private static void requireChild(List<String> parts) throws IOException {
        if (parts.isEmpty()) throw new SecurityException("不能修改授权的根目录");
    }

    private Uri find(Uri tree, List<String> parts) throws Exception {
        Uri current = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        for (String name : parts) {
            JSONArray entries = children(tree, current); Uri found = null;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                if (entry.getString("name").equals(name)) {
                    if (found != null) throw new IOException("目录内存在同名文档，无法确定文件");
                    found = DocumentsContract.buildDocumentUriUsingTree(tree, entry.getString("documentId"));
                }
            }
            if (found == null) throw new FileNotFoundException("授权目录内未找到文件");
            current = found;
        }
        return current;
    }

    private JSONObject attributes(Uri document) throws Exception {
        try (Cursor cursor = context.getContentResolver().query(document, COLUMNS, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new FileNotFoundException("目录授权已失效或文件不存在");
            return row(cursor);
        }
    }

    private JSONObject row(Cursor cursor) throws Exception {
        String name = cursor.getString(1);
        boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2));
        if (!directory && cursor.isNull(3)) throw new IOException("存储提供方未提供文件大小");
        return new JSONObject().put("documentId", cursor.getString(0)).put("name", name).put("directory", directory)
            .put("size", directory ? 0 : cursor.getLong(3)).put("modified", cursor.isNull(4) ? 0 : cursor.getLong(4));
    }

    private JSONArray children(Uri tree, Uri parent) throws Exception {
        if (!attributes(parent).getBoolean("directory")) throw new IOException("所选路径不是文件夹");
        Uri query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        JSONArray result = new JSONArray();
        try (Cursor cursor = context.getContentResolver().query(query, COLUMNS, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取授权目录");
            while (cursor.moveToNext()) result.put(row(cursor));
        }
        return result;
    }

    private Uri create(Uri tree, List<String> parts, String mime) throws Exception {
        Uri parent = find(tree, parts.subList(0, parts.size() - 1)); String name = parts.get(parts.size() - 1);
        Uri created = DocumentsContract.createDocument(context.getContentResolver(), parent, mime, name);
        if (created == null) throw new IOException("存储提供方未创建文件");
        if (!attributes(created).getString("name").equals(name)) {
            DocumentsContract.deleteDocument(context.getContentResolver(), created);
            throw new Exists("存储提供方返回了不同的文件名，未覆盖现有文件");
        }
        return created;
    }

    private void move(Uri tree, List<String> source, List<String> target) throws Exception {
        if (source.equals(target)) return;
        try { find(tree, target); throw new Exists("目标文件已存在"); }
        catch (FileNotFoundException missing) { }
        Uri original = find(tree, source), current = original;
        Uri oldParent = find(tree, source.subList(0, source.size() - 1));
        Uri newParent = find(tree, target.subList(0, target.size() - 1));
        boolean moved = false;
        try {
            if (!oldParent.equals(newParent)) {
                current = DocumentsContract.moveDocument(context.getContentResolver(), current, oldParent, newParent);
                if (current == null) throw new IOException("存储提供方不支持移动文件");
                moved = true;
            }
            String newName = target.get(target.size() - 1);
            if (!source.get(source.size() - 1).equals(newName)) {
                Uri renamed = DocumentsContract.renameDocument(context.getContentResolver(), current, newName);
                if (renamed == null) throw new IOException("存储提供方不支持重命名");
                current = renamed;
            }
            if (!DocumentsContract.getDocumentId(find(tree, target)).equals(DocumentsContract.getDocumentId(current)))
                throw new IOException("文件移动结果未确认");
        } catch (Exception failure) {
            if (moved && current != null) {
                try {
                    Uri restored = DocumentsContract.moveDocument(context.getContentResolver(), current, newParent, oldParent);
                    if (restored == null) throw new IOException("移动恢复失败");
                    String oldName = source.get(source.size() - 1);
                    if (!attributes(restored).getString("name").equals(oldName)) {
                        if (DocumentsContract.renameDocument(context.getContentResolver(), restored, oldName) == null)
                            throw new IOException("名称恢复失败");
                    }
                } catch (Exception rollback) { throw new IOException("文件移动状态未确认，请在手机文件管理中检查原目录与目标目录", failure); }
            }
            throw failure;
        }
    }

    @Override public void close() {
        closed = true;
        synchronized (this) { if (pending != null) pending.completeExceptionally(new IOException("安卓目录服务已停止")); }
        synchronized (DirectoryBridge.class) { if (current == this) current = null; }
        try { server.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
    }

    private static final class Exists extends IOException { Exists(String message) { super(message); } }
}
