package dev.ollefx.musicplayer.screen;

import dev.ollefx.musicplayer.MusicPlayerMod;
import dev.ollefx.musicplayer.audio.MusicManager;
import dev.ollefx.musicplayer.audio.PlaybackQueue;
import dev.ollefx.musicplayer.audio.Track;
import dev.ollefx.musicplayer.config.MusicConfig;
import dev.ollefx.musicplayer.integration.MusicSearchManager;
import dev.ollefx.musicplayer.lyrics.LyricsManager;
import dev.ollefx.musicplayer.render.AnimationController;
import dev.ollefx.musicplayer.render.LiquidGlassRenderer;
import dev.ollefx.musicplayer.render.MusicVisualizer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Main music player screen — opened with M key.
 *
 * Layout (1280×720 reference — scales with window):
 *
 *   ┌─────────────────────────────────────────────────────────┐
 *   │  [Home] [Search] [Library] [Queue] [Lyrics] [Settings]  │  nav bar
 *   ├──────────────┬──────────────────────────────────────────┤
 *   │              │                                          │
 *   │  Album Art   │  Content Area (page-dependent)           │
 *   │  256×256     │                                          │
 *   │              │                                          │
 *   ├──────────────┴──────────────────────────────────────────┤
 *   │  ◀◀  ◀  ▶/▐▐  ▶  ▶▶  ♥  🔀  🔁    ──────────── vol │  player bar
 *   │  Track title / Artist                     00:00 / 03:45 │
 *   │  ════════════════████░░░░░░░░░░░░░░░░░░░  seek bar      │
 *   └─────────────────────────────────────────────────────────┘
 *
 * Pages: HOME, SEARCH, LIBRARY, QUEUE, LYRICS, SETTINGS
 *
 * Animation: panel expands from center on open, collapses on close (spring).
 */
public class MusicScreen extends Screen {

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final int PANEL_W_FRAC  = 85;  // % of screen width
    private static final int PANEL_H_FRAC  = 85;  // % of screen height
    private static final int NAV_H         = 32;
    private static final int PLAYER_H      = 72;
    private static final int ART_SIZE      = 160;
    private static final int SIDEBAR_W     = 180;
    private static final int CORNER_R      = 8;
    private static final int BTN_W         = 32;
    private static final int BTN_H         = 24;
    private static final int SEARCH_H      = 20;

    // ── Pages ─────────────────────────────────────────────────────────────────
    public enum Page { HOME, SEARCH, LIBRARY, QUEUE, LYRICS, SETTINGS }
    private Page currentPage = Page.HOME;

    // ── Open/close animation ─────────────────────────────────────────────────
    private final AnimationController.SpringFloat scaleAnim;
    private final AnimationController.AnimatedFloat alphaAnim;
    private final AnimationController.AnimatedFloat pageTransX;
    private boolean closing = false;

    // ── Search state ──────────────────────────────────────────────────────────
    private final MusicSearchManager searchManager;
    private String      searchQuery    = "";
    private List<Track> searchResults  = new ArrayList<>();
    private boolean     searchLoading  = false;
    private String      searchError    = null;
    private int         searchCursor   = 0;

    // ── Scroll state per page ─────────────────────────────────────────────────
    private int scrollHome     = 0;
    private int scrollQueue    = 0;
    private int scrollLibrary  = 0;
    private int scrollLyrics   = 0;

    // ── Hover / press state ───────────────────────────────────────────────────
    private int hoveredBtn     = -1;
    private int pressedBtn     = -1;

    // ── Seek drag ─────────────────────────────────────────────────────────────
    private boolean seekDragging = false;
    private float   seekPreview  = 0f;

    // ── Visualizer ────────────────────────────────────────────────────────────
    private final MusicVisualizer visualizer;

    // ── Settings editing ──────────────────────────────────────────────────────
    private int settingsEditField = -1; // which text field is focused in settings

    // ── Art loading ───────────────────────────────────────────────────────────
    private String       loadedArtUrl = null;
    // artTexture would be a DynamicTexture — stored here when loaded
    // Network image loading runs on background thread; for now we draw a placeholder

    // ─────────────────────────────────────────────────────────────────────────

    public MusicScreen() {
        super(Component.translatable("musicplayer.screen.title"));
        AnimationController ac = MusicPlayerMod.animationController;
        scaleAnim  = ac.newSpring(0.85f, 220f, 24f);
        alphaAnim  = ac.newFloat(0f);
        pageTransX = ac.newFloat(0f);
        visualizer = new MusicVisualizer(MusicPlayerMod.musicManager, MusicPlayerMod.config);
        searchManager = new MusicSearchManager(
                MusicPlayerMod.spotify,
                MusicPlayerMod.youtube,
                MusicPlayerMod.ytMusic);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        // Spring open
        scaleAnim.set(0.85f);
        scaleAnim.setTarget(1.0f);
        alphaAnim.animateTo(1.0f, 0.25f);
        playUiSound("ui.menu.open");
    }

    @Override
    public void removed() {
        // Clean up animation handles
        AnimationController ac = MusicPlayerMod.animationController;
        ac.remove(scaleAnim);
        ac.remove(alphaAnim);
        ac.remove(pageTransX);
        searchManager.shutdown();
    }

    @Override
    public void tick() {
        super.tick();
        if (closing) {
            // Allow close after collapse animation
            if (alphaAnim.get() < 0.02f) onClose();
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        float alpha = alphaAnim.get();
        float scale = scaleAnim.get();
        if (alpha < 0.01f) return;

        boolean hw   = MusicPlayerMod.config.halloweenTheme;
        int     sw   = this.width;
        int     sh   = this.height;

        // ── Backdrop dim ─────────────────────────────────────────────────────
        int dimAlpha = (int)(160 * alpha);
        gfx.fill(0, 0, sw, sh, LiquidGlassRenderer.withAlpha(0x000000, dimAlpha));

        // ── Panel geometry (centered, scaled) ─────────────────────────────────
        int panelW = sw * PANEL_W_FRAC / 100;
        int panelH = sh * PANEL_H_FRAC / 100;

        // Apply scale transform via PoseStack in GuiGraphicsExtractor
        int cx = sw / 2, cy = sh / 2;
        gfx.pose().pushPose();
        // Translate to center, scale about center, translate back
        gfx.pose().translate(cx, cy, 0);
        gfx.pose().scale(scale, scale, 1.0f);
        gfx.pose().translate(-cx, -cy, 0);

        int px = (sw - panelW) / 2;
        int py = (sh - panelH) / 2;

        // Background
        LiquidGlassRenderer.drawScreenBackground(gfx, sw, sh, hw, alpha * 0.6f);

        // Main panel
        LiquidGlassRenderer.drawPanel(gfx, px, py, panelW, panelH, CORNER_R, hw, alpha);

        // ── Navigation bar ────────────────────────────────────────────────────
        renderNavBar(gfx, px, py, panelW, hw, alpha, mouseX, mouseY);

        // ── Sidebar (album art + now-playing info) ────────────────────────────
        renderSidebar(gfx, px, py + NAV_H, hw, alpha, mouseX, mouseY);

        // ── Content area ──────────────────────────────────────────────────────
        int contentX = px + SIDEBAR_W;
        int contentY = py + NAV_H;
        int contentW = panelW - SIDEBAR_W;
        int contentH = panelH - NAV_H - PLAYER_H;

        // Page transition: horizontal slide
        gfx.enableScissor(contentX, contentY, contentX + contentW, contentY + contentH);
        int xOff = (int)(pageTransX.get() * contentW);
        switch (currentPage) {
            case HOME     -> renderHome(gfx, contentX + xOff, contentY, contentW, contentH, hw, alpha, mouseX, mouseY);
            case SEARCH   -> renderSearch(gfx, contentX + xOff, contentY, contentW, contentH, hw, alpha, mouseX, mouseY);
            case LIBRARY  -> renderLibrary(gfx, contentX + xOff, contentY, contentW, contentH, hw, alpha, mouseX, mouseY);
            case QUEUE    -> renderQueue(gfx, contentX + xOff, contentY, contentW, contentH, hw, alpha, mouseX, mouseY);
            case LYRICS   -> renderLyricsPage(gfx, contentX + xOff, contentY, contentW, contentH, hw, alpha);
            case SETTINGS -> renderSettings(gfx, contentX + xOff, contentY, contentW, contentH, hw, alpha, mouseX, mouseY);
        }
        gfx.disableScissor();

        // ── Player bar ────────────────────────────────────────────────────────
        int playerY = py + panelH - PLAYER_H;
        renderPlayerBar(gfx, px, playerY, panelW, hw, alpha, mouseX, mouseY);

        gfx.pose().popPose();

        super.render(gfx, mouseX, mouseY, partialTick);
    }

    // ── Nav bar ───────────────────────────────────────────────────────────────

    private static final String[] NAV_LABELS = {
            "Home", "Search", "Library", "Queue", "Lyrics", "Settings"
    };
    private static final Page[] NAV_PAGES = {
            Page.HOME, Page.SEARCH, Page.LIBRARY, Page.QUEUE, Page.LYRICS, Page.SETTINGS
    };

    private void renderNavBar(GuiGraphicsExtractor gfx, int px, int py,
                              int panelW, boolean hw, float alpha,
                              int mx, int my) {
        // Nav background
        LiquidGlassRenderer.fill(gfx, px, py, panelW, NAV_H,
                LiquidGlassRenderer.withAlpha(hw ? 0x0A0015 : 0x0A0A14,
                        (int)(200 * alpha)));
        LiquidGlassRenderer.fill(gfx, px, py + NAV_H - 1, panelW, 1,
                LiquidGlassRenderer.multiplyAlpha(hw ? LiquidGlassRenderer.HW_BORDER
                        : LiquidGlassRenderer.BORDER_STD, alpha * 0.6f));

        int tabW = panelW / NAV_LABELS.length;
        for (int i = 0; i < NAV_LABELS.length; i++) {
            int tx  = px + i * tabW;
            boolean active  = NAV_PAGES[i] == currentPage;
            boolean hovered = mx >= tx && mx < tx + tabW && my >= py && my < py + NAV_H;

            if (active) {
                // Active tab: subtle highlight
                int activeColor = hw
                        ? LiquidGlassRenderer.withAlpha(0xFF6600, (int)(50 * alpha))
                        : LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(30 * alpha));
                LiquidGlassRenderer.fill(gfx, tx, py, tabW, NAV_H, activeColor);
                // Bottom indicator line
                int lineColor = hw ? LiquidGlassRenderer.withAlpha(0xFF8800, (int)(220 * alpha))
                        : LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(200 * alpha));
                LiquidGlassRenderer.fill(gfx, tx + 2, py + NAV_H - 2, tabW - 4, 2, lineColor);
            } else if (hovered) {
                LiquidGlassRenderer.fill(gfx, tx, py, tabW, NAV_H,
                        LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(12 * alpha)));
            }

            int labelColor = active
                    ? (hw ? LiquidGlassRenderer.withAlpha(0xFF8800, (int)(255 * alpha))
                           : LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(255 * alpha)))
                    : LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(160 * alpha));

            int labelW = this.font.width(NAV_LABELS[i]);
            gfx.drawString(this.font, NAV_LABELS[i],
                    tx + (tabW - labelW) / 2,
                    py + (NAV_H - this.font.lineHeight) / 2,
                    labelColor, false);
        }
    }

    // ── Sidebar ───────────────────────────────────────────────────────────────

    private void renderSidebar(GuiGraphicsExtractor gfx, int px, int py,
                               boolean hw, float alpha, int mx, int my) {
        int contentH = this.height * PANEL_H_FRAC / 100 - NAV_H - PLAYER_H;

        // Sidebar background
        LiquidGlassRenderer.fill(gfx, px, py, SIDEBAR_W, contentH,
                LiquidGlassRenderer.withAlpha(hw ? 0x06010F : 0x060610, (int)(180 * alpha)));
        // Right border
        LiquidGlassRenderer.fill(gfx, px + SIDEBAR_W - 1, py, 1, contentH,
                LiquidGlassRenderer.multiplyAlpha(LiquidGlassRenderer.BORDER_STD, alpha * 0.5f));

        Track now = MusicPlayerMod.musicManager != null ? MusicPlayerMod.musicManager.getNowPlaying() : null;

        // Album art area
        int artX = px + (SIDEBAR_W - ART_SIZE) / 2;
        int artY = py + 16;

        // Art frame
        LiquidGlassRenderer.drawPanel(gfx, artX - 4, artY - 4, ART_SIZE + 8, ART_SIZE + 8,
                4, hw, alpha * 0.8f);

        if (now != null && !now.artworkUrl().isBlank()) {
            // Actual texture rendering requires async download + DynamicTexture registration
            // Placeholder: draw colored rect with initials
            int artBg = hw ? LiquidGlassRenderer.withAlpha(0x2A0040, (int)(220 * alpha))
                           : LiquidGlassRenderer.withAlpha(0x1A1A2E, (int)(220 * alpha));
            LiquidGlassRenderer.fill(gfx, artX, artY, ART_SIZE, ART_SIZE, artBg);
            // Art initials
            String initials = now.title().isEmpty() ? "♫" : String.valueOf(now.title().charAt(0));
            int iw = this.font.width(initials);
            gfx.drawString(this.font, initials,
                    artX + (ART_SIZE - iw) / 2,
                    artY + ART_SIZE / 2 - this.font.lineHeight / 2,
                    LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(180 * alpha)), false);
        } else {
            // No track: music note placeholder
            int bg = hw ? LiquidGlassRenderer.withAlpha(0x180030, (int)(200 * alpha))
                       : LiquidGlassRenderer.withAlpha(0x121224, (int)(200 * alpha));
            LiquidGlassRenderer.fill(gfx, artX, artY, ART_SIZE, ART_SIZE, bg);
            String note = "♫";
            int nw = this.font.width(note);
            gfx.drawString(this.font, note,
                    artX + (ART_SIZE - nw) / 2,
                    artY + ART_SIZE / 2 - this.font.lineHeight / 2,
                    LiquidGlassRenderer.withAlpha(hw ? 0xFF6600 : 0x666688, (int)(180 * alpha)), false);
        }

        // Track title + artist
        int infoY = artY + ART_SIZE + 16;
        if (now != null) {
            String title  = truncate(now.title(), SIDEBAR_W - 10);
            String artist = truncate(now.artist(), SIDEBAR_W - 10);
            int titleW  = this.font.width(title);
            int artistW = this.font.width(artist);
            gfx.drawString(this.font, title,
                    px + (SIDEBAR_W - titleW) / 2, infoY,
                    LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(230 * alpha)), false);
            gfx.drawString(this.font, artist,
                    px + (SIDEBAR_W - artistW) / 2, infoY + 14,
                    LiquidGlassRenderer.withAlpha(hw ? 0xCC8800 : 0xAAAAAA, (int)(180 * alpha)), false);
        } else {
            String idle = "Nothing playing";
            int iw = this.font.width(idle);
            gfx.drawString(this.font, idle,
                    px + (SIDEBAR_W - iw) / 2, infoY,
                    LiquidGlassRenderer.withAlpha(0x666666, (int)(160 * alpha)), false);
        }

        // Mini visualizer in sidebar
        int vizY = infoY + 36;
        if (vizY + 24 < py + contentH) {
            visualizer.render(gfx, px + 8, vizY, SIDEBAR_W - 16, 24, alpha);
        }
    }

    // ── Home page ─────────────────────────────────────────────────────────────

    private void renderHome(GuiGraphicsExtractor gfx, int x, int y, int w, int h,
                            boolean hw, float alpha, int mx, int my) {
        int color = LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(180 * alpha));
        gfx.drawString(this.font, "Recently Played", x + 12, y + 10, color);

        List<Track> recent = MusicPlayerMod.musicManager.getRecent();
        if (recent.isEmpty()) {
            int dim = LiquidGlassRenderer.withAlpha(0x666666, (int)(150 * alpha));
            gfx.drawString(this.font, "No recent tracks — search to get started",
                    x + 12, y + 32, dim, false);
            return;
        }

        int rowH = 36;
        int startY = y + 28 - scrollHome;
        for (int i = 0; i < recent.size(); i++) {
            int ry = startY + i * rowH;
            if (ry + rowH < y || ry > y + h) continue;
            renderTrackRow(gfx, x + 8, ry, w - 16, recent.get(i), i, hw, alpha, mx, my, false);
        }
    }

    // ── Search page ───────────────────────────────────────────────────────────

    private void renderSearch(GuiGraphicsExtractor gfx, int x, int y, int w, int h,
                              boolean hw, float alpha, int mx, int my) {
        // Search bar
        int barY  = y + 10;
        int barX  = x + 10;
        int barW  = w - 20;
        LiquidGlassRenderer.drawPanel(gfx, barX, barY, barW, SEARCH_H, 4, hw, alpha * 0.8f);
        int searchColor = searchQuery.isEmpty()
                ? LiquidGlassRenderer.withAlpha(0x888888, (int)(160 * alpha))
                : LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(220 * alpha));
        String display = searchQuery.isEmpty() ? "Search songs, artists, albums..." : searchQuery;
        gfx.drawString(this.font, display, barX + 6, barY + (SEARCH_H - this.font.lineHeight) / 2,
                searchColor, false);

        // Source indicators
        boolean spAuth = MusicPlayerMod.spotify.isAuthenticated();
        boolean ytConf = MusicPlayerMod.youtube.isConfigured();
        int srcY = barY + SEARCH_H + 4;
        int srcColor = LiquidGlassRenderer.withAlpha(0x888888, (int)(150 * alpha));
        if (spAuth) gfx.drawString(this.font, "Spotify ✓", x + 10, srcY, LiquidGlassRenderer.withAlpha(0x1DB954, (int)(180 * alpha)));
        if (ytConf)  gfx.drawString(this.font, "YouTube ✓", x + 10 + (spAuth ? 80 : 0), srcY, LiquidGlassRenderer.withAlpha(0xFF0000, (int)(180 * alpha)));

        // Results
        if (searchLoading) {
            gfx.drawString(this.font, "Searching…", x + 10, srcY + 20,
                    LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(160 * alpha)), false);
            return;
        }
        if (searchError != null) {
            gfx.drawString(this.font, searchError, x + 10, srcY + 20,
                    LiquidGlassRenderer.withAlpha(0xFF4444, (int)(200 * alpha)), false);
            return;
        }
        if (searchResults.isEmpty() && !searchQuery.isEmpty()) {
            gfx.drawString(this.font, "No results", x + 10, srcY + 20,
                    LiquidGlassRenderer.withAlpha(0x666666, (int)(160 * alpha)), false);
            return;
        }

        int rowH   = 38;
        int startY = y + 44 - searchCursor;
        gfx.enableScissor(x, y + 44, x + w, y + h);
        for (int i = 0; i < searchResults.size(); i++) {
            int ry = startY + i * rowH;
            if (ry + rowH < y || ry > y + h) continue;
            renderTrackRow(gfx, x + 8, ry, w - 16, searchResults.get(i),
                    1000 + i, hw, alpha, mx, my, true);
        }
        gfx.disableScissor();
    }

    // ── Library page ──────────────────────────────────────────────────────────

    private void renderLibrary(GuiGraphicsExtractor gfx, int x, int y, int w, int h,
                               boolean hw, float alpha, int mx, int my) {
        int color = LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(200 * alpha));
        gfx.drawString(this.font, "Favorites", x + 12, y + 10, color);

        List<Track> favs = MusicPlayerMod.musicManager.getFavorites();
        if (favs.isEmpty()) {
            gfx.drawString(this.font, "Heart a track to add it here",
                    x + 12, y + 32,
                    LiquidGlassRenderer.withAlpha(0x666666, (int)(150 * alpha)), false);
            return;
        }
        int rowH = 36;
        for (int i = 0; i < favs.size(); i++) {
            int ry = y + 28 + i * rowH - scrollLibrary;
            if (ry + rowH < y || ry > y + h) continue;
            renderTrackRow(gfx, x + 8, ry, w - 16, favs.get(i), 2000 + i, hw, alpha, mx, my, false);
        }
    }

    // ── Queue page ────────────────────────────────────────────────────────────

    private void renderQueue(GuiGraphicsExtractor gfx, int x, int y, int w, int h,
                             boolean hw, float alpha, int mx, int my) {
        int color = LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(200 * alpha));
        gfx.drawString(this.font, "Up Next", x + 12, y + 10, color);

        PlaybackQueue queue = MusicPlayerMod.musicManager.getQueue();
        List<Track> items  = queue.getPlayOrder();
        int         cur    = queue.getCurrentIndex();

        if (items.isEmpty()) {
            gfx.drawString(this.font, "Queue is empty",
                    x + 12, y + 32,
                    LiquidGlassRenderer.withAlpha(0x666666, (int)(150 * alpha)), false);
            return;
        }
        int rowH = 36;
        for (int i = 0; i < items.size(); i++) {
            int ry = y + 28 + i * rowH - scrollQueue;
            if (ry + rowH < y || ry > y + h) continue;
            boolean isCur = i == cur;
            renderTrackRow(gfx, x + 8, ry, w - 16, items.get(i),
                    3000 + i, hw, alpha * (isCur ? 1f : 0.7f), mx, my, false);
            if (isCur) {
                // Playing indicator
                LiquidGlassRenderer.fill(gfx, x + 2, ry, 3, rowH - 2,
                        LiquidGlassRenderer.withAlpha(hw ? 0xFF8800 : 0xFFFFFF, (int)(200 * alpha)));
            }
        }
    }

    // ── Lyrics page ───────────────────────────────────────────────────────────

    private void renderLyricsPage(GuiGraphicsExtractor gfx, int x, int y, int w, int h,
                                  boolean hw, float alpha) {
        LyricsManager ly = MusicPlayerMod.lyricsManager;
        if (!ly.isAvailable()) {
            String msg = ly.isLoading() ? "Loading lyrics…" : "No lyrics available";
            int tw = this.font.width(msg);
            gfx.drawString(this.font, msg,
                    x + (w - tw) / 2, y + h / 2,
                    LiquidGlassRenderer.withAlpha(0x888888, (int)(160 * alpha)), false);
            return;
        }

        List<LyricsManager.LyricsLine> lines = ly.getAllLines();
        LyricsManager.LyricsLine cur = ly.getCurrentLine();
        int curIdx = lines.indexOf(cur);

        int lineH    = this.font.lineHeight + 6;
        int visLines = h / lineH;
        int scrollTarget = curIdx < 0 ? 0 : Math.max(0, curIdx - visLines / 2);
        // Smooth scroll
        scrollLyrics = (int)(scrollLyrics * 0.85f + scrollTarget * lineH * 0.15f);

        gfx.enableScissor(x, y, x + w, y + h);
        for (int i = 0; i < lines.size(); i++) {
            int ly2 = y + i * lineH - scrollLyrics;
            if (ly2 + lineH < y || ly2 > y + h) continue;

            LyricsManager.LyricsLine line = lines.get(i);
            boolean isCur = i == curIdx;

            int textColor;
            if (isCur) {
                textColor = hw
                        ? LiquidGlassRenderer.withAlpha(0xFF8800, (int)(255 * alpha))
                        : LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(255 * alpha));
                // Highlight background
                LiquidGlassRenderer.fill(gfx, x + 4, ly2, w - 8, lineH,
                        LiquidGlassRenderer.withAlpha(hw ? 0xFF4400 : 0x444444, (int)(40 * alpha)));
            } else if (Math.abs(i - curIdx) == 1) {
                textColor = LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(180 * alpha));
            } else {
                textColor = LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(110 * alpha));
            }

            String clipped = truncate(line.text(), w - 20);
            gfx.drawString(this.font, clipped, x + 12, ly2 + 3, textColor);
        }
        gfx.disableScissor();
    }

    // ── Settings page ─────────────────────────────────────────────────────────

    // Settings sub-tab: 0=Lyrics, 1=Music&HUD
    private int settingsTab = 0;

    private void renderSettings(GuiGraphicsExtractor gfx, int x, int y, int w, int h,
                                boolean hw, float alpha, int mx, int my) {
        MusicConfig cfg = MusicPlayerMod.config;

        // ── Tab selector (Dango: "Lyrics" | "Music & HUD") ───────────────────
        int tabW = 90, tabH = 20;
        int tabX = x + w - tabW * 2 - 20;
        int tabY = y + 6;
        String[] tabNames = { "Lyrics", "Music & HUD" };
        for (int i = 0; i < 2; i++) {
            boolean sel = settingsTab == i;
            int bg = sel ? (hw ? LiquidGlassRenderer.withAlpha(0xFF6600, (int)(180*alpha))
                               : LiquidGlassRenderer.withAlpha(0x446688, (int)(180*alpha)))
                         : LiquidGlassRenderer.withAlpha(0x222222, (int)(100*alpha));
            LiquidGlassRenderer.fill(gfx, tabX + i * (tabW + 4), tabY, tabW, tabH, bg);
            int fc = LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)((sel ? 240 : 160) * alpha));
            int lw = this.font.width(tabNames[i]);
            gfx.drawString(this.font, tabNames[i],
                    tabX + i * (tabW + 4) + (tabW - lw) / 2, tabY + 5, fc, false);
        }

        gfx.enableScissor(x, y + tabH + 8, x + w, y + h);

        int lx = x + 10;
        int rx = x + w / 2 + 10;
        int ly = y + tabH + 14;
        int rly = ly;
        int lh = 22;

        if (settingsTab == 0) {
            // ── LEFT: 3D Lyrics placement (Dango "Placement" section) ────────
            section(gfx, "Placement  In the world", lx, ly, alpha, hw);
            ly += 14;
            ly = togRow(gfx, "3D lyrics", cfg.lyrics3dEnabled, lx, ly, lh, alpha, hw);
            ly = sliderRow(gfx, "Distance", cfg.skyLyricsDistance, 1, 80, lx, ly, lh, alpha, hw);
            ly = togRow(gfx, "Face player", cfg.lyricsFacePlayer, lx, ly, lh, alpha, hw);
            ly = togRow(gfx, "Keep away", cfg.lyricsKeepAway, lx, ly, lh, alpha, hw);
            ly = togRow(gfx, "Stay on screen", cfg.lyricsStayOnScreen, lx, ly, lh, alpha, hw);
            ly = togRow(gfx, "Above ground", cfg.lyricsAboveGround, lx, ly, lh, alpha, hw);
            ly = sliderRow(gfx, "Spread", cfg.lyricsSpread, 0, 180, lx, ly, lh, alpha, hw);
            ly = sliderRow(gfx, "Scale", cfg.lyricsScale, 0.1f, 3f, lx, ly, lh, alpha, hw);
            ly = togRow(gfx, "Smooth font", cfg.lyricsSmoothFont, lx, ly, lh, alpha, hw);
            ly = togRow(gfx, "Split lines", cfg.lyricsSplitLines, lx, ly, lh, alpha, hw);

            ly += 6;
            section(gfx, "Translation  Under each line", lx, ly, alpha, hw);
            ly += 14;
            ly = togRow(gfx, "Translation", cfg.lyricsTranslation, lx, ly, lh, alpha, hw);
            ly = togRow(gfx, "Translation animation", cfg.lyricsTransAnimation, lx, ly, lh, alpha, hw);
            ly = togRow(gfx, "Translation wiggle", cfg.lyricsTransWiggle, lx, ly, lh, alpha, hw);

            // ── RIGHT: Glow + Motion ──────────────────────────────────────────
            section(gfx, "Glow  Deep Glow", rx, rly, alpha, hw);
            rly += 14;
            rly = togRow(gfx, "Glow", cfg.glowEnabled, rx, rly, lh, alpha, hw);
            rly = togRow(gfx, "Night glow only", cfg.glowNightOnly, rx, rly, lh, alpha, hw);
            rly = sliderRow(gfx, "Glow strength", cfg.glowStrength, 0, 1, rx, rly, lh, alpha, hw);
            rly = sliderRow(gfx, "Glow radius", cfg.glowRadius, 0, 1, rx, rly, lh, alpha, hw);

            rly += 6;
            section(gfx, "Motion  Animation", rx, rly, alpha, hw);
            rly += 14;
            rly = togRow(gfx, "Wiggle", cfg.wiggle, rx, rly, lh, alpha, hw);
            rly = sliderRow(gfx, "Wiggle fps", cfg.wiggleFps, 1, 30, rx, rly, lh, alpha, hw);
            rly = sliderRow(gfx, "Wiggle amount", cfg.wiggleAmount, 0, 2, rx, rly, lh, alpha, hw);
            rly = togRow(gfx, "Text flash", cfg.textFlash, rx, rly, lh, alpha, hw);
            rly = togRow(gfx, "Word pop", cfg.wordPop, rx, rly, lh, alpha, hw);
            rly = sliderRow(gfx, "Flash size", cfg.flashSize, 0.5f, 2f, rx, rly, lh, alpha, hw);

            rly += 6;
            section(gfx, "Dance  Mobs on the beat", rx, rly, alpha, hw);
            rly += 14;
            rly = togRow(gfx, "Mob dance", cfg.danceEnabled, rx, rly, lh, alpha, hw);
            rly = togRow(gfx, "Auto BPM", cfg.danceAuto, rx, rly, lh, alpha, hw);
            rly = togRow(gfx, "Spin", cfg.danceSpin, rx, rly, lh, alpha, hw);
            rly = sliderRow(gfx, "Dance amount", cfg.danceAmount, 1f, 3f, rx, rly, lh, alpha, hw);
            rly = sliderRow(gfx, "Beat offset", cfg.danceOffsetMs, -500, 500, rx, rly, lh, alpha, hw);

        } else {
            // ── LEFT: Player / API keys ───────────────────────────────────────
            section(gfx, "Player  Playback", lx, ly, alpha, hw);
            ly += 14;
            ly = sliderRow(gfx, "Volume", cfg.musicVolume, 0, 1, lx, ly, lh, alpha, hw);
            ly = togRow(gfx, "Volume normalization", cfg.volumeNormalization, lx, ly, lh, alpha, hw);

            ly += 6;
            section(gfx, "Hotkeys", lx, ly, alpha, hw);
            ly += 14;
            String[] hotkeyLabels = { "Open key", "Play / Pause", "Previous", "Next", "Volume up", "Volume down", "Toggle lyrics" };
            for (String hl : hotkeyLabels) {
                gfx.drawString(this.font, hl, lx, ly, LiquidGlassRenderer.withAlpha(0xCCCCCC, (int)(180*alpha)));
                LiquidGlassRenderer.drawPanel(gfx, lx + 180, ly - 2, 50, 14, 3, hw, alpha * 0.7f);
                gfx.drawString(this.font, "M", lx + 192, ly, LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(200*alpha)));
                ly += 16;
            }

            ly += 6;
            section(gfx, "API Keys", lx, ly, alpha, hw);
            ly += 14;
            ly = renderTextField(gfx, "Spotify Client ID",     cfg.spotifyClientId,     lx, ly, w/2 - 20, 0, alpha, hw, mx, my);
            ly = renderTextField(gfx, "Spotify Client Secret", cfg.spotifyClientSecret, lx, ly, w/2 - 20, 1, alpha, hw, mx, my);
            boolean spConn = MusicPlayerMod.spotify.isAuthenticated();
            LiquidGlassRenderer.drawPanel(gfx, lx, ly, 160, 18, 4, hw, alpha * 0.8f);
            gfx.drawString(this.font, spConn ? "Spotify Connected ✓" : "Connect Spotify",
                    lx + 6, ly + 4,
                    LiquidGlassRenderer.withAlpha(spConn ? 0x1DB954 : 0xFFFFFF, (int)(220*alpha)), false);
            ly += 24;
            ly = renderTextField(gfx, "YouTube API Key", cfg.youtubeApiKey, lx, ly, w/2 - 20, 2, alpha, hw, mx, my);

            // ── RIGHT: Environment audio + Song card + Dynamic Island ─────────
            section(gfx, "Environment audio  Sound around you", rx, rly, alpha, hw);
            rly += 14;
            rly = togRow(gfx, "Environment audio", cfg.envAudioEnabled, rx, rly, lh, alpha, hw);
            rly = togRow(gfx, "Underwater", cfg.envUnderwater, rx, rly, lh, alpha, hw);
            rly = togRow(gfx, "Cave echo", cfg.envCaveEcho, rx, rly, lh, alpha, hw);
            rly = togRow(gfx, "Indoors", cfg.envIndoors, rx, rly, lh, alpha, hw);
            rly = togRow(gfx, "Rain", cfg.envRain, rx, rly, lh, alpha, hw);
            rly = sliderRow(gfx, "Effect strength", cfg.envStrength, 0, 1, rx, rly, lh, alpha, hw);

            rly += 6;
            section(gfx, "Song card  Overlay", rx, rly, alpha, hw);
            rly += 14;
            rly = sliderRow(gfx, "Scale", cfg.songCardScale, 0.5f, 2f, rx, rly, lh, alpha, hw);
            rly = togRow(gfx, "Show when paused", cfg.songCardShowPaused, rx, rly, lh, alpha, hw);
            rly = sliderRow(gfx, "Pos X", cfg.songCardPosX, 0, 1, rx, rly, lh, alpha, hw);
            rly = sliderRow(gfx, "Pos Y", cfg.songCardPosY, 0, 1, rx, rly, lh, alpha, hw);

            rly += 6;
            section(gfx, "Dynamic Island  Overlay", rx, rly, alpha, hw);
            rly += 14;
            rly = togRow(gfx, "Liquid glass", cfg.dynamicIslandGlass, rx, rly, lh, alpha, hw);
            rly = togRow(gfx, "Music", cfg.dynamicIslandMusic, rx, rly, lh, alpha, hw);
            rly = togRow(gfx, "Song notify", cfg.dynamicIslandNotify, rx, rly, lh, alpha, hw);
            rly = sliderRow(gfx, "Scale", cfg.dynamicIslandScale, 0.5f, 2f, rx, rly, lh, alpha, hw);
            rly = sliderRow(gfx, "Notify time", cfg.dynamicIslandNotifyTime, 0.5f, 10f, rx, rly, lh, alpha, hw);

            rly += 6;
            section(gfx, "Now playing  Classic overlay", rx, rly, alpha, hw);
            rly += 14;
            rly = togRow(gfx, "Now playing", cfg.nowPlayingOverlay, rx, rly, lh, alpha, hw);
            rly = togRow(gfx, "Cover", cfg.nowPlayingCover, rx, rly, lh, alpha, hw);

            rly += 6;
            section(gfx, "Sources", rx, rly, alpha, hw);
            rly += 14;
            rly = togRow(gfx, "YouTube Music (no key)", cfg.ytMusicEnabled, rx, rly, lh, alpha, hw);
        }

        gfx.disableScissor();
    }

    // ── Settings row helpers ──────────────────────────────────────────────────

    private int togRow(GuiGraphicsExtractor gfx, String label, boolean val,
                       int x, int y, int lh, float alpha, boolean hw) {
        renderToggle(gfx, label, val, x, y, alpha, hw);
        return y + lh;
    }

    private int sliderRow(GuiGraphicsExtractor gfx, String label, float val,
                          float min, float max,
                          int x, int y, int lh, float alpha, boolean hw) {
        int labelColor = LiquidGlassRenderer.withAlpha(0xCCCCCC, (int)(190*alpha));
        gfx.drawString(this.font, label, x, y + 3, labelColor);
        int slW = 100;
        int slX = x + 170;
        // Track
        LiquidGlassRenderer.fill(gfx, slX, y + 6, slW, 4,
                LiquidGlassRenderer.withAlpha(0x444444, (int)(180*alpha)));
        // Fill
        float t = (max > min) ? (val - min) / (max - min) : 0f;
        t = Math.max(0f, Math.min(1f, t));
        int fillW = (int)(slW * t);
        if (fillW > 0) {
            LiquidGlassRenderer.fill(gfx, slX, y + 6, fillW, 4,
                    LiquidGlassRenderer.withAlpha(hw ? 0xFF6600 : 0x6688FF, (int)(200*alpha)));
        }
        // Value label
        String valStr = (max - min < 2f) ? String.format("%.2f", val)
                      : (max > 100 ? String.format("%.0f ms", val) : String.format("%.1f", val));
        gfx.drawString(this.font, valStr, slX + slW + 4, y + 2,
                LiquidGlassRenderer.withAlpha(0xAAAAAA, (int)(170*alpha)), false);
        return y + lh;
    }

    private void section(GuiGraphicsExtractor gfx, String label, int x, int y,
                         float alpha, boolean hw) {
        int color = hw ? LiquidGlassRenderer.withAlpha(0xFF8800, (int)(200*alpha))
                       : LiquidGlassRenderer.withAlpha(0x8888CC, (int)(200*alpha));
        gfx.drawString(this.font, "— " + label, x, y, color);
    }

    private int renderTextField(GuiGraphicsExtractor gfx, String label, String value,
                                int x, int y, int w, int fieldIdx,
                                float alpha, boolean hw, int mx, int my) {
        int labelColor = LiquidGlassRenderer.withAlpha(0xAAAAAA, (int)(200*alpha));
        gfx.drawString(this.font, label, x, y, labelColor);
        y += 12;
        boolean focused = settingsEditField == fieldIdx;
        LiquidGlassRenderer.drawPanel(gfx, x, y, w, 16, 3, hw, alpha * (focused ? 1f : 0.6f));
        if (focused) {
            LiquidGlassRenderer.drawBorder(gfx, x, y, w, 16, 1,
                    LiquidGlassRenderer.withAlpha(hw ? 0xFF8800 : 0x6688FF, (int)(200*alpha)));
        }
        String display = value.isEmpty() ? "(click to edit)" : value;
        int textColor  = value.isEmpty()
                ? LiquidGlassRenderer.withAlpha(0x666666, (int)(160*alpha))
                : LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(220*alpha));
        gfx.drawString(this.font, display, x + 4, y + 3, textColor);
        y += 20;
        return y;
    }

    private void renderToggle(GuiGraphicsExtractor gfx, String label, boolean value,
                              int x, int y, float alpha, boolean hw) {
        // Toggle track
        int tw = 28, th = 14;
        LiquidGlassRenderer.fill(gfx, x, y, tw, th,
                LiquidGlassRenderer.withAlpha(value ? (hw ? 0xFF6600 : 0x446688) : 0x222222,
                        (int)(200*alpha)));
        // Knob
        int kx = value ? x + tw - th + 1 : x + 1;
        LiquidGlassRenderer.fill(gfx, kx, y + 1, th - 2, th - 2,
                LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(220*alpha)));

        int labelColor = LiquidGlassRenderer.withAlpha(0xEEEEEE, (int)(200*alpha));
        gfx.drawString(this.font, label, x + tw + 6, y + 2, labelColor);
    }

    // ── Player bar ────────────────────────────────────────────────────────────

    private void renderPlayerBar(GuiGraphicsExtractor gfx, int px, int py,
                                 int panelW, boolean hw, float alpha, int mx, int my) {
        // Player bar background
        LiquidGlassRenderer.fill(gfx, px, py, panelW, PLAYER_H,
                LiquidGlassRenderer.withAlpha(hw ? 0x08000F : 0x080810, (int)(210*alpha)));
        LiquidGlassRenderer.fill(gfx, px, py, panelW, 1,
                LiquidGlassRenderer.multiplyAlpha(hw ? LiquidGlassRenderer.HW_BORDER
                        : LiquidGlassRenderer.BORDER_STD, alpha * 0.5f));

        MusicManager mm = MusicPlayerMod.musicManager;
        if (mm == null) return;

        MusicManager.State state = mm.getState();
        Track now    = mm.getNowPlaying();
        long  pos    = mm.getPlaybackPositionMs();
        long  dur    = now != null ? now.durationMs() : 0;

        // ── Seek bar ─────────────────────────────────────────────────────────
        int seekX  = px + SIDEBAR_W;
        int seekY  = py + PLAYER_H - 14;
        int seekW  = panelW - SIDEBAR_W - 80; // leave room for time labels
        float prog = (dur > 0) ? (seekDragging ? seekPreview : (float)pos / dur) : 0f;

        LiquidGlassRenderer.drawProgressBar(gfx, seekX, seekY, seekW, 4, prog, hw, alpha);

        // Time labels
        int tColor = LiquidGlassRenderer.withAlpha(0xAAAAAA, (int)(180*alpha));
        gfx.drawString(this.font, formatTime(pos),   seekX - 2, seekY - 10, tColor);
        gfx.drawString(this.font, formatTime(dur), seekX + seekW - 30, seekY - 10, tColor);

        // ── Control buttons (centered) ────────────────────────────────────────
        int ctrlY = py + 10;
        int ctrlX = px + (panelW - 160) / 2;
        int bSpacing = 32;

        // |◀◀  ◀  ▶/▐▐  ▶  ▶▶|  ♥  🔀  🔁
        String[] btnLabels = { "⟪", "◀", state == MusicManager.State.PLAYING ? "⏸" : "▶", "▶", "⟫" };
        for (int i = 0; i < btnLabels.length; i++) {
            int bx = ctrlX + i * bSpacing;
            boolean hov = mx >= bx && mx < bx + BTN_W && my >= ctrlY && my < ctrlY + BTN_H;
            LiquidGlassRenderer.drawCapsule(gfx, bx, ctrlY, BTN_W - 2, BTN_H,
                    hw, alpha * (hov ? 1f : 0.65f));
            int bw = this.font.width(btnLabels[i]);
            int btnTxtColor = i == 2
                    ? LiquidGlassRenderer.withAlpha(hw ? 0xFF8800 : 0xFFFFFF, (int)(240*alpha))
                    : LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(200*alpha));
            gfx.drawString(this.font, btnLabels[i],
                    bx + (BTN_W - 2 - bw) / 2, ctrlY + (BTN_H - this.font.lineHeight) / 2,
                    btnTxtColor, false);
        }

        // Shuffle + repeat
        boolean shuffle = mm.getQueue().isShuffle();
        boolean repeat  = mm.getQueue().getRepeatMode() != MusicConfig.RepeatMode.OFF;
        int shuffleX    = ctrlX + 5 * bSpacing + 8;
        int favoriteX   = shuffleX + 26;
        int repeatX     = favoriteX + 26;

        // Shuffle button
        int shColor = shuffle ? (hw ? LiquidGlassRenderer.withAlpha(0xFF8800, (int)(240*alpha))
                                   : LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(240*alpha)))
                              : LiquidGlassRenderer.withAlpha(0x888888, (int)(180*alpha));
        gfx.drawString(this.font, "⇄", shuffleX, ctrlY + 6, shColor);

        // Favorite
        boolean isFav = now != null && mm.isFavorite(now);
        int favColor  = isFav ? LiquidGlassRenderer.withAlpha(hw ? 0xFF4444 : 0xFF4444, (int)(240*alpha))
                              : LiquidGlassRenderer.withAlpha(0x888888, (int)(180*alpha));
        gfx.drawString(this.font, "♥", favoriteX, ctrlY + 6, favColor);

        // Repeat
        MusicConfig.RepeatMode rm = mm.getQueue().getRepeatMode();
        String repLabel = rm == MusicConfig.RepeatMode.ONE ? "⟳₁" : "⟳";
        int repColor    = repeat ? (hw ? LiquidGlassRenderer.withAlpha(0xFF8800, (int)(240*alpha))
                                      : LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(240*alpha)))
                                 : LiquidGlassRenderer.withAlpha(0x888888, (int)(180*alpha));
        gfx.drawString(this.font, repLabel, repeatX, ctrlY + 6, repColor);

        // Volume slider (right side of player bar)
        int volX = px + panelW - 72;
        int volY = ctrlY + 6;
        gfx.drawString(this.font, "🔊", volX - 14, volY,
                LiquidGlassRenderer.withAlpha(0x888888, (int)(160*alpha)), false);
        float vol = MusicPlayerMod.config.musicVolume;
        LiquidGlassRenderer.drawProgressBar(gfx, volX, volY + 2, 60, 4, vol, hw, alpha * 0.8f);
    }

    // ── Track row ─────────────────────────────────────────────────────────────

    private void renderTrackRow(GuiGraphicsExtractor gfx, int x, int y, int w,
                                Track track, int id,
                                boolean hw, float alpha, int mx, int my,
                                boolean showSource) {
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + 32;

        // Row bg
        if (hov) {
            LiquidGlassRenderer.fill(gfx, x, y, w, 32,
                    LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(12 * alpha)));
        }

        // Art placeholder (8×8 colored square)
        int artColor = track.source() == Track.Source.SPOTIFY
                ? LiquidGlassRenderer.withAlpha(0x1DB954, (int)(180*alpha))
                : LiquidGlassRenderer.withAlpha(0xFF0000, (int)(180*alpha));
        LiquidGlassRenderer.fill(gfx, x + 2, y + 7, 18, 18, artColor);

        // Title
        String title   = truncate(track.title(), w - 90);
        int titleColor = LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(220*alpha));
        gfx.drawString(this.font, title, x + 24, y + 6, titleColor);

        // Artist
        String artist  = truncate(track.artist(), w - 110);
        int artColor2  = LiquidGlassRenderer.withAlpha(0xAAAAAA, (int)(170*alpha));
        gfx.drawString(this.font, artist, x + 24, y + 18, artColor2);

        // Duration
        String dur = formatTime(track.durationMs());
        int dw     = this.font.width(dur);
        gfx.drawString(this.font, dur, x + w - dw - 4, y + 12,
                LiquidGlassRenderer.withAlpha(0x888888, (int)(150*alpha)), false);

        // Source badge
        if (showSource) {
            String src = track.source() == Track.Source.SPOTIFY ? "SP" : "YT";
            int srcColor = track.source() == Track.Source.SPOTIFY
                    ? LiquidGlassRenderer.withAlpha(0x1DB954, (int)(180*alpha))
                    : LiquidGlassRenderer.withAlpha(0xFF4444, (int)(180*alpha));
            gfx.drawString(this.font, src, x + w - dw - 26, y + 12, srcColor);
        }

        // Separator
        LiquidGlassRenderer.fill(gfx, x, y + 31, w, 1,
                LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(12*alpha)));
    }

    // ── Mouse / Keyboard ──────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        // Forward to pill first (it's outside the panel)
        if (MusicPlayerMod.lyricsPill.mouseClicked(mx, my, button)) return true;

        int sw = this.width, sh = this.height;
        int panelW = sw * PANEL_W_FRAC / 100;
        int panelH = sh * PANEL_H_FRAC / 100;
        int px     = (sw - panelW) / 2;
        int py     = (sh - panelH) / 2;

        // Nav bar click
        if (my >= py && my < py + NAV_H) {
            int tabW = panelW / NAV_LABELS.length;
            int idx  = (int)((mx - px) / tabW);
            if (idx >= 0 && idx < NAV_PAGES.length) {
                switchPage(NAV_PAGES[idx]);
                return true;
            }
        }

        // Player bar buttons
        int playerY  = py + panelH - PLAYER_H;
        int ctrlX    = px + (panelW - 160) / 2;
        int bSpacing = 32;
        if (my >= playerY + 10 && my < playerY + 10 + BTN_H) {
            for (int i = 0; i < 5; i++) {
                int bx = ctrlX + i * bSpacing;
                if (mx >= bx && mx < bx + BTN_W) {
                    handleControlClick(i);
                    return true;
                }
            }
            // Shuffle
            int shuffleX = ctrlX + 5 * bSpacing + 8;
            if (mx >= shuffleX && mx < shuffleX + 20) {
                MusicPlayerMod.musicManager.setShuffle(!MusicPlayerMod.musicManager.getQueue().isShuffle());
                playUiSound("ui.toggle");
                return true;
            }
            // Favorite
            int favX = shuffleX + 26;
            if (mx >= favX && mx < favX + 14) {
                Track now = MusicPlayerMod.musicManager.getNowPlaying();
                if (now != null) {
                    if (MusicPlayerMod.musicManager.isFavorite(now)) MusicPlayerMod.musicManager.removeFavorite(now);
                    else MusicPlayerMod.musicManager.addFavorite(now);
                    playUiSound("ui.toggle");
                }
                return true;
            }
            // Repeat
            int repX = favX + 26;
            if (mx >= repX && mx < repX + 20) {
                MusicConfig.RepeatMode rm = MusicPlayerMod.musicManager.getQueue().getRepeatMode();
                MusicConfig.RepeatMode next = switch (rm) {
                    case OFF -> MusicConfig.RepeatMode.ALL;
                    case ALL -> MusicConfig.RepeatMode.ONE;
                    case ONE -> MusicConfig.RepeatMode.OFF;
                };
                MusicPlayerMod.musicManager.setRepeat(next);
                playUiSound("ui.toggle");
                return true;
            }
        }

        // Seek bar click
        int seekX = px + SIDEBAR_W;
        int seekY = playerY + PLAYER_H - 14;
        int seekW = panelW - SIDEBAR_W - 80;
        if (mx >= seekX && mx <= seekX + seekW && my >= seekY - 4 && my <= seekY + 8) {
            float t = (float)((mx - seekX) / seekW);
            long dur = MusicPlayerMod.musicManager.getDurationMs();
            MusicPlayerMod.musicManager.seek((long)(t * dur));
            playUiSound("ui.button.click");
            return true;
        }

        // Search — click in bar
        int cpx = (sw - panelW) / 2;
        int cpy = py + NAV_H;
        int cw  = panelW - SIDEBAR_W;
        int ch  = panelH - NAV_H - PLAYER_H;
        int cx  = cpx + SIDEBAR_W;
        int cy  = cpy;
        if (currentPage == Page.SEARCH && mx >= cx + 10 && mx <= cx + cw - 10
                && my >= cy + 10 && my <= cy + 10 + SEARCH_H) {
            // Focus search bar — handled by keyPressed
            playUiSound("ui.button.click");
            return true;
        }

        // Track row clicks in search
        if (currentPage == Page.SEARCH) {
            int rowH   = 38;
            int startY = cy + 44 - searchCursor;
            for (int i = 0; i < searchResults.size(); i++) {
                int ry = startY + i * rowH;
                if (my >= ry && my < ry + rowH && mx >= cx + 8 && mx <= cx + cw - 8) {
                    playTrack(searchResults.get(i));
                    return true;
                }
            }
        }

        // Settings toggles / fields
        if (currentPage == Page.SETTINGS) {
            handleSettingsClick(mx, my, px, py, panelW, panelH);
            return true;
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC closes
        if (keyCode == 256) { triggerClose(); return true; }
        // M closes
        if (keyCode == 77 && currentPage != Page.SEARCH) { triggerClose(); return true; }

        // Search input
        if (currentPage == Page.SEARCH) {
            if (keyCode == 259 && searchQuery.length() > 0) { // BACKSPACE
                searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                triggerSearch();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (currentPage == Page.SEARCH && c >= 32) {
            searchQuery += c;
            triggerSearch();
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        switch (currentPage) {
            case HOME    -> scrollHome    = Math.max(0, scrollHome    - (int)(dy * 12));
            case QUEUE   -> scrollQueue   = Math.max(0, scrollQueue   - (int)(dy * 12));
            case LIBRARY -> scrollLibrary = Math.max(0, scrollLibrary - (int)(dy * 12));
            case SEARCH  -> searchCursor  = Math.max(0, searchCursor  - (int)(dy * 12));
        }
        return true;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void switchPage(Page page) {
        if (page == currentPage) return;
        playUiSound("ui.navigate");
        currentPage = page;
        pageTransX.set(0.1f);
        pageTransX.animateTo(0f, 0.2f, AnimationController.EasingType.EASE_OUT_CUBIC);
    }

    private void triggerClose() {
        closing = true;
        scaleAnim.setTarget(0.9f);
        alphaAnim.animateTo(0f, 0.18f);
        playUiSound("ui.menu.close");
    }

    private void handleControlClick(int idx) {
        MusicManager mm = MusicPlayerMod.musicManager;
        playUiSound("ui.button.click");
        switch (idx) {
            case 0 -> mm.seek(0);              // restart / prev-prev
            case 1 -> mm.previous();
            case 2 -> mm.togglePause();
            case 3 -> mm.next();
            case 4 -> {}                       // skip to end — no-op
        }
    }

    private void playTrack(Track t) {
        MusicPlayerMod.musicManager.addToQueue(t);
        MusicPlayerMod.musicManager.play(t);
        playUiSound("ui.button.click");
    }

    private void triggerSearch() {
        if (searchQuery.length() < 2) { searchResults.clear(); return; }
        searchLoading = true;
        searchError   = null;
        searchManager.search(searchQuery,
                results -> { searchResults = results; searchLoading = false; },
                err     -> { searchError = err; searchLoading = false; });
    }

    private void handleSettingsClick(double mx, double my, int px, int py, int panelW, int panelH) {
        MusicConfig cfg = MusicPlayerMod.config;
        int contentX    = px + SIDEBAR_W;
        int tabW        = 90;
        int tabX        = contentX + (panelW - SIDEBAR_W) - tabW * 2 - 20;
        int tabY        = py + NAV_H + 6;

        // Tab switch
        for (int i = 0; i < 2; i++) {
            int bx = tabX + i * (tabW + 4);
            if (mx >= bx && mx < bx + tabW && my >= tabY && my < tabY + 20) {
                settingsTab = i;
                playUiSound("ui.navigate");
                return;
            }
        }

        // For all toggle rows we do a broad Y-range scan
        // Each row is lh=22px high; we find the row and flip the right field
        // This matches the render order in renderSettings exactly.
        int lx  = contentX + 10;
        int rx  = contentX + (panelW - SIDEBAR_W) / 2 + 10;
        int ly  = py + NAV_H + 34;  // tabH + 14 + NAV_H
        int rly = ly;
        int lh  = 22;

        boolean inLeft  = mx >= lx && mx < lx + 160;
        boolean inRight = mx >= rx && mx < rx + 160;

        if (settingsTab == 0) {
            // LEFT toggles (in render order)
            if (inLeft) {
                ly += 14; // section header
                cfg.lyrics3dEnabled   = tryToggle(my, ly, lh, cfg.lyrics3dEnabled);    ly += lh + lh; // skip slider
                cfg.lyricsFacePlayer  = tryToggle(my, ly, lh, cfg.lyricsFacePlayer);   ly += lh;
                cfg.lyricsKeepAway    = tryToggle(my, ly, lh, cfg.lyricsKeepAway);     ly += lh;
                cfg.lyricsStayOnScreen= tryToggle(my, ly, lh, cfg.lyricsStayOnScreen); ly += lh;
                cfg.lyricsAboveGround = tryToggle(my, ly, lh, cfg.lyricsAboveGround);  ly += lh;
                ly += lh * 2 + 6 + 14; // sliders + section
                cfg.lyricsTranslation = tryToggle(my, ly, lh, cfg.lyricsTranslation);  ly += lh;
                cfg.lyricsTransAnimation = tryToggle(my, ly, lh, cfg.lyricsTransAnimation); ly += lh;
                cfg.lyricsTransWiggle = tryToggle(my, ly, lh, cfg.lyricsTransWiggle);
            }
            // RIGHT toggles
            if (inRight) {
                rly += 14;
                cfg.glowEnabled     = tryToggle(my, rly, lh, cfg.glowEnabled);     rly += lh;
                cfg.glowNightOnly   = tryToggle(my, rly, lh, cfg.glowNightOnly);   rly += lh;
                rly += lh * 2 + 6 + 14; // sliders + section
                cfg.wiggle          = tryToggle(my, rly, lh, cfg.wiggle);          rly += lh;
                rly += lh * 2; // wiggle sliders
                cfg.textFlash       = tryToggle(my, rly, lh, cfg.textFlash);       rly += lh;
                cfg.wordPop         = tryToggle(my, rly, lh, cfg.wordPop);         rly += lh;
                rly += lh + 6 + 14; // flash size slider + section
                cfg.danceEnabled    = tryToggle(my, rly, lh, cfg.danceEnabled);    rly += lh;
                cfg.danceAuto       = tryToggle(my, rly, lh, cfg.danceAuto);       rly += lh;
                cfg.danceSpin       = tryToggle(my, rly, lh, cfg.danceSpin);
            }
        } else {
            // Music & HUD tab
            if (inLeft) {
                ly += 14 + lh; // section + volume slider
                cfg.volumeNormalization = tryToggle(my, ly, lh, cfg.volumeNormalization);
            }
            if (inRight) {
                rly += 14;
                cfg.envAudioEnabled = tryToggle(my, rly, lh, cfg.envAudioEnabled); rly += lh;
                cfg.envUnderwater   = tryToggle(my, rly, lh, cfg.envUnderwater);   rly += lh;
                cfg.envCaveEcho     = tryToggle(my, rly, lh, cfg.envCaveEcho);     rly += lh;
                cfg.envIndoors      = tryToggle(my, rly, lh, cfg.envIndoors);      rly += lh;
                cfg.envRain         = tryToggle(my, rly, lh, cfg.envRain);         rly += lh;
                rly += lh + 6 + 14; // slider + section
                rly += lh;    // scale slider
                cfg.songCardShowPaused = tryToggle(my, rly, lh, cfg.songCardShowPaused); rly += lh;
                rly += lh * 2 + 6 + 14; // pos sliders + section
                cfg.dynamicIslandGlass  = tryToggle(my, rly, lh, cfg.dynamicIslandGlass);  rly += lh;
                cfg.dynamicIslandMusic  = tryToggle(my, rly, lh, cfg.dynamicIslandMusic);  rly += lh;
                cfg.dynamicIslandNotify = tryToggle(my, rly, lh, cfg.dynamicIslandNotify); rly += lh;
                rly += lh * 2 + 6 + 14; // scale/time sliders + section
                cfg.nowPlayingOverlay   = tryToggle(my, rly, lh, cfg.nowPlayingOverlay);   rly += lh;
                cfg.nowPlayingCover     = tryToggle(my, rly, lh, cfg.nowPlayingCover);     rly += lh;
                rly += 6 + 14;
                cfg.ytMusicEnabled      = tryToggle(my, rly, lh, cfg.ytMusicEnabled);
            }
        }

        // Sync mutable systems
        MusicPlayerMod.syncDanceConfig();
        MusicPlayerMod.syncEnvAudioConfig();
        cfg.save();
        playUiSound("ui.toggle");
    }

    /** If click Y falls within this row, flip the boolean; return new value. */
    private boolean tryToggle(double clickY, int rowY, int rowH, boolean current) {
        if (clickY >= rowY && clickY < rowY + rowH) return !current;
        return current;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String formatTime(long ms) {
        if (ms <= 0) return "0:00";
        long secs = ms / 1000;
        return secs / 60 + ":" + String.format("%02d", secs % 60);
    }

    private String truncate(String text, int maxW) {
        if (this.font.width(text) <= maxW) return text;
        return this.font.plainSubstrByWidth(text, maxW - this.font.width("…")) + "…";
    }

    private static void playUiSound(String event) {
        if (!MusicPlayerMod.config.uiSounds) return;
        try {
            Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.resources.Identifier id =
                    net.minecraft.resources.Identifier.withDefaultNamespace("musicplayer:" + event);
            net.minecraft.sounds.SoundEvent se = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(id);
            mc.getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            se, MusicPlayerMod.config.uiSoundVolume));
        } catch (Exception ignored) {}
    }
}
