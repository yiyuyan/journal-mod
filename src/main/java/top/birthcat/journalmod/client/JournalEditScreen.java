/*
 * Copyright (c) BirthCat
 * SPDX-License-Identifier: Apache-2.0
 */

package top.birthcat.journalmod.client;

import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.Util;
import net.minecraft.client.GameNarrator;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.network.Filterable;
import net.minecraft.util.StringUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;

/**
 * Base on {@link net.minecraft.client.gui.screens.inventory.BookEditScreen}
 */
@OnlyIn(Dist.CLIENT)
public class JournalEditScreen extends Screen {

    private static final int TEXT_WIDTH = 114;
    private static final int TEXT_HEIGHT = 128;
    private static final int IMAGE_WIDTH = 192;
    private static final int IMAGE_HEIGHT = 192;
    private static final int BACKGROUND_TEXTURE_WIDTH = 256;
    private static final int BACKGROUND_TEXTURE_HEIGHT = 256;
    /**
     * Whether the book's title or contents has been modified since being opened
     */
    private boolean isModified;
    /**
     * Update ticks since the gui was opened
     */
    private int frameTick;
    private int currentPage;
    private final List<String> pages = Lists.newArrayList();
    private final TextFieldHelper pageEdit = new TextFieldHelper(
            this::getCurrentPageText,
            this::setCurrentPageText,
            this::getClipboard,
            this::setClipboard,
            p_280853_ -> p_280853_.length() < 1024 && this.font.wordWrapHeight(p_280853_, TEXT_WIDTH) <= TEXT_HEIGHT
    );
    /**
     * In milliseconds
     */
    private long lastClickTime;
    private int lastIndex = -1;
    private PageButton forwardButton;
    private PageButton backButton;
    private Button doneButton;
    private Button writeButton;
    @Nullable
    private DisplayCache displayCache = DisplayCache.EMPTY;
    private Component pageMsg = CommonComponents.EMPTY;
    private final ItemStack book;
    private final Player owner;

    public JournalEditScreen(Player owner, List<String> pages) {
        super(GameNarrator.NO_TITLE);
        this.owner = owner;
        var mainHandItem = owner.getItemInHand(InteractionHand.MAIN_HAND);
        var writablebookcontent = mainHandItem.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (writablebookcontent != null) {
            this.book = mainHandItem ;
        } else {
            var offHandItem = owner.getItemInHand(InteractionHand.OFF_HAND);
            writablebookcontent = offHandItem.get(DataComponents.WRITABLE_BOOK_CONTENT);
            if (writablebookcontent != null) {
                this.book = offHandItem ;
            } else {
                this.book = null;
            }
        }

        //For ServerboundEditBookPacket

        this.pages.addAll(pages);
        if (this.pages.isEmpty()) {
            this.pages.add("");
        }
    }

    private void setClipboard(String clipboardValue) {
        if (this.minecraft != null) {
            TextFieldHelper.setClipboardContents(this.minecraft, clipboardValue);
        }
    }

    private String getClipboard() {
        return this.minecraft != null ? TextFieldHelper.getClipboardContents(this.minecraft) : "";
    }

    private int getNumPages() {
        return this.pages.size();
    }

    @Override
    public void tick() {
        super.tick();
        this.frameTick++;
    }

    @Override
    protected void init() {
        this.clearDisplayCache();
        this.doneButton = this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, p_280851_ -> {
            this.minecraft.setScreen(null);
            this.saveChanges();
        }).bounds(this.width / 2 + 2, 196, 98, 20).build());
        this.writeButton = this.addRenderableWidget(Button.builder(Component.translatable("book.journalmod.transcription"), p_98177_ -> {
            this.minecraft.setScreen(null);
            this.writeToBook();
            this.saveChanges();
        }).bounds(this.width / 2 - 100, 196, 98, 20).build());
        int i = (this.width - IMAGE_WIDTH) / 2;
        this.forwardButton = this.addRenderableWidget(new PageButton(i + 116, 159, true, p_98144_ -> this.pageForward(), true));
        this.backButton = this.addRenderableWidget(new PageButton(i + 43, 159, false, p_98113_ -> this.pageBack(), true));
        this.updateButtonVisibility();
    }

    private void writeToBook() {
        this.book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(this.pages.stream().map(Filterable::passThrough).toList()));
    }

    private void pageBack() {
        if (this.currentPage > 0) {
            this.currentPage--;
        }

        this.updateButtonVisibility();
        this.clearDisplayCacheAfterPageChange();
    }

    private void pageForward() {
        if (this.currentPage < this.getNumPages() - 1) {
            this.currentPage++;
        } else {
            this.appendPageToBook();
            if (this.currentPage < this.getNumPages() - 1) {
                this.currentPage++;
            }
        }

        this.updateButtonVisibility();
        this.clearDisplayCacheAfterPageChange();
    }

    private void updateButtonVisibility() {
        this.backButton.visible = this.currentPage > 0;
        this.writeButton.active = this.book != null;
    }

    private void eraseEmptyTrailingPages() {
        ListIterator<String> listiterator = this.pages.listIterator(this.pages.size());

        while (listiterator.hasPrevious() && listiterator.previous().isEmpty()) {
            listiterator.remove();
        }
    }

    private void saveChanges() {
        if (this.isModified) {
            this.eraseEmptyTrailingPages();
            ClientJournalHolder.setJournal(pages,this.owner.getMainHandItem().has(DataComponents.WRITABLE_BOOK_CONTENT)?this.owner.getInventory().selected:(this.owner.getOffhandItem().has(DataComponents.WRITABLE_BOOK_CONTENT)?40:-1));
        }
    }

    private void appendPageToBook() {
        if (this.getNumPages() < 100) {
            this.pages.add("");
            this.isModified = true;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        } else {
            boolean flag = this.bookKeyPressed(keyCode, scanCode, modifiers);
            if (flag) {
                this.clearDisplayCache();
                return true;
            } else {
                return false;
            }
        }
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (super.charTyped(codePoint, modifiers)) {
            return true;
        } else if (StringUtil.isAllowedChatCharacter(codePoint)) {
            this.pageEdit.insertText(Character.toString(codePoint));
            this.clearDisplayCache();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Handles keypresses, clipboard functions, and page turning
     */
    private boolean bookKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (Screen.isSelectAll(keyCode)) {
            this.pageEdit.selectAll();
            return true;
        } else if (Screen.isCopy(keyCode)) {
            this.pageEdit.copy();
            return true;
        } else if (Screen.isPaste(keyCode)) {
            this.pageEdit.paste();
            return true;
        } else if (Screen.isCut(keyCode)) {
            this.pageEdit.cut();
            return true;
        } else {
            TextFieldHelper.CursorStep textfieldhelper$cursorstep = Screen.hasControlDown()
                    ? TextFieldHelper.CursorStep.WORD
                    : TextFieldHelper.CursorStep.CHARACTER;
            return switch (keyCode) {
                case 257, 335 -> {
                    this.pageEdit.insertText("\n");
                    yield true;
                }
                case 259 -> {
                    this.pageEdit.removeFromCursor(-1, textfieldhelper$cursorstep);
                    yield true;
                }
                case 261 -> {
                    this.pageEdit.removeFromCursor(1, textfieldhelper$cursorstep);
                    yield true;
                }
                case 262 -> {
                    this.pageEdit.moveBy(1, Screen.hasShiftDown(), textfieldhelper$cursorstep);
                    yield true;
                }
                case 263 -> {
                    this.pageEdit.moveBy(-1, Screen.hasShiftDown(), textfieldhelper$cursorstep);
                    yield true;
                }
                case 264 -> {
                    this.keyDown();
                    yield true;
                }
                case 265 -> {
                    this.keyUp();
                    yield true;
                }
                case 266 -> {
                    this.backButton.onPress();
                    yield true;
                }
                case 267 -> {
                    this.forwardButton.onPress();
                    yield true;
                }
                case 268 -> {
                    this.keyHome();
                    yield true;
                }
                case 269 -> {
                    this.keyEnd();
                    yield true;
                }
                default -> false;
            };
        }
    }

    private void keyUp() {
        this.changeLine(-1);
    }

    private void keyDown() {
        this.changeLine(1);
    }

    private void changeLine(int yChange) {
        int i = this.pageEdit.getCursorPos();
        int j = this.getDisplayCache().changeLine(i, yChange);
        this.pageEdit.setCursorPos(j, Screen.hasShiftDown());
    }

    private void keyHome() {
        if (Screen.hasControlDown()) {
            this.pageEdit.setCursorToStart(Screen.hasShiftDown());
        } else {
            int i = this.pageEdit.getCursorPos();
            int j = this.getDisplayCache().findLineStart(i);
            this.pageEdit.setCursorPos(j, Screen.hasShiftDown());
        }
    }

    private void keyEnd() {
        if (Screen.hasControlDown()) {
            this.pageEdit.setCursorToEnd(Screen.hasShiftDown());
        } else {
            DisplayCache bookeditscreen$displaycache = this.getDisplayCache();
            int i = this.pageEdit.getCursorPos();
            int j = bookeditscreen$displaycache.findLineEnd(i);
            this.pageEdit.setCursorPos(j, Screen.hasShiftDown());
        }
    }

    private String getCurrentPageText() {
        return this.currentPage >= 0 && this.currentPage < this.pages.size() ? this.pages.get(this.currentPage) : "";
    }

    private void setCurrentPageText(String text) {
        if (this.currentPage >= 0 && this.currentPage < this.pages.size()) {
            this.pages.set(this.currentPage, text);
            this.isModified = true;
            this.clearDisplayCache();
        }
    }

    @Override
    public void render(GuiGraphics p_281724_, int p_282965_, int p_283294_, float p_281293_) {
        super.render(p_281724_, p_282965_, p_283294_, p_281293_);
        this.setFocused(null);
        int i = (this.width - IMAGE_WIDTH) / 2;
        int j1 = this.font.width(this.pageMsg);
        p_281724_.drawString(this.font, this.pageMsg, i - j1 + IMAGE_WIDTH - 44, 18, 0, false);
        DisplayCache bookeditscreen$displaycache = this.getDisplayCache();

        for (LineInfo bookeditscreen$lineinfo : bookeditscreen$displaycache.lines) {
            p_281724_.drawString(this.font, bookeditscreen$lineinfo.asComponent, bookeditscreen$lineinfo.x, bookeditscreen$lineinfo.y, -16777216, false);
        }

        this.renderHighlight(p_281724_, bookeditscreen$displaycache.selection);
        this.renderCursor(p_281724_, bookeditscreen$displaycache.cursor, bookeditscreen$displaycache.cursorAtEnd);
    }

    @Override
    public void renderBackground(GuiGraphics p_294860_, int p_295019_, int p_294307_, float p_295562_) {
        this.renderTransparentBackground(p_294860_);
        p_294860_.blit(RenderType::guiTextured, BookViewScreen.BOOK_LOCATION, (this.width - IMAGE_WIDTH) / 2, 2, 0.0F, 0.0F, IMAGE_WIDTH, IMAGE_HEIGHT, BACKGROUND_TEXTURE_WIDTH, BACKGROUND_TEXTURE_HEIGHT);
    }

    private void renderCursor(GuiGraphics guiGraphics, Pos2i cursorPos, boolean isEndOfText) {
        if (this.frameTick / 6 % 2 == 0) {
            cursorPos = this.convertLocalToScreen(cursorPos);
            if (!isEndOfText) {
                guiGraphics.fill(cursorPos.x, cursorPos.y - 1, cursorPos.x + 1, cursorPos.y + 9, -16777216);
            } else {
                guiGraphics.drawString(this.font, "_", cursorPos.x, cursorPos.y, 0, false);
            }
        }
    }

    private void renderHighlight(GuiGraphics guiGraphics, Rect2i[] highlightAreas) {
        for (Rect2i rect2i : highlightAreas) {
            int i = rect2i.getX();
            int j = rect2i.getY();
            int k = i + rect2i.getWidth();
            int l = j + rect2i.getHeight();
            guiGraphics.fill(RenderType.guiTextHighlight(), i, j, k, l, -16776961);
        }
    }

    private Pos2i convertScreenToLocal(Pos2i screenPos) {
        return new Pos2i(screenPos.x - (this.width - IMAGE_WIDTH) / 2 - 36, screenPos.y - 32);
    }

    private Pos2i convertLocalToScreen(Pos2i localScreenPos) {
        return new Pos2i(localScreenPos.x + (this.width - IMAGE_WIDTH) / 2 + 36, localScreenPos.y + 32);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        } else {
            if (button == 0) {
                long i = Util.getMillis();
                DisplayCache bookeditscreen$displaycache = this.getDisplayCache();
                int j = bookeditscreen$displaycache.getIndexAtPosition(
                        this.font, this.convertScreenToLocal(new Pos2i((int) mouseX, (int) mouseY))
                );
                if (j >= 0) {
                    if (j != this.lastIndex || i - this.lastClickTime >= 250L) {
                        this.pageEdit.setCursorPos(j, Screen.hasShiftDown());
                    } else if (!this.pageEdit.isSelecting()) {
                        this.selectWord(j);
                    } else {
                        this.pageEdit.selectAll();
                    }

                    this.clearDisplayCache();
                }

                this.lastIndex = j;
                this.lastClickTime = i;
            }

            return true;
        }
    }

    private void selectWord(int index) {
        String s = this.getCurrentPageText();
        this.pageEdit.setSelectionRange(StringSplitter.getWordPosition(s, -1, index, false), StringSplitter.getWordPosition(s, 1, index, false));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        } else {
            if (button == 0) {
                DisplayCache bookeditscreen$displaycache = this.getDisplayCache();
                int i = bookeditscreen$displaycache.getIndexAtPosition(
                        this.font, this.convertScreenToLocal(new Pos2i((int) mouseX, (int) mouseY))
                );
                this.pageEdit.setCursorPos(i, true);
                this.clearDisplayCache();
            }

            return true;
        }
    }

    private DisplayCache getDisplayCache() {
        if (this.displayCache == null) {
            this.displayCache = this.rebuildDisplayCache();
            this.pageMsg = Component.translatable("book.pageIndicator", this.currentPage + 1, this.getNumPages());
        }

        return this.displayCache;
    }

    private void clearDisplayCache() {
        this.displayCache = null;
    }

    private void clearDisplayCacheAfterPageChange() {
        this.pageEdit.setCursorToEnd();
        this.clearDisplayCache();
    }

    private DisplayCache rebuildDisplayCache() {
        String s = this.getCurrentPageText();
        if (s.isEmpty()) {
            return DisplayCache.EMPTY;
        } else {
            int i = this.pageEdit.getCursorPos();
            int j = this.pageEdit.getSelectionPos();
            IntList intlist = new IntArrayList();
            List<LineInfo> list = Lists.newArrayList();
            MutableInt mutableint = new MutableInt();
            MutableBoolean mutableboolean = new MutableBoolean();
            StringSplitter stringsplitter = this.font.getSplitter();
            stringsplitter.splitLines(s, TEXT_WIDTH, Style.EMPTY, true, (p_98132_, p_98133_, p_98134_) -> {
                int k3 = mutableint.getAndIncrement();
                String s2 = s.substring(p_98133_, p_98134_);
                mutableboolean.setValue(s2.endsWith("\n"));
                String s3 = StringUtils.stripEnd(s2, " \n");
                int l3 = k3 * 9;
                Pos2i bookeditscreen$pos2i1 = this.convertLocalToScreen(new Pos2i(0, l3));
                intlist.add(p_98133_);
                list.add(new LineInfo(p_98132_, s3, bookeditscreen$pos2i1.x, bookeditscreen$pos2i1.y));
            });
            int[] aint = intlist.toIntArray();
            boolean flag = i == s.length();
            Pos2i bookeditscreen$pos2i;
            if (flag && mutableboolean.isTrue()) {
                bookeditscreen$pos2i = new Pos2i(0, list.size() * 9);
            } else {
                int k = findLineFromPos(aint, i);
                int l = this.font.width(s.substring(aint[k], i));
                bookeditscreen$pos2i = new Pos2i(l, k * 9);
            }

            List<Rect2i> list1 = Lists.newArrayList();
            if (i != j) {
                int l2 = Math.min(i, j);
                int i1 = Math.max(i, j);
                int j1 = findLineFromPos(aint, l2);
                int k1 = findLineFromPos(aint, i1);
                if (j1 == k1) {
                    int l1 = j1 * 9;
                    int i2 = aint[j1];
                    list1.add(this.createPartialLineSelection(s, stringsplitter, l2, i1, l1, i2));
                } else {
                    int i3 = j1 + 1 > aint.length ? s.length() : aint[j1 + 1];
                    list1.add(this.createPartialLineSelection(s, stringsplitter, l2, i3, j1 * 9, aint[j1]));

                    for (int j3 = j1 + 1; j3 < k1; j3++) {
                        int j2 = j3 * 9;
                        String s1 = s.substring(aint[j3], aint[j3 + 1]);
                        int k2 = (int) stringsplitter.stringWidth(s1);
                        list1.add(this.createSelection(new Pos2i(0, j2), new Pos2i(k2, j2 + 9)));
                    }

                    list1.add(this.createPartialLineSelection(s, stringsplitter, aint[k1], i1, k1 * 9, aint[k1]));
                }
            }

            return new DisplayCache(
                    s, bookeditscreen$pos2i, flag, aint, list.toArray(new LineInfo[0]), list1.toArray(new Rect2i[0])
            );
        }
    }

    static int findLineFromPos(int[] lineStarts, int find) {
        int i = Arrays.binarySearch(lineStarts, find);
        return i < 0 ? -(i + 2) : i;
    }

    private Rect2i createPartialLineSelection(String input, StringSplitter splitter, int startPos, int endPos, int y, int lineStart) {
        String s = input.substring(lineStart, startPos);
        String s1 = input.substring(lineStart, endPos);
        Pos2i bookeditscreen$pos2i = new Pos2i((int) splitter.stringWidth(s), y);
        Pos2i bookeditscreen$pos2i1 = new Pos2i((int) splitter.stringWidth(s1), y + 9);
        return this.createSelection(bookeditscreen$pos2i, bookeditscreen$pos2i1);
    }

    private Rect2i createSelection(Pos2i corner1, Pos2i corner2) {
        Pos2i bookeditscreen$pos2i = this.convertLocalToScreen(corner1);
        Pos2i bookeditscreen$pos2i1 = this.convertLocalToScreen(corner2);
        int i = Math.min(bookeditscreen$pos2i.x, bookeditscreen$pos2i1.x);
        int j = Math.max(bookeditscreen$pos2i.x, bookeditscreen$pos2i1.x);
        int k = Math.min(bookeditscreen$pos2i.y, bookeditscreen$pos2i1.y);
        int l = Math.max(bookeditscreen$pos2i.y, bookeditscreen$pos2i1.y);
        return new Rect2i(i, k, j - i, l - k);
    }

    @OnlyIn(Dist.CLIENT)
    static class DisplayCache {
        static final DisplayCache EMPTY = new DisplayCache(
                "",
                new Pos2i(0, 0),
                true,
                new int[]{0},
                new LineInfo[]{new LineInfo(Style.EMPTY, "", 0, 0)},
                new Rect2i[0]
        );
        private final String fullText;
        final Pos2i cursor;
        final boolean cursorAtEnd;
        private final int[] lineStarts;
        final LineInfo[] lines;
        final Rect2i[] selection;

        public DisplayCache(
                String fullText, Pos2i cursor, boolean cursorAtEnd, int[] lineStarts, LineInfo[] lines, Rect2i[] selection
        ) {
            this.fullText = fullText;
            this.cursor = cursor;
            this.cursorAtEnd = cursorAtEnd;
            this.lineStarts = lineStarts;
            this.lines = lines;
            this.selection = selection;
        }

        public int getIndexAtPosition(Font font, Pos2i cursorPosition) {
            int i = cursorPosition.y / 9;
            if (i < 0) {
                return 0;
            } else if (i >= this.lines.length) {
                return this.fullText.length();
            } else {
                LineInfo bookeditscreen$lineinfo = this.lines[i];
                return this.lineStarts[i]
                        + font.getSplitter().plainIndexAtWidth(bookeditscreen$lineinfo.contents, cursorPosition.x, bookeditscreen$lineinfo.style);
            }
        }

        public int changeLine(int xChange, int yChange) {
            int i = findLineFromPos(this.lineStarts, xChange);
            int j = i + yChange;
            int k;
            if (0 <= j && j < this.lineStarts.length) {
                int l = xChange - this.lineStarts[i];
                int i1 = this.lines[j].contents.length();
                k = this.lineStarts[j] + Math.min(l, i1);
            } else {
                k = xChange;
            }

            return k;
        }

        public int findLineStart(int line) {
            int i = findLineFromPos(this.lineStarts, line);
            return this.lineStarts[i];
        }

        public int findLineEnd(int line) {
            int i = findLineFromPos(this.lineStarts, line);
            return this.lineStarts[i] + this.lines[i].contents.length();
        }
    }

    @OnlyIn(Dist.CLIENT)
    static class LineInfo {
        final Style style;
        final String contents;
        final Component asComponent;
        final int x;
        final int y;

        public LineInfo(Style style, String contents, int x, int y) {
            this.style = style;
            this.contents = contents;
            this.x = x;
            this.y = y;
            this.asComponent = Component.literal(contents).setStyle(style);
        }
    }

    @OnlyIn(Dist.CLIENT)
    static class Pos2i {
        public final int x;
        public final int y;

        Pos2i(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

}
