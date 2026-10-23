package io.github.qing936.xianyuops.module.xianyu.service.source.clean;

import io.github.qing936.xianyuops.module.infra.api.file.FileApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.util.Iterator;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SourceCleanImageStore {
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final long MAX_PIXELS = 20_000_000L;
    private static final int DOWNLOAD_CONNECT_TIMEOUT_MILLIS = 30000;
    private static final int DOWNLOAD_READ_TIMEOUT_MILLIS = 60000;
    private static final int DOWNLOAD_MAX_ATTEMPTS = 3;
    private static final long DOWNLOAD_RETRY_DELAY_MILLIS = 1000L;
    private final SourceCleanProperties properties;
    private final FileApi fileApi;

    public String store(String url) {
        String stage = "下载地址校验";
        try {
            URI uri = validateUrl(url);
            stage = "图片下载";
            byte[] content = download(uri);
            stage = "图片转码";
            byte[] jpeg = toJpeg(content);
            stage = "文件存储";
            return fileApi.createFile(jpeg, UUID.randomUUID() + ".jpg", "source/clean", "image/jpeg");
        } catch (SourceCleanFailure e) {
            throw e;
        } catch (Exception e) {
            // 异常消息可能包含签名 URL 或存储凭证，只记录阶段和异常类型。
            Throwable cause = e;
            for (int i = 0; i < 10 && cause.getCause() != null && cause.getCause() != cause; i++) {
                cause = cause.getCause();
            }
            log.warn("[sourceCleanImageStore] stage={}, failureType={}, causeType={}",
                    stage, e.getClass().getSimpleName(), cause.getClass().getSimpleName());
            if ("文件存储".equals(stage)) {
                throw new SourceCleanFailure("图片已下载，但文件存储失败，请检查默认存储配置、上传权限及 OSS endpoint 连通性（本机应使用公网地址）");
            }
            throw new SourceCleanFailure("生成图片" + stage + "失败，请检查下载白名单、图片有效期、格式及大小（最大20MB）");
        }
    }

    public String storeAsJpeg(String url) {
        String stage = "下载地址校验";
        try {
            URI uri = validateUrl(url);
            stage = "图片下载";
            byte[] content = download(uri);
            stage = "图片转码";
            byte[] jpeg = toJpeg(content);
            stage = "文件存储";
            return fileApi.createFile(jpeg, UUID.randomUUID() + ".jpg", "source/inventory", "image/jpeg");
        } catch (SourceCleanFailure e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e;
            for (int i = 0; i < 10 && cause.getCause() != null && cause.getCause() != cause; i++) {
                cause = cause.getCause();
            }
            log.warn("[sourceCleanImageStore] JPEG stage={}, failureType={}, causeType={}",
                    stage, e.getClass().getSimpleName(), cause.getClass().getSimpleName());
            throw new SourceCleanFailure("货源图片转 JPEG 并转存失败，请检查图片来源、格式及文件存储配置");
        }
    }

    /** 将图片微调工作流产出的图片下载转 JPEG 后转存到 source/refine。 */
    public String storeRefinedImage(String url) {
        String stage = "下载地址校验";
        try {
            URI uri = validateUrl(url);
            stage = "图片下载";
            byte[] content = download(uri);
            stage = "图片转码";
            byte[] jpeg = toJpeg(content);
            stage = "文件存储";
            return fileApi.createFile(jpeg, UUID.randomUUID() + ".jpg", "source/refine", "image/jpeg");
        } catch (SourceCleanFailure e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e;
            for (int i = 0; i < 10 && cause.getCause() != null && cause.getCause() != cause; i++) {
                cause = cause.getCause();
            }
            log.warn("[sourceCleanImageStore] refine stage={}, failureType={}, causeType={}",
                    stage, e.getClass().getSimpleName(), cause.getClass().getSimpleName());
            throw new SourceCleanFailure("图片微调结果" + stage + "失败，请检查工作流输出图片地址、有效期及文件存储配置");
        }
    }

    URI validateUrl(String url) throws IOException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null || uri.getScheme() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException();
        }
        boolean supplier = "https".equalsIgnoreCase(uri.getScheme())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && properties.getImageHosts().stream().anyMatch(host::equalsIgnoreCase);
        URI dify = URI.create(properties.getBaseUrl());
        boolean difyFile = uri.getScheme().equalsIgnoreCase(dify.getScheme())
                && dify.getHost() != null && host.equalsIgnoreCase(dify.getHost())
                && uri.getPort() == dify.getPort()
                && uri.getRawPath() != null
                && uri.getRawPath().matches("/files/tools/[A-Za-z0-9_-]+\\.bin");
        if (!supplier && !difyFile) {
            throw new IllegalArgumentException();
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException();
            }
        }
        return uri;
    }

    private byte[] download(URI uri) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return downloadOnce(uri);
            } catch (SocketTimeoutException e) {
                if (attempt >= DOWNLOAD_MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("[sourceCleanImageStore] 图片下载超时，将重试 attempt={}/{}",
                        attempt, DOWNLOAD_MAX_ATTEMPTS);
                try {
                    Thread.sleep(DOWNLOAD_RETRY_DELAY_MILLIS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    e.addSuppressed(interrupted);
                    throw e;
                }
            }
        }
    }

    private byte[] downloadOnce(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MILLIS);
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new SourceCleanFailure("生成图片下载失败，HTTP 状态码：" + status);
            }
            if (connection.getContentLengthLong() > MAX_BYTES) {
                throw new IllegalArgumentException();
            }
            byte[] content;
            try (InputStream stream = connection.getInputStream()) {
                content = stream.readNBytes(MAX_BYTES + 1);
            }
            if (content.length == 0 || content.length > MAX_BYTES) {
                throw new IllegalArgumentException();
            }
            return content;
        } finally {
            connection.disconnect();
        }
    }

    private byte[] toJpeg(byte[] content) throws IOException {
        if (content.length >= 3 && (content[0] & 255) == 255
                && (content[1] & 255) == 216 && (content[2] & 255) == 255) {
            return content;
        }
        BufferedImage source;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("不支持的图片格式");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw new IllegalArgumentException("图片尺寸超出限制");
                }
                source = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        if (source == null) {
            throw new IllegalArgumentException("图片无法解码");
        }
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "jpeg", output)) {
                throw new IllegalStateException("JPEG 编码器不可用");
            }
            return output.toByteArray();
        }
    }
}
