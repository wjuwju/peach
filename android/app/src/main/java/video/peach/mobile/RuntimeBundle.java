package video.peach.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.system.Os;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

/** 将固定版本的完整 Peach 运行环境展开在应用私有目录，用户数据独立持久保存。 */
final class RuntimeBundle {
    static File data(Context context) { return new File(context.getFilesDir(), "peach-data"); }
    static File install(Context context, Consumer<String> progress) throws Exception {
        JSONObject manifest;
        try (InputStream input = context.getAssets().open("peach/runtime.json")) {
            manifest = new JSONObject(readText(input));
        }
        String version = manifest.getString("version");
        if (!version.matches("[a-f0-9]{16,64}")) throw new IOException("运行环境版本标识无效");
        File destination = new File(context.getFilesDir(), "peach-runtime-" + version);
        if (new File(destination, ".complete").isFile()) return destination;
        if (destination.exists()) Files.walkFileTree(destination.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
        });
        if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("无法建立运行环境目录");
        progress.accept("正在准备原版 Peach 运行环境，首次启动需要一些时间…");
        String prefix = destination.getCanonicalPath() + File.separator;
        JSONObject metadata = null;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream verified = new DigestInputStream(context.getAssets().open("peach/runtime.zip"), digest);
                ZipInputStream archive = new ZipInputStream(verified)) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = archive.getNextEntry()) != null) {
                if (entry.getName().equals("runtime-links.json")) {
                    metadata = new JSONObject(readText(archive));
                    archive.closeEntry(); continue;
                }
                File target = new File(destination, entry.getName());
                if (!target.getCanonicalPath().startsWith(prefix)) throw new IOException("运行环境归档路径越界");
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("无法建立运行环境子目录");
                } else {
                    File parent = target.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法建立运行环境子目录");
                    try (OutputStream output = new FileOutputStream(target)) {
                        int count; while ((count = archive.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                }
                archive.closeEntry();
            }
            while (verified.read(buffer) != -1) { }
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
        if (!actual.toString().equals(manifest.getString("sha256"))) throw new IOException("运行环境文件校验失败，请重新安装完整 APK");
        if (metadata == null) throw new IOException("运行环境文件清单缺失");
        JSONObject modes = metadata.getJSONObject("modes");
        Iterator<String> paths = modes.keys();
        while (paths.hasNext()) {
            String path = paths.next(); File target = new File(destination, path);
            if (!target.getCanonicalPath().startsWith(prefix)) throw new IOException("运行环境权限路径越界");
            Os.chmod(target.getAbsolutePath(), modes.getInt(path) & 0777);
        }
        JSONObject links = metadata.getJSONObject("links");
        paths = links.keys();
        while (paths.hasNext()) {
            String path = paths.next(), link = links.getString(path);
            File target = new File(destination, path);
            if (!target.getCanonicalPath().startsWith(prefix)) throw new IOException("运行环境链接路径越界");
            File resolved = new File(target.getParentFile(), link);
            if (new File(link).isAbsolute() || !resolved.getCanonicalPath().startsWith(prefix)) throw new IOException("运行环境链接目标越界");
            target.getParentFile().mkdirs();
            if (!target.exists()) Os.symlink(link, target.getAbsolutePath());
        }
        try (OutputStream output = new FileOutputStream(new File(destination, ".complete"))) { output.write(version.getBytes(StandardCharsets.US_ASCII)); }
        return destination;
    }
    static String readText(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toString("UTF-8");
    }
    static Process launch(Context context, File root) throws Exception {
        ConnectivityManager network = context.getSystemService(ConnectivityManager.class);
        LinkProperties properties = network.getLinkProperties(network.getActiveNetwork());
        StringBuilder resolvers = new StringBuilder();
        if (properties != null) for (java.net.InetAddress server : properties.getDnsServers())
            resolvers.append("nameserver ").append(server.getHostAddress()).append('\n');
        try (OutputStream output = new FileOutputStream(new File(root, "etc/resolv.conf"))) {
            output.write(resolvers.toString().getBytes(StandardCharsets.US_ASCII));
        }
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File dependencies = new File(context.getFilesDir(), "native-dependencies"); dependencies.mkdirs();
        File talloc = new File(dependencies, "libtalloc.so.2");
        if (!talloc.exists()) Os.symlink(new File(nativeDir, "libtalloc.so").getAbsolutePath(), talloc.getAbsolutePath());
        File data = data(context), media = new File(context.getFilesDir(), "peach-media");
        data.mkdirs(); media.mkdirs();
        File shared = new File(context.getCacheDir(), "peach-shm"); shared.mkdirs();
        List<String> command = new ArrayList<>(Arrays.asList(new File(nativeDir, "libproot.so").getAbsolutePath(),
            "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "--change-id=1000:1000",
            "-r", root.getAbsolutePath(), "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/system", "-b", "/apex",
            "-b", data.getAbsolutePath() + ":/data", "-b", media.getAbsolutePath() + ":/media",
            "-b", shared.getAbsolutePath() + ":/dev/shm", "-w", "/data",
            "/usr/bin/env", "-u", "LD_LIBRARY_PATH", "-u", "LD_PRELOAD", "-u", "HOME", "-u", "PYTHONHOME", "-u", "VIRTUAL_ENV", "PATH=/usr/local/bin:/usr/bin:/bin",
            "PYTHONPATH=/app/src", "PYTHONUTF8=1", "PYTHONUNBUFFERED=1", "PYSTRAY_BACKEND=dummy", "PEACH_ANDROID=1", "PEACH_DATA_ROOT=/data",
            "TZ=" + TimeZone.getDefault().getID(),
            "PEACH_HEADLESS_MANAGED=1", "PEACH_FACE_MODELS=/app/tools/face-models", "PEACH_AMANE_RUNTIME=/app/amane-runtime",
            "/usr/local/bin/python3.14", "-m", "peach.server", "--host", "127.0.0.1", "--port", "8900"));
        ProcessBuilder builder = new ProcessBuilder(command).directory(data);
        builder.environment().put("PROOT_LOADER", new File(nativeDir, "libprootloader.so").getAbsolutePath());
        builder.environment().put("PROOT_TMP_DIR", context.getCacheDir().getAbsolutePath());
        builder.environment().put("LD_LIBRARY_PATH", dependencies.getAbsolutePath() + ":" + nativeDir.getAbsolutePath());
        builder.redirectErrorStream(true).redirectOutput(new File(data, "android-runtime.log"));
        return builder.start();
    }
}
