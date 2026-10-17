package de.keksuccino.rinku;

import net.minecraft.client.Minecraft;

import static org.lwjgl.glfw.GLFW.*;

/**
 * GTWebUI fork (M15-14): the hidden GL context the CEF thread imports shared textures in, shared with the game's. The
 * only part of the shared texture path that differs per Minecraft version: GLFW here, SDL from 26.3.
 */
final class RinkuSharedContext {

    private static long window;

    private RinkuSharedContext() {}

    /** Render thread, game context current: creates the context once; false when that failed. */
    static boolean create() {
        if (window != 0) return true;
        long game = Minecraft.getInstance().getWindow().getWindow();
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CLIENT_API, glfwGetWindowAttrib(game, GLFW_CLIENT_API));
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, glfwGetWindowAttrib(game, GLFW_CONTEXT_VERSION_MAJOR));
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, glfwGetWindowAttrib(game, GLFW_CONTEXT_VERSION_MINOR));
        glfwWindowHint(GLFW_OPENGL_PROFILE, glfwGetWindowAttrib(game, GLFW_OPENGL_PROFILE));
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, glfwGetWindowAttrib(game, GLFW_OPENGL_FORWARD_COMPAT));
        window = glfwCreateWindow(1, 1, "Rinku shared texture", 0L, game);
        glfwDefaultWindowHints();
        return window != 0;
    }

    /** "GL 3.3" of the game's context, for the log. Render thread. */
    static String describe() {
        long game = Minecraft.getInstance().getWindow().getWindow();
        return "GL " + glfwGetWindowAttrib(game, GLFW_CONTEXT_VERSION_MAJOR) + "." + glfwGetWindowAttrib(game, GLFW_CONTEXT_VERSION_MINOR);
    }

    /** The calling thread (the CEF thread, once) makes the context current. */
    static void makeCurrent() {
        glfwMakeContextCurrent(window);
    }
}
