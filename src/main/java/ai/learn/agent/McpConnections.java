package ai.learn.agent;

import ai.koog.agents.core.tools.ToolBase;
import ai.koog.agents.core.tools.ToolRegistry;
import ai.koog.agents.mcp.DefaultMcpToolDescriptorParser;
import ai.koog.agents.mcp.McpToolRegistryProvider;
import ai.koog.agents.mcp.McpToolRegistryProvider_jvmKt;
import ai.koog.agents.mcp.metadata.McpServerInfo;
import com.agentclientprotocol.sdk.spec.AcpSchema.EnvVariable;
import com.agentclientprotocol.sdk.spec.AcpSchema.HttpHeader;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServer;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerHttp;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerSse;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerStdio;
import io.ktor.client.HttpClient;
import io.ktor.client.HttpClientKt;
import io.ktor.client.engine.cio.CIO;
import io.ktor.client.plugins.DefaultRequestKt;
import io.ktor.client.plugins.sse.SSEKt;
import io.modelcontextprotocol.kotlin.sdk.types.Implementation;
import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The MCP servers a session was given by the ACP client in {@code session/new} — with JetBrains AIR
 * and {@code use_idea_mcp}, that includes the IDE's own MCP server — connected through Koog's MCP
 * support, with their tools ready to hand to the Koog agent.
 *
 * <p>A server that fails to connect is skipped (and logged to stderr) so the session still starts.
 */
final class McpConnections implements AutoCloseable {

    private static final long CONNECT_TIMEOUT_SECONDS = 30;
    private static final Implementation CLIENT_INFO =
            new Implementation("teaching-agent", "1.0", null, null, null);

    /** A connected server: its name and the tools it contributed. */
    record Connected(String name, List<String> toolNames) {
    }

    private final List<ToolBase<?, ?>> tools = new ArrayList<>();
    private final List<Connected> connected = new ArrayList<>();
    private final List<AutoCloseable> resources = new ArrayList<>();

    private McpConnections() {
    }

    /**
     * Connects to every server, keeping only tools whose names are not in {@code reservedNames}
     * (the agent's own tools) or already taken by an earlier server.
     */
    static McpConnections connect(List<McpServer> servers, Set<String> reservedNames) {
        McpConnections mcp = new McpConnections();
        if (servers == null) {
            return mcp;
        }
        Set<String> taken = new HashSet<>(reservedNames);
        for (McpServer server : servers) {
            String name = serverName(server);
            try {
                ToolRegistry registry = CompletableFuture.supplyAsync(() -> mcp.open(server))
                        .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                List<String> added = new ArrayList<>();
                for (ToolBase<?, ?> tool : registry.getTools()) {
                    if (taken.add(tool.getName())) {
                        mcp.tools.add(tool);
                        added.add(tool.getName());
                        System.err.println("[mcp] " + name + " tool: " + tool.getDescriptor());
                    } else {
                        System.err.println("[mcp] " + name + ": skipping tool '" + tool.getName()
                                + "' (name already in use)");
                    }
                }
                mcp.connected.add(new Connected(name, added));
                System.err.println("[mcp] connected to " + name + " (" + added.size() + " tools)");
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                System.err.println("[mcp] could not connect to " + name + ": " + cause);
            }
        }
        return mcp;
    }

    List<ToolBase<?, ?>> tools() {
        return tools;
    }

    List<Connected> connected() {
        return connected;
    }

    boolean hasTool(String toolName) {
        return tools.stream().anyMatch(t -> t.getName().equals(toolName));
    }

    @Override
    public void close() {
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
        resources.clear();
    }

    // ---------------------------------------------------------------- transports

    private ToolRegistry open(McpServer server) {
        try {
            if (server instanceof McpServerStdio stdio) {
                return openStdio(stdio);
            } else if (server instanceof McpServerHttp http) {
                HttpClient client = httpClient(http.headers());
                resources.add(client);
                return blocking((scope, cont) -> McpToolRegistryProvider.INSTANCE.streamableHttp(cfg -> {
                    cfg.setUrl(http.url());
                    cfg.setHttpClient(client);
                    cfg.setName(CLIENT_INFO.getName());
                    return Unit.INSTANCE;
                }, cont));
            } else if (server instanceof McpServerSse sse) {
                HttpClient client = httpClient(sse.headers());
                resources.add(client);
                var transport = McpToolRegistryProvider.INSTANCE.defaultSseTransport(sse.url(), client);
                return blocking((scope, cont) -> McpToolRegistryProvider.INSTANCE.fromTransport(
                        transport, new McpServerInfo(),
                        DefaultMcpToolDescriptorParser.INSTANCE,
                        CLIENT_INFO.getName(), CLIENT_INFO.getVersion(), cont));
            }
            throw new IllegalArgumentException("Unsupported MCP server type: " + server.getClass().getSimpleName());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ToolRegistry openStdio(McpServerStdio stdio) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(stdio.command());
        if (stdio.args() != null) {
            command.addAll(stdio.args());
        }
        ProcessBuilder pb = new ProcessBuilder(command)
                // Its stderr goes to ours (the IDE's agent log); never to our stdout, which is the ACP channel.
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        if (stdio.env() != null) {
            for (EnvVariable env : stdio.env()) {
                pb.environment().put(env.name(), env.value());
            }
        }
        Process process = pb.start();
        resources.add(process::destroy);
        return blocking((scope, cont) -> McpToolRegistryProvider_jvmKt.fromProcess(
                McpToolRegistryProvider.INSTANCE, process, CLIENT_INFO,
                DefaultMcpToolDescriptorParser.INSTANCE, cont));
    }

    private static HttpClient httpClient(List<HttpHeader> headers) {
        return HttpClientKt.HttpClient(CIO.INSTANCE, cfg -> {
            cfg.install(SSEKt.getSSE(), sse -> Unit.INSTANCE);
            if (headers != null && !headers.isEmpty()) {
                DefaultRequestKt.defaultRequest(cfg, req -> {
                    for (HttpHeader h : headers) {
                        req.getHeaders().append(h.name(), h.value());
                    }
                    return Unit.INSTANCE;
                });
            }
            return Unit.INSTANCE;
        });
    }

    /** Runs a Koog suspend function to completion on this thread. */
    private static ToolRegistry blocking(
            kotlin.jvm.functions.Function2<kotlinx.coroutines.CoroutineScope,
                    kotlin.coroutines.Continuation<? super ToolRegistry>, Object> block) throws Exception {
        return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, block);
    }

    private static String serverName(McpServer server) {
        if (server instanceof McpServerStdio s) return s.name();
        if (server instanceof McpServerHttp s) return s.name();
        if (server instanceof McpServerSse s) return s.name();
        return String.valueOf(server);
    }
}
