package dev.skycraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import org.lwjgl.glfw.GLFW;

/**
 * Minecraft 1.21.1 input bridge.
 *
 * Skyrim's SKSE side sends USB-HID/SDL scancodes through the shared input ring.
 * 1.21.1 still uses GLFW, so translate those HID usages into GLFW key symbols and
 * drive vanilla KeyMapping state directly. That is enough for movement, jump,
 * sprint/sneak, attack/use, hotbar keys and mod keybinds while Skyrim owns focus.
 */
public final class InputBridge {
    private static final boolean[] DOWN = new boolean[512];
    private static boolean logged;

    private InputBridge() {}

    public static void drain(Minecraft minecraft) {
        SkyLink.drainInput((type, code, a, b, c) -> {
            switch (type) {
                case Proto.IN_KEY -> setKey(code, a != 0);
                case Proto.IN_MOUSE_BUTTON -> setMouse(code, a != 0);
                case Proto.IN_SCROLL -> scroll(minecraft, a);
                case Proto.IN_RELEASE_ALL -> releaseAll();
                case Proto.IN_OPEN_MENU -> {
                    releaseAll();
                    if (minecraft.screen == null) {
                        minecraft.setScreen(new PauseScreen(true));
                    }
                }
                default -> {
                    // Cursor/text/hurt are later parts of the complete original bridge.
                }
            }
        });

        if (!logged) {
            logged = true;
            SkyCraft.LOG.info("SkyCraft: Skyrim -> Minecraft KeyMapping input bridge active");
        }
    }

    public static void releaseAll() {
        for (int hid = 0; hid < DOWN.length; hid++) {
            if (DOWN[hid]) {
                DOWN[hid] = false;
                int glfw = hidToGlfw(hid);
                if (glfw != GLFW.GLFW_KEY_UNKNOWN) {
                    KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(glfw), false);
                }
            }
        }

        for (int button = 0; button <= GLFW.GLFW_MOUSE_BUTTON_8; button++) {
            KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(button), false);
        }
    }

    private static void setKey(int hid, boolean down) {
        if (hid <= 0 || hid >= DOWN.length) return;
        int glfw = hidToGlfw(hid);
        if (glfw == GLFW.GLFW_KEY_UNKNOWN) return;

        boolean was = DOWN[hid];
        DOWN[hid] = down;
        var key = InputConstants.Type.KEYSYM.getOrCreate(glfw);
        KeyMapping.set(key, down);
        if (down && !was) {
            KeyMapping.click(key);
        }
    }

    private static void setMouse(int sdlButton, boolean down) {
        int glfwButton = switch (sdlButton) {
            case 1 -> GLFW.GLFW_MOUSE_BUTTON_LEFT;
            case 2 -> GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
            case 3 -> GLFW.GLFW_MOUSE_BUTTON_RIGHT;
            case 4 -> GLFW.GLFW_MOUSE_BUTTON_4;
            case 5 -> GLFW.GLFW_MOUSE_BUTTON_5;
            default -> -1;
        };
        if (glfwButton < 0) return;

        var key = InputConstants.Type.MOUSE.getOrCreate(glfwButton);
        KeyMapping.set(key, down);
        if (down) {
            KeyMapping.click(key);
        }
    }

    private static void scroll(Minecraft minecraft, int wheel120) {
        if (minecraft.player == null || wheel120 == 0) return;
        int steps = wheel120 / 120;
        if (steps == 0) steps = Integer.signum(wheel120);
        minecraft.player.getInventory().swapPaint(steps);
    }

    /** USB HID keyboard usage (same numbers as SDL scancodes) -> GLFW key symbol. */
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
