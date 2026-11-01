package video.peach.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.widget.*;
import io.grpc.Status;
import java.util.*;
import java.util.concurrent.*;
import video.peach.mobile.rpc.CloudDriveFile;

/** 安卓独立媒体入口，所有云盘网络调用在后台执行。 */
public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(35, 43, 43), ACCENT = Color.rgb(195, 75, 65);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private CloudClient client;
    private TokenVault vault;
    private LibraryStore library;
    private TextView status, pathLabel, message;
    private EditText search;
    private GridView grid;
    private Button refresh;
    private boolean browsing, busy;
    private String path = "/";
    private List<CloudDriveFile> files = new ArrayList<>();
    private final List<CloudDriveFile> visible = new ArrayList<>();
    private BaseAdapter adapter;
    private final Runnable pulse = new Runnable() {
        @Override public void run() { status.setText(CoreService.state); handler.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        client = new CloudClient(); vault = new TokenVault(this); library = new LibraryStore(this);
        try { client.setToken(vault.load()); }
        catch (Exception e) { vault.clear(); Toast.makeText(this, "登录状态无法解密，请重新登录", Toast.LENGTH_LONG).show(); }
        if (state != null) { path = state.getString("path", "/"); browsing = state.getBoolean("browsing"); }
        build();
        showLibrary();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(INK); return view;
    }
    private Button button(String value, Runnable action) {
        Button button = new Button(this); button.setText(value); button.setAllCaps(false); button.setTextColor(INK);
        button.setOnClickListener(v -> action.run()); return button;
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private void build() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(8)); root.setBackgroundColor(Color.rgb(249, 247, 243));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(8) + bars.bottom);
            } else {
                v.setPadding(dp(16) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(), dp(16) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        TextView title = text("Peach Mobile", 30); title.setTypeface(null, Typeface.BOLD); root.addView(title);
        root.addView(text("自用实验版 · 手机本机运行", 12));
        status = text(CoreService.state, 13); status.setPadding(0, dp(10), 0, dp(6)); root.addView(status);
        LinearLayout service = row();
        service.addView(button("启动核心", this::startCore), new LinearLayout.LayoutParams(0, dp(48), 1));
        service.addView(button("登录账号", this::login), new LinearLayout.LayoutParams(0, dp(48), 1));
        service.addView(button("云盘管理", () -> {
            if (requireCore()) startActivity(new Intent(this, ConsoleActivity.class));
        }), new LinearLayout.LayoutParams(0, dp(48), 1)); root.addView(service);
        LinearLayout navigation = row();
        navigation.addView(button("媒体库", this::showLibrary), new LinearLayout.LayoutParams(0, dp(48), 1));
        navigation.addView(button("浏览云盘", () -> browse("/", false)), new LinearLayout.LayoutParams(0, dp(48), 1));
        navigation.addView(button("设置", this::settings), new LinearLayout.LayoutParams(0, dp(48), 1)); root.addView(navigation);
        search = new EditText(this); search.setSingleLine(); search.setTextSize(15); search.setHint("筛选当前列表…"); root.addView(search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { filter(); }
            public void afterTextChanged(Editable e) {}
        });
        LinearLayout location = row();
        location.addView(button("上一级", () -> {
            if (!browsing || path.equals("/")) return;
            int last = path.lastIndexOf('/'); browse(last <= 0 ? "/" : path.substring(0, last), false);
        }), new LinearLayout.LayoutParams(dp(85), dp(44)));
        pathLabel = text("媒体库", 13); pathLabel.setMaxLines(2);
        location.addView(pathLabel, new LinearLayout.LayoutParams(0, dp(44), 1));
        refresh = button("刷新", () -> { if (browsing) browse(path, true); else showLibrary(); });
        location.addView(refresh, new LinearLayout.LayoutParams(dp(72), dp(44))); root.addView(location);
        message = text("", 14); message.setPadding(0, dp(8), 0, dp(8)); root.addView(message);
        grid = new GridView(this); grid.setColumnWidth(dp(150)); grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); grid.setHorizontalSpacing(dp(10)); grid.setVerticalSpacing(dp(10));
        adapter = new BaseAdapter() {
            public int getCount() { return visible.size(); }
            public Object getItem(int position) { return visible.get(position); }
            public long getItemId(int position) { return position; }
            public View getView(int position, View old, ViewGroup parent) {
                CloudDriveFile file = visible.get(position);
                LinearLayout card = new LinearLayout(MainActivity.this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(12), dp(14), dp(12), dp(14));
                GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE); background.setCornerRadius(dp(14)); card.setBackground(background);
                TextView icon = text(file.getIsDirectory() ? "▣" : "▶", 32); icon.setTextColor(ACCENT); card.addView(icon);
                TextView name = text(file.getName(), 15); name.setMaxLines(3); name.setMinLines(3); name.setEllipsize(TextUtils.TruncateAt.END); card.addView(name);
                TextView meta = text(file.getIsDirectory() ? "打开目录" : (library.contains(file.getFullPathName()) ? "已加入媒体库 · " : "") + size(file.getSize()), 11);
                meta.setMaxLines(1); meta.setEllipsize(TextUtils.TruncateAt.END); card.addView(meta);
                card.setContentDescription(file.getName()); return card;
            }
        };
        grid.setAdapter(adapter); grid.setOnItemClickListener((a, v, position, id) -> {
            CloudDriveFile file = visible.get(position);
            if (file.getIsDirectory()) browse(file.getFullPathName(), false); else details(file);
        });
        grid.setOnItemLongClickListener((a, v, position, id) -> { details(visible.get(position)); return true; });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
    }
    private String size(long bytes) {
        return bytes >= 1073741824 ? String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824.0) : String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }
    private void startCore() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        startForegroundService(new Intent(this, CoreService.class));
    }
    private boolean requireCore() {
        if (CoreService.ready) return true;
        new AlertDialog.Builder(this).setTitle("本机服务尚未就绪").setMessage(CoreService.state + "。请启动核心，等待显示运行中。")
            .setPositiveButton("启动核心", (d,w) -> startCore()).setNegativeButton("关闭", null).show(); return false;
    }
    private void login() {
        if (!requireCore() || busy) return;
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText username = new EditText(this); username.setHint("CloudDrive 账号邮箱"); username.setSingleLine(); username.setInputType(33); form.addView(username);
        EditText password = new EditText(this); password.setHint("CloudDrive 密码"); password.setSingleLine(); password.setInputType(129); password.setTransformationMethod(PasswordTransformationMethod.getInstance()); form.addView(password);
        EditText otp = new EditText(this); otp.setHint("两步验证码（未开启可留空）"); otp.setSingleLine(); otp.setInputType(2); form.addView(otp);
        CheckBox sync = new CheckBox(this); sync.setText("首次登录时同步 CloudDrive 云端账号列表"); form.addView(sync);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("登录本机 CloudDrive2").setView(form)
            .setPositiveButton("登录", null).setNeutralButton("使用 API 令牌", (d,w) -> tokenLogin()).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            String user = username.getText().toString().trim(), pass = password.getText().toString(), code = otp.getText().toString().trim();
            if (user.isEmpty() || pass.isEmpty()) { username.setError("请填写账号和密码"); return; }
            boolean cloudSync = sync.isChecked(); password.setText(""); otp.setText(""); dialog.dismiss();
            submit(() -> {
                String token = client.login(user, pass, code, cloudSync); vault.save(token); client.setToken(token);
                return client.list("/", false);
            }, result -> loaded("/", result));
        })); dialog.show();
    }
    private void tokenLogin() {
        EditText token = new EditText(this); token.setHint("本机核心的 API 令牌"); token.setInputType(129);
        new AlertDialog.Builder(this).setTitle("连接本机核心").setMessage("可以在云盘管理的 API 令牌页面创建，至少授予列目录、文件读取权限。")
            .setView(token).setPositiveButton("连接", (d,w) -> {
                String value = token.getText().toString().trim(); token.setText("");
                if (value.isEmpty()) return;
                submit(() -> { client.setToken(value); var result = client.list("/", false); vault.save(value); return result; }, result -> loaded("/", result));
            }).setNegativeButton("取消", null).show();
    }
    private void settings() {
        new AlertDialog.Builder(this).setTitle("本机设置").setItems(new String[]{"停止本机核心", "清除 Peach 登录令牌", "关于此版本"}, (d, which) -> {
            if (which == 0) stopService(new Intent(this, CoreService.class));
            if (which == 1) { vault.clear(); client.setToken(""); message.setText("Peach 登录令牌已清除；核心中的云盘账号保留"); }
            if (which == 2) new AlertDialog.Builder(this).setTitle("Peach Mobile 0.1.0")
                .setMessage("基于 Peach 项目的个人安卓移植。\n当前支持：本机 CloudDrive2、账号登录、云盘浏览、媒体库和断点播放。\n原版 Python 刮削、NFO 导入和桌面 ledger 同步尚未移植。\nCloudDrive2 由原作者提供，遵循其许可；此包仅供本人自用。")
                .setPositiveButton("关闭", null).show();
        }).setNegativeButton("关闭", null).show();
    }
    private void showLibrary() {
        if (busy) return;
        browsing = false; files = library.all(); pathLabel.setText("媒体库 · 本机保存"); filter();
    }
    private void browse(String target, boolean force) {
        if (busy || !requireCore()) return;
        if (!client.hasToken()) { login(); return; }
        submit(() -> client.list(target, force), result -> loaded(target, result));
    }
    private void loaded(String target, List<CloudDriveFile> result) {
        browsing = true; path = target; files = result; pathLabel.setText(target); filter();
    }
    private boolean video(CloudDriveFile file) {
        return file.getName().toLowerCase(Locale.ROOT).matches(".*\\.(mp4|m4v|mkv|avi|wmv|mov|ts|flv|rmvb|mpg|m2ts|webm)$");
    }
    private void filter() {
        if (adapter == null || search == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (CloudDriveFile file : files) if ((file.getIsDirectory() || video(file)) && file.getName().toLowerCase(Locale.ROOT).contains(query)) visible.add(file);
        adapter.notifyDataSetChanged();
        message.setText(visible.isEmpty() ? (query.isEmpty() ? (browsing ? "此处没有目录或视频。可在云盘管理中添加网盘。" : "媒体库还是空的。浏览云盘，点视频加入媒体库。") : "当前列表没有匹配结果") : visible.size() + " 项 · 点视频播放或保存到媒体库");
    }
    private void details(CloudDriveFile file) {
        if (busy) return;
        if (file.getIsDirectory()) { browse(file.getFullPathName(), false); return; }
        boolean saved = library.contains(file.getFullPathName());
        new AlertDialog.Builder(this).setTitle(file.getName()).setMessage(file.getFullPathName() + "\n" + size(file.getSize()))
            .setPositiveButton("播放", (d,w) -> play(file))
            .setNeutralButton(saved ? "移出媒体库" : "加入媒体库", (d,w) -> {
                if (saved) library.remove(file.getFullPathName()); else library.add(file);
                if (browsing) filter(); else showLibrary();
            }).setNegativeButton("关闭", null).show();
    }
    private void play(CloudDriveFile file) {
        if (!requireCore()) return;
        if (!client.hasToken()) { login(); return; }
        submit(() -> CloudClient.localUrl(client.playback(file.getFullPathName())), url -> {
            library.add(file);
            startActivity(new Intent(this, PlayerActivity.class).putExtra("url", url).putExtra("path", file.getFullPathName()).putExtra("title", file.getName()));
        });
    }
    private <T> void submit(Callable<T> action, java.util.function.Consumer<T> done) {
        if (busy) return;
        busy = true; refresh.setEnabled(false); message.setText("正在连接本机云盘…");
        worker.execute(() -> {
            try {
                T result = action.call();
                handler.post(() -> { if (!isDestroyed()) { busy = false; refresh.setEnabled(true); done.accept(result); } });
            } catch (Exception error) {
                handler.post(() -> {
                    if (isDestroyed()) return;
                    busy = false; refresh.setEnabled(true);
                    if (Status.fromThrowable(error).getCode() == Status.Code.UNAUTHENTICATED) { client.setToken(""); vault.clear(); }
                    String detail = error.getMessage();
                    message.setText("读取失败，当前列表保留。可以重新登录或刷新。");
                    new AlertDialog.Builder(this).setTitle("操作未完成").setMessage(detail == null ? "连接失败，请检查本机服务和登录状态" : detail).setPositiveButton("关闭", null).show();
                });
            }
        });
    }
    @Override protected void onResume() { super.onResume(); handler.post(pulse); if (!browsing && !busy) showLibrary(); }
    @Override protected void onPause() { handler.removeCallbacks(pulse); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("path", path); out.putBoolean("browsing", browsing); super.onSaveInstanceState(out); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); worker.shutdownNow(); client.close(); library.close(); super.onDestroy(); }
}
