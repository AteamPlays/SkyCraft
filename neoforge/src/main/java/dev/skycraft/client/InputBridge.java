package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.mixin.KeyboardHandlerInvoker;
import dev.skycraft.mixin.MouseHandlerInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import org.lwjgl.glfw.GLFW;

/**
 * Skyrim -> Minecraft 1.21.1 input bridge.
 *
 * Skyrim sends SDL/USB-style scan codes through the shared ring. Convert them to GLFW keys,
 * then feed Minecraft's real KeyboardHandler/MouseHandler callbacks. That preserves vanilla
 * inventory/chat/GUI behavior and also lets mod keybinds see the same input path as a real window.
 */
public final class InputBridge {
    private static final boolean[] DOWN = new boolean[512];
    private static final boolean[] MOUSE_DOWN = new boolean[8];
    private static boolean logged;

    private InputBridge() {}

    public static void drain(Minecraft minecraft) {
        SkyLink.drainInput((type, code, a, b, c) -> {
            switch (type) {
                case Proto.IN_KEY -> key(minecraft, code, a != 0);
                case Proto.IN_MOUSE_BUTTON -> mouseButton(minecraft, code, a != 0);
                case Proto.IN_SCROLL -> scroll(minecraft, a);
                case Proto.IN_CURSOR -> moveCursor(minecraft, a, b);
                case Proto.IN_TEXT -> text(minecraft, a);
                case Proto.IN_RELEASE_ALL -> releaseAll(minecraft);
                case Proto.IN_OPEN_MENU -> {
                    releaseAll(minecraft);
                    if (minecraft.screen == null) {
                        minecraft.setScreen(new PauseScreen(true));
                    }
                }
                default -> {
                }
            }
        });

        if (!logged) {
            logged = true;
            SkyCraft.LOG.info("SkyCraft: Skyrim -> Minecraft vanilla input replay active");
        }
    }

    public static void releaseAll() {
        releaseAll(Minecraft.getInstance());
    }

    public static void releaseAll(Minecraft minecraft) {
        if (minecraft == null) return;
        for (int hid = 0; hid < DOWN.length; hid++) {
            if (DOWN[hid]) {
                DOWN[hid] = false;
                sendKey(minecraft, hid, false);
            }
        }
        for (int i = 0; i < MOUSE_DOWN.length; i++) {
            if (MOUSE_DOWN[i]) {
                MOUSE_DOWN[i] = false;
                sendMouse(minecraft, i, false);
            }
        }
    }

    private static void key(Minecraft minecraft, int hid, boolean down) {
        if (hid <= 0 || hid >= DOWN.length) return;
        if (DOWN[hid] == down) return;
        DOWN[hid] = down;
        sendKey(minecraft, hid, down);
    }

    private static void sendKey(Minecraft minecraft, int hid, boolean down) {
        int glfw = hidToGlfw(hid);
        if (glfw == GLFW.GLFW_KEY_UNKNOWN) return;

        long window = minecraft.getWindow().getWindow();
        int scanCode = GLFW.glfwGetKeyScancode(glfw);
        int action = down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE;
        ((KeyboardHandlerInvoker) minecraft.keyboardHandler)
            .skycraft$keyPress(window, glfw, scanCode, action, modifiers());
    }

    private static void text(Minecraft minecraft, int codePoint) {
        if (codePoint <= 0) return;
        ((KeyboardHandlerInvoker) minecraft.keyboardHandler)
            .skycraft$charTyped(minecraft.getWindow().getWindow(), codePoint, modifiers());
    }

    private static void mouseButton(Minecraft minecraft, int sdlButton, boolean down) {
        int glfwButton = switch (sdlButton) {
            case 1 -> GLFW.GLFW_MOUSE_BUTTON_LEFT;
            case 2 -> GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
            case 3 -> GLFW.GLFW_MOUSE_BUTTON_RIGHT;
            case 4 -> GLFW.GLFW_MOUSE_BUTTON_4;
            case 5 -> GLFW.GLFW_MOUSE_BUTTON_5;
            default -> -1;
        };
        if (glfwButton < 0 || glfwButton >= MOUSE_DOWN.length) return;
        if (MOUSE_DOWN[glfwButton] == down) return;
        MOUSE_DOWN[glfwButton] = down;
        sendMouse(minecraft, glfwButton, down);
    }

    private static void sendMouse(Minecraft minecraft, int glfwButton, boolean down) {
        int action = down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE;
        ((MouseHandlerInvoker) minecraft.mouseHandler)
            .skycraft$onPress(minecraft.getWindow().getWindow(), glfwButton, action, modifiers());
    }

    private static void scroll(Minecraft minecraft, int wheel120) {
        if (wheel120 == 0) return;
        double steps = wheel120 / 120.0;
        ((MouseHandlerInvoker) minecraft.mouseHandler)
            .skycraft$onScroll(minecraft.getWindow().getWindow(), 0.0, steps);
    }

    private static void moveCursor(Minecraft minecraft, int x, int y) {
        ((MouseHandlerInvoker) minecraft.mouseHandler)
            .skycraft$onMove(minecraft.getWindow().getWindow(), x, y);
    }

    private static int modifiers() {
        int mods = 0;
        if (down(224) || down(228)) mods |= GLFW.GLFW_MOD_CONTROL;
        if (down(225) || down(229)) mods |= GLFW.GLFW_MOD_SHIFT;
        if (down(226) || down(230)) mods |= GLFW.GLFW_MOD_ALT;
        if (down(227) || down(231)) mods |= GLFW.GLFW_MOD_SUPER;
        return mods;
    }

    private static boolean down(int hid) {
        return hid >= 0 && hid < DOWN.length && DOWN[hid];
    }

    /** Used by InputConstantsMixin when vanilla polls GLFW keyboard state directly. */
    public static boolean isGlfwKeyDown(int glfwKey) {
        for (int hid = 1; hid < DOWN.length; hid++) {
            if (DOWN[hid] && hidToGlfw(hid) == glfwKey) {
                return true;
            }
        }
        return false;
    }

    /** USB HID keyboard usage / SDL scancode -> GLFW key symbol. */
    private static int hidToGlfw(int h) {
        if (h >= 4 && h <= 29) return GLFW.GLFW_KEY_A + (h - 4);
        if (h >= 30 && h <= 38) return GLFW.GLFW_KEY_1 + (h - 30);
        if (h == 39) return GLFW.GLFW_KEY_0;
        if (h >= 58 && h <= 69) return GLFW.GLFW_KEY_F1 + (h - 58);
        if (h >= 89 && h <= 97) return GLFW.GLFW_KEY_KP_1 + (h - 89);

        return switch (h) {
            case 40 -> GLFW.GLFW_KEY_ENTER;
            case 41 -> GLFW.GLFW_KEY_ESCAPE;
            case 42 -> GLFW.GLFW_KEY_BACKSPACE;
            case 43 -> GLFW.GLFW_KEY_TAB;
            case 44 -> GLFW.GLFW_KEY_SPACE;
            case 45 -> GLFW.GLFW_KEY_MINUS;
            case 46 -> GLFW.GLFW_KEY_EQUAL;
            case 47 -> GLFW.GLFW_KEY_LEFT_BRACKET;
            case 48 -> GLFW.GLFW_KEY_RIGHT_BRACKET;
            case 49 -> GLFW.GLFW_KEY_BACKSLASH;
            case 51 -> GLFW.GLFW_KEY_SEMICOLON;
            case 52 -> GLFW.GLFW_KEY_APOSTROPHE;
            case 53 -> GLFW.GLFW_KEY_GRAVE_ACCENT;
            case 54 -> GLFW.GLFW_KEY_COMMA;
            case 55 -> GLFW.GLFW_KEY_PERIOD;
            case 56 -> GLFW.GLFW_KEY_SLASH;
            case 57 -> GLFW.GLFW_KEY_CAPS_LOCK;
            case 70 -> GLFW.GLFW_KEY_PRINT_SCREEN;
            case 71 -> GLFW.GLFW_KEY_SCROLL_LOCK;
            case 72 -> GLFW.GLFW_KEY_PAUSE;
            case 73 -> GLFW.GLFW_KEY_INSERT;
            case 74 -> GLFW.GLFW_KEY_HOME;
            case 75 -> GLFW.GLFW_KEY_PAGE_UP;
            case 76 -> GLFW.GLFW_KEY_DELETE;
            case 77 -> GLFW.GLFW_KEY_END;
            case 78 -> GLFW.GLFW_KEY_PAGE_DOWN;
            case 79 -> GLFW.GLFW_KEY_RIGHT;
            case 80 -> GLFW.GLFW_KEY_LEFT;
            case 81 -> GLFW.GLFW_KEY_DOWN;
            case 82 -> GLFW.GLFW_KEY_UP;
            case 83 -> GLFW.GLFW_KEY_NUM_LOCK;
            case 84 -> GLFW.GLFW_KEY_KP_DIVIDE;
            case 85 -> GLFW.GLFW_KEY_KP_MULTIPLY;
            case 86 -> GLFW.GLFW_KEY_KP_SUBTRACT;
            case 87 -> GLFW.GLFW_KEY_KP_ADD;
            case 88 -> GLFW.GLFW_KEY_KP_ENTER;
            case 98 -> GLFW.GLFW_KEY_KP_0;
            case 99 -> GLFW.GLFW_KEY_KP_DECIMAL;
            case 100 -> GLFW.GLFW_KEY_WORLD_2;
            case 101 -> GLFW.GLFW_KEY_MENU;
            case 224 -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case 225 -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case 226 -> GLFW.GLFW_KEY_LEFT_ALT;
            case 227 -> GLFW.GLFW_KEY_LEFT_SUPER;
            case 228 -> GLFW.GLFW_KEY_RIGHT_CONTROL;
            case 229 -> GLFW.GLFW_KEY_RIGHT_SHIFT;
            case 230 -> GLFW.GLFW_KEY_RIGHT_ALT;
            case 231 -> GLFW.GLFW_KEY_RIGHT_SUPER;
            default -> GLFW.GLFW_KEY_UNKNOWN;
        };
    }
}
