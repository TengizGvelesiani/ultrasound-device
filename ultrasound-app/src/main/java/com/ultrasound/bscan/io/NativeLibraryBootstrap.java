package com.ultrasound.bscan.io;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Robust native library bootstrap for jSerialComm.
 * Fixes the jSerialComm 2.11.0 bug on Windows x86_64 where it attempts to extract
 * Windows/aarch64 first and then fails with Access is denied.
 * By placing the verified x86_64 binary at the expected temp location before
 * SerialPort is initialized, jSerialComm loads the AMD64 DLL on the first check.
 */
public final class NativeLibraryBootstrap {

    private static volatile boolean initialized = false;

    private NativeLibraryBootstrap() {}

    public static synchronized void prepareNativeLibraries() {
        if (initialized) {
            return;
        }

        try {
            String osName = System.getProperty("os.name", "").toLowerCase();
            String osArch = System.getProperty("os.arch", "").toLowerCase();

            if (osName.contains("win")) {
                boolean isAmd64 = osArch.contains("64") && !osArch.contains("arm") && !osArch.contains("aarch");
                String resourcePath = isAmd64 ? "/Windows/x86_64/jSerialComm.dll" : "/Windows/aarch64/jSerialComm.dll";

                // Expected jSerialComm temp directory:
                // <tempDir>/jSerialComm/<appid>/<version>/jSerialComm.dll
                String tempDir = System.getProperty("java.io.tmpdir");
                String appid = System.getProperty("fazecast.jSerialComm.appid", ".");
                String version = "2.11.0";

                File targetDir = new File(tempDir, "jSerialComm" + File.separator + appid + File.separator + version);
                targetDir.mkdirs();

                File targetDll = new File(targetDir, "jSerialComm.dll");

                // Check if the file is missing or has the wrong (ARM64) size: 194048 is ARM64, 210432 is x86_64
                if (!targetDll.exists() || (isAmd64 && targetDll.length() != 210432)) {
                    if (targetDll.exists()) {
                        targetDll.setWritable(true, false);
                        targetDll.delete();
                    }

                    try (InputStream in = NativeLibraryBootstrap.class.getResourceAsStream(resourcePath)) {
                        if (in != null) {
                            Files.copy(in, targetDll.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            targetDll.setReadable(true, false);
                            targetDll.setWritable(true, true);
                            targetDll.setExecutable(true, false);
                        }
                    }
                }

                // Also place in user.home/.jSerialComm/2.11.0 as secondary candidate
                try {
                    File homeDir = new File(System.getProperty("user.home"), ".jSerialComm" + File.separator + version);
                    homeDir.mkdirs();
                    File homeDll = new File(homeDir, "jSerialComm.dll");
                    if (!homeDll.exists() || (isAmd64 && homeDll.length() != 210432)) {
                        if (homeDll.exists()) {
                            homeDll.setWritable(true, false);
                            homeDll.delete();
                        }
                        if (targetDll.exists()) {
                            Files.copy(targetDll.toPath(), homeDll.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable t) {
            System.err.println("[NativeBootstrap] Notice: " + t.getMessage());
        } finally {
            initialized = true;
        }
    }
}
