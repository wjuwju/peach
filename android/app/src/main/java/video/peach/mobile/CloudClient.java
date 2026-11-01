package video.peach.mobile;

import com.google.protobuf.Empty;
import io.grpc.*;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;
import video.peach.mobile.rpc.*;

/** 文件浏览、令牌和播放地址复用官方公开 gRPC 协议。 */
public final class CloudClient implements AutoCloseable {
    private final ManagedChannel channel = OkHttpChannelBuilder.forAddress("127.0.0.1", 19798).usePlaintext().build();
    private volatile String token = "";
    void setToken(String token) { this.token = token; }
    boolean hasToken() { return !token.isEmpty(); }
    private CloudDriveFileSrvGrpc.CloudDriveFileSrvBlockingStub stub() {
        var stub = CloudDriveFileSrvGrpc.newBlockingStub(channel).withDeadlineAfter(30, TimeUnit.SECONDS);
        if (!token.isEmpty()) {
            Metadata headers = new Metadata();
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
            stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        }
        return stub;
    }
    String login(String username, String password, String otp, boolean sync) {
        if (!stub().getSystemInfo(Empty.getDefaultInstance()).getIsLogin()) {
            if (!otp.isEmpty()) {
                var verified = stub().loginWith2FA(LoginWith2FARequest.newBuilder().setUserName(username).setPassword(password).setTotpCode(otp).setSynDataToCloud(sync).build());
                if (!verified.getSuccess() || verified.getToken().isEmpty()) throw new IllegalStateException(verified.getErrorMessage());
                return verified.getToken();
            }
            var result = stub().login(UserLoginRequest.newBuilder().setUserName(username).setPassword(password).setSynDataToCloud(sync).build());
            if (!result.getSuccess()) throw new IllegalStateException(result.getErrorMessage());
        }
        var result = stub().getToken(GetTokenRequest.newBuilder().setUserName(username).setPassword(password).setTotpCode(otp).build());
        if (!result.getSuccess() || result.getToken().isEmpty()) throw new IllegalStateException(result.getErrorMessage());
        return result.getToken();
    }
    List<CloudDriveFile> list(String path, boolean refresh) {
        List<CloudDriveFile> files = new ArrayList<>();
        var responses = stub().getSubFiles(ListSubFileRequest.newBuilder().setPath(path).setForceRefresh(refresh).build());
        while (responses.hasNext()) files.addAll(responses.next().getSubFilesList());
        files.sort(Comparator.comparing((CloudDriveFile f) -> !f.getIsDirectory()).thenComparing(CloudDriveFile::getName, String.CASE_INSENSITIVE_ORDER));
        return files;
    }
    DownloadUrlPathInfo playback(String path) {
        return stub().getDownloadUrlPath(GetDownloadUrlPathRequest.newBuilder().setPath(path).setPreview(true).setLazyRead(true).setGetDirectUrl(false).build());
    }
    static String localUrl(DownloadUrlPathInfo info) {
        String path = info.getDownloadUrlPath().replace("{SCHEME}", "http").replace("{HOST}", "127.0.0.1:19798").replace("{PREVIEW}", "true");
        URI resolved = URI.create("http://127.0.0.1:19798").resolve(path);
        if (!"http".equals(resolved.getScheme()) || !"127.0.0.1".equals(resolved.getHost()) || resolved.getPort() != 19798)
            throw new IllegalArgumentException("核心未返回有效的本机播放地址");
        return resolved.toString();
    }
    @Override public void close() { channel.shutdownNow(); }
}
