package com.badlogic.NHSS.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.NHSS.Main;

public class Lwjgl3Launcher {
    public static void main(String[] args) {
        if (StartupHelper.startNewJvmIfRequired()) return; // macOS/Windows helper
        createApplication();
    }

    private static Lwjgl3Application createApplication() {
        return new Lwjgl3Application(new Main(), getDefaultConfiguration());
    }

    private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("NerdHavenStoreSign");

        // 🔑 Prevent GLFW from minimizing when focus changes during desktop startup
        configuration.setAutoIconify(false);

        // Fullscreen is usually most robust on the Pi
        configuration.setDecorated(false);
        configuration.setFullscreenMode(Lwjgl3ApplicationConfiguration.getDisplayMode());

        configuration.useVsync(true);
        configuration.setForegroundFPS(30);

        configuration.setWindowIcon("libgdx128.png", "libgdx64.png", "libgdx32.png", "libgdx16.png");
        return configuration;
    }
}
