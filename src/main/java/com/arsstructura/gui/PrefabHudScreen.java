package com.arsstructura.gui;

import com.arsstructura.client.ClientTabletController;
import com.arsstructura.config.ModConfig;
import com.arsstructura.file.PrefabFileManager;
import com.arsstructura.network.ConstructionTabletNetwork.Action;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A local file browser backed by server snapshots; all item actions use the network channel. */
public final class PrefabHudScreen extends Screen {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final UUID tablet;
    private final InteractionHand hand;
    private List<PrefabFileManager.Entry> files = List.of();
    private final List<Button> actions = new ArrayList<>();
    private EditBox name;
    private PrefabFileManager.Format format = PrefabFileManager.Format.NBT;
    private int selected = -1;
    private int scroll;
    private Component status = Component.empty();
    private boolean error;
    private boolean waiting;
    private long waitStarted;
    private boolean refreshed;
    private String deleteArmed = "";
    private long deleteUntil;
    private String rememberedName = "";

    public PrefabHudScreen(UUID tablet, InteractionHand hand) {
        super(Component.translatable("gui.arsstructura.title"));
        this.tablet = tablet;
        this.hand = hand;
    }

    public UUID tabletId() { return tablet; }
    @Override public boolean isPauseScreen() { return ModConfig.PAUSE_HUD.get(); }

    @Override protected void init() {
        actions.clear();
        int usable = width - 20;
        int column = (usable - 9) / 4;
        name = new EditBox(font, 10, height - 94, Math.max(80, usable - 104), 20, tr("name"));
        name.setMaxLength(64);
        name.setHint(tr("name_hint"));
        name.setValue(rememberedName);
        name.setResponder(value -> rememberedName = value);
        addRenderableWidget(name);
        addRenderableWidget(Button.builder(Component.literal(format.extension), button -> {
            format = format == PrefabFileManager.Format.NBT ? PrefabFileManager.Format.LITEMATIC : PrefabFileManager.Format.NBT;
            button.setMessage(Component.literal(format.extension));
        }).bounds(width - 108, height - 94, 98, 20).build());
        button("save", 0, 0, column, () -> save(PrefabFileManager.Format.NBT));
        button("import", 1, 0, column, this::importSelected);
        button("export", 2, 0, column, () -> save(format));
        button("refresh", 3, 0, column, this::refreshFiles);
        button("rename", 0, 1, column, this::renameSelected);
        button("delete", 1, 1, column, this::deleteSelected);
        button("preview", 2, 1, column, () -> request(Action.PREVIEW, ""));
        button("clear", 3, 1, column, () -> request(Action.CLEAR, ""));
        addRenderableWidget(Button.builder(tr("close"), b -> onClose()).bounds(width - 58, 5, 48, 18).build());
        addRenderableWidget(Button.builder(tr("cancel_preview"), b -> request(Action.CANCEL, ""))
                .bounds(width - 156, 5, 94, 18).build());
        addRenderableWidget(Button.builder(Component.literal("▲"), b -> scroll = Math.max(0, scroll - rows()))
                .bounds(width - 48, 82, 18, 12).build());
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> scroll = Math.min(Math.max(0, files.size() - rows()), scroll + rows()))
                .bounds(width - 28, 82, 18, 12).build());
        if (!refreshed) { refreshed = true; refreshFiles(); }
    }

    private void button(String key, int col, int row, int size, Runnable action) {
        Button button = Button.builder(tr(key), b -> action.run()).bounds(10 + col * (size + 3), height - 70 + row * 24, size, 20).build();
        actions.add(button);
        addRenderableWidget(button);
    }

    private int rows() { return Math.max(1, (height - 198) / 24); }

    public void refreshFiles() {
        if (PrefabFileManager.busy()) { setStatus(tr("busy"), true); return; }
        status = tr("scanning");
        error = false;
        PrefabFileManager.async(PrefabFileManager::list, result -> {
            String old = selected >= 0 && selected < files.size() ? files.get(selected).fileName() : "";
            files = result;
            selected = -1;
            for (int i = 0; i < files.size(); i++) if (files.get(i).fileName().equals(old)) selected = i;
            scroll = Math.min(scroll, Math.max(0, files.size() - rows()));
            setStatus(Component.translatable("gui.arsstructura.file_count", files.size()), false);
        }, this::fileError);
    }

    public void snapshotUpdated() {
        if (waiting) setStatus(tr("updated"), false);
    }

    public void setStatus(Component value, boolean isError) {
        status = value;
        error = isError;
        waiting = false;
    }

    private void save(PrefabFileManager.Format wanted) {
        if (!ClientTabletController.hasStructure(tablet)) { setStatus(tr("empty"), true); return; }
        try {
            String requestedName = PrefabFileManager.validateName(name.getValue());
            request(wanted == PrefabFileManager.Format.LITEMATIC ? Action.SAVE_LITEMATIC : Action.SAVE_NBT, requestedName);
        } catch (Exception e) { fileError(e); }
    }

    private void importSelected() {
        PrefabFileManager.Entry entry = selection();
        if (entry == null) return;
        if (!entry.valid()) { setStatus(Component.translatable("gui.arsstructura.file_error", entry.error()), true); return; }
        ClientTabletController.importFile(entry.fileName(), tablet, hand);
        beginWait();
    }

    private void renameSelected() {
        PrefabFileManager.Entry entry = selection();
        if (entry == null) return;
        String newName = name.getValue();
        boolean started = PrefabFileManager.async(() -> PrefabFileManager.rename(entry.fileName(), newName), result -> refreshFiles(), this::fileError);
        if (!started) setStatus(tr("busy"), true);
    }

    private void deleteSelected() {
        PrefabFileManager.Entry entry = selection();
        if (entry == null) return;
        if (!entry.fileName().equals(deleteArmed) || System.currentTimeMillis() > deleteUntil) {
            deleteArmed = entry.fileName();
            deleteUntil = System.currentTimeMillis() + 5000;
            setStatus(Component.translatable("gui.arsstructura.confirm_delete", entry.fileName()), true);
            return;
        }
        deleteArmed = "";
        boolean started = PrefabFileManager.async(() -> { PrefabFileManager.delete(entry.fileName()); return true; }, result -> refreshFiles(), this::fileError);
        if (!started) setStatus(tr("busy"), true);
    }

    private PrefabFileManager.Entry selection() {
        if (selected < 0 || selected >= files.size()) { setStatus(tr("select_file"), true); return null; }
        return files.get(selected);
    }

    private void request(Action action, String requestedName) {
        if (!ClientTabletController.holding(tablet, hand)) { setStatus(tr("changed_item"), true); return; }
        if (waiting || PrefabFileManager.busy()) { setStatus(tr("busy"), true); return; }
        ClientTabletController.action(action, hand, requestedName);
        beginWait();
    }

    private void beginWait() {
        waiting = true;
        waitStarted = System.currentTimeMillis();
        status = tr("waiting");
        error = false;
    }

    private void fileError(Throwable problem) {
        String detail = PrefabFileManager.safeError(problem);
        Component text = detail.matches("prefab_[a-z_]+") ? Component.translatable("gui.arsstructura.error." + detail) : Component.literal(detail);
        setStatus(Component.translatable("gui.arsstructura.file_error", text), true);
    }

    @Override public void tick() {
        name.tick();
        boolean available = !PrefabFileManager.busy() && !waiting;
        for (Button button : actions) button.active = available;
        if (waiting && System.currentTimeMillis() - waitStarted > 30000) setStatus(tr("timeout"), true);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && x >= 10 && x < width - 10 && y >= 96 && y < 96 + rows() * 24) {
            int index = scroll + (int) ((y - 96) / 24);
            if (index < files.size()) {
                selected = index;
                deleteArmed = "";
                String file = files.get(index).fileName();
                String base = file.substring(0, file.lastIndexOf('.'));
                name.setValue(base.substring(0, Math.min(64, base.length())));
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        if (y >= 96 && y < 96 + rows() * 24) {
            scroll = Math.max(0, Math.min(Math.max(0, files.size() - rows()), scroll - (int) Math.signum(amount)));
            return true;
        }
        return super.mouseScrolled(x, y, amount);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(5, 2, width - 5, height - 4, 0xD51B1726);
        clipped(graphics, title, 10, 10, width - 174, 0xDFC998);
        CompoundTag meta = ClientTabletController.metadata(tablet);
        Component summary = meta.getBoolean("HasStructure")
                ? Component.translatable("gui.arsstructura.structure", meta.getInt("SizeX"), meta.getInt("SizeY"), meta.getInt("SizeZ"), meta.getInt("BlockCount")) : tr("empty");
        clipped(graphics, summary, 10, 28, width - 20, 0xECE6FC);
        clipped(graphics, Component.translatable("gui.arsstructura.points", point(meta, "Point1"), point(meta, "Point2")), 10, 40, width - 20, 0xC8BDD9);
        clipped(graphics, Component.translatable("gui.arsstructura.selection_dimension", value(meta, "SelectionDimension")), 10, 52, width - 20, 0xC8BDD9);
        clipped(graphics, Component.translatable("gui.arsstructura.anchor", point(meta, "Anchor"), value(meta, "AnchorDimension")), 10, 64, width - 20, 0xC8BDD9);
        clipped(graphics, Component.translatable("gui.arsstructura.local_files", files.size()), 10, 84, width - 66, 0xD8CAEF);
        int bottom = 96 + rows() * 24;
        graphics.fill(10, 96, width - 10, bottom, 0x8C0E0C15);
        if (files.isEmpty()) clipped(graphics, tr("no_files"), 15, 102, width - 30, 0xA49AB7);
        for (int line = 0; line < rows() && scroll + line < files.size(); line++) {
            int index = scroll + line;
            int y = 96 + line * 24;
            PrefabFileManager.Entry entry = files.get(index);
            if (selected == index) graphics.fill(11, y, width - 11, y + 24, 0xAA654A87);
            String dimensions = entry.valid() ? "  " + entry.sizeX() + "×" + entry.sizeY() + "×" + entry.sizeZ() : "";
            clipped(graphics, Component.literal(entry.fileName() + dimensions), 15, y + 3, width - 30, entry.valid() ? 0xF4EFFB : 0xFF9191);
            String detail = entry.valid() ? entry.author() + "  ·  " + date(entry.createdAt()) + "  ·  " + entry.modVersion() : entry.error();
            clipped(graphics, Component.literal(detail), 15, y + 13, width - 30, entry.valid() ? 0xB9AEC8 : 0xC78484);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        clipped(graphics, PrefabFileManager.busy() ? tr("working") : status, 10, height - 18, width - 20, error ? 0xFF9B9B : 0xA7DEC6);
        if (mouseY >= height - 21 && mouseY < height - 5 && font.width(status) > width - 20)
            graphics.renderTooltip(font, font.split(status, Math.min(320, width - 30)), mouseX, mouseY);
    }

    private void clipped(GuiGraphics graphics, Component component, int x, int y, int maxWidth, int color) {
        String text = component.getString();
        if (font.width(text) > maxWidth) text = font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
        graphics.drawString(font, text, x, y, color, false);
    }

    private static String point(CompoundTag meta, String key) {
        if (!meta.contains(key)) return "—";
        BlockPos pos = BlockPos.of(meta.getLong(key));
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String value(CompoundTag meta, String key) {
        String value = meta.getString(key);
        return value.isEmpty() ? "—" : value;
    }

    private static String date(long epoch) {
        try { return epoch > 0 ? DATE.format(Instant.ofEpochMilli(epoch)) : "—"; }
        catch (RuntimeException ex) { return "—"; }
    }

    private static Component tr(String key) { return Component.translatable("gui.arsstructura." + key); }
}
