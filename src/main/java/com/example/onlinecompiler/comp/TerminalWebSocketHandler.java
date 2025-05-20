package com.example.onlinecompiler.comp;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TerminalWebSocketHandler extends TextWebSocketHandler {
    private volatile Process currentProcess;
    private volatile File tempFile; // Make tempFile volatile for thread safety
    private AtomicBoolean isWaitingForInput = new AtomicBoolean(false);

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        if (payload.startsWith("RUN ")) {
            if (currentProcess != null) {
                session.sendMessage(new TextMessage("Another execution is already running.\n"));
                return;
            }
            String[] parts = payload.substring(4).split(" ", 2);
            String language = parts[0];
            String code = parts[1];
            startExecution(session, language, code);
        } else if (payload.startsWith("INPUT ")) {
            if (currentProcess == null || !isWaitingForInput.get()) {
                session.sendMessage(new TextMessage("Not accepting input currently.\n"));
                return;
            }
            String input = payload.substring(6) + "\n";
            OutputStream stdin = currentProcess.getOutputStream();
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            isWaitingForInput.set(false);
        } else if (payload.equals("STOP")) {
            if (currentProcess != null) {
                currentProcess.destroy();
                try {
                    currentProcess.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    currentProcess.destroyForcibly();
                }
                session.sendMessage(new TextMessage("\nExecution terminated by user (Ctrl+C).\n"));
                currentProcess = null;
                // Clean up tempFile only once
                if (tempFile != null) {
                    tempFile.delete();
                    tempFile = null;
                }
                isWaitingForInput.set(false);
            }
        }
    }

    private void startExecution(WebSocketSession session, String language, String code) {
        new Thread(() -> {
            try {
                tempFile = createTempFile(language, code);
                currentProcess = startDockerProcess(language, tempFile);
                session.sendMessage(new TextMessage("Starting execution...\n"));

                // Thread for stdout
                Thread outputThread = new Thread(() -> {
                    try (InputStream stdout = currentProcess.getInputStream()) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = stdout.read(buffer)) != -1) {
                            String output = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                            session.sendMessage(new TextMessage(output));
                            if (output.trim().endsWith(":")) {
                                isWaitingForInput.set(true);
                            }
                        }
                    } catch (IOException e) {
                        try {
                            session.sendMessage(new TextMessage("Error reading output: " + e.getMessage() + "\n"));
                        } catch (IOException ex) {
                            // Log or ignore
                        }
                    }
                });
                outputThread.start();

                // Thread for stderr
                Thread errorThread = new Thread(() -> {
                    try (InputStream stderr = currentProcess.getErrorStream()) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = stderr.read(buffer)) != -1) {
                            String error = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                            session.sendMessage(new TextMessage("Error: " + error));
                        }
                    } catch (IOException e) {
                        try {
                            session.sendMessage(new TextMessage("Error reading stderr: " + e.getMessage() + "\n"));
                        } catch (IOException ex) {
                            // Log or ignore
                        }
                    }
                });
                errorThread.start();

                // Wait for process to finish
                int exitCode = currentProcess.waitFor();
                outputThread.join();
                errorThread.join();
                session.sendMessage(new TextMessage("\nExecution finished with exit code " + exitCode + "\n"));
                currentProcess = null;
                // Only delete if tempFile hasn't been cleaned up by STOP
                if (tempFile != null) {
                    tempFile.delete();
                    tempFile = null;
                }
            } catch (Exception e) {
                try {
                    session.sendMessage(new TextMessage("Error: " + e.getMessage() + "\n"));
                } catch (IOException ex) {
                    // Log or ignore
                }
                if (currentProcess != null) {
                    currentProcess.destroy();
                    currentProcess = null;
                }
                if (tempFile != null) {
                    tempFile.delete();
                    tempFile = null;
                }
            }
        }).start();
    }

    private File createTempFile(String language, String code) throws IOException {
        String suffix = language.equals("python") ? ".py" : language.equals("c") ? ".c" : ".cpp";
        File tempFile = File.createTempFile("code", suffix);
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(code);
        }
        return tempFile;
    }

    private Process startDockerProcess(String language, File tempFile) throws IOException {
        String mountPath = tempFile.getAbsolutePath().replace("\\", "/");
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("-i");
        command.add("--rm");
        command.add("--cpus=0.5");
        command.add("--memory=256m");
        command.add("--network=none");
        command.add("-v");
        command.add(mountPath + ":/code" + (language.equals("python") ? ".py" : language.equals("c") ? ".c" : ".cpp"));
        if (language.equals("python")) {
            command.add("python:3.9-slim");
            command.add("python");
            command.add("/code.py");
        } else if (language.equals("c")) {
            command.add("gcc:latest");
            command.add("sh");
            command.add("-c");
            command.add("gcc /code.c -o /code && /code");
        } else {
            command.add("gcc:latest");
            command.add("sh");
            command.add("-c");
            command.add("g++ /code.cpp -o /code && /code");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        return pb.start();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) throws Exception {
        if (currentProcess != null) {
            currentProcess.destroy();
            currentProcess = null;
        }
        if (tempFile != null) {
            tempFile.delete();
            tempFile = null;
        }
    }
}