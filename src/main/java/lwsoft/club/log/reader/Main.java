package lwsoft.club.log.reader;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws InterruptedException {
        LOGGER.info("Log viewer is starting...");
        final var config = getServerLogConfig();
        if (config == null) {
            var currentDir = System.getProperty("user.dir");
            LOGGER.error("No config found or Config format error, please check {} {} config.yaml", currentDir, File.separator);
            return;
        }
        if (config.getPort() == null) {
            LOGGER.error("Please config server.port");
            return;
        }
        EventLoopGroup bossGroup = new NioEventLoopGroup(1, Thread.ofPlatform().factory());
        EventLoopGroup workerGroup = new NioEventLoopGroup(0, Thread.ofVirtual().factory());
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(65536));
                        ch.pipeline().addLast(new WebSocketServerProtocolHandler("/log", true));
                        ch.pipeline().addLast(new HttpServerHandler(config.getFiles()));
                        ch.pipeline().addLast(new WebSocketFrameHandler(config.getFiles()));
                    }
                });
        config.getFiles().forEach((k, v) -> LOGGER.info("Server {} listening at {}:{}", k, v.getLabel(), v.getPath()));
        ChannelFuture f = b.bind(config.getPort())
                .addListener((o) -> LOGGER.info("Log viewer is successfully started on port {}", config.getPort()))
                .sync();
        f.channel().closeFuture().addListener(future -> {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        });
    }

    private static Config getServerLogConfig() {
        final var currentDir = System.getProperty("user.dir");
        final var path = currentDir + File.separator + "config.yaml";
        LOGGER.info("Loading config {}", path);
        final Yaml yaml = new Yaml();
        try (InputStream inputStream = new FileInputStream(path)) {
            return yaml.loadAs(inputStream, Config.class);
        } catch (IOException e) {
            LOGGER.error("Loading config {} error: {}", path, e.getMessage());
        }
        return null;
    }

    public static class HttpServerHandler extends SimpleChannelInboundHandler<HttpObject> {

        private final ByteBuf htmlContent;
        private final ByteBuf serverResponse;

        public HttpServerHandler(Map<String, Config.ServerConfig> files) {
            this.htmlContent = loadHtmlContent();
            this.serverResponse = loadServerResponse(files);
        }

        private ByteBuf loadServerResponse(Map<String, Config.ServerConfig> files) {
            final var servers = files.entrySet().stream().map(entry -> """
                            {
                                "value": "%s",
                                "label": "%s"
                            }
                            """.formatted(entry.getKey(), entry.getValue().getLabel()))
                    .collect(Collectors.joining(","));
            return Unpooled.copiedBuffer("[%s]".formatted(servers), StandardCharsets.UTF_8);
        }

        private ByteBuf loadHtmlContent() {
            return loadFile("./static/index.html");
        }

        private ByteBuf loadFile(String path) {
            try (var in = Main.class.getClassLoader().getResourceAsStream(path); var out = new ByteArrayOutputStream()) {
                if (in == null) {
                    throw new RuntimeException("File not found, " + path);
                }
                int index;
                var buf = new byte[1024];
                while ((index = in.read(buf)) != -1) {
                    out.write(buf, 0, index);
                }
                return Unpooled.copiedBuffer(out.toByteArray());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            if (msg instanceof HttpRequest request) {
                FullHttpResponse response;
                List<GenericFutureListener<ChannelFuture>> listeners = new ArrayList<>(List.of(ChannelFutureListener.CLOSE));
                if (request.method() == HttpMethod.GET && "/api/servers".equals(request.uri())) {
                    response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.OK,
                            serverResponse);
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, this.serverResponse.readableBytes());
                } else if (request.method() == HttpMethod.GET && request.uri().startsWith("/vs")) {
                    final var byteBuf = loadFile("./static" + request.uri());
                    response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.OK,
                            byteBuf);
                    final var type = request.uri().substring(request.uri().lastIndexOf(".")).toLowerCase();
                    String contentType = switch (type) {
                        case "css" -> "text/css";
                        case "js" -> "text/javascript";
                        case "ttf" -> "text/x-ttf";
                        default -> "text/plain";
                    };
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, byteBuf.readableBytes());
                    listeners.add((f) -> byteBuf.release());
                } else {
                    response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.OK,
                            this.htmlContent);
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, this.htmlContent.readableBytes());
                }
                final var channelFuture = ctx.writeAndFlush(response);
                for (final GenericFutureListener<ChannelFuture> listener : listeners) {
                    channelFuture.addListener(listener);
                }
            }
        }
    }

    public static class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

        private static final AttributeKey<String> WEB_SOCKET_FLAG = AttributeKey.valueOf("WebSocket");
        private final ReentrantLock lock = new ReentrantLock();
        private final FileWatcher watcher = new FileWatcher();
        private final Map<String, Set<Channel>> serverWatchingSessionMap = new HashMap<>();
        private final Map<Channel, ChannelFuture> channelLastFutureMap = new HashMap<>();
        private final Map<String, Config.ServerConfig> serverFilePath;

        public WebSocketFrameHandler(final Map<String, Config.ServerConfig> serverFilePath) {
            this.serverFilePath = serverFilePath;
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete e) {
                final var serverId = getQueryParam(URI.create(e.requestUri()), "serverId");
                final var numberStr = getQueryParam(URI.create(e.requestUri()), "number");
                if (serverId == null || !serverFilePath.containsKey(serverId) || numberStr == null) {
                    ctx.channel().close();
                    return;
                }
                lock.lock();
                try {
                    final var str = TailReader.readLastNLines(new File(this.serverFilePath.get(serverId).getPath())
                            , Integer.parseInt(numberStr));
                    StringBuilder builder = new StringBuilder();
                    for (final char c : str.toCharArray()) {
                        builder.append(c);
                        if (builder.length() >= 1024) {
                            sendMessage(ctx.channel(), builder.toString());
                            builder = new StringBuilder();
                        }
                    }
                    if (!builder.isEmpty()) {
                        ctx.channel().writeAndFlush(new TextWebSocketFrame(builder.toString()));
                    }
                    this.createWatcher(serverId);
                    this.serverWatchingSessionMap.computeIfAbsent(serverId, k -> new HashSet<>()).add(ctx.channel());
                } catch (Exception exception) {
                    ctx.channel().close();
                } finally {
                    lock.unlock();
                }
                ctx.channel().attr(WEB_SOCKET_FLAG);
            } else {
                super.userEventTriggered(ctx, evt);
            }
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            if (ctx.channel().hasAttr(WEB_SOCKET_FLAG)) {
                lock.lock();
                try {
                    final var iterator = this.serverWatchingSessionMap.entrySet().iterator();
                    while (iterator.hasNext()) {
                        final var entry = iterator.next();
                        if (entry.getValue().contains(ctx.channel())) {
                            entry.getValue().remove(ctx.channel());
                            this.channelLastFutureMap.remove(ctx.channel());
                            if (entry.getValue().isEmpty()) {
                                iterator.remove();
                                this.watcher.remove(new File(this.serverFilePath.get(entry.getKey()).getPath()));
                            }
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }
            super.channelInactive(ctx);
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame msg) {
        }

        private void sendMessage(Channel channel, String message) {
            final var frame = new TextWebSocketFrame(message);
            var future = this.channelLastFutureMap.get(channel);
            if (future != null) {
                future = future.addListener(f -> channel.writeAndFlush(frame));
            } else {
                future = channel.writeAndFlush(frame);
            }
            this.channelLastFutureMap.put(channel, future);
        }

        private String getQueryParam(final URI uri, final String paramName) {
            final var query = Optional.ofNullable(uri)
                    .map(URI::getQuery)
                    .orElse(null);
            if (query == null) {
                return null;
            }
            return Arrays.stream(query.split("&"))
                    .map(str -> str.split("="))
                    .filter(str -> str.length == 2)
                    .filter(str -> str[0].equals(paramName))
                    .map(str -> str[1])
                    .findFirst()
                    .orElse(null);
        }

        private void createWatcher(final String serverId) throws IOException {
            final var file = new File(this.serverFilePath.get(serverId).getPath());
            if (!watcher.contains(file)) {
                this.watcher.addListener(file, new Sender(serverId));
            }
        }

        public class Sender implements FileWatcher.FileChangeHandler {

            private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            private final CharBuffer charBuffer = CharBuffer.allocate(1024);
            private final String serverId;

            public Sender(final String serverId) {
                this.serverId = serverId;
            }

            @Override
            public void handle(final ByteBuffer buffer) {
                decoder.decode(buffer, charBuffer, false);
                charBuffer.flip();
                final var message = charBuffer.toString();
                charBuffer.clear();
                if (!message.isEmpty()) {
                    for (final Channel channel : WebSocketFrameHandler.this.serverWatchingSessionMap
                            .getOrDefault(serverId, Collections.emptySet())) {
                        sendMessage(channel, message);
                    }
                }
            }
        }
    }

    public static class Config {
        Integer port;
        Map<String, ServerConfig> files;

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public Map<String, ServerConfig> getFiles() {
            return files;
        }

        public void setFiles(Map<String, ServerConfig> files) {
            this.files = files;
        }

        public static class ServerConfig {
            String label;
            String path;

            public String getLabel() {
                return label;
            }

            public void setLabel(String label) {
                this.label = label;
            }

            public String getPath() {
                return path;
            }

            public void setPath(String path) {
                this.path = path;
            }
        }
    }
}