package ink.hoka.odyssey.client.model;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * 0.69 Окно настройки предмета-модели в руке (только админам: открывает сервер, /hoitem edit). Мир виден за окном —
 * правки сразу на своей руке (1-е лицо) или на себе со стороны (3-е лицо); «Сохранить» — на сервер, всем игрокам.
 * Протокол ядра op 10: S2C — UTF id, UTF путь модели (открыть окно); C2S — UTF id, UTF json настроек.
 */
public final class HeldEditor extends Screen {

    static final int OP = 10;
    static HeldModels.Def editing;
    static String editingId;

    private static final int PW = 176;
    private static final String[] VIEWS = {"1-е лицо", "3-е лицо", "Общее"};
    private static final String[][] SECTIONS = {{"Модель", "Рука 1", "Рука 2"}, {"Модель", "Рука 1", "Рука 2"}, {}};

    private final String id;
    private final HeldModels.Def def, original;
    private final CameraType camera;
    private int view, section;
    private String status = "";

    private HeldEditor(String id, HeldModels.Def def) {
        super(Component.literal("Настройка: " + id));
        this.id = id;
        this.def = def;
        this.original = HeldModels.copy(def);
        this.camera = Minecraft.getInstance().options.getCameraType();
    }

    /** S2C op 10. */
    public static void handle(DataInputStream in) throws IOException {
        String id = in.readUTF(), model = in.readUTF();
        Minecraft.getInstance().execute(() -> {
            HeldModels.Def cur = HeldModels.get(id);
            HeldModels.Def d = cur != null ? HeldModels.copy(cur) : new HeldModels.Def();
            if (d.model.isEmpty()) d.model = model;
            if (d.anim.isEmpty()) { List<String> a = Models.animations(d.model); if (!a.isEmpty()) d.anim = a.get(0); }
            Minecraft.getInstance().setScreen(new HeldEditor(id, d));
        });
    }

    @Override
    protected void init() {
        editing = def;
        editingId = id;
        HeldModels.put(id, def);
        Minecraft.getInstance().options.setCameraType(view == 1 ? CameraType.THIRD_PERSON_FRONT : CameraType.FIRST_PERSON);
        int x = width - PW - 6, y = 22;
        int tw = (PW - 4) / 3;
        for (int i = 0; i < 3; i++) {
            int v = i;
            Button b = Button.builder(Component.literal(VIEWS[i]), btn -> { view = v; section = 0; rebuildWidgets(); })
                    .bounds(x + i * (tw + 2), y, tw, 18).build();
            b.active = view != i;
            addRenderableWidget(b);
        }
        y += 22;
        String[] secs = SECTIONS[view];
        for (int i = 0; i < secs.length; i++) {
            int s = i;
            Button b = Button.builder(Component.literal(secs[i]), btn -> { section = s; rebuildWidgets(); })
                    .bounds(x + i * (tw + 2), y, tw, 16).build();
            b.active = section != i;
            addRenderableWidget(b);
        }
        if (secs.length > 0) y += 22;
        if (view == 0) {
            if (section == 0) y = trs(x, y, def.fpModel, true, false);
            else y = trs(x, y, section == 1 ? def.fpArm1 : def.fpArm2, false, false);
        } else if (view == 1) {
            if (section == 0) y = trs(x, y, def.tpModel, true, false);
            else {
                addRenderableWidget(Button.builder(Component.literal("Поза рук: " + (def.tpPose ? "своя" : "ванильная")),
                        b -> { def.tpPose = !def.tpPose; rebuildWidgets(); }).bounds(x, y, PW, 18).build());
                y += 22;
                y = trs(x, y, section == 1 ? def.tpArm1 : def.tpArm2, false, true);
            }
        } else {
            addRenderableWidget(Button.builder(Component.literal("Руки: " + armsName()), b -> {
                def.arms = switch (def.arms) { case "both" -> "right"; case "right" -> "none"; default -> "both"; };
                rebuildWidgets();
            }).bounds(x, y, PW, 18).build());
            y += 22;
            addRenderableWidget(Button.builder(Component.literal("Анимация: " + (def.anim.isEmpty() ? "нет" : def.anim)), b -> {
                List<String> a = Models.animations(def.model);
                int i = a.indexOf(def.anim);
                def.anim = i + 1 < a.size() ? a.get(i + 1) : "";
                rebuildWidgets();
            }).bounds(x, y, PW, 18).build());
            y += 22;
            addRenderableWidget(new Slider(x, y, "Скорость анимации", 0, 3, 0.05f, () -> def.speed, v -> def.speed = v));
            y += 22;
            addRenderableWidget(new Slider(x, y, "Дальность (блоки, 0 — обычная)", 0, 64, 0.5f, () -> def.range, v -> def.range = v));
            y += 22;
        }
        int by = Math.max(y + 6, height - 28), bw = (PW - 4) / 3;
        addRenderableWidget(Button.builder(Component.literal("Сохранить"), b -> save()).bounds(x, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Сброс"), b -> reset()).bounds(x + bw + 2, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Закрыть"), b -> onClose()).bounds(x + 2 * (bw + 2), by, bw, 20).build());
    }

    private String armsName() {
        return switch (def.arms) { case "both" -> "обе"; case "right" -> "одна"; default -> "без рук"; };
    }

    /** Ползунки сдвига/поворота/масштаба одного массива настроек. rotOnly — только поворот (позы рук 3-го лица). */
    private int trs(int x, int y, float[] t, boolean scale, boolean rotOnly) {
        String[] ax = {"X", "Y", "Z"};
        if (!rotOnly) for (int i = 0; i < 3; i++) {
            int k = i;
            addRenderableWidget(new Slider(x, y, "Сдвиг " + ax[i], -48, 48, 0.25f, () -> t[k], v -> t[k] = v));
            y += 22;
        }
        int r0 = rotOnly ? 0 : 3;
        for (int i = 0; i < 3; i++) {
            int k = r0 + i;
            addRenderableWidget(new Slider(x, y, "Поворот " + ax[i], -180, 180, 1, () -> t[k], v -> t[k] = v));
            y += 22;
        }
        if (scale) {
            addRenderableWidget(new Slider(x, y, "Размер", 0.01f, 2, 0.01f, () -> t[6], v -> t[6] = v));
            y += 22;
        }
        return y;
    }

    private void save() {
        ink.hoka.odyssey.client.Net.sendOp(OP, out -> {
            out.writeUTF(id);
            out.writeUTF(HeldModels.text(def));
        });
        status = "Сохранено — у всех игроков";
    }

    private void reset() {
        HeldModels.Def o = HeldModels.copy(original);
        def.model = o.model; def.anim = o.anim; def.arms = o.arms; def.speed = o.speed; def.range = o.range; def.tpPose = o.tpPose;
        System.arraycopy(o.fpModel, 0, def.fpModel, 0, 7);
        System.arraycopy(o.fpArm1, 0, def.fpArm1, 0, 6);
        System.arraycopy(o.fpArm2, 0, def.fpArm2, 0, 6);
        System.arraycopy(o.tpModel, 0, def.tpModel, 0, 7);
        System.arraycopy(o.tpArm1, 0, def.tpArm1, 0, 3);
        System.arraycopy(o.tpArm2, 0, def.tpArm2, 0, 3);
        status = "Сброшено к сохранённому";
        rebuildWidgets();
    }

    @Override
    public void removed() {
        editing = null;
        editingId = null;
        Minecraft.getInstance().options.setCameraType(camera);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        int x = width - PW - 12;
        g.fill(x, 0, width, height, 0xB40A1428);
        g.fill(x, 0, x + 1, height, 0xFF3C64A0);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        int x = width - PW - 6;
        g.drawString(font, title, x, 8, 0xFF6FD3FF, true);
        if (!status.isEmpty()) g.drawString(font, status, x, height - 40, 0xFF9AB0CC, false);
        g.drawString(font, "Предпросмотр — слева, в мире", 6, 6, 0xFFFFFFFF, true);
    }

    /** Ползунок со значением в подписи; шаг — step. */
    private static final class Slider extends AbstractSliderButton {
        private final String name;
        private final float min, max, step;
        private final Consumer<Float> set;
        private final DoubleSupplier get;

        Slider(int x, int y, String name, float min, float max, float step, DoubleSupplier get, Consumer<Float> set) {
            super(x, y, PW, 20, Component.empty(), Math.clamp((get.getAsDouble() - min) / (max - min), 0, 1));
            this.name = name;
            this.min = min;
            this.max = max;
            this.step = step;
            this.get = get;
            this.set = set;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double v = get.getAsDouble();
            setMessage(Component.literal(name + ": " + (step >= 1 ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.ROOT, "%.2f", v))));
        }

        @Override
        protected void applyValue() {
            float v = min + (float) value * (max - min);
            set.accept(Math.round(v / step) * step);
        }
    }

}
