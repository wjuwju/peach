package video.peach.mobile;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.*;

/** 系统解码器播放；不支持的封装或编码交给用户选择的播放器。 */
public final class PlayerActivity extends Activity {
    private VideoView video;
    private LibraryStore library;
    private String path, url;
    private boolean prepared, completed;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        path = getIntent().getStringExtra("path"); url = getIntent().getStringExtra("url");
        if (path == null || url == null) { finish(); return; }
        library = new LibraryStore(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setFitsSystemWindows(true);
        LinearLayout toolbar = new LinearLayout(this);
        Button back = new Button(this); back.setText("返回"); back.setOnClickListener(v -> finish()); toolbar.addView(back);
        TextView title = new TextView(this); title.setText(getIntent().getStringExtra("title")); title.setMaxLines(2);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button external = new Button(this); external.setText("外部播放"); external.setOnClickListener(v -> external()); toolbar.addView(external); root.addView(toolbar);
        video = new VideoView(this); root.addView(video, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        MediaController controller = new MediaController(this); controller.setAnchorView(video); video.setMediaController(controller);
        video.setOnPreparedListener(player -> { prepared = true; int position = library.position(path); if (position > 0) video.seekTo(position); video.start(); });
        video.setOnCompletionListener(player -> { completed = true; library.progress(path, 0); });
        video.setOnErrorListener((player, what, extra) -> {
            new AlertDialog.Builder(this).setTitle("系统播放器无法播放此视频")
                .setMessage("可能是编码不受支持、登录已过期或网盘读取失败。可选择 VLC、MX Player 等已安装播放器，或返回重新登录。")
                .setPositiveButton("选择播放器", (d,w) -> external()).setNegativeButton("返回", (d,w) -> finish()).show(); return true;
        });
        video.setVideoURI(Uri.parse(url));
    }
    private void external() {
        if (video != null) video.pause();
        try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*"), "选择播放器")); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "请先安装一个视频播放器", Toast.LENGTH_LONG).show(); }
    }
    @Override protected void onPause() {
        if (video != null && prepared) { if (!completed) library.progress(path, video.getCurrentPosition()); video.pause(); }
        super.onPause();
    }
    @Override protected void onDestroy() { if (video != null) video.stopPlayback(); if (library != null) library.close(); super.onDestroy(); }
}
