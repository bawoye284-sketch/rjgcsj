package com.star.service.risk;

import com.star.Application;
import com.star.config.DiseaseRiskProperties;
import com.star.config.ModelRuntimeProperties;
import com.star.service.disease.DiseaseRiskContract;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.lang.ProcessHandle;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/** Owns only the inference process it starts; existing compatible services may be shared. */
@Component
@Slf4j
public class ModelRuntime implements SmartLifecycle {
    private final ModelRuntimeProperties options;
    private final DiseaseRiskProperties diseases;
    private final String personalUrl;
    private final RestTemplate health;
    private final Object processLock = new Object();
    private final String ownerToken = UUID.randomUUID().toString();
    private volatile boolean running;
    private volatile boolean managed;
    private volatile Process process;
    private volatile ScheduledExecutorService supervisor;
    private volatile String state = "NOT_STARTED";
    private volatile String error = "";
    private volatile boolean ownsReadyProcess;
    private URI endpoint;

    public ModelRuntime(ModelRuntimeProperties options, DiseaseRiskProperties diseases,
                        @Value("${risk-model.service-url:http://127.0.0.1:8001/predict}") String personalUrl) {
        this.options = options; this.diseases = diseases; this.personalUrl = personalUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(700); factory.setReadTimeout(1500);
        this.health = new RestTemplate(factory);
    }

    @Override public void start() {
        if (running) return;
        running = true;
        try {
            endpoint = URI.create(diseases.getServiceBaseUrl().replaceAll("/+$", ""));
            managed = options.isAutoStart() && "http".equalsIgnoreCase(endpoint.getScheme())
                    && Arrays.asList("127.0.0.1", "localhost", "[::1]", "::1").contains(endpoint.getHost())
                    && (endpoint.getPath() == null || endpoint.getPath().isEmpty());
            if (!managed) {
                state = "EXTERNAL";
                log.info("模型自动启动已关闭或地址为外部服务，使用现有推理地址");
                return;
            }
            attemptStart();
        } catch (Exception ex) {
            unavailable(ex.getMessage());
        }
        if (managed && running) {
            supervisor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "ihms-model-supervisor"); thread.setDaemon(true); return thread;
            });
            supervisor.scheduleWithFixedDelay(() -> {
                if (!running) return;
                try { attemptStart(); } catch (Exception ex) { unavailable(ex.getMessage()); }
            }, Math.max(2, options.getCheckIntervalSeconds()), Math.max(2, options.getCheckIntervalSeconds()), TimeUnit.SECONDS);
        }
    }

    private boolean sameHost(String first, String second) {
        return Objects.equals(first, second) || (Arrays.asList("127.0.0.1", "localhost").contains(first)
                && Arrays.asList("127.0.0.1", "localhost").contains(second));
    }
    private int port(URI uri) { return uri.getPort() < 0 ? 80 : uri.getPort(); }
    private String host() { return endpoint.getHost().replace("[", "").replace("]", ""); }

    private Map<?, ?> probe() {
        try { return health.getForObject(endpoint.toString() + "/health", Map.class); }
        catch (RuntimeException ex) { return Collections.emptyMap(); }
    }
    private boolean compatible(Map<?, ?> response) {
        if (response == null || !"unified-health-risk".equals(response.get("service"))
                || !Boolean.TRUE.equals(response.get("ok"))) return false;
        Object p = response.get("personalized"), d = response.get("chronic");
        if (!(p instanceof Map) || !(d instanceof Map)) return false;
        Map<?, ?> chronic = (Map<?, ?>) d;
        Object count = chronic.get("ready_count"), codes = chronic.get("ready_diseases");
        return Boolean.TRUE.equals(((Map<?, ?>) p).get("model_ready"))
                && diseases.getExpectedModelVersion().equals(chronic.get("model_version"))
                && count instanceof Number && ((Number) count).intValue() == 5
                && codes instanceof Collection
                && new HashSet<>((Collection<?>) codes).equals(new HashSet<>(DiseaseRiskContract.CODES));
    }

    private void ready(Map<?, ?> response) {
        boolean owned = process != null && process.isAlive() && ownerToken.equals(response.get("runtime_owner"));
        if (!owned && process != null) stopOwnedProcess();
        if (!"READY".equals(state) || ownsReadyProcess != owned)
            log.info("六个模型已就绪：个性化模型1个、慢性病模型5个；{}，版本{}",
                    owned ? "由本后端自动管理" : "复用已就绪的本地模型进程", diseases.getExpectedModelVersion());
        ownsReadyProcess = owned; state = "READY"; error = "";
    }

    private void attemptStart() throws Exception {
        if (!running) return;
        URI personal = URI.create(personalUrl);
        if (!sameHost(personal.getHost(), endpoint.getHost()) || port(personal) != port(endpoint)
                || !"http".equalsIgnoreCase(personal.getScheme()) || !"/predict".equals(personal.getPath()))
            throw new IllegalArgumentException("托管模式要求个性化和慢性病地址指向同一服务，请核对risk-model与disease-risk配置");
        Map<?, ?> response = probe();
        if (compatible(response)) { ready(response); return; }
        // A live process with a temporarily slow health response gets another probe before restart.
        if (process != null && process.isAlive()) {
            response = probe();
            if (compatible(response)) { ready(response); return; }
            stopOwnedProcess();
        }
        if (!running) return;
        try (ServerSocket check = new ServerSocket()) {
            check.setReuseAddress(false); check.bind(new InetSocketAddress(InetAddress.getByName(host()), port(endpoint)));
        } catch (IOException occupied) {
            unavailable("模型端口" + port(endpoint) + "已被占用，但六个模型未就绪或版本不符；请检查该端口的进程");
            return;
        }
        Path root = projectRoot();
        Path python = python(root);
        Path logFile = root.resolve("IHMS-admin/logs/model-runtime.log");
        Files.createDirectories(logFile.getParent());
        ProcessBuilder builder = new ProcessBuilder(python.toString(), "-B",
                root.resolve("risk-service/managed_runtime.py").toString(),
                "--parent-pid", String.valueOf(ProcessHandle.current().pid()),
                "--host", host(), "--port", String.valueOf(port(endpoint)));
        builder.directory(root.toFile()); builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("CHRONIC_MODEL_VERSION", diseases.getExpectedModelVersion());
        builder.environment().put("IHMS_MODEL_OWNER", ownerToken);
        synchronized (processLock) {
            if (!running) return;
            state = "STARTING"; ownsReadyProcess = false;
            process = builder.start();
        }
        log.info("后端正在加载六个模型，推理日志：{}", logFile);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(5, options.getStartupTimeoutSeconds()));
        while (running && System.nanoTime() < deadline) {
            response = probe();
            if (compatible(response)) { ready(response); return; }
            Process child = process;
            if (child == null || child.waitFor(300, TimeUnit.MILLISECONDS))
                throw new IllegalStateException("模型进程启动失败，请检查IHMS-admin/logs/model-runtime.log中的Python环境和权重错误");
        }
        stopOwnedProcess();
        if (running) throw new IllegalStateException("六个模型加载超时，请检查IHMS-admin/logs/model-runtime.log");
    }

    private Path projectRoot() {
        if (options.getProjectRoot() != null && !options.getProjectRoot().trim().isEmpty()) {
            Path configured = Paths.get(options.getProjectRoot()).toAbsolutePath().normalize();
            if (isProject(configured)) return configured;
            throw new IllegalArgumentException("model-runtime.project-root目录缺少risk-service或chronic-disease");
        }
        for (Path origin : Arrays.asList(Paths.get("").toAbsolutePath(), new ApplicationHome(Application.class).getDir().toPath())) {
            Path current = origin.toAbsolutePath().normalize();
            for (int i = 0; current != null && i < 8; i++, current = current.getParent())
                if (isProject(current)) return current;
        }
        throw new IllegalStateException("找不到模型工程目录，请设置IHMS_PROJECT_ROOT为包含risk-service与chronic-disease的目录");
    }
    private boolean isProject(Path root) {
        return Files.isRegularFile(root.resolve("risk-service/managed_runtime.py"))
                && Files.isRegularFile(root.resolve("risk-service/unified_app.py"))
                && Files.isRegularFile(root.resolve("chronic-disease/src/api.py"));
    }
    private Path python(Path root) {
        if (options.getPythonExecutable() != null && !options.getPythonExecutable().trim().isEmpty()) {
            Path configured = Paths.get(options.getPythonExecutable());
            if (!configured.isAbsolute()) configured = root.resolve(configured);
            if (Files.isRegularFile(configured)) return configured.normalize();
            throw new IllegalStateException("model-runtime.python-executable指定的Python不存在");
        }
        Path candidate = root.resolve("chronic-disease/.venv/" +
                (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "Scripts/python.exe" : "bin/python"));
        if (!Files.isRegularFile(candidate)) throw new IllegalStateException("缺少chronic-disease/.venv，请先按运行说明安装模型依赖");
        return candidate;
    }
    private void unavailable(String message) {
        if (!running) return;
        String safeMessage = message == null ? "模型环境未就绪，请查看后端日志" : message;
        if (!safeMessage.equals(error)) log.warn("六个模型暂不可用：{}；后端保留档案和历史查询功能", safeMessage);
        error = safeMessage; state = "UNAVAILABLE"; ownsReadyProcess = false;
    }

    public void requireReady() {
        if (managed && (!"READY".equals(state) || (ownsReadyProcess && (process == null || !process.isAlive()))))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "STARTING".equals(state) ? "后端正在加载六个模型，请稍后重试" : "后端模型暂未就绪，正在检查恢复；请查看后端日志");
    }
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("mode", managed ? (ownsReadyProcess ? "backend-managed" : "shared-local") : "external");
        status.put("state", state); status.put("ready", managed ? "READY".equals(state) : null);
        status.put("autoStart", options.isAutoStart()); status.put("modelCount", 6);
        return status;
    }

    private void stopOwnedProcess() {
        Process child;
        synchronized (processLock) { child = process; process = null; ownsReadyProcess = false; }
        if (child == null) return;
        List<ProcessHandle> descendants = child.toHandle().descendants().collect(Collectors.toList());
        Collections.reverse(descendants);
        descendants.forEach(ProcessHandle::destroy); child.destroy();
        try { child.waitFor(Math.max(1, options.getShutdownTimeoutSeconds()), TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (child.isAlive()) child.destroyForcibly();
    }
    @Override public void stop() {
        running = false;
        ScheduledExecutorService executor = supervisor;
        if (executor != null) executor.shutdownNow();
        stopOwnedProcess(); state = "STOPPED";
    }
    @Override public void stop(@NonNull Runnable callback) { try { stop(); } finally { callback.run(); } }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return 0; }
}
