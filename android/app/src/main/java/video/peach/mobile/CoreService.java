package video.peach.mobile;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/** 本应用启动的核心独立运行；通知停止只终止本次持有的子进程。 */
public final class CoreService extends Service {
    public static volatile String state = "尚未启动";
    public static volatile boolean ready;
    public static volatile boolean peachReady;
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private volatile Process process;
    private volatile Process peach;
    private DirectoryBridge directories;
    private volatile boolean stopping;
    private boolean starting;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("core", "本机云盘服务", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CoreService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cloud = PendingIntent.getActivity(this, 2, new Intent(this, CloudDriveActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "core")
            .setSmallIcon(R.drawable.ic_peach).setContentTitle("Peach 本机云盘服务")
            .setContentText("浏览和播放期间保持运行，可随时停止")
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "CloudDrive2", cloud).build())
            .addAction(new Notification.Action.Builder(null, "停止服务", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, notification);
        if (!starting) { starting = true; worker.execute(this::launch); }
        return START_NOT_STICKY;
    }

    private boolean listening() { return listening(19798); }

    private boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (IOException e) { return false; }
    }

    private void copyAssets(String source, File destination) throws IOException {
        String[] children = getAssets().list(source);
        if (children != null && children.length > 0) {
            if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("无法建立核心资源目录");
            for (String child : children) copyAssets(source + "/" + child, new File(destination, child));
        } else {
            try (InputStream input = getAssets().open(source); OutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        }
    }

    private void launch() {
        try {
            state = "正在准备本机服务…";
            if (listening()) throw new IOException("19798 端口已被其他服务占用，请先停止该服务");
            File root = new File(getFilesDir(), "clouddrive");
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法建立核心数据目录");
            File stamp = new File(root, "resources-1.1.3");
            if (!stamp.exists()) {
                copyAssets("clouddrive/wwwroot", new File(root, "wwwroot"));
                if (!stamp.createNewFile()) throw new IOException("无法保存资源版本");
            }
            if (stopping) return;
            File executable = new File(getApplicationInfo().nativeLibraryDir, "libclouddrive.so");
            if (!executable.isFile()) throw new IOException("安装包未包含此 CPU 架构的核心");
            ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath()).directory(root);
            builder.environment().put("CLOUDDRIVE_HOME", new File(root, "config").getAbsolutePath());
            builder.redirectErrorStream(true).redirectOutput(new File(root, "core.log"));
            process = builder.start();
            if (stopping) { process.destroy(); return; }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
            while (!stopping && System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IOException("核心启动失败，退出码 " + process.exitValue());
                if (listening()) { ready = true; state = "本机服务运行中"; break; }
                Thread.sleep(250);
            }
            if (!ready && !stopping) throw new IOException("本机服务启动超时，可停止后重试");
            if (ready) {
                if (listening(8900)) throw new IOException("8900 端口已被其他服务占用");
                directories = new DirectoryBridge(this);
                File runtime = RuntimeBundle.install(this, value -> state = value);
                if (stopping) return;
                state = "正在启动原版 Peach…";
                peach = RuntimeBundle.launch(this, runtime);
                if (stopping) { peach.destroy(); return; }
                long peachDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
                while (!stopping && System.nanoTime() < peachDeadline) {
                    if (!peach.isAlive()) throw new IOException("Peach 运行环境启动失败，退出码 " + peach.exitValue());
                    if (healthy()) { peachReady = true; state = "Peach 与本机云盘运行中"; break; }
                    Thread.sleep(500);
                }
                if (!peachReady && !stopping) throw new IOException("Peach 启动超时，请查看运行诊断");
                worker.execute(() -> {
                    try {
                        int code = peach.waitFor();
                        if (!stopping) { peachReady = false; state = "Peach 已退出（" + code + "）"; stopSelf(); }
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                int code = process.waitFor();
                if (!stopping) { ready = false; state = "本机服务已退出（" + code + "），请重新启动"; stopSelf(); }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            ready = false;
            state = e.getMessage() == null ? "本机服务无法启动" : e.getMessage();
            stopSelf();
        }
    }

    private boolean healthy() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:8900/healthz?ready=1").openConnection();
            connection.setConnectTimeout(2000); connection.setReadTimeout(15000);
            return connection.getResponseCode() == 200;
        } catch (IOException e) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }

    @Override public void onDestroy() {
        stopping = true;
        ready = false;
        peachReady = false;
        if (directories != null) directories.close();
        for (Process owned : new Process[]{peach, process}) if (owned != null) {
            owned.destroy();
            worker.execute(() -> {
                try { if (!owned.waitFor(3, TimeUnit.SECONDS)) owned.destroyForcibly(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        }
        worker.shutdown();
        if (state.equals("本机服务运行中") || state.equals("Peach 与本机云盘运行中") || state.startsWith("正在")) state = "本机服务已停止";
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
