package de.keksuccino.rinku;

import net.minecraft.client.Minecraft;

import static org.lwjgl.sdl.SDLVideo.*;

/**
 * GTWebUI fork (M15-14): the hidden GL context the CEF thread imports shared textures in, shared with the game's. The
 * only part of the shared texture path that differs per Minecraft version: 26.3 makes its window and context with
 * SDL, so this is a hidden SDL window with a context created while the game's is current (the GL attributes the game
 * set before its window are still in force: same version, profile and pixel format).
 */
final class RinkuSharedContext {

    private static long window;
    private static long context;

    private RinkuSharedContext() {}

    /** Render thread, game context current: creates the context once; false when that failed. */
    static boolean create() {
        if (context != 0) return true;
        long game = Minecraft.getInstance().getWindow().handle();
        long gameContext = SDL_GL_GetCurrentContext();
        if (gameContext == 0) return false;
        window = SDL_CreateWindow("Rinku shared texture", 1, 1, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
        if (window == 0) return false;
        SDL_GL_SetAttribute(SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 1);
        context = SDL_GL_CreateContext(window);   // current on this thread now
        SDL_GL_SetAttribute(SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 0);
        SDL_GL_MakeCurrent(game, gameContext);
        if (context == 0) {
            SDL_DestroyWindow(window);
            window = 0;
            return false;
        }
        return true;
    }

    /** "GL 3.3" of the shared context, for the log. Render thread. */
    static String describe() {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer major = stack.mallocInt(1), minor = stack.mallocInt(1);
            SDL_GL_GetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, major);
            SDL_GL_GetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, minor);
            return "GL " + major.get(0) + "." + minor.get(0) + ", SDL";
        }
    }

    /** The calling thread (the CEF thread, once) makes the context current. */
    static void makeCurrent() {
        SDL_GL_MakeCurrent(window, context);
    }
}
