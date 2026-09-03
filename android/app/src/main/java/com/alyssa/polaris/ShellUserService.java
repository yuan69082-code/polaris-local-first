package com.alyssa.polaris;

import android.content.Context;
import android.system.Os;

import androidx.annotation.Keep;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Keep
public class ShellUserService extends IShellService.Stub {

    private static final int MAX_OUTPUT_BYTES = 256 * 1024;
    private static final long COMMAND_TIMEOUT_SECONDS = 20;

    public ShellUserService() {
    }

    @Keep
    public ShellUserService(Context context) {
    }

    @Override
    public int uid() {
        return Os.getuid();
    }

    @Override
    public String[] exec(String command) {
        if (command == null || command.trim().isEmpty()) {
            return new String[]{"2", "", "命令不能为空"};
        }

        final Process process;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command).start();
        } catch (Throwable error) {
            return new String[]{"1", "", "启动 shell 失败：" + message(error)};
        }

        final ExecutorService readers = Executors.newFixedThreadPool(2);
        final Future<String> stdoutFuture = readers.submit(
                () -> readLimited(process.getInputStream())
        );
        final Future<String> stderrFuture = readers.submit(
                () -> readLimited(process.getErrorStream())
        );

        try {
            final boolean finished = process.waitFor(
                    COMMAND_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!finished) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }

                return new String[]{
                        "124",
                        readFuture(stdoutFuture),
                        "命令执行超时\n" + readFuture(stderrFuture)
                };
            }

            return new String[]{
                    String.valueOf(process.exitValue()),
                    readFuture(stdoutFuture),
                    readFuture(stderrFuture)
            };
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroy();
            return new String[]{"130", "", "命令执行被中断"};
        } finally {
            readers.shutdownNow();
        }
    }

    private static String readFuture(Future<String> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Throwable error) {
            return "";
        }
    }

    private static String readLimited(InputStream input) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int total = 0;
        boolean truncated = false;

        while (true) {
            final int read = input.read(buffer);
            if (read < 0) {
                break;
            }

            final int remaining = MAX_OUTPUT_BYTES - total;
            if (remaining <= 0) {
                truncated = true;
                break;
            }

            final int writeCount = Math.min(read, remaining);
            output.write(buffer, 0, writeCount);
            total += writeCount;

            if (writeCount < read || total >= MAX_OUTPUT_BYTES) {
                truncated = true;
                break;
            }
        }

        String text = output.toString(StandardCharsets.UTF_8.name());
        if (truncated) {
            text += "\n[输出已截断]";
        }
        return text;
    }

    private static String message(Throwable error) {
        final String value = error.getMessage();
        return value == null || value.isEmpty()
                ? error.getClass().getSimpleName()
                : value;
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
